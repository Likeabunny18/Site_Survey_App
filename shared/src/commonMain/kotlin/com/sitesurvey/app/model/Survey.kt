package com.sitesurvey.app.model

import kotlinx.serialization.Serializable
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.atan2
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan

@OptIn(ExperimentalUuidApi::class)
fun newId(): String = Uuid.random().toString()

/** A point on the floor in room coordinates (meters). Room origin is the first corner, floor is y = 0. */
@Serializable
data class V2(val x: Float, val z: Float) {
    operator fun plus(o: V2) = V2(x + o.x, z + o.z)
    operator fun minus(o: V2) = V2(x - o.x, z - o.z)
    operator fun times(s: Float) = V2(x * s, z * s)
    fun len() = hypot(x, z)
    fun norm(): V2 { val l = len(); return if (l < 1e-6f) V2(1f, 0f) else V2(x / l, z / l) }
    fun dot(o: V2) = x * o.x + z * o.z
    fun cross(o: V2) = x * o.z - z * o.x
}

@Serializable
enum class OpeningType(val label: String) { DOOR("Door"), WINDOW("Window"), OPENING("Opening") }

/** A rectangle on wall [wall]: u along the wall from its start corner, v up from the floor. */
@Serializable
data class Opening(
    val id: String = newId(),
    val type: OpeningType,
    val wall: Int,
    val u0: Float, val u1: Float,
    val v0: Float, val v1: Float,
)

@Serializable
enum class GearType(val label: String, val prefix: String) {
    DISPLAY("Display", "D"),
    CEILING_SPEAKER("Ceiling speaker", "SPK"),
    CEILING_MIC("Ceiling mic", "CM"),
    TABLE_MIC("Table mic", "TM"),
}

@Serializable
data class Gear(
    val id: String = newId(),
    val type: GearType,
    val model: String = "",
    val notes: String = "",
    // Floor-plan position for ceiling and table items.
    val x: Float = 0f,
    val z: Float = 0f,
    // Wall-mounted items: wall index, distance along the wall to the center, center height.
    val wall: Int = -1,
    val u: Float = 0f,
    val height: Float = 0f,
    // Display size (diagonal inches, 16:9).
    val sizeIn: Int = 75,
    // Speaker coverage angle (degrees, conical).
    val angleDeg: Float = 90f,
    // Mic pickup radius (meters).
    val radiusM: Float = 3f,
) {
    val widthM get() = sizeIn * 16f / sqrt(337f) * Units.M_PER_IN
    val imageHeightM get() = sizeIn * 9f / sqrt(337f) * Units.M_PER_IN

    fun defaultModel(): String = when (type) {
        GearType.DISPLAY -> "$sizeIn\" display"
        GearType.CEILING_SPEAKER -> "Ceiling speaker"
        GearType.CEILING_MIC -> "Ceiling array mic"
        GearType.TABLE_MIC -> "Table mic"
    }
    val displayName get() = model.ifBlank { defaultModel() }

    companion object {
        val DISPLAY_SIZES = listOf(43, 55, 65, 75, 85, 98, 110)
        const val TABLE_HEIGHT = 0.76f
    }
}

@Serializable
data class Survey(
    val id: String = newId(),
    val name: String,
    val client: String = "",
    val created: Long = 0L,
    val updated: Long = 0L,
    val corners: List<V2> = emptyList(),
    val closed: Boolean = false,
    val ceilingM: Float = 2.74f,      // 9'
    val ceilingMeasured: Boolean = false,
    val earHeightM: Float = 1.2f,     // seated ear height for coverage
    val openings: List<Opening> = emptyList(),
    val gear: List<Gear> = emptyList(),
    val notes: String = "",
) {
    val wallCount get() = if (closed) corners.size else (corners.size - 1).coerceAtLeast(0)

    fun wallStart(i: Int) = corners[i]
    fun wallEnd(i: Int) = corners[(i + 1) % corners.size]
    fun wallLength(i: Int) = (wallEnd(i) - wallStart(i)).len()
    fun wallDir(i: Int) = (wallEnd(i) - wallStart(i)).norm()

    /** Inward-facing normal of wall i (toward the room interior). */
    fun wallInward(i: Int): V2 {
        val d = wallDir(i)
        val left = V2(-d.z, d.x)
        return if (signedArea() > 0) left else left * -1f
    }

    fun signedArea(): Float {
        if (corners.size < 3) return 0f
        var a = 0f
        for (i in corners.indices) {
            val p = corners[i]; val q = corners[(i + 1) % corners.size]
            a += p.x * q.z - q.x * p.z
        }
        return a / 2f
    }
    fun area() = abs(signedArea())
    fun perimeter() = (0 until wallCount).sumOf { wallLength(it).toDouble() }.toFloat()

    fun contains(p: V2): Boolean {
        var inside = false
        var j = corners.size - 1
        for (i in corners.indices) {
            val a = corners[i]; val b = corners[j]
            if ((a.z > p.z) != (b.z > p.z) && p.x < (b.x - a.x) * (p.z - a.z) / (b.z - a.z) + a.x) inside = !inside
            j = i
        }
        return inside
    }

    /** Floor position of a gear item (walls items: a point on the wall). */
    fun gearFloor(g: Gear): V2 =
        if (g.wall in 0 until wallCount) wallStart(g.wall) + wallDir(g.wall) * g.u else V2(g.x, g.z)

    fun gearHeight(g: Gear): Float = when (g.type) {
        GearType.DISPLAY -> g.height
        GearType.CEILING_SPEAKER, GearType.CEILING_MIC -> ceilingM
        GearType.TABLE_MIC -> Gear.TABLE_HEIGHT
    }

    /** Radius of the speaker coverage circle at ear height. */
    fun coverageRadius(g: Gear): Float = when (g.type) {
        GearType.CEILING_SPEAKER -> ((ceilingM - earHeightM).coerceAtLeast(0.1f) * tan(g.angleDeg / 2f * PI.toFloat() / 180f))
        GearType.CEILING_MIC, GearType.TABLE_MIC -> g.radiusM
        GearType.DISPLAY -> 0f
    }

    fun tag(g: Gear): String {
        val n = gear.filter { it.type == g.type }.indexOfFirst { it.id == g.id } + 1
        return "${g.type.prefix}$n"
    }

    /** Is this a near-rectangle (4 corners, all within 10 degrees of square)? */
    fun isNearRectangle(): Boolean {
        if (corners.size != 4) return false
        for (i in 0 until 4) {
            val a = (corners[(i + 3) % 4] - corners[i]).norm()
            val b = (corners[(i + 1) % 4] - corners[i]).norm()
            val ang = acos(a.dot(b).coerceIn(-1f, 1f)) * 180f / PI.toFloat()
            if (abs(ang - 90f) > 10f) return false
        }
        return true
    }

    /** Snaps a near-rectangle to a true rectangle, keeping corner 1 and the first wall's direction. */
    fun squared(): Survey {
        if (!isNearRectangle()) return this
        val c = corners
        val u = (c[1] - c[0]).norm()
        var v = V2(-u.z, u.x)
        if ((c[3] - c[0]).dot(v) < 0) v = v * -1f
        val w = ((c[1] - c[0]).len() + (c[2] - c[3]).len()) / 2f
        val l = ((c[3] - c[0]).len() + (c[2] - c[1]).len()) / 2f
        return copy(corners = listOf(c[0], c[0] + u * w, c[0] + u * w + v * l, c[0] + v * l))
    }

    /**
     * Applies a laser reading to wall i. Rectangles keep their shape (the opposite wall changes too).
     * Other shapes slide every later corner along this wall, so the closing wall absorbs the change.
     */
    fun withWallLength(i: Int, newLen: Float): Survey {
        if (i !in 0 until wallCount || newLen <= 0.05f) return this
        val delta = wallDir(i) * (newLen - wallLength(i))
        val n = corners.size
        val moved = corners.toMutableList()
        if (closed && n == 4 && isNearRectangle()) {
            // Move the two corners of the far side of this wall.
            val a = (i + 1) % n; val b = (i + 2) % n
            if (a == 0 || b == 0) {
                // Never move corner 1 (the AR anchor); move the other two instead, in reverse.
                val ra = i; val rb = (i + 3) % n
                moved[ra] = moved[ra] - delta; moved[rb] = moved[rb] - delta
            } else {
                moved[a] = moved[a] + delta; moved[b] = moved[b] + delta
            }
        } else if (i == n - 1 && closed) {
            moved[n - 1] = moved[n - 1] - delta // closing wall: slide the last corner back along it
        } else {
            for (k in i + 1 until n) moved[k] = moved[k] + delta
        }
        return copy(corners = moved)
    }

    /** Distance from a display to the farthest corner of the room (worst-case seat). */
    fun farthestViewer(g: Gear): Float {
        val p = gearFloor(g)
        return corners.maxOfOrNull { (it - p).len() } ?: 0f
    }

    data class ViewCheck(val ratio: Float, val verdict: String, val ok: Boolean, val minSizeIn: Int)

    /** 4/6/8 rule: farthest viewer within 6x image height reads detail; past 8x is too far. */
    fun viewCheck(g: Gear): ViewCheck {
        val far = farthestViewer(g)
        val ratio = far / g.imageHeightM
        val needH = far / 6f
        val needDiag = needH / Units.M_PER_IN * sqrt(337f) / 9f
        val minSize = Gear.DISPLAY_SIZES.firstOrNull { it >= needDiag } ?: ceil(needDiag).toInt()
        return when {
            ratio <= 6f -> ViewCheck(ratio, "Good", true, minSize)
            ratio <= 8f -> ViewCheck(ratio, "Tight", false, minSize)
            else -> ViewCheck(ratio, "Too small", false, minSize)
        }
    }
}

/** Heading of a floor vector, used to align a saved room in a new AR session. */
fun yawOf(v: V2): Float = atan2(-v.z, v.x)

fun rotate(v: V2, yaw: Float): V2 {
    val c = cos(yaw); val s = sin(yaw)
    // Rotation about +Y by yaw maps (x, z) to (x c + z s, -x s + z c).
    return V2(v.x * c + v.z * s, -v.x * s + v.z * c)
}
