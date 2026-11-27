package com.sitesurvey.app

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.IntentCompat
import com.sitesurvey.app.data.ScanImporter
import com.sitesurvey.app.ui.ImportSheet
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.ScrollState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import kotlinx.coroutines.launch
import com.sitesurvey.app.update.Updater
import com.sitesurvey.app.update.UpdateChip
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.sitesurvey.app.ar.ArScreen
import com.sitesurvey.app.data.SurveyStore
import com.sitesurvey.app.export.Exporter
import com.sitesurvey.app.model.Survey
import com.sitesurvey.app.ui.HomeScreen
import com.sitesurvey.app.ui.SurveyScreen
import com.sitesurvey.app.ui.SurveyTheme
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : ComponentActivity() {
    private lateinit var store: SurveyStore
    private lateinit var updater: Updater
    private var incoming by mutableStateOf<Uri?>(null)

    private fun takeIntent(i: Intent?) {
        i ?: return
        val uri = when (i.action) {
            Intent.ACTION_SEND -> IntentCompat.getParcelableExtra(i, Intent.EXTRA_STREAM, Uri::class.java)
            Intent.ACTION_VIEW -> i.data
            else -> null
        }
        if (uri != null) incoming = uri
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        takeIntent(intent)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        store = SurveyStore(applicationContext)
        updater = Updater(applicationContext)
        if (savedInstanceState == null) takeIntent(intent)
        setContent {
            SurveyTheme {
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    // Screen is "home", "survey:<id>" or "ar:<id>".
                    var screen by rememberSaveable { mutableStateOf("home") }
                    val homeList = rememberLazyListState()
                    val scope = rememberCoroutineScope()
                    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { scope.launch { updater.check() } }
                    var importOutcome by remember { mutableStateOf<ScanImporter.Outcome?>(null) }
                    val pick = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { it?.let { u -> incoming = u } }
                    androidx.compose.runtime.LaunchedEffect(incoming) {
                        val u = incoming ?: return@LaunchedEffect
                        importOutcome = ScanImporter.read(applicationContext, u)
                        incoming = null
                    }
                    importOutcome?.let { out ->
                        ImportSheet(out, onDismiss = { importOutcome = null }, onCreate = {
                            val found = out as? ScanImporter.Outcome.Found
                            if (found != null) {
                                val s = found.result.survey
                                store.add(s)
                                screen = "survey:${s.id}"
                            }
                            importOutcome = null
                        })
                    }
                    val scrolls = remember { mutableMapOf<String, ScrollState>() }
                    val id = screen.substringAfter(':', "")
                    val survey = store.get(id)

                    when {
                        screen.startsWith("ar:") && survey != null -> {
                            BackHandler { screen = "survey:$id" }
                            ArScreen(survey, onUpdate = { store.update(id, it) }, onBack = { screen = "survey:$id" })
                        }
                        screen.startsWith("survey:") && survey != null -> {
                            BackHandler { screen = "home" }
                            val scroll = scrolls.getOrPut(id) { ScrollState(0) }
                            SurveyScreen(
                                survey, scroll,
                                onBack = { screen = "home" },
                                onAr = { screen = "ar:$id" },
                                onExport = { Exporter.share(this@MainActivity, survey) },
                                onUpdate = { store.update(id, it) },
                                onDelete = { store.delete(id); screen = "home" },
                            )
                        }
                        else -> HomeScreen(
                            store.surveys, homeList,
                            onNew = {
                                val name = "Survey " + SimpleDateFormat("MMM d", Locale.US).format(Date())
                                val s = Survey(name = name)
                                store.add(s)
                                screen = "ar:${s.id}"
                            },
                            onOpen = { screen = "survey:$it" },
                            onImport = { pick.launch(arrayOf("*/*")) },
                            update = { UpdateChip(updater.state) { scope.launch { updater.install() } } },
                        )
                    }
                }
            }
        }
    }
}
