package com.sitesurvey.app.data

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import com.sitesurvey.app.model.RoomImport
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.zip.ZipInputStream

/** Reads a shared or picked scan file (OBJ, RoomPlan JSON, or a ZIP holding one). */
object ScanImporter {
    sealed class Outcome {
        data class Found(val result: RoomImport.Result) : Outcome()
        data class Problem(val message: String) : Outcome()
    }

    suspend fun read(context: Context, uri: Uri): Outcome = withContext(Dispatchers.IO) {
        runCatching {
            val fileName = displayName(context, uri) ?: "scan"
            val base = fileName.substringBeforeLast('.').replace('_', ' ').trim().ifBlank { "Scan" }
            val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                ?: return@runCatching Outcome.Problem("Couldn't open that file.")
            val text: String = if (bytes.size > 2 && bytes[0] == 'P'.code.toByte() && bytes[1] == 'K'.code.toByte()) {
                var found: String? = null
                var sawUsd = false
                ZipInputStream(bytes.inputStream()).use { zip ->
                    while (true) {
                        val e = zip.nextEntry ?: break
                        val n = e.name.lowercase()
                        if (n.endsWith(".usdc") || n.endsWith(".usda")) sawUsd = true
                        if (!e.isDirectory && (n.endsWith(".obj") || n.endsWith(".json"))) { found = zip.readBytes().decodeToString(); break }
                    }
                }
                found ?: return@runCatching Outcome.Problem(
                    if (sawUsd) "That's a USDZ file. In the scanning app, export as OBJ instead." else "No OBJ or JSON room file inside that ZIP."
                )
            } else bytes.decodeToString()
            val result = RoomImport.import(text, base)
                ?: return@runCatching Outcome.Problem("Couldn't find a room in that file. Export the scan as OBJ.")
            Outcome.Found(result)
        }.getOrElse { Outcome.Problem("Couldn't read that file (${it.javaClass.simpleName}).") }
    }

    private fun displayName(context: Context, uri: Uri): String? = runCatching {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }
    }.getOrNull() ?: uri.lastPathSegment
}
