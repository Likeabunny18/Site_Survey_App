package com.sitesurvey.app.ar

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.graphics.Paint
import android.opengl.GLSurfaceView
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.Undo
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.google.ar.core.ArCoreApk
import com.sitesurvey.app.model.Gear
import com.sitesurvey.app.model.GearType
import com.sitesurvey.app.model.Opening
import com.sitesurvey.app.model.OpeningType
import com.sitesurvey.app.model.Survey
import com.sitesurvey.app.model.Units
import com.sitesurvey.app.model.V2
import com.sitesurvey.app.model.yawOf
import com.sitesurvey.app.ui.Accent
import com.sitesurvey.app.ui.MeasureSheet
import com.sitesurvey.app.ui.Muted
import com.sitesurvey.app.ui.Warn
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

enum class Step(val label: String) { ALIGN("Align"), FLOOR("Floor"), CEILING("Ceiling"), OPENINGS("Openings"), GEAR("Gear"), VIEW("View") }

private val SpeakerC = Color(0xFF3DDBB8)
private val MicC = Color(0xFFF2B84B)
private val DisplayC = Color(0xFF6CB6FF)
private val OpeningC = Color(0xFFB18CFF)
private val RoomC = Color(0xCCFFFFFF)

/** A reading the person can correct with the laser: which wall, or the ceiling. */
private sealed class Reading {
    data class Wall(val i: Int) : Reading()
    data object Ceiling : Reading()
}

private data class PendingCorner(val wall: Int, val u: Float, val v: Float)

@Composable
fun ArScreen(survey: Survey, onUpdate: ((Survey) -> Survey) -> Unit, onBack: () -> Unit) {
    val context = LocalContext.current
    val activity = context as Activity
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val density = LocalDensity.current.density

    var permission by remember {
        mutableStateOf(ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED)
    }
    val askPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { permission = it }
    LaunchedEffect(Unit) { if (!permission) askPermission.launch(Manifest.permission.CAMERA) }

    var error by remember { mutableStateOf<String?>(null) }
    var snap by remember { mutableStateOf<ArSnapshot?>(null) }
    val engine = remember { ArEngine(context.applicationContext) }
    val glView = remember {
        GLSurfaceView(context).apply {
            preserveEGLContextOnPause = true
            setEGLContextClientVersion(2)
            setEGLConfigChooser(8, 8, 8, 8, 16, 0)
            setRenderer(engine)
            renderMode = GLSurfaceView.RENDERMODE_CONTINUOUSLY
            keepScreenOn = true
            engine.view = this
        }
    }
    engine.onSnapshot = { snap = it }

    // Session lifecycle: install ARCore if needed, create, resume and pause with the screen.
    DisposableEffect(permission) {
        var installRequested = false
        var running = false
        fun start() {
            if (!permission || running) return
            if (engine.session == null) {
                try {
                    when (ArCoreApk.getInstance().requestInstall(activity, !installRequested)) {
                        ArCoreApk.InstallStatus.INSTALL_REQUESTED -> { installRequested = true; return }
                        else -> {}
                    }
                } catch (e: Exception) {
                    error = "Google Play Services for AR is needed. Install it from the Play Store."; return
                }
                engine.createSession()?.let { error = it; return }
            }
            engine.resume()?.let { error = it; return }
            running = true
        }
        fun stop() { if (running) { engine.pause(); running = false } }
        val obs = LifecycleEventObserver { _, e ->
            when (e) {
                Lifecycle.Event.ON_RESUME -> start()
                Lifecycle.Event.ON_PAUSE -> stop()
                else -> {}
            }
        }
        lifecycle.addObserver(obs)
        if (lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) start()
        onDispose {
            lifecycle.removeObserver(obs)
            stop()
            engine.close()
        }
    }

    // Capture state
    var step by remember { mutableStateOf(if (survey.corners.isEmpty()) Step.FLOOR else Step.ALIGN) }
    var anchored by remember { mutableStateOf(false) }
    var alignA by remember { mutableStateOf(false) }
    var openingType by remember { mutableStateOf(OpeningType.DOOR) }
    var gearType by remember { mutableStateOf(GearType.DISPLAY) }
    var displaySize by remember { mutableStateOf(75) }
    var pending by remember { mutableStateOf<PendingCorner?>(null) }
    var reading by remember { mutableStateOf<Reading?>(null) }
    var editReading by remember { mutableStateOf(false) }
    var toast by remember { mutableStateOf<String?>(null) }
    var confirmRestart by remember { mutableStateOf(false) }
    LaunchedEffect(toast) { if (toast != null) { kotlinx.coroutines.delay(2500); toast = null } }

    val s = snap
    val proj = s?.mvp?.let { Projector(it, s.width.toFloat(), s.height.toFloat()) }
    val ray = proj?.ray()

    // What the crosshair is on right now, for the current step.
    val aimFloor = ray?.let { hitPlaneY(it, 0f) }
    val aimCeilingCorner = if (step == Step.CEILING && ray != null && survey.closed) hitCornerHeight(ray, survey) else null
    val aimWall = if ((step == Step.OPENINGS || (step == Step.GEAR && gearType == GearType.DISPLAY)) && ray != null) hitWall(ray, survey) else null
    val aimCeiling = if (step == Step.GEAR && ray != null) {
        val y = if (gearType == GearType.TABLE_MIC) Gear.TABLE_HEIGHT else survey.ceilingM
        hitPlaneY(ray, y)?.takeIf { survey.contains(it.floor) }
    } else null

    val canCapture = when (step) {
        Step.ALIGN -> if (!alignA) s?.floorUnderCrosshair == true else aimFloor != null
        Step.FLOOR -> if (!anchored) s?.floorUnderCrosshair == true else aimFloor != null
        Step.CEILING -> aimCeilingCorner != null
        Step.OPENINGS -> aimWall != null && (pending == null || pending!!.wall == aimWall.wall)
        Step.GEAR -> if (gearType == GearType.DISPLAY) aimWall != null else aimCeiling != null
        Step.VIEW -> false
    }

    fun closeRoom() {
        onUpdate { r ->
            val c = r.copy(closed = true)
            if (c.isNearRectangle()) c.squared() else c
        }
        if (survey.corners.size == 4) toast = "Room closed. Squared the corners."
        reading = null
        step = Step.CEILING
    }

    fun capture() {
        when (step) {
            Step.ALIGN -> {
                if (!alignA) {
                    engine.anchorAtCrosshair { ok -> if (ok) { alignA = true; anchored = true } else toast = "No floor there yet" }
                } else {
                    val p = aimFloor ?: return
                    val d = V2(p.x, p.z)
                    val e = survey.corners[1] - survey.corners[0]
                    val yaw = yawOf(d) - yawOf(e)
                    val err = d.len() - e.len()
                    engine.rotateAnchor(yaw) {
                        step = if (survey.gear.isEmpty()) Step.GEAR else Step.VIEW
                        toast = if (kotlin.math.abs(err) > 0.15f) "Aligned. Corner B was ${Units.ftInShort(kotlin.math.abs(err))} off." else "Aligned"
                    }
                }
            }
            Step.FLOOR -> {
                if (!anchored) {
                    engine.anchorAtCrosshair { ok ->
                        if (ok) { anchored = true; onUpdate { it.copy(corners = listOf(V2(0f, 0f)), closed = false) } }
                        else toast = "No floor there yet. Move the phone slowly."
                    }
                } else {
                    val p = aimFloor ?: return
                    val v = V2(p.x, p.z)
                    if (survey.corners.size >= 3 && (v - survey.corners[0]).len() < 0.25f) { closeRoom(); return }
                    onUpdate { it.copy(corners = it.corners + v) }
                    reading = Reading.Wall(survey.corners.size - 1)
                }
            }
            Step.CEILING -> {
                val (_, h) = aimCeilingCorner ?: return
                onUpdate { it.copy(ceilingM = h, ceilingMeasured = true) }
                reading = Reading.Ceiling
                step = Step.OPENINGS
            }
            Step.OPENINGS -> {
                val w = aimWall ?: return
                val first = pending
                if (first == null) {
                    pending = PendingCorner(w.wall, w.u, w.v)
                } else if (first.wall == w.wall) {
                    val v0 = if (openingType == OpeningType.DOOR) 0f else minOf(first.v, w.v)
                    val o = Opening(type = openingType, wall = w.wall, u0 = minOf(first.u, w.u), u1 = maxOf(first.u, w.u), v0 = v0, v1 = maxOf(first.v, w.v))
                    if (o.u1 - o.u0 > 0.1f) onUpdate { it.copy(openings = it.openings + o) }
                    pending = null
                }
            }
            Step.GEAR -> {
                val g = when (gearType) {
                    GearType.DISPLAY -> aimWall?.let { Gear(type = GearType.DISPLAY, wall = it.wall, u = it.u, height = it.v, sizeIn = displaySize) }
                    GearType.CEILING_SPEAKER -> aimCeiling?.let { Gear(type = gearType, x = it.x, z = it.z, angleDeg = 90f) }
                    GearType.CEILING_MIC -> aimCeiling?.let { Gear(type = gearType, x = it.x, z = it.z, radiusM = 3.05f) }
                    GearType.TABLE_MIC -> aimCeiling?.let { Gear(type = gearType, x = it.x, z = it.z, radiusM = 1.5f) }
                } ?: return
                onUpdate { it.copy(gear = it.gear + g) }
                toast = "${survey.copy(gear = survey.gear + g).tag(g)} placed"
            }
            Step.VIEW -> {}
        }
    }

    fun undo() {
        when (step) {
            Step.FLOOR -> when {
                survey.closed -> onUpdate { it.copy(closed = false) }
                survey.corners.size > 1 -> { onUpdate { it.copy(corners = it.corners.dropLast(1)) }; reading = null }
                survey.corners.size == 1 -> { onUpdate { it.copy(corners = emptyList()) }; engine.clearAnchor(); anchored = false; reading = null }
            }
            Step.OPENINGS -> if (pending != null) pending = null else onUpdate { it.copy(openings = it.openings.dropLast(1)) }
            Step.GEAR -> onUpdate { it.copy(gear = it.gear.dropLast(1)) }
            Step.ALIGN -> { engine.clearAnchor(); alignA = false; anchored = false }
            else -> {}
        }
    }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        AndroidView(factory = { glView }, modifier = Modifier.fillMaxSize())

        // Overlay: the room, gear and crosshair, drawn from the latest camera pose.
        Canvas(Modifier.fillMaxSize()) {
            val p = proj ?: return@Canvas
            val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = android.graphics.Color.WHITE; textSize = 13f * density; textAlign = Paint.Align.CENTER
                setShadowLayer(4f, 0f, 0f, android.graphics.Color.BLACK)
            }
            fun line(a: Vec3, b: Vec3, c: Color, w: Float = 2f) {
                p.segment(a, b)?.let { (x, y) -> drawLine(c, x, y, strokeWidth = w * density) }
            }
            fun label(at: Vec3, t: String, c: Int = android.graphics.Color.WHITE) {
                p.point(at)?.let { o -> textPaint.color = c; drawContext.canvas.nativeCanvas.drawText(t, o.x, o.y, textPaint) }
            }
            fun circle(center: Vec3, r: Float, c: Color, fill: Boolean) {
                val pts = (0..32).map { k ->
                    val a = k / 32f * 2f * PI.toFloat()
                    center + Vec3(cos(a) * r, 0f, sin(a) * r)
                }
                for (k in 0 until 32) line(pts[k], pts[k + 1], c, 1.5f)
                if (fill) {
                    val sp = pts.map { p.point(it) }
                    if (sp.all { it != null }) {
                        val path = Path().apply { sp.forEachIndexed { i, o -> if (i == 0) moveTo(o!!.x, o.y) else lineTo(o!!.x, o.y) }; close() }
                        drawPath(path, c.copy(alpha = 0.15f))
                    }
                }
            }
            fun wallPoint(wall: Int, u: Float, v: Float): Vec3 =
                (survey.wallStart(wall) + survey.wallDir(wall) * u).at(v)
            fun wallRect(wall: Int, u0: Float, u1: Float, v0: Float, v1: Float, c: Color, fill: Boolean = false) {
                val a = wallPoint(wall, u0, v0); val b = wallPoint(wall, u1, v0)
                val cc = wallPoint(wall, u1, v1); val d = wallPoint(wall, u0, v1)
                line(a, b, c); line(b, cc, c); line(cc, d, c); line(d, a, c)
                if (fill) {
                    val sp = listOf(a, b, cc, d).map { p.point(it) }
                    if (sp.all { it != null }) drawPath(Path().apply {
                        moveTo(sp[0]!!.x, sp[0]!!.y); sp.drop(1).forEach { lineTo(it!!.x, it.y) }; close()
                    }, c.copy(alpha = 0.35f))
                }
            }

            val h = survey.ceilingM
            val showCeiling = survey.closed && step != Step.FLOOR
            // Room wireframe
            for (i in 0 until survey.wallCount) {
                val a = survey.wallStart(i); val b = survey.wallEnd(i)
                line(a.at(0f), b.at(0f), RoomC)
                if (showCeiling) line(a.at(h), b.at(h), RoomC.copy(alpha = 0.5f), 1.5f)
                if (step == Step.FLOOR || step == Step.ALIGN) label(((a + b) * 0.5f).at(0.02f), Units.ftInShort(survey.wallLength(i)))
            }
            if (showCeiling) survey.corners.forEach { c -> line(c.at(0f), c.at(h), RoomC.copy(alpha = 0.5f), 1.5f) }
            survey.corners.forEachIndexed { i, c ->
                p.point(c.at(0f))?.let { o -> drawCircle(if (i == 0) Accent else Color.White, 4f * density, o) }
                if (step == Step.ALIGN && i <= 1) label(c.at(0.15f), if (i == 0) "A" else "B", android.graphics.Color.rgb(61, 219, 184))
            }

            // Openings
            survey.openings.forEach { o -> if (o.wall in 0 until survey.wallCount) wallRect(o.wall, o.u0, o.u1, o.v0, o.v1, OpeningC) }

            // Gear
            survey.gear.forEach { g ->
                val f = survey.gearFloor(g)
                when (g.type) {
                    GearType.DISPLAY -> if (g.wall in 0 until survey.wallCount) {
                        val hw = g.widthM / 2; val hh = g.imageHeightM / 2
                        wallRect(g.wall, g.u - hw, g.u + hw, g.height - hh, g.height + hh, DisplayC, fill = true)
                        label(wallPoint(g.wall, g.u, g.height), "${survey.tag(g)} ${g.sizeIn}\"")
                    }
                    GearType.CEILING_SPEAKER -> {
                        val apex = f.at(h)
                        val r = survey.coverageRadius(g)
                        val ear = survey.earHeightM
                        circle(apex, 0.1f, SpeakerC, true)
                        circle(f.at(ear), r, SpeakerC, true)
                        for (k in 0 until 4) {
                            val a = k * PI.toFloat() / 2
                            line(apex, f.at(ear) + Vec3(cos(a) * r, 0f, sin(a) * r), SpeakerC.copy(alpha = 0.6f), 1.2f)
                        }
                        label(apex - Vec3(0f, 0.2f, 0f), survey.tag(g))
                    }
                    GearType.CEILING_MIC, GearType.TABLE_MIC -> {
                        val y = survey.gearHeight(g)
                        circle(f.at(y), 0.08f, MicC, true)
                        circle(f.at(if (g.type == GearType.CEILING_MIC) survey.earHeightM else y), g.radiusM, MicC, true)
                        label(f.at(y + 0.15f), survey.tag(g))
                    }
                }
            }

            // Live previews at the crosshair
            when (step) {
                Step.FLOOR -> if (aimFloor != null && survey.corners.isNotEmpty() && !survey.closed) {
                    val last = survey.corners.last().at(0f)
                    line(last, aimFloor, Accent, 2.5f)
                    label((last + aimFloor) * 0.5f, Units.ftInShort((aimFloor - last).len()))
                }
                Step.CEILING -> aimCeilingCorner?.let { (i, hh) ->
                    val c = survey.corners[i]
                    line(c.at(0f), c.at(hh), Accent, 3f)
                    label(c.at(hh / 2), "Ceiling ${Units.ftIn(hh)}")
                }
                Step.OPENINGS -> aimWall?.let { w ->
                    val first = pending
                    if (first != null && first.wall == w.wall) {
                        val v0 = if (openingType == OpeningType.DOOR) 0f else minOf(first.v, w.v)
                        wallRect(w.wall, minOf(first.u, w.u), maxOf(first.u, w.u), v0, maxOf(first.v, w.v), Accent)
                        label(w.point, Units.ftInShort(kotlin.math.abs(w.u - first.u)) + " × " + Units.ftInShort(maxOf(first.v, w.v) - v0))
                    }
                }
                Step.GEAR -> when (gearType) {
                    GearType.DISPLAY -> aimWall?.let { w ->
                        val g = Gear(type = GearType.DISPLAY, sizeIn = displaySize)
                        wallRect(w.wall, w.u - g.widthM / 2, w.u + g.widthM / 2, w.v - g.imageHeightM / 2, w.v + g.imageHeightM / 2, Accent)
                        label(w.point, "$displaySize\"  center ${Units.ftInShort(w.v)} AFF")
                    }
                    else -> aimCeiling?.let { c -> circle(c, 0.12f, Accent, true) }
                }
                else -> {}
            }

            // Crosshair
            if (step != Step.VIEW) {
                val c = Offset(size.width / 2, size.height / 2)
                val col = if (canCapture) Accent else Color.White.copy(alpha = 0.7f)
                drawCircle(col, 14f * density, c, style = Stroke(2f * density))
                drawCircle(col, 2.5f * density, c)
            }
        }
        // Before anchoring there's no matrix yet, but we still want a crosshair.
        if (proj == null && step != Step.VIEW) {
            Canvas(Modifier.fillMaxSize()) {
                val c = Offset(size.width / 2, size.height / 2)
                val col = if (canCapture) Accent else Color.White.copy(alpha = 0.7f)
                drawCircle(col, 14f * density, c, style = Stroke(2f * density))
                drawCircle(col, 2.5f * density, c)
            }
        }

        // Top: back + one instruction line
        Row(Modifier.fillMaxWidth().statusBarsPadding().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
            RoundIcon(onClick = onBack) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "Back", tint = Color.White) }
            Spacer(Modifier.width(8.dp))
            val msg = error ?: s?.message ?: instruction(step, survey, anchored, alignA, pending != null, s?.floorUnderCrosshair == true)
            Text(
                msg, color = Color.White, style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f).clip(RoundedCornerShape(10.dp)).background(Color(0x99000000)).padding(horizontal = 12.dp, vertical = 8.dp),
            )
        }
        if (step == Step.ALIGN) MiniMap(survey, Modifier.align(Alignment.TopEnd).statusBarsPadding().padding(top = 64.dp, end = 12.dp))

        // Bottom controls
        Column(
            Modifier.align(Alignment.BottomCenter).fillMaxWidth().navigationBarsPadding().padding(bottom = 8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            toast?.let {
                Text(it, color = Color.White, modifier = Modifier.clip(RoundedCornerShape(10.dp)).background(Color(0xCC000000)).padding(horizontal = 12.dp, vertical = 6.dp))
                Spacer(Modifier.height(8.dp))
            }
            // Laser correction chip for the last thing measured.
            val r = reading
            if (r != null && (step == Step.FLOOR || step == Step.CEILING || step == Step.OPENINGS)) {
                val text = when (r) {
                    is Reading.Wall -> if (r.i < survey.wallCount) "Wall ${r.i + 1}  ${Units.ftIn(survey.wallLength(r.i))}" else null
                    Reading.Ceiling -> "Ceiling  ${Units.ftIn(survey.ceilingM)}"
                }
                if (text != null) {
                    Row(
                        Modifier.clip(RoundedCornerShape(18.dp)).background(Color(0xCC000000)).clickable { editReading = true }
                            .padding(horizontal = 14.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(text, color = Color.White)
                        Spacer(Modifier.width(8.dp))
                        Icon(Icons.Outlined.Edit, "Enter laser reading", tint = Accent, modifier = Modifier.size(16.dp))
                    }
                    Spacer(Modifier.height(8.dp))
                }
            }
            // Choices for the current step
            when (step) {
                Step.OPENINGS -> ChipRow(OpeningType.entries.map { it.label }, openingType.ordinal) { openingType = OpeningType.entries[it]; pending = null }
                Step.GEAR -> {
                    if (gearType == GearType.DISPLAY) {
                        ChipRow(Gear.DISPLAY_SIZES.map { "$it\"" }, Gear.DISPLAY_SIZES.indexOf(displaySize)) { displaySize = Gear.DISPLAY_SIZES[it] }
                        Spacer(Modifier.height(6.dp))
                    }
                    ChipRow(listOf("Display", "Speaker", "Ceiling mic", "Table mic"), gearType.ordinal) { gearType = GearType.entries[it] }
                }
                Step.ALIGN -> if (survey.corners.isNotEmpty()) {
                    Text(
                        if (confirmRestart) "Tap again to erase and recapture" else "Recapture room",
                        color = if (confirmRestart) Warn else Muted,
                        modifier = Modifier.clip(RoundedCornerShape(16.dp)).background(Color(0x99000000)).clickable {
                            if (confirmRestart) {
                                onUpdate { it.copy(corners = emptyList(), closed = false, openings = emptyList(), gear = emptyList(), ceilingMeasured = false) }
                                engine.clearAnchor(); anchored = false; alignA = false; step = Step.FLOOR; confirmRestart = false
                            } else confirmRestart = true
                        }.padding(horizontal = 14.dp, vertical = 8.dp),
                    )
                }
                else -> {}
            }
            Spacer(Modifier.height(10.dp))
            // Undo · Capture · Next
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(28.dp)) {
                RoundIcon(onClick = { undo() }, enabled = step != Step.VIEW && step != Step.CEILING) {
                    Icon(Icons.AutoMirrored.Outlined.Undo, "Undo", tint = Color.White)
                }
                Box(
                    Modifier.size(72.dp).clip(CircleShape)
                        .background(if (canCapture) Accent else Color(0x66FFFFFF))
                        .border(3.dp, Color.White.copy(alpha = 0.8f), CircleShape)
                        .clickable(enabled = canCapture) { capture() },
                    contentAlignment = Alignment.Center,
                ) { if (step != Step.VIEW) Icon(Icons.Outlined.Add, "Capture", tint = Color(0xFF00201A), modifier = Modifier.size(34.dp)) }
                RoundIcon(
                    onClick = {
                        when (step) {
                            Step.FLOOR -> if (!survey.closed && survey.corners.size >= 3) closeRoom() else if (survey.closed) step = Step.CEILING
                            Step.CEILING -> step = Step.OPENINGS
                            Step.OPENINGS -> { pending = null; step = Step.GEAR }
                            Step.GEAR -> step = Step.VIEW
                            Step.VIEW -> onBack()
                            Step.ALIGN -> {}
                        }
                    },
                    enabled = step != Step.ALIGN && (step != Step.FLOOR || survey.corners.size >= 3),
                ) { Icon(Icons.Outlined.Check, "Next", tint = Color.White) }
            }
            Spacer(Modifier.height(12.dp))
            // Steps
            Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                Step.entries.filter { it != Step.ALIGN }.forEach { st ->
                    val enabled = step != Step.ALIGN && (st == Step.FLOOR || survey.closed)
                    Text(
                        st.label,
                        color = when { st == step -> Accent; enabled -> Color.White; else -> Color.White.copy(alpha = 0.35f) },
                        style = MaterialTheme.typography.labelLarge,
                        modifier = Modifier.clip(RoundedCornerShape(14.dp))
                            .background(if (st == step) Color(0xCC000000) else Color(0x66000000))
                            .clickable(enabled = enabled) { step = st; pending = null }
                            .padding(horizontal = 12.dp, vertical = 7.dp),
                    )
                }
            }
        }
    }

    if (editReading) {
        when (val r = reading) {
            is Reading.Wall -> MeasureSheet("Wall ${r.i + 1}: laser reading", survey.wallLength(r.i), { editReading = false }) { m ->
                onUpdate { it.withWallLength(r.i, m) }; editReading = false
            }
            Reading.Ceiling -> MeasureSheet("Ceiling: laser reading", survey.ceilingM, { editReading = false }) { m ->
                onUpdate { it.copy(ceilingM = m, ceilingMeasured = true) }; editReading = false
            }
            null -> editReading = false
        }
    }
}

private fun instruction(step: Step, s: Survey, anchored: Boolean, alignA: Boolean, pending: Boolean, floor: Boolean): String = when (step) {
    Step.ALIGN -> if (!alignA) (if (floor) "Aim at corner A (solid dot on the map) and tap +" else "Scanning the floor. Move the phone slowly.")
        else "Now aim at corner B (ring on the map) and tap +"
    Step.FLOOR -> when {
        !anchored -> if (floor) "Aim at a floor corner and tap +" else "Scanning the floor. Move the phone slowly."
        s.closed -> "Room closed. Tap ✓ for ceiling height."
        s.corners.size < 3 -> "Aim at the next corner and tap +"
        else -> "Next corner, or tap ✓ to close the room"
    }
    Step.CEILING -> "Aim at where a corner meets the ceiling and tap +"
    Step.OPENINGS -> if (pending) "Now aim at the opposite corner" else "Aim at one corner of a door or window and tap +. ✓ when done."
    Step.GEAR -> "Aim where it goes and tap +"
    Step.VIEW -> "Walkthrough. Walk the room with your client."
}

@Composable
private fun RoundIcon(onClick: () -> Unit, enabled: Boolean = true, content: @Composable () -> Unit) {
    Box(
        Modifier.size(48.dp).clip(CircleShape).background(Color(0x99000000)).clickable(enabled = enabled, onClick = onClick)
            .then(if (enabled) Modifier else Modifier.background(Color(0x66000000))),
        contentAlignment = Alignment.Center,
    ) { content() }
}

@Composable
private fun ChipRow(labels: List<String>, selected: Int, onSelect: (Int) -> Unit) {
    Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        labels.forEachIndexed { i, l ->
            Text(
                l, color = if (i == selected) Color(0xFF00201A) else Color.White,
                style = MaterialTheme.typography.labelLarge, textAlign = TextAlign.Center,
                modifier = Modifier.clip(RoundedCornerShape(16.dp))
                    .background(if (i == selected) Accent else Color(0x99000000))
                    .clickable { onSelect(i) }
                    .padding(horizontal = 12.dp, vertical = 7.dp),
            )
        }
    }
}

/** Small plan with corners A and B marked, so the person knows where to aim when re-aligning. */
@Composable
private fun MiniMap(s: Survey, modifier: Modifier) {
    Canvas(modifier.size(110.dp).clip(RoundedCornerShape(10.dp)).background(Color(0xAA000000))) {
        if (s.corners.size < 2) return@Canvas
        val minX = s.corners.minOf { it.x }; val maxX = s.corners.maxOf { it.x }
        val minZ = s.corners.minOf { it.z }; val maxZ = s.corners.maxOf { it.z }
        val pad = 14.dp.toPx()
        val sc = minOf((size.width - 2 * pad) / maxOf(maxX - minX, 0.5f), (size.height - 2 * pad) / maxOf(maxZ - minZ, 0.5f))
        fun o(v: V2) = Offset(pad + (v.x - minX) * sc, pad + (v.z - minZ) * sc)
        for (i in 0 until s.wallCount) drawLine(Color.White, o(s.wallStart(i)), o(s.wallEnd(i)), 2.dp.toPx())
        drawCircle(Accent, 5.dp.toPx(), o(s.corners[0]))
        drawCircle(Accent, 5.dp.toPx(), o(s.corners[1]), style = Stroke(2.dp.toPx()))
        drawLine(Accent, o(s.corners[0]), o(s.corners[1]), 3.dp.toPx())
    }
}

