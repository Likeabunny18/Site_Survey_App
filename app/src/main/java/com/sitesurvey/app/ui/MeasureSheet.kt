package com.sitesurvey.app.ui

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.speech.RecognizerIntent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Mic
import androidx.compose.material3.ExperimentalMaterial3Api
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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import com.sitesurvey.app.model.Units

/**
 * One field for a laser reading or any length: type 12' 6", 12 6, 150 in, 3.81 m, or say it.
 * Enter saves. The sheet rides above the keyboard.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MeasureSheet(
    title: String,
    currentM: Float?,
    onDismiss: () -> Unit,
    onSave: (Float) -> Unit,
) {
    val state = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val initial = currentM?.let { Units.ftIn(it).replace("¼", " 1/4").replace("½", " 1/2").replace("¾", " 3/4") } ?: ""
    var value by remember { mutableStateOf(TextFieldValue(initial, TextRange(0, initial.length))) }
    val parsed = Units.parse(value.text)
    val focus = remember { FocusRequester() }

    val speech = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        if (r.resultCode == Activity.RESULT_OK) {
            val heard = r.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS).orEmpty()
            val best = heard.firstOrNull { Units.parse(it) != null } ?: heard.firstOrNull()
            if (best != null) {
                value = TextFieldValue(best, TextRange(best.length))
                Units.parse(best)?.let { onSave(it) }
            }
        }
    }

    fun submit() { parsed?.let { onSave(it) } }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = state, dragHandle = null) {
        Column(
            Modifier
                .fillMaxWidth()
                .imePadding()
                .navigationBarsPadding()
                .padding(16.dp)
        ) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(10.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = value,
                    onValueChange = { value = it },
                    singleLine = true,
                    placeholder = { Text("12' 6\"  or  3.81 m") },
                    supportingText = {
                        Text(
                            when {
                                value.text.isBlank() -> "Feet-inches, inches, or meters. Enter saves."
                                parsed == null -> "Not sure what that is"
                                else -> "= ${Units.ftIn(parsed)}  (${"%.3f".format(parsed)} m)"
                            }
                        )
                    },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text, imeAction = ImeAction.Done, autoCorrectEnabled = false),
                    keyboardActions = KeyboardActions(onDone = { submit() }),
                    trailingIcon = {
                        IconButton(onClick = {
                            val i = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                                .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                                .putExtra(RecognizerIntent.EXTRA_PROMPT, "Say the measurement")
                                .putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 5)
                            try { speech.launch(i) } catch (_: ActivityNotFoundException) {}
                        }) { Icon(Icons.Outlined.Mic, "Say it") }
                    },
                    modifier = Modifier
                        .weight(1f)
                        .focusRequester(focus)
                        .onPreviewKeyEvent {
                            if (it.type == KeyEventType.KeyDown && it.key == Key.Enter && !it.isShiftPressed) { submit(); true } else false
                        },
                )
                IconButton(onClick = { submit() }, enabled = parsed != null) { Icon(Icons.Outlined.Check, "Save") }
            }
        }
    }
    LaunchedEffect(Unit) { focus.requestFocus() }
}
