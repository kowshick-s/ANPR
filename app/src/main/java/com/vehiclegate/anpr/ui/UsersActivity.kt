package com.vehiclegate.anpr.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.RadioGroup
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.vehiclegate.anpr.AnprApp
import com.vehiclegate.anpr.R
import com.vehiclegate.anpr.core.PinHash
import com.vehiclegate.anpr.data.AppUser
import com.vehiclegate.anpr.data.Role
import com.vehiclegate.anpr.databinding.ActivityUsersBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Admin: add staff / admins / TV displays, change PINs, switch people off. */
class UsersActivity : AppCompatActivity() {

    private lateinit var b: ActivityUsersBinding
    private val app get() = application as AnprApp
    private var users: List<AppUser> = emptyList()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!app.session.isAdmin && !intent.getBooleanExtra(SettingsActivity.EXTRA_ADMIN_OK, false)) { finish(); return }
        b = ActivityUsersBinding.inflate(layoutInflater)
        setContentView(b.root)
        b.list.layoutManager = LinearLayoutManager(this)
        val adapter = object : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
            override fun getItemCount() = users.size
            override fun onCreateViewHolder(p: ViewGroup, t: Int) =
                object : RecyclerView.ViewHolder(LayoutInflater.from(p.context).inflate(R.layout.item_user, p, false)) {}
            override fun onBindViewHolder(h: RecyclerView.ViewHolder, i: Int) {
                val u = users[i]
                val v = h.itemView
                v.findViewById<TextView>(R.id.name).apply {
                    text = u.name + if (u.id == app.session.user?.id) "  (you)" else ""
                    alpha = if (u.active) 1f else 0.45f
                }
                v.findViewById<TextView>(R.id.role).text = Role.label(u.role)
                v.findViewById<Button>(R.id.edit).setOnClickListener { edit(u) }
                v.findViewById<Button>(R.id.toggle).apply {
                    text = if (u.active) "Switch off" else "Switch on"
                    setOnClickListener { toggle(u) }
                }
            }
        }
        b.list.adapter = adapter
        b.add.setOnClickListener { edit(null) }
        lifecycleScope.launch {
            app.db.users().allFlow().collect { users = it; adapter.notifyDataSetChanged() }
        }
    }

    private fun toggle(u: AppUser) {
        lifecycleScope.launch {
            if (u.active && u.role == Role.ADMIN && withContext(Dispatchers.IO) { app.db.users().adminCount() } <= 1) {
                Toast.makeText(this@UsersActivity, "Keep at least one admin", Toast.LENGTH_LONG).show(); return@launch
            }
            if (u.id == app.session.user?.id) {
                Toast.makeText(this@UsersActivity, "You can't switch yourself off", Toast.LENGTH_LONG).show(); return@launch
            }
            withContext(Dispatchers.IO) { app.session.setActive(u, !u.active) }
        }
    }

    private fun edit(u: AppUser?) {
        val v = layoutInflater.inflate(R.layout.dialog_user, null)
        val name = v.findViewById<EditText>(R.id.name)
        val role = v.findViewById<RadioGroup>(R.id.role)
        val pin = v.findViewById<EditText>(R.id.pin)
        if (u != null) {
            name.setText(u.name)
            role.check(when (u.role) { Role.ADMIN -> R.id.roleAdmin; Role.TV -> R.id.roleTv; else -> R.id.roleStaff })
            v.findViewById<TextView>(R.id.pinLabel).text = "New PIN (leave empty to keep the current one)"
        }
        MaterialAlertDialogBuilder(this)
            .setTitle(if (u == null) "Add user" else "Edit ${u.name}")
            .setView(v)
            .setPositiveButton("Save") { _, _ ->
                val n = name.text.toString().trim()
                val r = when (role.checkedRadioButtonId) { R.id.roleAdmin -> Role.ADMIN; R.id.roleTv -> Role.TV; else -> Role.STAFF }
                val p = pin.text.toString().ifEmpty { null }
                lifecycleScope.launch {
                    val err = when {
                        n.isEmpty() -> "Type a name"
                        u == null && p == null -> "Give a PIN"
                        p != null && !PinHash.validPin(p) -> "PIN must be 4 to 6 digits"
                        u?.role == Role.ADMIN && r != Role.ADMIN &&
                            withContext(Dispatchers.IO) { app.db.users().adminCount() } <= 1 -> "Keep at least one admin"
                        else -> null
                    }
                    if (err != null) { Toast.makeText(this@UsersActivity, err, Toast.LENGTH_LONG).show(); return@launch }
                    withContext(Dispatchers.IO) { app.session.saveUser(u, n, r, p) }
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }
}
