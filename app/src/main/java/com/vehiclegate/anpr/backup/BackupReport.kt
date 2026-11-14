package com.vehiclegate.anpr.backup

import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import com.vehiclegate.anpr.AnprApp
import com.vehiclegate.anpr.ParkingDesk
import com.vehiclegate.anpr.core.ParkingFee
import com.vehiclegate.anpr.data.VehicleEntry
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * Emergency paperwork: a printable PDF of every parked vehicle and today's CSV. If the app or the
 * phone fails, print the last one and carry on with pen and paper.
 */
class BackupReport(private val app: AnprApp) {

    data class Report(val pdf: File, val csv: File, val parked: List<VehicleEntry>, val outToday: Int,
                      val collected: Int, val fingerprint: String, val summary: String)

    private val dt = SimpleDateFormat("dd MMM yyyy, hh:mm a", Locale.getDefault())
    private val stamp = SimpleDateFormat("yyyyMMdd_HHmm", Locale.US)

    suspend fun build(): Report {
        val dao = app.db.entries()
        val parked = dao.openSessions().sortedByDescending { it.entryTime }
        val dayStart = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
        }.timeInMillis
        val today = dao.since(dayStart)
        val outs = today.filter { it.exitTime != null && it.exitTime >= dayStart }
        val collected = outs.sumOf { it.amount ?: 0 }
        val dir = File(app.cacheDir, "backup").apply { mkdirs(); listFiles()?.forEach { it.delete() } }
        val now = Date()
        val site = app.settingsStore.settings().siteTitle
        val pdf = File(dir, "parked_${stamp.format(now)}.pdf")
        writePdf(pdf, site, now, parked, outs.size, collected)
        val csv = File(dir, "today_${stamp.format(now)}.csv")
        writeCsv(csv, today)
        val fp = parked.joinToString(",") { "${it.id}:${it.plate}:${it.name}" } + "|" + outs.size + "|" + collected
        val summary = buildString {
            appendLine("$site – backup ${dt.format(now)}")
            appendLine("Parked now: ${parked.size}   ·   Out today: ${outs.size}   ·   Collected today: ₹$collected")
            appendLine()
            appendLine("PARKED VEHICLES")
            parked.forEachIndexed { i, e ->
                appendLine("${i + 1}. ${e.plateFormatted}  –  ${e.name?.ifBlank { "-" } ?: "-"}  –  ${e.model?.ifBlank { "-" } ?: "-"}  –  in ${dt.format(Date(e.entryTime))}" +
                    tag(e).let { if (it.isEmpty()) "" else "  ($it)" })
            }
            appendLine()
            appendLine("Attached: printable PDF of parked vehicles and today's CSV.")
        }
        return Report(pdf, csv, parked, outs.size, collected, fp, summary)
    }

    private fun tag(e: VehicleEntry) = when {
        e.isMonthly -> "monthly"
        e.cameraSummary == ParkingDesk.MANUAL -> "manual"
        else -> ""
    }

    private fun writePdf(file: File, site: String, now: Date, parked: List<VehicleEntry>, outToday: Int, collected: Int) {
        val doc = PdfDocument()
        val w = 595; val h = 842; val margin = 36f
        val title = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 16f; typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD) }
        val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 10.5f }
        val bold = Paint(text).apply { typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD) }
        val line = Paint().apply { strokeWidth = 0.6f; color = 0xFF999999.toInt() }
        val cols = floatArrayOf(margin, margin + 24, margin + 130, margin + 270, margin + 380, margin + 490)
        val heads = listOf("#", "Vehicle no.", "Token / Name", "Model", "Checked in", "Out / ₹")
        var pageNo = 0
        var page: PdfDocument.Page? = null
        var y = 0f
        fun newPage() {
            page?.let { doc.finishPage(it) }
            pageNo++
            page = doc.startPage(PdfDocument.PageInfo.Builder(w, h, pageNo).create())
            val c = page!!.canvas
            y = margin + 10
            c.drawText("$site – parked vehicles", margin, y, title); y += 18
            c.drawText("Printed ${dt.format(now)}   ·   Parked: ${parked.size}   ·   Out today: $outToday   ·   Collected today: ₹$collected   ·   Page $pageNo",
                margin, y, text); y += 20
            heads.forEachIndexed { i, s -> c.drawText(s, cols[i], y, bold) }
            y += 6; c.drawLine(margin, y, w - margin, y, line); y += 14
        }
        newPage()
        parked.forEachIndexed { i, e ->
            if (y > h - margin - 20) newPage()
            val c = page!!.canvas
            val cells = listOf(
                "${i + 1}", e.plateFormatted + tag(e).let { if (it.isEmpty()) "" else " ($it)" },
                e.name?.ifBlank { "" } ?: "", e.model ?: "",
                SimpleDateFormat("dd MMM hh:mm a", Locale.getDefault()).format(Date(e.entryTime)),
                "_____  ₹____"
            )
            cells.forEachIndexed { k, s -> c.drawText(ellipsize(s, text, (cols.getOrNull(k + 1) ?: (w - margin)) - cols[k] - 6), cols[k], y, if (k == 1) bold else text) }
            y += 6; c.drawLine(margin, y, w - margin, y, line); y += 14
        }
        if (parked.isEmpty()) page!!.canvas.drawText("No vehicles parked.", margin, y, text)
        page?.let { doc.finishPage(it) }
        FileOutputStream(file).use { doc.writeTo(it) }
        doc.close()
    }

    private fun ellipsize(s: String, p: Paint, max: Float): String {
        if (p.measureText(s) <= max) return s
        var t = s
        while (t.isNotEmpty() && p.measureText("$t…") > max) t = t.dropLast(1)
        return "$t…"
    }

    private fun writeCsv(file: File, rows: List<VehicleEntry>) {
        fun q(s: String?) = "\"" + (s ?: "").replace("\"", "\"\"") + "\""
        val f = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US)
        val sb = StringBuilder("﻿Vehicle no,Token/Name,Model,Checked in,Checked out,Duration,Amount,Status,Type,In by,Out by\n")
        for (e in rows.sortedBy { it.entryTime }) {
            sb.append(listOf(
                q(e.plateFormatted), q(e.name), q(e.model), q(f.format(Date(e.entryTime))),
                q(e.exitTime?.let { f.format(Date(it)) }),
                q(ParkingFee.duration(e.entryTime, e.exitTime ?: System.currentTimeMillis())),
                (e.amount ?: 0).toString(), q(e.state), q(tag(e).ifEmpty { "daily" }), q(e.checkedInBy), q(e.checkedOutBy)
            ).joinToString(",")).append('\n')
        }
        file.writeText(sb.toString())
    }
}
