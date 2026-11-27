package com.sitesurvey.app.data

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.sitesurvey.app.model.Survey
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import java.io.File

/** Surveys live in memory as Compose state and are saved as one JSON file each. */
class SurveyStore(context: Context) {
    private val dir = File(context.filesDir, "surveys").apply { mkdirs() }
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    private val io = CoroutineScope(SupervisorJob() + Dispatchers.IO.limitedParallelism(1))
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true; prettyPrint = false }

    var surveys by mutableStateOf(load())
        private set

    private fun load(): List<Survey> =
        dir.listFiles { f -> f.extension == "json" }.orEmpty().mapNotNull {
            runCatching { json.decodeFromString(Survey.serializer(), it.readText()) }.getOrNull()
        }.sortedByDescending { it.updated }

    fun get(id: String): Survey? = surveys.firstOrNull { it.id == id }

    fun add(s: Survey) {
        surveys = listOf(s) + surveys
        save(s)
    }

    fun update(id: String, change: (Survey) -> Survey) {
        val old = get(id) ?: return
        val new = change(old).copy(updated = System.currentTimeMillis())
        if (new == old) return
        surveys = surveys.map { if (it.id == id) new else it }.sortedByDescending { it.updated }
        save(new)
    }

    fun delete(id: String) {
        surveys = surveys.filterNot { it.id == id }
        io.launch { File(dir, "$id.json").delete() }
    }

    private fun save(s: Survey) {
        val text = json.encodeToString(Survey.serializer(), s)
        io.launch {
            val tmp = File(dir, "${s.id}.json.tmp")
            tmp.writeText(text)
            tmp.renameTo(File(dir, "${s.id}.json"))
        }
    }
}
