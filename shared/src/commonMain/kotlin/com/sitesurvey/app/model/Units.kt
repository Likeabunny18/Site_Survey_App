package com.sitesurvey.app.model

import kotlin.math.abs
import kotlin.math.roundToInt

/** Lengths are stored in meters and shown in feet and inches (nearest 1/4"). */
object Units {
    const val M_PER_IN = 0.0254f
    const val M_PER_FT = 0.3048f

    fun ftIn(m: Float): String {
        val quarters = (abs(m) / M_PER_IN * 4f).roundToInt()
        val totalIn = quarters / 4
        val frac = quarters % 4
        val ft = totalIn / 12
        val inch = totalIn % 12
        val fracStr = when (frac) { 1 -> "¼"; 2 -> "½"; 3 -> "¾"; else -> "" }
        val sign = if (m < 0) "-" else ""
        return if (ft == 0) "$sign$inch$fracStr\"" else "$sign$ft' $inch$fracStr\""
    }

    /** Short form for tight spots, e.g. 12' 6". */
    fun ftInShort(m: Float): String {
        val totalIn = (abs(m) / M_PER_IN).roundToInt()
        return "${totalIn / 12}' ${totalIn % 12}\""
    }

    fun sqFt(m2: Float): String = "${(m2 / (M_PER_FT * M_PER_FT)).roundToInt()} sq ft"

    private val num = """(\d+(?:\.\d+)?|\.\d+)"""
    private val ws = "\\s*"

    /**
     * Parses what someone types or says: 12' 6", 12'6, 12 6, 12 ft 6 in, 12 feet 6 inches,
     * 12.5 ft, 150 in, 150", 3.81 m, 381 cm, 3810 mm. A bare number is feet.
     * Returns meters, or null when it can't make sense of it.
     */
    fun parse(input: String): Float? {
        var s = input.trim().lowercase()
            .replace("’", "'").replace("‘", "'").replace("”", "\"").replace("“", "\"")
            .replace("″", "\"").replace("′", "'")
            .replace(",", ".")
            .replace(" and ", " ")
        if (s.isEmpty()) return null
        // Fractions like 6 1/2 -> 6.5
        s = Regex("""(\d+)\s+(\d+)/(\d+)""").replace(s) {
            val (w, n, d) = it.destructured
            val v = w.toFloat() + n.toFloat() / d.toFloat().coerceAtLeast(1f)
            v.toString()
        }
        s = Regex("""(?<![\d.])(\d+)/(\d+)""").replace(s) {
            val (n, d) = it.destructured
            (n.toFloat() / d.toFloat().coerceAtLeast(1f)).toString()
        }
        val ftWord = "(?:'|ft\\.?|feet|foot)"
        val inWord = "(?:\"|''|in\\.?|inch|inches)"

        Regex("^$num$ws$ftWord$ws(?:$num$ws$inWord?)?$").find(s)?.let {
            val ft = it.groupValues[1].toFloat()
            val inch = it.groupValues[2].toFloatOrNull() ?: 0f
            return ft * M_PER_FT + inch * M_PER_IN
        }
        Regex("^$num$ws$inWord$").find(s)?.let { return it.groupValues[1].toFloat() * M_PER_IN }
        Regex("^$num$ws(?:mm|millimeters?)$").find(s)?.let { return it.groupValues[1].toFloat() / 1000f }
        Regex("^$num$ws(?:cm|centimeters?)$").find(s)?.let { return it.groupValues[1].toFloat() / 100f }
        Regex("^$num$ws(?:m|meters?|metres?)$").find(s)?.let { return it.groupValues[1].toFloat() }
        Regex("^$num\\s+$num$").find(s)?.let {
            return it.groupValues[1].toFloat() * M_PER_FT + it.groupValues[2].toFloat() * M_PER_IN
        }
        Regex("^$num$").find(s)?.let { return it.groupValues[1].toFloat() * M_PER_FT }
        return null
    }
}
