package com.sitesurvey.app.model

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.floatOrNull
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Turns a LiDAR room scan (Apple RoomPlan, exported as OBJ or RoomPlan JSON) into a Survey:
 * floor outline, ceiling height, doors, windows and openings. Pure Kotlin, so iOS can use it too.
 */
object RoomImport {
    enum class Kind { WALL, DOOR, WINDOW, OPENING, FLOOR }

    /** One scanned element as an upright box: center on the floor plan, facing direction, size. */
    data class Box(val kind: Kind, val cx: Float, val cz: Float, val dirX: Float, val dirZ: Float, val width: Float, val minY: Float, val maxY: Float)

    data class Result(val survey: Survey, val summary: String, val exact: Boolean)

    fun import(text: String, name: String): Result? {
        val t = text.trimStart()
        val boxes = when {
            t.startsWith("{") -> parseRoomPlanJson(t)
            else -> parseObj(text)
        } ?: return null
        return build(boxes.first, boxes.second, name)
    }

    // ---------- OBJ ----------

    private fun kindOf(name: String): Kind? {
        val n = name.lowercase()
        return when {
            "wall" in n -> Kind.WALL
            "door" in n -> Kind.DOOR
            "window" in n -> Kind.WINDOW
            "opening" in n -> Kind.OPENING
            "floor" in n -> Kind.FLOOR
            else -> null
        }
    }

    /** Returns the named boxes, plus every vertex (for a fallback outline). */
    fun parseObj(text: String): Pair<List<Box>, List<Triple<Float, Float, Float>>>? {
        val verts = ArrayList<Triple<Float, Float, Float>>()
        val groups = LinkedHashMap<String, MutableSet<Int>>()
        var group = ""
        var material = ""
        for (raw in text.lineSequence()) {
            val line = raw.trim()
            if (line.isEmpty() || line[0] == '#') continue
            val parts = line.split(Regex("\\s+"))
            when (parts[0]) {
                "v" -> if (parts.size >= 4) {
                    val x = parts[1].toFloatOrNull(); val y = parts[2].toFloatOrNull(); val z = parts[3].toFloatOrNull()
                    if (x != null && y != null && z != null) verts += Triple(x, y, z)
                }
                "o", "g" -> group = parts.drop(1).joinToString(" ")
                "usemtl" -> material = parts.drop(1).joinToString(" ")
                "f" -> {
                    val key = when {
                        kindOf(group) != null -> group
                        kindOf(material) != null -> material
                        else -> group.ifEmpty { material }
                    }
                    val set = groups.getOrPut(key) { HashSet() }
                    for (p in parts.drop(1)) {
                        val idx = p.substringBefore('/').toIntOrNull() ?: continue
                        set += if (idx < 0) verts.size + idx else idx - 1
                    }
                }
            }
        }
        if (verts.size < 3) return null
        val scale = unitScale(verts)
        val sv = verts.map { Triple(it.first * scale, it.second * scale, it.third * scale) }
        val boxes = groups.mapNotNull { (name, idx) ->
            val kind = kindOf(name) ?: return@mapNotNull null
            val pts = idx.filter { it in sv.indices }.map { sv[it] }
            if (pts.size < 3) null else boxOf(kind, pts)
        }
        return boxes to sv
    }

    /** OBJ has no units. RoomPlan uses meters; catch centimeter or millimeter exports. */
    private fun unitScale(v: List<Triple<Float, Float, Float>>): Float {
        val span = maxOf(v.maxOf { it.first } - v.minOf { it.first }, v.maxOf { it.third } - v.minOf { it.third })
        return when { span > 600f -> 0.001f; span > 60f -> 0.01f; else -> 1f }
    }

    /** Fits an upright box: principal direction on the floor plan via PCA. */
    private fun boxOf(kind: Kind, pts: List<Triple<Float, Float, Float>>): Box {
        val n = pts.size.toFloat()
        val mx = pts.sumOf { it.first.toDouble() }.toFloat() / n
        val mz = pts.sumOf { it.third.toDouble() }.toFloat() / n
        var sxx = 0f; var szz = 0f; var sxz = 0f
        for (p in pts) { val dx = p.first - mx; val dz = p.third - mz; sxx += dx * dx; szz += dz * dz; sxz += dx * dz }
        val ang = 0.5f * atan2(2 * sxz, sxx - szz)
        val dx = cos(ang); val dz = sin(ang)
        var lo = Float.MAX_VALUE; var hi = -Float.MAX_VALUE; var off = 0f
        for (p in pts) {
            val u = (p.first - mx) * dx + (p.third - mz) * dz
            lo = minOf(lo, u); hi = maxOf(hi, u)
            off += -(p.first - mx) * dz + (p.third - mz) * dx
        }
        off /= n
        val mid = (lo + hi) / 2
        val cx = mx + dx * mid - dz * off
        val cz = mz + dz * mid + dx * off
        return Box(kind, cx, cz, dx, dz, hi - lo, pts.minOf { it.second }, pts.maxOf { it.second })
    }

    // ---------- RoomPlan CapturedRoom JSON ----------

    private fun flatten(e: JsonElement?, out: MutableList<Float>) {
        when (e) {
            is JsonArray -> e.forEach { flatten(it, out) }
            is JsonPrimitive -> e.floatOrNull?.let { out += it }
            else -> {}
        }
    }

    fun parseRoomPlanJson(text: String): Pair<List<Box>, List<Triple<Float, Float, Float>>>? {
        val root = runCatching { Json.parseToJsonElement(text) as? JsonObject }.getOrNull() ?: return null
        val boxes = ArrayList<Box>()
        val pts = ArrayList<Triple<Float, Float, Float>>()
        for ((key, kind) in listOf("walls" to Kind.WALL, "doors" to Kind.DOOR, "windows" to Kind.WINDOW, "openings" to Kind.OPENING)) {
            val arr = root[key] as? JsonArray ?: continue
            for (item in arr) {
                val o = item as? JsonObject ?: continue
                val dims = ArrayList<Float>().also { flatten(o["dimensions"], it) }
                val t = ArrayList<Float>().also { flatten(o["transform"], it) }
                if (dims.size < 2 || t.size < 16) continue
                // simd_float4x4, column-major: column 0 is the element's width axis, column 3 its center.
                val len = sqrt(t[0] * t[0] + t[2] * t[2]).coerceAtLeast(1e-6f)
                val w = dims[0]; val h = dims[1]
                val b = Box(kind, t[12], t[14], t[0] / len, t[2] / len, w, t[13] - h / 2, t[13] + h / 2)
                boxes += b
                pts += Triple(b.cx - b.dirX * w / 2, b.minY, b.cz - b.dirZ * w / 2)
                pts += Triple(b.cx + b.dirX * w / 2, b.maxY, b.cz + b.dirZ * w / 2)
            }
        }
        return if (boxes.isEmpty()) null else boxes to pts
    }

    // ---------- Build the room ----------

    private data class Seg(var a: V2, var b: V2) {
        val dir get() = (b - a).norm()
        fun flip() { val t = a; a = b; b = t }
    }

    private fun intersect(p: Seg, q: Seg): V2? {
        val d1 = p.b - p.a; val d2 = q.b - q.a
        val den = d1.cross(d2)
        if (abs(den) < 1e-3f * d1.len() * d2.len()) return null
        val t = (q.a - p.a).cross(d2) / den
        return p.a + d1 * t
    }

    fun build(boxes: List<Box>, allPoints: List<Triple<Float, Float, Float>>, name: String): Result? {
        val walls = boxes.filter { it.kind == Kind.WALL && it.width > 0.3f }
        val floorY = (walls.minOfOrNull { it.minY } ?: allPoints.minOfOrNull { it.second }) ?: return null
        val ceilY = if (walls.isNotEmpty()) walls.map { it.maxY }.sorted()[walls.size / 2] else allPoints.maxOf { it.second }

        var corners = if (walls.size >= 3) chain(walls) else null
        val exact = corners != null
        if (corners == null) corners = minRect(allPoints.map { V2(it.first, it.third) }) ?: return null
        corners = dropCollinear(corners)
        if (corners.size < 3) return null

        val origin = corners[0]
        val local = corners.map { it - origin }
        var s = Survey(name = name, corners = local, closed = true, ceilingM = ceilY - floorY, ceilingMeasured = true)

        val ops = ArrayList<Opening>()
        for (b in boxes) {
            val type = when (b.kind) { Kind.DOOR -> OpeningType.DOOR; Kind.WINDOW -> OpeningType.WINDOW; Kind.OPENING -> OpeningType.OPENING; else -> continue }
            val c = V2(b.cx, b.cz) - origin
            var best = -1; var bestD = Float.MAX_VALUE; var bestU = 0f
            for (i in 0 until s.wallCount) {
                val a = s.wallStart(i); val d = s.wallDir(i); val len = s.wallLength(i)
                val u = (c - a).dot(d).coerceIn(0f, len)
                val dist = (a + d * u - c).len()
                if (dist < bestD) { bestD = dist; best = i; bestU = u }
            }
            if (best < 0 || bestD > 0.75f) continue
            val len = s.wallLength(best)
            ops += Opening(
                type = type, wall = best,
                u0 = (bestU - b.width / 2).coerceIn(0f, len), u1 = (bestU + b.width / 2).coerceIn(0f, len),
                v0 = if (type == OpeningType.DOOR) 0f else (b.minY - floorY).coerceAtLeast(0f),
                v1 = (b.maxY - floorY).coerceAtMost(s.ceilingM),
            )
        }
        s = s.copy(openings = ops)
        if (s.isNearRectangle()) s = s.squared()

        val counts = listOf(
            ops.count { it.type == OpeningType.DOOR } to "door",
            ops.count { it.type == OpeningType.WINDOW } to "window",
            ops.count { it.type == OpeningType.OPENING } to "opening",
        ).filter { it.first > 0 }.joinToString(", ") { "${it.first} ${it.second}${if (it.first > 1) "s" else ""}" }
        val size = if (s.corners.size == 4) "${Units.ftInShort(s.wallLength(0))} × ${Units.ftInShort(s.wallLength(1))}" else "${s.corners.size} walls, ${Units.sqFt(s.area())}"
        val summary = "$size, ceiling ${Units.ftInShort(s.ceilingM)}" + (if (counts.isNotEmpty()) ", $counts" else "") +
            if (!exact) ". Walls weren't labeled, so this is the outer outline." else ""
        return Result(s, summary, exact)
    }

    /** Joins wall segments end to end into a closed outline; corners are where neighbors meet. */
    private fun chain(walls: List<Box>): List<V2>? {
        val segs = walls.map { w ->
            val h = w.width / 2
            Seg(V2(w.cx - w.dirX * h, w.cz - w.dirZ * h), V2(w.cx + w.dirX * h, w.cz + w.dirZ * h))
        }
        val order = arrayListOf(segs[0])
        val used = BooleanArray(segs.size).also { it[0] = true }
        while (true) {
            val end = order.last().b
            var best = -1; var bestD = 0.6f; var flip = false
            for (i in segs.indices) {
                if (used[i]) continue
                val da = (segs[i].a - end).len(); val db = (segs[i].b - end).len()
                if (da < bestD) { bestD = da; best = i; flip = false }
                if (db < bestD) { bestD = db; best = i; flip = true }
            }
            if (best < 0) break
            used[best] = true
            if (flip) segs[best].flip()
            order += segs[best]
        }
        if (order.size < 3 || (order.last().b - order[0].a).len() > 0.6f) return null
        return order.indices.map { i ->
            val prev = order[(i - 1 + order.size) % order.size]; val cur = order[i]
            intersect(prev, cur) ?: ((prev.b + cur.a) * 0.5f)
        }
    }

    private fun dropCollinear(c: List<V2>): List<V2> {
        if (c.size <= 3) return c
        val out = c.filterIndexed { i, p ->
            val a = (p - c[(i - 1 + c.size) % c.size]).norm(); val b = (c[(i + 1) % c.size] - p).norm()
            abs(a.cross(b)) > 0.05f || a.dot(b) < 0
        }
        return if (out.size >= 3) out else c
    }

    /** Smallest rectangle around the points (1 degree steps), for scans without wall labels. */
    private fun minRect(p: List<V2>): List<V2>? {
        if (p.size < 3) return null
        var best: List<V2>? = null; var bestA = Float.MAX_VALUE
        for (deg in 0 until 90) {
            val a = deg * PI.toFloat() / 180f
            val u = V2(cos(a), sin(a)); val v = V2(-u.z, u.x)
            val us = p.map { it.dot(u) }; val vs = p.map { it.dot(v) }
            val u0 = us.min(); val u1 = us.max(); val v0 = vs.min(); val v1 = vs.max()
            val area = (u1 - u0) * (v1 - v0)
            if (area < bestA) {
                bestA = area
                best = listOf(u * u0 + v * v0, u * u1 + v * v0, u * u1 + v * v1, u * u0 + v * v1)
            }
        }
        return best
    }
}
