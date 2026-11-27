package com.sitesurvey.app.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.sitesurvey.app.model.Survey
import com.sitesurvey.app.model.Units
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun HomeScreen(
    surveys: List<Survey>,
    listState: androidx.compose.foundation.lazy.LazyListState = rememberLazyListState(),
    onNew: () -> Unit,
    onOpen: (String) -> Unit,
) {
    val fmt = SimpleDateFormat("MMM d, h:mm a", Locale.US)
    Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
        Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp, top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Surveys", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
            IconButton(onClick = onNew) { Icon(Icons.Outlined.Add, "New survey", tint = Accent) }
        }
        if (surveys.isEmpty()) {
            Text(
                "Tap + to start a survey. The camera opens and walks you through the room.",
                color = Muted, modifier = Modifier.padding(16.dp),
            )
        }
        LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
            items(surveys, key = { it.id }) { s ->
                Row(
                    Modifier.fillMaxWidth().clickable { onOpen(s.id) }.padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(s.name, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(
                            listOfNotNull(s.client.ifBlank { null }, fmt.format(Date(s.updated))).joinToString(" · "),
                            color = Muted, style = MaterialTheme.typography.bodySmall, maxLines = 1,
                        )
                    }
                    Column(horizontalAlignment = Alignment.End) {
                        Text(if (s.closed) Units.sqFt(s.area()) else "Not captured", style = MaterialTheme.typography.bodyMedium,
                            color = if (s.closed) MaterialTheme.colorScheme.onSurface else Muted)
                        Text("${s.gear.size} items", color = Muted, style = MaterialTheme.typography.bodySmall)
                    }
                }
                HorizontalDivider(color = Line)
            }
        }
    }
}
