package com.sitesurvey.app.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import com.sitesurvey.app.model.Gear
import com.sitesurvey.app.model.GearType
import com.sitesurvey.app.model.Survey
import com.sitesurvey.app.model.Units

/** Single-line text edit. Enter saves, Shift+Enter is ignored (single line). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TextSheet(title: String, current: String, placeholder: String = "", onDismiss: () -> Unit, onSave: (String) -> Unit) {
    val state = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var value by remember { mutableStateOf(TextFieldValue(current, TextRange(0, current.length))) }
    val focus = remember { FocusRequester() }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = state, dragHandle = null) {
        Column(Modifier.fillMaxWidth().imePadding().navigationBarsPadding().padding(16.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(10.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = value, onValueChange = { value = it }, singleLine = true,
                    placeholder = { Text(placeholder) },
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words, imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { onSave(value.text.trim()) }),
                    modifier = Modifier.weight(1f).focusRequester(focus).onPreviewKeyEvent {
                        if (it.type == KeyEventType.KeyDown && it.key == Key.Enter && !it.isShiftPressed) { onSave(value.text.trim()); true } else false
                    },
                )
                IconButton(onClick = { onSave(value.text.trim()) }) { Icon(Icons.Outlined.Check, "Save") }
            }
        }
    }
    LaunchedEffect(Unit) { focus.requestFocus() }
}

/** Everything about one piece of gear in one sheet. Lengths open the measure field. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GearSheet(
    survey: Survey,
    gear: Gear,
    onDismiss: () -> Unit,
    onChange: (Gear) -> Unit,
    onDelete: () -> Unit,
) {
    val state = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var editModel by remember { mutableStateOf(false) }
    var editNotes by remember { mutableStateOf(false) }
    var editLength by remember { mutableStateOf<String?>(null) }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = state, dragHandle = null) {
        Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("${survey.tag(gear)}  ${gear.type.label}", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                IconButton(onClick = onDelete) { Icon(Icons.Outlined.Delete, "Remove", tint = Bad) }
            }
            SheetRow("Model", gear.model.ifBlank { "Add model" }) { editModel = true }
            when (gear.type) {
                GearType.DISPLAY -> {
                    Text("Size", style = MaterialTheme.typography.labelMedium, color = Muted, modifier = Modifier.padding(top = 8.dp))
                    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Gear.DISPLAY_SIZES.forEach { sz ->
                            FilterChip(selected = gear.sizeIn == sz, onClick = { onChange(gear.copy(sizeIn = sz)) }, label = { Text("$sz\"") })
                        }
                    }
                    SheetRow("Center height (AFF)", Units.ftIn(gear.height)) { editLength = "height" }
                    SheetRow("Along wall ${gear.wall + 1} (to center)", Units.ftIn(gear.u)) { editLength = "u" }
                    val vc = survey.viewCheck(gear)
                    Text(
                        "Farthest seat ${Units.ftInShort(survey.farthestViewer(gear))} = ${"%.1f".format(vc.ratio)}× image height. ${vc.verdict}" +
                            if (!vc.ok) ". Needs about ${vc.minSizeIn}\" for 6×." else ".",
                        color = if (vc.ok) Accent else Warn, style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }
                GearType.CEILING_SPEAKER -> {
                    Text("Coverage angle", style = MaterialTheme.typography.labelMedium, color = Muted, modifier = Modifier.padding(top = 8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        listOf(60f, 90f, 120f, 150f).forEach { a ->
                            FilterChip(selected = gear.angleDeg == a, onClick = { onChange(gear.copy(angleDeg = a)) }, label = { Text("${a.toInt()}°") })
                        }
                    }
                    Text(
                        "Covers ${Units.ftInShort(survey.coverageRadius(gear) * 2)} circle at ear height (${Units.ftInShort(survey.earHeightM)}).",
                        color = Muted, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 8.dp),
                    )
                }
                GearType.CEILING_MIC, GearType.TABLE_MIC -> {
                    SheetRow("Pickup radius", Units.ftIn(gear.radiusM)) { editLength = "radius" }
                }
            }
            SheetRow("Notes", gear.notes.ifBlank { "Add notes" }) { editNotes = true }
            Spacer(Modifier.height(8.dp))
        }
    }

    if (editModel) TextSheet("Model", gear.model, gear.defaultModel(), { editModel = false }) {
        onChange(gear.copy(model = it)); editModel = false
    }
    if (editNotes) TextSheet("Notes", gear.notes, "Mounting, power, cable path…", { editNotes = false }) {
        onChange(gear.copy(notes = it)); editNotes = false
    }
    editLength?.let { which ->
        val (title, cur) = when (which) {
            "height" -> "Center height above floor" to gear.height
            "u" -> "Distance along wall to center" to gear.u
            else -> "Pickup radius" to gear.radiusM
        }
        MeasureSheet(title, cur, { editLength = null }) { m ->
            onChange(
                when (which) {
                    "height" -> gear.copy(height = m)
                    "u" -> gear.copy(u = m.coerceIn(0f, survey.wallLength(gear.wall)))
                    else -> gear.copy(radiusM = m)
                }
            )
            editLength = null
        }
    }
}

@Composable
fun SheetRow(label: String, value: String, onClick: () -> Unit) {
    Column(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(label, color = Muted, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.width(170.dp))
            Text(value, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        }
    }
    HorizontalDivider(color = Line)
}

