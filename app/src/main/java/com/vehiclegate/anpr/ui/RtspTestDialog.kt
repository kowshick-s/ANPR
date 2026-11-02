package com.vehiclegate.anpr.ui

import android.graphics.Typeface
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.vehiclegate.anpr.R
import com.vehiclegate.anpr.core.RtspProbe
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Runs [RtspProbe] for a URL and shows every handshake step with timings and a fix hint. */
object RtspTestDialog {

    fun show(activity: AppCompatActivity, name: String, url: String) {
        val density = activity.resources.displayMetrics.density
        val text = TextView(activity).apply {
            typeface = Typeface.MONOSPACE
            textSize = 13f
            setTextColor(ContextCompat.getColor(activity, R.color.text))
            val p = (16 * density).toInt()
            setPadding(p, p, p, p)
        }
        val scroll = ScrollView(activity).apply { addView(text); isFocusable = true }

        val dialog = MaterialAlertDialogBuilder(activity)
            .setTitle("Connection test – $name")
            .setView(scroll)
            .setPositiveButton("Close", null)
            .setNeutralButton("Test again", null)
            .create()

        var job: Job? = null
        fun runTest() {
            job?.cancel()
            text.text = "Testing ${maskPassword(url)}\n\nConnecting to the camera… (up to ~15 s)"
            job = activity.lifecycleScope.launch {
                val r = withContext(Dispatchers.IO) { RtspProbe(url).run() }
                if (!dialog.isShowing) return@launch
                text.text = buildString {
                    append(if (r.success) "✅ " else "❌ ")
                    append(r.summary).append("\n")
                    r.hint?.let { append("\n💡 ").append(it).append("\n") }
                    append("\n").append(r.report())
                    append("\nTotal: ${r.totalMs} ms")
                }
            }
        }

        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener { runTest() }
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).requestFocus()
            runTest()
        }
        dialog.setOnDismissListener { job?.cancel() }
        dialog.show()
    }

    fun maskPassword(url: String) = url.replace(Regex("(rtsps?://[^:/@]+:)[^/]*@"), "$1••••@")
}
