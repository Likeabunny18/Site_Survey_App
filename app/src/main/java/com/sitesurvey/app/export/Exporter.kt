package com.sitesurvey.app.export

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.graphics.Paint
import android.graphics.pdf.PdfDocument
import android.net.Uri
import androidx.core.content.FileProvider
import com.sitesurvey.app.model.GearType
import com.sitesurvey.app.model.Survey
import com.sitesurvey.app.model.Units
import com.sitesurvey.app.ui.PlanPalette
import com.sitesurvey.app.ui.PlanRenderer
import com.sitesurvey.app.ui.locationOf
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Builds the plan PDF and equipment CSV, then opens the share sheet with both. */
object Exporter {
    private const val W = 612 // US Letter, points
    private const val H = 792
    private const val M = 36f

    fun share(context: Context, s: Survey) {
        val dir = File(context.cacheDir, "exports").apply { mkdirs(); listFiles()?.forEach { it.delete() } }
        val base = s.name.replace(Regex("[^A-Za-z0-9 _-]"), "").trim().replace(' ', '_').ifBlank { "survey" }
        val pdf = File(dir, "$base.pdf").also { writePdf(s, it) }
        val csv = File(dir, "${base}_equipment.csv").also { it.writeText(csv(s)) }
        val auth = "${context.packageName}.files"
        val uris = arrayListOf<Uri>(FileProvider.getUriForFile(context, auth, pdf), FileProvider.getUriForFile(context, auth, csv))
        val send = Intent(Intent.ACTION_SEND_MULTIPLE).apply {
            type = "*/*"
            putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
            putExtra(Intent.EXTRA_SUBJECT, "Site survey: ${s.name}")
            clipData = ClipData.newRawUri("", uris[0]).apply { addItem(ClipData.Item(uris[1])) }
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(send, "Export survey").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    private fun esc(v: String) = if (v.any { it == ',' || it == '"' || it == '\n' }) "\"" + v.replace("\"", "\"\"") + "\"" else v

    fun csv(s: Survey): String {
        val sb = StringBuilder()
        sb.appendLine("Tag,Type,Model / description,Qty,Location,Mount height,Size,Coverage,Notes")
        for (g in s.gear.sortedBy { it.type.ordinal }) {
            val size = if (g.type == GearType.DISPLAY) "${g.sizeIn}\"" else ""
            val cov = when (g.type) {
                GearType.CEILING_SPEAKER -> "${g.angleDeg.toInt()}° cone, ${Units.ftInShort(s.coverageRadius(g) * 2)} dia at ear"
                GearType.CEILING_MIC, GearType.TABLE_MIC -> "${Units.ftInShort(g.radiusM)} radius"
                GearType.DISPLAY -> s.viewCheck(g).let { "${"%.1f".format(it.ratio)}x image height to far seat (${it.verdict})" }
            }
            sb.appendLine(listOf(s.tag(g), g.type.label, g.displayName, "1", locationOf(s, g), Units.ftIn(s.gearHeight(g)), size, cov, g.notes)
                .joinToString(",") { esc(it) })
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

    private fun writePdf(s: Survey, out: File) {
        val doc = PdfDocument()
        val date = SimpleDateFormat("MMM d, yyyy", Locale.US).format(Date(s.updated))
        val title = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 16f; isFakeBoldText = true }
        val body = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 9.5f }
        val muted = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 9f; color = 0xFF666666.toInt() }
        val bold = Paint(body).apply { isFakeBoldText = true }

        // Page 1: plan
        val p1 = doc.startPage(PdfDocument.PageInfo.Builder(W, H, 1).create())
        val c = p1.canvas
        c.drawText(s.name, M, M + 12, title)
        c.drawText(listOfNotNull(s.client.ifBlank { null }, date).joinToString("  ·  "), M, M + 28, muted)
        val facts = "Area ${Units.sqFt(s.area())}   Ceiling ${Units.ftIn(s.ceilingM)}   Perimeter ${Units.ftIn(s.perimeter())}   Openings ${s.openings.size}"
        c.drawText(facts, M, M + 44, body)
        PlanRenderer(s, PlanPalette.Print, 1.1f).draw(c, M, M + 56, W - 2 * M, H - 2 * M - 110)
        var y = H - M - 40
        c.drawText("Legend: D display (on wall)   SPK ceiling speaker, dashed circle = coverage at seated ear height   CM ceiling mic   TM table mic", M, y, muted)
        y += 12
        c.drawText("Dimensions are wall-to-wall at the floor. Heights are AFF (above finished floor).", M, y, muted)
        doc.finishPage(p1)

        // Page 2: equipment list
        val p2 = doc.startPage(PdfDocument.PageInfo.Builder(W, H, 2).create())
        val c2 = p2.canvas
        c2.drawText("Equipment list", M, M + 12, title)
        val cols = floatArrayOf(M, M + 42, M + 250, M + 380, M + 450)
        val heads = listOf("Tag", "Item", "Location", "Height", "Notes")
        y = M + 40
        heads.forEachIndexed { i, h -> c2.drawText(h, cols[i], y, bold) }
        y += 6
        c2.drawLine(M, y, W - M, y, muted)
        y += 14
        for (g in s.gear.sortedBy { it.type.ordinal }) {
            if (y > H - M - 140) break
            c2.drawText(s.tag(g), cols[0], y, body)
            c2.drawText(clip(g.displayName, body, cols[2] - cols[1] - 8), cols[1], y, body)
            c2.drawText(clip(locationOf(s, g), body, cols[3] - cols[2] - 8), cols[2], y, body)
            c2.drawText(Units.ftIn(s.gearHeight(g)), cols[3], y, body)
            c2.drawText(clip(g.notes, body, W - M - cols[4]), cols[4], y, body)
            y += 16
        }
        y += 10
        c2.drawText("Summary", M, y, bold); y += 14
        s.gear.groupBy { it.displayName }.forEach { (k, v) -> c2.drawText("${v.size} ×  $k", M, y, body); y += 14 }

        val displays = s.gear.filter { it.type == GearType.DISPLAY }
        if (displays.isNotEmpty()) {
            y += 10
            c2.drawText("Display viewing distance (4/6/8 rule: farthest seat within 6× image height)", M, y, bold); y += 14
            displays.forEach { g ->
                val vc = s.viewCheck(g)
                val line = "${s.tag(g)} ${g.sizeIn}\": farthest seat ${Units.ftIn(s.farthestViewer(g))}, " +
                    "${"%.1f".format(vc.ratio)}× image height. ${vc.verdict}" + if (!vc.ok) ". Recommend ${vc.minSizeIn}\" or larger." else "."
                c2.drawText(line, M, y, body); y += 14
            }
        }
        if (s.openings.isNotEmpty()) {
            y += 10
            c2.drawText("Openings", M, y, bold); y += 14
            s.openings.forEach { o ->
                c2.drawText("${o.type.label} on wall ${o.wall + 1}: ${Units.ftIn(o.u1 - o.u0)} wide × ${Units.ftIn(o.v1 - o.v0)} high, " +
                    "${Units.ftIn(o.u0)} from wall start", M, y, body); y += 14
            }
        }
        doc.finishPage(p2)
        out.outputStream().use { doc.writeTo(it) }
        doc.close()
    }

    private fun clip(t: String, p: Paint, w: Float): String {
        if (p.measureText(t) <= w) return t
        var e = t
        while (e.isNotEmpty() && p.measureText("$e…") > w) e = e.dropLast(1)
        return "$e…"
    }
}
