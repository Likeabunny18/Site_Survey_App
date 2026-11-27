package com.sitesurvey.app.model

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class RoomImportTest {
    private fun near(a: Float, b: Float, tol: Float = 0.03f) = assertTrue(abs(a - b) < tol, "$a != $b")

    /** Builds an OBJ box (8 verts, 6 quads) named [name] spanning the given ranges. */
    private fun box(sb: StringBuilder, name: String, x0: Float, x1: Float, y0: Float, y1: Float, z0: Float, z1: Float, base: Int): Int {
        sb.appendLine("o $name")
        for (x in listOf(x0, x1)) for (y in listOf(y0, y1)) for (z in listOf(z0, z1)) sb.appendLine("v $x $y $z")
        val f = listOf(listOf(1, 2, 4, 3), listOf(5, 6, 8, 7), listOf(1, 2, 6, 5), listOf(3, 4, 8, 7), listOf(1, 3, 7, 5), listOf(2, 4, 8, 6))
        f.forEach { q -> sb.appendLine("f " + q.joinToString(" ") { (it + base).toString() }) }
        return base + 8
    }

    private fun room(): String {
        val sb = StringBuilder()
        var b = 0
        // 6 m x 4 m room, 2.7 m ceiling, 0.1 m thick walls centered on the outline.
        b = box(sb, "Wall0", 0f, 6f, 0f, 2.7f, -0.05f, 0.05f, b)
        b = box(sb, "Wall1", 5.95f, 6.05f, 0f, 2.7f, 0f, 4f, b)
        b = box(sb, "Wall2", 0f, 6f, 0f, 2.7f, 3.95f, 4.05f, b)
        b = box(sb, "Wall3", -0.05f, 0.05f, 0f, 2.7f, 0f, 4f, b)
        b = box(sb, "Door0", 1f, 1.9f, 0f, 2.1f, -0.06f, 0.06f, b)
        b = box(sb, "Window0", 5.94f, 6.06f, 0.9f, 2.1f, 1f, 2.5f, b)
        box(sb, "Table0", 2f, 4f, 0f, 0.75f, 1f, 3f, b)
        return sb.toString()
    }

    @Test fun importsLabeledObj() {
        val r = assertNotNull(RoomImport.import(room(), "Scan"))
        val s = r.survey
        assertTrue(r.exact)
        assertEquals(4, s.corners.size)
        near(s.area(), 24f, 0.3f)
        near(s.ceilingM, 2.7f)
        assertEquals(1, s.openings.count { it.type == OpeningType.DOOR })
        val w = s.openings.first { it.type == OpeningType.WINDOW }
        near(w.u1 - w.u0, 1.5f, 0.05f); near(w.v0, 0.9f); near(w.v1, 2.1f)
        val d = s.openings.first { it.type == OpeningType.DOOR }
        near(d.u1 - d.u0, 0.9f, 0.05f); near(d.v1, 2.1f)
    }

    @Test fun importsRoomPlanJson() {
        fun el(w: Float, h: Float, x: Float, y: Float, z: Float, alongX: Boolean) =
            """{"dimensions":[$w,$h,0],"transform":[${if (alongX) "1,0,0,0, 0,1,0,0, 0,0,1,0" else "0,0,-1,0, 0,1,0,0, 1,0,0,0"}, $x,$y,$z,1]}"""
        val json = """{"walls":[${el(5f, 2.5f, 2.5f, 1.25f, 0f, true)},${el(3f, 2.5f, 5f, 1.25f, 1.5f, false)},
            ${el(5f, 2.5f, 2.5f, 1.25f, 3f, true)},${el(3f, 2.5f, 0f, 1.25f, 1.5f, false)}],
            "doors":[${el(0.9f, 2.0f, 1f, 1.0f, 0f, true)}],"windows":[],"openings":[]}"""
        val s = assertNotNull(RoomImport.import(json, "J")).survey
        near(s.area(), 15f, 0.2f)
        near(s.ceilingM, 2.5f)
        assertEquals(1, s.openings.size)
    }

    @Test fun unlabeledObjFallsBackToOutline() {
        val text = room().replace(Regex("o \\w+"), "o mesh")
        val r = assertNotNull(RoomImport.import(text, "X"))
        assertTrue(!r.exact)
        near(r.survey.area(), 6.1f * 4.1f, 0.5f)
    }
}
