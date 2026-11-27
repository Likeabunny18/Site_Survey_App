package com.sitesurvey.app.ar

import android.opengl.Matrix
import androidx.compose.ui.geometry.Offset
import com.sitesurvey.app.model.Survey
import com.sitesurvey.app.model.V2
import kotlin.math.abs
import kotlin.math.sqrt

data class Vec3(val x: Float, val y: Float, val z: Float) {
    operator fun plus(o: Vec3) = Vec3(x + o.x, y + o.y, z + o.z)
    operator fun minus(o: Vec3) = Vec3(x - o.x, y - o.y, z - o.z)
    operator fun times(s: Float) = Vec3(x * s, y * s, z * s)
    fun dot(o: Vec3) = x * o.x + y * o.y + z * o.z
    fun len() = sqrt(dot(this))
    fun norm() = len().let { if (it < 1e-6f) this else this * (1f / it) }
    val floor get() = V2(x, z)
}

fun V2.at(y: Float) = Vec3(x, y, z)

data class Ray(val o: Vec3, val d: Vec3)

/** Projects room-space points to screen pixels and casts rays back, using one model-view-projection matrix. */
class Projector(private val mvp: FloatArray, val w: Float, val h: Float) {
    private val inv = FloatArray(16).also { Matrix.invertM(it, 0, mvp, 0) }
    private val tmpIn = FloatArray(4)
    private val tmpOut = FloatArray(4)

    private fun clip(p: Vec3): FloatArray {
        tmpIn[0] = p.x; tmpIn[1] = p.y; tmpIn[2] = p.z; tmpIn[3] = 1f
        val out = FloatArray(4)
        Matrix.multiplyMV(out, 0, mvp, 0, tmpIn, 0)
        return out
    }

    private fun toScreen(c: FloatArray) = Offset((c[0] / c[3] * 0.5f + 0.5f) * w, (0.5f - c[1] / c[3] * 0.5f) * h)

    fun point(p: Vec3): Offset? { val c = clip(p); return if (c[3] > NEAR) toScreen(c) else null }

    /** A segment clipped against the near plane, or null when it is fully behind the camera. */
    fun segment(a: Vec3, b: Vec3): Pair<Offset, Offset>? {
        var ca = clip(a); var cb = clip(b)
        if (ca[3] <= NEAR && cb[3] <= NEAR) return null
        if (ca[3] <= NEAR) ca = lerp(ca, cb, (NEAR - ca[3]) / (cb[3] - ca[3]))
        else if (cb[3] <= NEAR) cb = lerp(cb, ca, (NEAR - cb[3]) / (ca[3] - cb[3]))
        return toScreen(ca) to toScreen(cb)
    }

    private fun lerp(a: FloatArray, b: FloatArray, t: Float) = FloatArray(4) { a[it] + (b[it] - a[it]) * t }

    fun ray(sx: Float = w / 2, sy: Float = h / 2): Ray {
        val nx = sx / w * 2f - 1f
        val ny = 1f - sy / h * 2f
        val n = unproject(nx, ny, -1f)
        val f = unproject(nx, ny, 1f)
        return Ray(n, (f - n).norm())
    }

    private fun unproject(x: Float, y: Float, z: Float): Vec3 {
        tmpIn[0] = x; tmpIn[1] = y; tmpIn[2] = z; tmpIn[3] = 1f
        Matrix.multiplyMV(tmpOut, 0, inv, 0, tmpIn, 0)
        return Vec3(tmpOut[0] / tmpOut[3], tmpOut[1] / tmpOut[3], tmpOut[2] / tmpOut[3])
    }

    companion object { const val NEAR = 0.05f }
}

/** Where a ray meets the horizontal plane at height y (room space), within [maxDist]. */
fun hitPlaneY(r: Ray, y: Float, maxDist: Float = 15f): Vec3? {
    if (abs(r.d.y) < 1e-4f) return null
    val t = (y - r.o.y) / r.d.y
    if (t <= 0f || t > maxDist) return null
    return r.o + r.d * t
}

data class WallHit(val wall: Int, val u: Float, val v: Float, val point: Vec3, val dist: Float)

/** Nearest wall face hit by the ray. */
fun hitWall(r: Ray, s: Survey): WallHit? {
    var best: WallHit? = null
    for (i in 0 until s.wallCount) {
        val a = s.wallStart(i); val d = s.wallDir(i); val len = s.wallLength(i)
        val n = Vec3(-d.z, 0f, d.x)
        val denom = r.d.dot(n)
        if (abs(denom) < 1e-4f) continue
        val t = (a.at(0f) - r.o).dot(n) / denom
        if (t <= 0f) continue
        val p = r.o + r.d * t
        val u = (p.floor - a).dot(d)
        if (u < -0.05f || u > len + 0.05f || p.y < -0.05f || p.y > s.ceilingM + 0.05f) continue
        if (best == null || t < best.dist) best = WallHit(i, u.coerceIn(0f, len), p.y.coerceIn(0f, s.ceilingM), p, t)
    }
    return best
}

/** Which corner's vertical edge the ray passes closest to, and the height there. */
fun hitCornerHeight(r: Ray, s: Survey): Pair<Int, Float>? {
    var best: Triple<Int, Float, Float>? = null
    val up = Vec3(0f, 1f, 0f)
    s.corners.forEachIndexed { i, c ->
        val l0 = c.at(0f)
        val w0 = r.o - l0
        val a = r.d.dot(r.d); val b = r.d.dot(up); val cc = 1f
        val dd = r.d.dot(w0); val e = up.dot(w0)
        val den = a * cc - b * b
        if (abs(den) < 1e-5f) return@forEachIndexed
        val sRay = (b * e - cc * dd) / den
        val tLine = (a * e - b * dd) / den
        if (sRay <= 0f || tLine < 1.5f || tLine > 8f) return@forEachIndexed
        val dist = ((r.o + r.d * sRay) - (l0 + up * tLine)).len()
        if (best == null || dist < best!!.third) best = Triple(i, tLine, dist)
    }
    return best?.takeIf { it.third < 0.6f }?.let { it.first to it.second }
}
