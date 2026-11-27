package com.sitesurvey.app.ui

import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import com.sitesurvey.app.model.GearType
import com.sitesurvey.app.model.OpeningType
import com.sitesurvey.app.model.Survey
import com.sitesurvey.app.model.Units
import com.sitesurvey.app.model.V2
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/** Colors for the plan; the screen uses a dark set and the PDF a light one. */
data class PlanPalette(
    val background: Int,
    val floor: Int,
    val wall: Int,
    val dim: Int,
    val text: Int,
    val accent: Int,
    val speaker: Int,
    val mic: Int,
    val display: Int,
    val opening: Int,
) {
    companion object {
        val Dark = PlanPalette(
            background = 0xFF0E1114.toInt(), floor = 0xFF161C22.toInt(), wall = 0xFFE6EDF3.toInt(),
            dim = 0xFF8B98A5.toInt(), text = 0xFFE6EDF3.toInt(), accent = 0xFF3DDBB8.toInt(),
            speaker = 0xFF3DDBB8.toInt(), mic = 0xFFF2B84B.toInt(), display = 0xFF6CB6FF.toInt(),
            opening = 0xFFB18CFF.toInt(),
        )
        val Print = PlanPalette(
            background = 0xFFFFFFFF.toInt(), floor = 0xFFF5F7F9.toInt(), wall = 0xFF111111.toInt(),
            dim = 0xFF555555.toInt(), text = 0xFF111111.toInt(), accent = 0xFF00897B.toInt(),
            speaker = 0xFF00897B.toInt(), mic = 0xFFC77700.toInt(), display = 0xFF1565C0.toInt(),
            opening = 0xFF6A1B9A.toInt(),
        )
    }
}

sealed class PlanTarget {
    data class Wall(val index: Int) : PlanTarget()
    data class GearItem(val id: String) : PlanTarget()
    data object Ceiling : PlanTarget()
}

/** Where things landed on the canvas, for tap targets in edit mode. */
class PlanLayout(val targets: List<Pair<PlanTarget, android.graphics.PointF>>) {
    fun hit(x: Float, y: Float, radius: Float): PlanTarget? =
        targets.minByOrNull { (_, p) -> (p.x - x) * (p.x - x) + (p.y - y) * (p.y - y) }
            ?.takeIf { (_, p) -> (p.x - x) * (p.x - x) + (p.y - y) * (p.y - y) <= radius * radius }
            ?.first
}

/**
 * Draws a dimensioned top-down plan onto an Android canvas. One renderer feeds both the
 * on-screen plan and the PDF, so they always match.
 * [unit] is pixels per dp (screen) or points per "dp" (PDF).
 */
class PlanRenderer(
    private val s: Survey,
    private val pal: PlanPalette,
    private val unit: Float,
    private val highlight: Set<PlanTarget> = emptySet(),
) {
    private val theta = if (s.corners.size >= 2) (s.corners[1] - s.corners[0]).let { atan2(it.z, it.x) } else 0f
    private var scale = 1f
    private var ox = 0f
    private var oy = 0f

    private fun rot(p: V2): V2 {
        val c = cos(theta); val sn = sin(theta)
        return V2(p.x * c + p.z * sn, -p.x * sn + p.z * c)
    }
    private fun sx(p: V2) = ox + rot(p).x * scale
    private fun sy(p: V2) = oy + rot(p).z * scale

    fun draw(canvas: Canvas, left: Float, top: Float, width: Float, height: Float): PlanLayout {
        val targets = mutableListOf<Pair<PlanTarget, android.graphics.PointF>>()
        val bg = Paint().apply { color = pal.background }
        canvas.drawRect(left, top, left + width, top + height, bg)
        if (s.corners.isEmpty()) {
            val t = text(14f, pal.dim).apply { textAlign = Paint.Align.CENTER }
            canvas.drawText("No room yet. Open AR to capture it.", left + width / 2, top + height / 2, t)
            return PlanLayout(targets)
        }

        // Fit the room (plus room for dimensions) into the box.
        val pts = s.corners.map { rot(it) }
        val minX = pts.minOf { it.x }; val maxX = pts.maxOf { it.x }
        val minZ = pts.minOf { it.z }; val maxZ = pts.maxOf { it.z }
        val margin = 44f * unit
        val bw = max(maxX - minX, 0.5f); val bh = max(maxZ - minZ, 0.5f)
        scale = min((width - 2 * margin) / bw, (height - 2 * margin) / bh)
        ox = left + (width - bw * scale) / 2 - minX * scale
        oy = top + (height - bh * scale) / 2 - minZ * scale

        val n = s.corners.size
        val walls = s.wallCount

        // Floor
        if (s.closed && n >= 3) {
            val path = Path()
            s.corners.forEachIndexed { i, c -> if (i == 0) path.moveTo(sx(c), sy(c)) else path.lineTo(sx(c), sy(c)) }
            path.close()
            canvas.drawPath(path, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = pal.floor })
        }

        // Coverage circles under everything else
        canvas.save()
        if (s.closed && n >= 3) {
            val clip = Path()
            s.corners.forEachIndexed { i, c -> if (i == 0) clip.moveTo(sx(c), sy(c)) else clip.lineTo(sx(c), sy(c)) }
            clip.close()
            canvas.clipPath(clip)
        }
        for (g in s.gear) {
            val r = s.coverageRadius(g)
            if (r <= 0f) continue
            val col = if (g.type == GearType.CEILING_SPEAKER) pal.speaker else pal.mic
            val p = s.gearFloor(g)
            canvas.drawCircle(sx(p), sy(p), r * scale, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = withAlpha(col, 0x22) })
            canvas.drawCircle(sx(p), sy(p), r * scale, stroke(col, 1f).apply {
                pathEffect = DashPathEffect(floatArrayOf(5f * unit, 4f * unit), 0f); color = withAlpha(col, 0x99)
            })
        }
        canvas.restore()

        // Walls
        val wallPaint = stroke(pal.wall, 3f).apply { strokeCap = Paint.Cap.ROUND }
        for (i in 0 until walls) {
            val a = s.wallStart(i); val b = s.wallEnd(i)
            canvas.drawLine(sx(a), sy(a), sx(b), sy(b), wallPaint)
        }
        s.corners.forEach { c -> canvas.drawCircle(sx(c), sy(c), 2.5f * unit, fill(pal.wall)) }

        // Openings
        for (o in s.openings) {
            if (o.wall !in 0 until walls) continue
            val a = s.wallStart(o.wall); val d = s.wallDir(o.wall)
            val p0 = a + d * o.u0; val p1 = a + d * o.u1
            val gap = stroke(pal.background, 5f)
            canvas.drawLine(sx(p0), sy(p0), sx(p1), sy(p1), gap)
            val col = pal.opening
            when (o.type) {
                OpeningType.DOOR -> {
                    val inward = s.wallInward(o.wall)
                    val w = (o.u1 - o.u0)
                    val hinge = p0
                    val leafEnd = hinge + inward * w
                    canvas.drawLine(sx(hinge), sy(hinge), sx(leafEnd), sy(leafEnd), stroke(col, 1.5f))
                    val arc = Path()
                    val steps = 16
                    for (k in 0..steps) {
                        val t = k / steps.toFloat() * (Math.PI.toFloat() / 2)
                        val q = hinge + (d * cos(t) + inward * sin(t)) * w
                        if (k == 0) arc.moveTo(sx(q), sy(q)) else arc.lineTo(sx(q), sy(q))
                    }
                    canvas.drawPath(arc, stroke(col, 1f).apply { pathEffect = DashPathEffect(floatArrayOf(3f * unit, 3f * unit), 0f) })
                }
                OpeningType.WINDOW -> {
                    val nrm = s.wallInward(o.wall) * (2.5f * unit / scale)
                    for (sign in listOf(-1f, 1f)) {
                        val q0 = p0 + nrm * sign; val q1 = p1 + nrm * sign
                        canvas.drawLine(sx(q0), sy(q0), sx(q1), sy(q1), stroke(col, 1.2f))
                    }
                }
                OpeningType.OPENING -> {
                    val nrm = s.wallInward(o.wall) * (5f * unit / scale)
                    for (q in listOf(p0, p1)) {
                        val q0 = q - nrm; val q1 = q + nrm
                        canvas.drawLine(sx(q0), sy(q0), sx(q1), sy(q1), stroke(col, 1.5f))
                    }
                }
            }
        }

        // Dimensions, outside each wall
        val dimPaint = stroke(pal.dim, 1f)
        for (i in 0 until walls) {
            val a = s.wallStart(i); val b = s.wallEnd(i)
            val out = if (s.closed) s.wallInward(i) * -1f else s.wallDir(i).let { V2(-it.z, it.x) }
            val off = out * (16f * unit / scale)
            val a2 = a + off; val b2 = b + off
            canvas.drawLine(sx(a2), sy(a2), sx(b2), sy(b2), dimPaint)
            val tick = out * (4f * unit / scale)
            for (q in listOf(a2, b2)) canvas.drawLine(sx(q - tick), sy(q - tick), sx(q + tick), sy(q + tick), dimPaint)
            val mid = (a + b) * 0.5f + out * (30f * unit / scale)
            val label = Units.ftIn(s.wallLength(i))
            val hl = PlanTarget.Wall(i) in highlight
            val tp = text(12f, if (hl) pal.accent else pal.text).apply { textAlign = Paint.Align.CENTER }
            val tx = sx(mid); val ty = sy(mid) + 4f * unit
            if (hl) {
                val w = tp.measureText(label) / 2 + 6f * unit
                canvas.drawRoundRect(RectF(tx - w, ty - 15f * unit, tx + w, ty + 6f * unit), 6f * unit, 6f * unit,
                    stroke(pal.accent, 1f))
            }
            canvas.drawText(label, tx, ty, tp)
            targets += PlanTarget.Wall(i) to android.graphics.PointF(tx, ty - 4f * unit)
        }

        // Gear
        for (g in s.gear) {
            val p = s.gearFloor(g)
            val x = sx(p); val y = sy(p)
            val tag = s.tag(g)
            val hl = PlanTarget.GearItem(g.id) in highlight
            when (g.type) {
                GearType.DISPLAY -> {
                    if (g.wall in 0 until walls) {
                        val d = s.wallDir(g.wall); val inward = s.wallInward(g.wall)
                        val half = g.widthM / 2
                        val inset = inward * (5f * unit / scale)
                        val q0 = p - d * half + inset; val q1 = p + d * half + inset
                        canvas.drawLine(sx(q0), sy(q0), sx(q1), sy(q1), stroke(pal.display, 5f).apply { strokeCap = Paint.Cap.BUTT })
                        val lp = p + inward * (18f * unit / scale)
                        drawTag(canvas, "$tag ${g.sizeIn}\"", sx(lp), sy(lp), pal.display, hl)
                        targets += PlanTarget.GearItem(g.id) to android.graphics.PointF(sx(lp), sy(lp))
                    }
                }
                GearType.CEILING_SPEAKER -> {
                    val r = 6f * unit
                    canvas.drawCircle(x, y, r, stroke(pal.speaker, 1.5f))
                    val k = r * 0.7f
                    canvas.drawLine(x - k, y - k, x + k, y + k, stroke(pal.speaker, 1.2f))
                    canvas.drawLine(x - k, y + k, x + k, y - k, stroke(pal.speaker, 1.2f))
                    drawTag(canvas, tag, x, y + 18f * unit, pal.speaker, hl)
                    targets += PlanTarget.GearItem(g.id) to android.graphics.PointF(x, y)
                }
                GearType.CEILING_MIC, GearType.TABLE_MIC -> {
                    val r = 6f * unit
                    if (g.type == GearType.CEILING_MIC) {
                        canvas.drawRect(x - r, y - r, x + r, y + r, stroke(pal.mic, 1.5f))
                    } else {
                        canvas.drawCircle(x, y, r, stroke(pal.mic, 1.5f))
                    }
                    canvas.drawCircle(x, y, 2f * unit, fill(pal.mic))
                    drawTag(canvas, tag, x, y + 18f * unit, pal.mic, hl)
                    targets += PlanTarget.GearItem(g.id) to android.graphics.PointF(x, y)
                }
            }
        }
        return PlanLayout(targets)
    }

    private fun drawTag(canvas: Canvas, label: String, x: Float, y: Float, color: Int, hl: Boolean) {
        val tp = text(10.5f, color).apply { textAlign = Paint.Align.CENTER; isFakeBoldText = true }
        if (hl) {
            val w = tp.measureText(label) / 2 + 5f * unit
            canvas.drawRoundRect(RectF(x - w, y - 12f * unit, x + w, y + 5f * unit), 5f * unit, 5f * unit, stroke(color, 1f))
        }
        canvas.drawText(label, x, y, tp)
    }

    private fun stroke(c: Int, w: Float) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; color = c; strokeWidth = w * unit
    }
    private fun fill(c: Int) = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL; color = c }
    private fun text(sizeDp: Float, c: Int) = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = c; textSize = sizeDp * unit }
    private fun withAlpha(c: Int, a: Int) = (c and 0x00FFFFFF) or (a shl 24)
}

/** Width / height of the plan drawing for a survey, so the on-screen box fits the room. */
fun planAspect(s: Survey): Float {
    if (s.corners.size < 2) return 1.6f
    val e = s.corners[1] - s.corners[0]
    val t = atan2(e.z, e.x)
    val c = cos(t); val sn = sin(t)
    val pts = s.corners.map { V2(it.x * c + it.z * sn, -it.x * sn + it.z * c) }
    val w = pts.maxOf { it.x } - pts.minOf { it.x } + 2.2f
    val h = pts.maxOf { it.z } - pts.minOf { it.z } + 2.2f
    return (w / h).coerceIn(0.75f, 2.2f)
}
