package com.sitesurvey.app.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import com.sitesurvey.app.data.ScanImporter

/** Shows what came out of a shared scan and creates the survey on one tap. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ImportSheet(outcome: ScanImporter.Outcome, onDismiss: () -> Unit, onCreate: () -> Unit) {
    val state = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val density = LocalDensity.current.density
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = state, dragHandle = null) {
        Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(16.dp)) {
            when (outcome) {
                is ScanImporter.Outcome.Problem -> {
                    Text("Couldn't import that scan", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(6.dp))
                    Text(outcome.message, color = Muted)
                    Spacer(Modifier.height(12.dp))
                    Button(onClick = onDismiss) { Text("OK") }
                }
                is ScanImporter.Outcome.Found -> {
                    val s = outcome.result.survey
                    Text("Room scan: ${s.name}", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(4.dp))
                    Text(outcome.result.summary, color = Muted, style = MaterialTheme.typography.bodyMedium)
                    Spacer(Modifier.height(10.dp))
                    Canvas(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).aspectRatio(planAspect(s))) {
                        drawIntoCanvas { PlanRenderer(s, PlanPalette.Dark, density).draw(it.nativeCanvas, 0f, 0f, size.width, size.height) }
                    }
                    Spacer(Modifier.height(12.dp))
                    Button(onClick = onCreate, modifier = Modifier.fillMaxWidth()) { Text("Create survey") }
                }
            }
        }
    }
}
