package com.sitesurvey.app.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material.icons.outlined.ViewInAr
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.sitesurvey.app.model.GearType
import com.sitesurvey.app.model.Survey
import com.sitesurvey.app.model.Units
import com.sitesurvey.app.model.locationOf

@Composable
fun SurveyScreen(
    survey: Survey,
    scroll: ScrollState,
    onBack: () -> Unit,
    onAr: () -> Unit,
    onExport: () -> Unit,
    onUpdate: ((Survey) -> Survey) -> Unit,
    onDelete: () -> Unit,
) {
    var edit by rememberSaveable { mutableStateOf(false) }
    var sheet by remember { mutableStateOf<PlanTarget?>(null) }
    var textEdit by remember { mutableStateOf<String?>(null) }
    var confirmDelete by remember { mutableStateOf(false) }
    val density = LocalDensity.current.density
    val layoutHolder = remember { arrayOfNulls<PlanLayout>(1) }

    Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().verticalScroll(scroll)) {
        // Header: back, name, three actions.
        Row(Modifier.fillMaxWidth().padding(end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "Back") }
            Column(
                Modifier.weight(1f).then(if (edit) Modifier.clickable { textEdit = "name" } else Modifier)
            ) {
                Text(survey.name, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    color = if (edit) Accent else MaterialTheme.colorScheme.onSurface)
                if (survey.client.isNotBlank() || edit) {
                    Text(survey.client.ifBlank { "Add client" }, style = MaterialTheme.typography.bodySmall, color = Muted,
                        modifier = if (edit) Modifier.clickable { textEdit = "client" } else Modifier)
                }
            }
            IconButton(onClick = onAr) { Icon(Icons.Outlined.ViewInAr, "AR") }
            IconButton(onClick = { edit = !edit }) {
                if (edit) Icon(Icons.Outlined.Check, "Done editing", tint = Accent) else Icon(Icons.Outlined.Edit, "Edit")
            }
            IconButton(onClick = onExport, enabled = survey.closed) { Icon(Icons.Outlined.Share, "Export") }
        }

        // Plan
        val highlight: Set<PlanTarget> = if (edit) buildSet {
            for (i in 0 until survey.wallCount) add(PlanTarget.Wall(i))
            survey.gear.forEach { add(PlanTarget.GearItem(it.id)) }
        } else emptySet()
        Canvas(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp)
                .clip(RoundedCornerShape(10.dp))
                .aspectRatio(planAspect(survey))
                .pointerInput(edit, survey) {
                    detectTapGestures { off ->
                        if (!edit) return@detectTapGestures
                        layoutHolder[0]?.hit(off.x, off.y, 28f * density)?.let { sheet = it }
                    }
                }
        ) {
            drawIntoCanvas { c ->
                layoutHolder[0] = PlanRenderer(survey, PlanPalette.Dark, density, highlight)
                    .draw(c.nativeCanvas, 0f, 0f, size.width, size.height)
            }
        }
        if (edit) {
            Text("Tap a dimension or item to change it. Laser readings go here.",
                color = Muted, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp))
        }

        // Room facts in one row.
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp), horizontalArrangement = Arrangement.SpaceBetween) {
            Fact("Area", if (survey.closed) Units.sqFt(survey.area()) else "—")
            Fact("Ceiling", Units.ftInShort(survey.ceilingM) + if (survey.ceilingMeasured) "" else " est.",
                onClick = if (edit) ({ sheet = PlanTarget.Ceiling }) else null)
            Fact("Perimeter", if (survey.closed) Units.ftInShort(survey.perimeter()) else "—")
            Fact("Openings", survey.openings.size.toString())
        }

        // Display checks
        survey.gear.filter { it.type == GearType.DISPLAY }.forEach { g ->
            val vc = survey.viewCheck(g)
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "${survey.tag(g)} ${g.sizeIn}\": farthest seat ${Units.ftInShort(survey.farthestViewer(g))}, " +
                        "${"%.1f".format(vc.ratio)}× image height. ${vc.verdict}" + if (!vc.ok) ", go ${vc.minSizeIn}\"+" else "",
                    color = if (vc.ok) Accent else Warn, style = MaterialTheme.typography.bodySmall,
                )
            }
        }

        // Equipment table
        Spacer(Modifier.height(10.dp))
        Row(Modifier.fillMaxWidth().background(Surface1).padding(horizontal = 16.dp, vertical = 8.dp)) {
            Cell("Tag", 52.dp, header = true); Cell("Item", null, header = true); Cell("Location", 132.dp, header = true)
        }
        if (survey.gear.isEmpty()) {
            Text("No gear yet. Place it in AR.", color = Muted, modifier = Modifier.padding(16.dp))
        }
        survey.gear.sortedBy { it.type.ordinal }.forEach { g ->
            Row(
                Modifier.fillMaxWidth()
                    .then(if (edit) Modifier.clickable { sheet = PlanTarget.GearItem(g.id) } else Modifier)
                    .padding(horizontal = 16.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Cell(survey.tag(g), 52.dp, color = if (edit) Accent else null)
                Column(Modifier.weight(1f)) {
                    Text(g.displayName, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    if (g.notes.isNotBlank()) Text(g.notes, color = Muted, style = MaterialTheme.typography.bodySmall, maxLines = 2)
                }
                Cell(locationOf(survey, g), 132.dp, muted = true)
            }
            HorizontalDivider(color = Line)
        }
        if (edit) {
            TextButton(onClick = { if (confirmDelete) onDelete() else confirmDelete = true }, modifier = Modifier.padding(8.dp)) {
                Text(if (confirmDelete) "Tap again to delete this survey" else "Delete survey", color = Bad)
            }
        }
        Spacer(Modifier.height(24.dp))
    }

    when (val t = sheet) {
        is PlanTarget.Wall -> MeasureSheet("Wall ${t.index + 1} length", survey.wallLength(t.index), { sheet = null }) { m ->
            onUpdate { it.withWallLength(t.index, m) }; sheet = null
        }
        is PlanTarget.Ceiling -> MeasureSheet("Ceiling height", survey.ceilingM, { sheet = null }) { m ->
            onUpdate { it.copy(ceilingM = m, ceilingMeasured = true) }; sheet = null
        }
        is PlanTarget.GearItem -> {
            val g = survey.gear.firstOrNull { it.id == t.id }
            if (g != null) GearSheet(
                survey, g, { sheet = null },
                onChange = { ng -> onUpdate { s -> s.copy(gear = s.gear.map { if (it.id == ng.id) ng else it }) } },
                onDelete = { onUpdate { s -> s.copy(gear = s.gear.filterNot { it.id == g.id }) }; sheet = null },
            )
        }
        null -> {}
    }
    when (textEdit) {
        "name" -> TextSheet("Survey name", survey.name, "Room or site", { textEdit = null }) { v ->
            if (v.isNotBlank()) onUpdate { it.copy(name = v) }; textEdit = null
        }
        "client" -> TextSheet("Client", survey.client, "Client name", { textEdit = null }) { v ->
            onUpdate { it.copy(client = v) }; textEdit = null
        }
    }
}

@Composable
private fun Fact(label: String, value: String, onClick: (() -> Unit)? = null) {
    Column(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier) {
        Text(label, color = Muted, style = MaterialTheme.typography.labelSmall)
        Text(value, style = MaterialTheme.typography.bodyMedium, color = if (onClick != null) Accent else MaterialTheme.colorScheme.onSurface)
    }
}

@Composable
private fun androidx.compose.foundation.layout.RowScope.Cell(
    text: String, width: androidx.compose.ui.unit.Dp?, header: Boolean = false, muted: Boolean = false,
    color: androidx.compose.ui.graphics.Color? = null,
) {
    val m = if (width == null) Modifier.weight(1f) else Modifier.width(width)
    Text(
        text, modifier = m, maxLines = 2, overflow = TextOverflow.Ellipsis,
        style = if (header) MaterialTheme.typography.labelMedium else MaterialTheme.typography.bodyMedium,
        fontWeight = if (header) FontWeight.Medium else null,
        color = color ?: if (header || muted) Muted else MaterialTheme.colorScheme.onSurface,
    )
}
