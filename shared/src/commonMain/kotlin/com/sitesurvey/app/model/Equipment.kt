package com.sitesurvey.app.model

import kotlin.math.roundToInt

/** Platform-neutral equipment list pieces, shared by Android now and iOS later. */

fun fmt1(v: Float): String {
    val t = (v * 10).roundToInt()
    return "${t / 10}.${kotlin.math.abs(t % 10)}"
}

fun locationOf(s: Survey, g: Gear): String = when (g.type) {
    GearType.DISPLAY -> "Wall ${g.wall + 1}, ${Units.ftInShort(g.height)} AFF"
    GearType.CEILING_SPEAKER, GearType.CEILING_MIC -> "Ceiling, ${Units.ftInShort(s.ceilingM)}"
    GearType.TABLE_MIC -> "Table"
}

private fun esc(v: String) = if (v.any { it == ',' || it == '"' || it == '\n' }) "\"" + v.replace("\"", "\"\"") + "\"" else v

fun equipmentCsv(s: Survey): String {
    val sb = StringBuilder()
    sb.appendLine("Tag,Type,Model / description,Qty,Location,Mount height,Size,Coverage,Notes")
    for (g in s.gear.sortedBy { it.type.ordinal }) {
        val size = if (g.type == GearType.DISPLAY) "${g.sizeIn}\"" else ""
        val cov = when (g.type) {
            GearType.CEILING_SPEAKER -> "${g.angleDeg.toInt()}° cone, ${Units.ftInShort(s.coverageRadius(g) * 2)} dia at ear"
            GearType.CEILING_MIC, GearType.TABLE_MIC -> "${Units.ftInShort(g.radiusM)} radius"
            GearType.DISPLAY -> s.viewCheck(g).let { "${fmt1(it.ratio)}x image height to far seat (${it.verdict})" }
        }
        sb.appendLine(
            listOf(s.tag(g), g.type.label, g.displayName, "1", locationOf(s, g), Units.ftIn(s.gearHeight(g)), size, cov, g.notes)
                .joinToString(",") { esc(it) }
        )
    }
    sb.appendLine()
    sb.appendLine("Summary")
    sb.appendLine("Item,Qty")
    s.gear.groupBy { it.type.label + " | " + it.displayName }.forEach { (k, v) -> sb.appendLine("${esc(k)},${v.size}") }
    sb.appendLine()
    sb.appendLine("Room,${esc(s.name)}")
    sb.appendLine("Area,${esc(Units.sqFt(s.area()))}")
    sb.appendLine("Ceiling,${esc(Units.ftIn(s.ceilingM))}")
    for (i in 0 until s.wallCount) sb.appendLine("Wall ${i + 1},${esc(Units.ftIn(s.wallLength(i)))}")
    return sb.toString()
}
