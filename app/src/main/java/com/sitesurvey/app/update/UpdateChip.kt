package com.sitesurvey.app.update

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.sitesurvey.app.ui.Accent
import com.sitesurvey.app.ui.Warn

/** Small chip in the home header; hidden unless there's an update. */
@Composable
fun UpdateChip(state: Updater.State, onTap: () -> Unit) {
    val (text, color, enabled) = when (state) {
        is Updater.State.Ready -> Triple("Update ready", Accent, true)
        is Updater.State.Downloading -> Triple("Updating ${state.percent}%", Accent, false)
        is Updater.State.Failed -> Triple(state.why, Warn, true)
        Updater.State.Idle -> return
    }
    Text(
        text, color = Color(0xFF00201A), style = MaterialTheme.typography.labelLarge,
        modifier = Modifier.padding(end = 4.dp).clip(RoundedCornerShape(14.dp)).background(color)
            .clickable(enabled = enabled, onClick = onTap).padding(horizontal = 12.dp, vertical = 6.dp),
    )
}
