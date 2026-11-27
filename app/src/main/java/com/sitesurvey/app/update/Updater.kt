package com.sitesurvey.app.update

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.FileProvider
import com.sitesurvey.app.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * Checks the repo's "latest" release for a newer build and installs it in one tap.
 * Android still shows its own install confirmation; that's the approval.
 */
class Updater(private val context: Context) {
    sealed class State {
        data object Idle : State()
        data class Ready(val build: Int, val apkUrl: String) : State()
        data class Downloading(val percent: Int) : State()
        data class Failed(val why: String) : State()
    }

    var state by mutableStateOf<State>(State.Idle)
        private set
    private var lastCheck = 0L

    suspend fun check(force: Boolean = false) {
        if (state is State.Downloading) return
        val now = System.currentTimeMillis()
        if (!force && now - lastCheck < 15 * 60_000) return
        lastCheck = now
        val found = withContext(Dispatchers.IO) {
            runCatching {
                val c = URL(RELEASE_API).openConnection() as HttpURLConnection
                c.setRequestProperty("Accept", "application/vnd.github+json")
                c.connectTimeout = 8000; c.readTimeout = 8000
                if (c.responseCode != 200) return@runCatching null
                val root = Json.parseToJsonElement(c.inputStream.bufferedReader().readText()).jsonObject
                val name = root["name"]?.jsonPrimitive?.content.orEmpty()
                val build = Regex("""build (\d+)""").find(name)?.groupValues?.get(1)?.toIntOrNull() ?: return@runCatching null
                val apk = root["assets"]?.jsonArray?.map { it.jsonObject }
                    ?.firstOrNull { it["name"]?.jsonPrimitive?.content?.endsWith(".apk") == true }
                    ?.get("browser_download_url")?.jsonPrimitive?.content ?: return@runCatching null
                if (build > BuildConfig.VERSION_CODE) State.Ready(build, apk) else null
            }.getOrNull()
        }
        if (found != null) state = found
    }

    suspend fun install() {
        if (state is State.Failed) { state = State.Idle; check(force = true); return }
        val ready = state as? State.Ready ?: return
        if (!context.packageManager.canRequestPackageInstalls()) {
            // One-time Android switch: let Site Survey install its own updates.
            context.startActivity(
                Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}"))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
            return
        }
        state = State.Downloading(0)
        val file = withContext(Dispatchers.IO) {
            runCatching {
                val dir = File(context.cacheDir, "updates").apply { mkdirs(); listFiles()?.forEach { it.delete() } }
                val out = File(dir, "site-survey-${ready.build}.apk")
                var url = URL(ready.apkUrl)
                var c: HttpURLConnection
                var hops = 0
                while (true) {
                    c = url.openConnection() as HttpURLConnection
                    c.instanceFollowRedirects = false
                    c.connectTimeout = 15000; c.readTimeout = 30000
                    val code = c.responseCode
                    if (code in 300..399 && hops++ < 5) { url = URL(url, c.getHeaderField("Location")); continue }
                    if (code != 200) error("HTTP $code")
                    break
                }
                val total = c.contentLengthLong
                c.inputStream.use { input ->
                    out.outputStream().use { output ->
                        val buf = ByteArray(64 * 1024)
                        var done = 0L
                        var lastPct = -1
                        while (true) {
                            val n = input.read(buf)
                            if (n < 0) break
                            output.write(buf, 0, n)
                            done += n
                            if (total > 0) {
                                val pct = (done * 100 / total).toInt()
                                if (pct != lastPct) { lastPct = pct; withContext(Dispatchers.Main) { state = State.Downloading(pct) } }
                            }
                        }
                    }
                }
                out
            }
        }
        file.onFailure { state = State.Failed("Download failed. Tap to retry."); lastCheck = 0; return }
        state = ready
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", file.getOrThrow())
        context.startActivity(
            Intent(Intent.ACTION_VIEW).setDataAndType(uri, "application/vnd.android.package-archive")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }

    companion object {
        const val RELEASE_API = "https://api.github.com/repos/Likeabunny18/Site_Survey_App/releases/tags/latest"
    }
}
