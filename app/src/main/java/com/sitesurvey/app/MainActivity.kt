package com.sitesurvey.app

import android.os.Bundle
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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        store = SurveyStore(applicationContext)
        updater = Updater(applicationContext)
        setContent {
            SurveyTheme {
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    // Screen is "home", "survey:<id>" or "ar:<id>".
                    var screen by rememberSaveable { mutableStateOf("home") }
                    val homeList = rememberLazyListState()
                    val scope = rememberCoroutineScope()
                    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { scope.launch { updater.check() } }
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
                            update = { UpdateChip(updater.state) { scope.launch { updater.install() } } },
                        )
                    }
                }
            }
        }
    }
}
