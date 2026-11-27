package com.sitesurvey.app.model

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class SurveyTest {
    private fun near(a: Float, b: Float, tol: Float = 0.002f) = assertTrue(abs(a - b) < tol, "$a != $b")

    @Test fun parsesLaserReadings() {
        near(Units.parse("12' 6\"")!!, 12 * 0.3048f + 6 * 0.0254f)
        near(Units.parse("12 6")!!, 12 * 0.3048f + 6 * 0.0254f)
        near(Units.parse("12 feet 6 inches")!!, 12 * 0.3048f + 6 * 0.0254f)
        near(Units.parse("150 in")!!, 150 * 0.0254f)
        near(Units.parse("3.81 m")!!, 3.81f)
        near(Units.parse("12")!!, 12 * 0.3048f)
        near(Units.parse("6 1/2 in")!!, 6.5f * 0.0254f)
    }

    @Test fun formatsFeetInches() {
        assertEquals("12' 6\"", Units.ftIn(12 * 0.3048f + 6 * 0.0254f))
        assertEquals("12' 6½\"", Units.ftIn(12 * 0.3048f + 6.5f * 0.0254f))
    }

    private val rect = Survey(name = "t", closed = true, corners = listOf(V2(0f, 0f), V2(6f, 0.1f), V2(6.1f, 4f), V2(0.05f, 4.05f)))

    @Test fun squaresNearRectangles() {
        val s = rect.squared()
        assertTrue(s.isNearRectangle())
        near(s.wallDir(0).dot(s.wallDir(1)), 0f, 1e-4f)
    }

    @Test fun laserReadingKeepsRectangleShape() {
        val s = rect.squared()
        for (i in 0 until 4) {
            val t = s.withWallLength(i, 7f)
            near(t.wallLength(i), 7f, 1e-3f)
            near(t.wallLength((i + 2) % 4), 7f, 1e-3f)
            near(t.corners[0].x, 0f); near(t.corners[0].z, 0f)
        }
    }

    @Test fun displayViewCheck() {
        val s = rect.squared().let { it.copy(gear = listOf(Gear(type = GearType.DISPLAY, wall = 0, u = 3f, height = 1.5f, sizeIn = 55))) }
        assertNotNull(s.viewCheck(s.gear[0]).verdict)
        assertTrue(equipmentCsv(s).contains("D1"))
    }
}
