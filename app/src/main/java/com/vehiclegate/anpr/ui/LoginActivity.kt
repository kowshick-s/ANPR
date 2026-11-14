package com.vehiclegate.anpr.ui

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.LinearLayout
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.AppCompatButton
import androidx.lifecycle.lifecycleScope
import com.google.android.material.chip.Chip
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.vehiclegate.anpr.AnprApp
import com.vehiclegate.anpr.R
import com.vehiclegate.anpr.core.PinHash
import com.vehiclegate.anpr.data.AppUser
import com.vehiclegate.anpr.data.Role
import com.vehiclegate.anpr.databinding.ActivityLoginBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * App start. Logged in before → straight to the counter (Staff / Admin) or the dashboard (TV).
 * Otherwise: pick your name and type your PIN. First run: create the admin.
 */
class LoginActivity : AppCompatActivity() {

    private lateinit var b: ActivityLoginBinding
    private val app get() = application as AnprApp
    private var selected: AppUser? = null
    private val pin = StringBuilder()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityLoginBinding.inflate(layoutInflater)
        setContentView(b.root)
        b.card.visibility = View.INVISIBLE
        b.title.text = app.settingsStore.settings().siteTitle
        lifecycleScope.launch {
            val restored = withContext(Dispatchers.IO) { app.session.restore() }
            if (restored != null && !intent.getBooleanExtra(EXTRA_SWITCH, false)) { route(restored); return@launch }
            val setup = withContext(Dispatchers.IO) { app.session.needsSetup() }
            b.card.visibility = View.VISIBLE
            if (setup) showSetup() else showLogin()
        }
    }

    private fun route(u: AppUser) {
        val target = if (u.role == Role.TV) DashboardActivity::class.java else MainActivity::class.java
        startActivity(Intent(this, target).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
        finish()
    }

    // ------------------------------------------------------------------ first run

    private fun showSetup() {
        b.subtitle.text = "First start: create the admin. The admin sets up cameras, fees, staff and TV displays."
        b.setupBox.visibility = View.VISIBLE
        b.setupCreate.setOnClickListener {
            val name = b.setupName.text.toString().trim()
            val p1 = b.setupPin.text.toString(); val p2 = b.setupPin2.text.toString()
            when {
                name.isEmpty() -> b.message.text = "Type the admin name"
                !PinHash.validPin(p1) -> b.message.text = "PIN must be 4 to 6 digits"
                p1 != p2 -> b.message.text = "The two PINs are different"
                else -> lifecycleScope.launch {
                    val u = withContext(Dispatchers.IO) { app.session.saveUser(null, name, Role.ADMIN, p1) }
                    val code = app.session.newResetCode()
                    withContext(Dispatchers.IO) { app.session.login(u, p1) }
                    MaterialAlertDialogBuilder(this@LoginActivity)
                        .setTitle("Write down this reset code")
                        .setMessage("If the admin PIN is forgotten, this code sets a new one:\n\n$code\n\n" +
                            "It is shown only now. Keep it somewhere safe (not on this device).")
                        .setCancelable(false)
                        .setPositiveButton("I have written it down") { _, _ -> route(u) }
                        .show()
                }
            }
        }
    }

    // ------------------------------------------------------------------ login

    private fun showLogin() {
        b.subtitle.text = "Who is using this device? Pick your name and type your PIN."
        b.loginBox.visibility = View.VISIBLE
        buildPad()
        lifecycleScope.launch {
            app.db.users().activeFlow().collect { list ->
                b.users.removeAllViews()
                for (u in list) {
                    val chip = Chip(this@LoginActivity).apply {
                        text = "${u.name}  ·  ${Role.label(u.role)}"
                        isCheckable = true
                        isChecked = selected?.id == u.id
                        textSize = 16f
                        setOnClickListener { selected = u; pin.clear(); showPin(); b.message.text = "" }
                    }
                    b.users.addView(chip)
                }
                if (list.size == 1 && selected == null) { selected = list.first(); (b.users.getChildAt(0) as Chip).isChecked = true }
            }
        }
        b.forgot.setOnClickListener { resetAdminPin() }
        showPin()
    }

    private fun buildPad() {
        val keys = listOf("1", "2", "3", "4", "5", "6", "7", "8", "9", "⌫", "0", "OK")
        val size = (resources.displayMetrics.density * 64).toInt()
        val gap = (resources.displayMetrics.density * 6).toInt()
        for (row in keys.chunked(3)) {
            val line = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            for (k in row) {
                val btn = AppCompatButton(this, null, 0).apply {
                    text = k
                    textSize = 22f
                    setTextColor(getColor(if (k == "OK") R.color.on_accent else R.color.text))
                    setBackgroundResource(if (k == "OK") R.drawable.btn_filled_bg else R.drawable.focus_bg)
                    isFocusable = true
                    setOnClickListener { press(k) }
                }
                line.addView(btn, LinearLayout.LayoutParams(size, size).apply { setMargins(gap, gap, gap, gap) })
            }
            b.pad.addView(line)
        }
    }

    private fun press(k: String) {
        when (k) {
            "⌫" -> if (pin.isNotEmpty()) pin.deleteCharAt(pin.length - 1)
            "OK" -> { tryLogin(); return }
            else -> if (pin.length < 6) pin.append(k)
        }
        b.message.text = ""
        showPin()
        if (pin.length == 6) tryLogin()
    }

    private fun showPin() {
        b.pinDots.text = if (selected == null) "" else "●".repeat(pin.length) + "○".repeat((4 - pin.length).coerceAtLeast(0))
    }

    private fun tryLogin() {
        val u = selected ?: run { b.message.text = "Pick your name first"; return }
        val p = pin.toString()
        lifecycleScope.launch {
            val ok = withContext(Dispatchers.IO) { app.session.login(u, p) }
            if (ok) route(u)
            else { b.message.text = "Wrong PIN"; pin.clear(); showPin() }
        }
    }

    /** Forgotten admin PIN: the reset code sets a new PIN for the admin. */
    private fun resetAdminPin() {
        val v = layoutInflater.inflate(R.layout.dialog_reset_pin, null)
        val code = v.findViewById<android.widget.EditText>(R.id.resetCode)
        val p1 = v.findViewById<android.widget.EditText>(R.id.resetPin)
        val p2 = v.findViewById<android.widget.EditText>(R.id.resetPin2)
        MaterialAlertDialogBuilder(this)
            .setTitle("Reset admin PIN")
            .setView(v)
            .setPositiveButton("Reset") { _, _ ->
                val np = p1.text.toString()
                when {
                    !app.session.resetCodeMatches(code.text.toString()) -> b.message.text = "Reset code is not correct"
                    !PinHash.validPin(np) -> b.message.text = "PIN must be 4 to 6 digits"
                    np != p2.text.toString() -> b.message.text = "The two PINs are different"
                    else -> lifecycleScope.launch {
                        val admin = withContext(Dispatchers.IO) { app.db.users().active().firstOrNull { it.role == Role.ADMIN } }
                        if (admin == null) { b.message.text = "No admin found"; return@launch }
                        withContext(Dispatchers.IO) { app.session.saveUser(admin, admin.name, admin.role, np) }
                        b.message.text = ""
                        android.widget.Toast.makeText(this@LoginActivity, "Admin PIN changed – log in with the new PIN", android.widget.Toast.LENGTH_LONG).show()
                    }
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    companion object {
        /** Open the login screen even if someone is logged in (Switch user). */
        const val EXTRA_SWITCH = "switch"
    }
}
