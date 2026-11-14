package com.vehiclegate.anpr.ui

import android.text.InputFilter
import android.text.InputType
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.vehiclegate.anpr.AnprApp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Runs [action] if an admin is logged in; otherwise asks for an admin PIN first
 * (so staff never have to log out for an admin task).
 */
fun AppCompatActivity.withAdmin(reason: String, action: () -> Unit) {
    val app = application as AnprApp
    if (app.session.isAdmin) { action(); return }
    val input = EditText(this).apply {
        inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD
        filters = arrayOf(InputFilter.LengthFilter(6))
        hint = "Admin PIN"
    }
    val box = FrameLayout(this).apply {
        val p = (resources.displayMetrics.density * 20).toInt()
        setPadding(p, p / 2, p, 0)
        addView(input)
    }
    MaterialAlertDialogBuilder(this)
        .setTitle("Admin PIN")
        .setMessage(reason)
        .setView(box)
        .setPositiveButton("OK") { _, _ ->
            val pin = input.text.toString()
            lifecycleScope.launch {
                val admin = withContext(Dispatchers.IO) { app.session.adminForPin(pin) }
                if (admin != null) action()
                else Toast.makeText(this@withAdmin, "Wrong admin PIN", Toast.LENGTH_SHORT).show()
            }
        }
        .setNegativeButton("Cancel", null)
        .show()
    input.requestFocus()
}
