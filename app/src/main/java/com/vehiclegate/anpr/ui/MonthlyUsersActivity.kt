package com.vehiclegate.anpr.ui

import android.annotation.SuppressLint
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.vehiclegate.anpr.AnprApp
import com.vehiclegate.anpr.R
import com.vehiclegate.anpr.core.IndianPlateParser
import com.vehiclegate.anpr.data.MonthlyMember
import com.vehiclegate.anpr.databinding.ActivityMonthlyBinding
import com.vehiclegate.anpr.databinding.DialogMemberBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Master data: vehicles that pay monthly. */
class MonthlyUsersActivity : AppCompatActivity() {

    private lateinit var b: ActivityMonthlyBinding
    private val app get() = application as AnprApp
    private val dateFmt = SimpleDateFormat("dd-MM-yyyy", Locale.US).apply { isLenient = false }
    private val adapter = Adapter()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityMonthlyBinding.inflate(layoutInflater)
        setContentView(b.root)
        b.list.layoutManager = LinearLayoutManager(this)
        b.list.adapter = adapter
        b.add.setOnClickListener { edit(null) }
        b.add.requestFocus()
        lifecycleScope.launch {
            app.db.members().allFlow().collect { list ->
                adapter.submit(list)
                b.add.text = "+ Add monthly user  (${list.size} saved)"
            }
        }
    }

    private fun edit(existing: MonthlyMember?) {
        val d = DialogMemberBinding.inflate(layoutInflater)
        existing?.let { m ->
            d.plate.setText(IndianPlateParser.parse(m.plate)?.formatted ?: m.plate)
            d.name.setText(m.name); d.phone.setText(m.phone); d.notes.setText(m.notes)
            m.validTill?.let { d.validTill.setText(dateFmt.format(Date(it))) }
        }
        val builder = MaterialAlertDialogBuilder(this)
            .setTitle(if (existing == null) "Add monthly user" else "Edit monthly user")
            .setView(d.root)
            .setPositiveButton("Save", null)
            .setNegativeButton("Cancel", null)
        if (existing != null) builder.setNeutralButton("Delete") { _, _ ->
            lifecycleScope.launch { withContext(Dispatchers.IO) { app.db.members().delete(existing.plate) } }
        }
        val dialog = builder.create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val plate = IndianPlateParser.normalize(d.plate.text.toString())
                if (plate.length < 6) { toast("Enter the vehicle number, e.g. TN 09 AB 1234"); return@setOnClickListener }
                val name = d.name.text.toString().trim()
                if (name.isEmpty()) { toast("Enter a name"); return@setOnClickListener }
                val tillText = d.validTill.text.toString().trim()
                val till = if (tillText.isEmpty()) null else try {
                    dateFmt.parse(tillText)!!.time + 24 * 3_600_000L - 1 // end of that day
                } catch (e: Exception) { toast("Valid till must be DD-MM-YYYY"); return@setOnClickListener }
                val m = MonthlyMember(plate, name, d.phone.text.toString().trim(), till, d.notes.text.toString().trim())
                lifecycleScope.launch {
                    withContext(Dispatchers.IO) {
                        if (existing != null && existing.plate != plate) app.db.members().delete(existing.plate)
                        app.db.members().upsert(m)
                    }
                    toast("Saved ${IndianPlateParser.parse(plate)?.formatted ?: plate}")
                    dialog.dismiss()
                }
            }
        }
        dialog.show()
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_LONG).show()

    private inner class Adapter : RecyclerView.Adapter<Adapter.VH>() {
        private var items: List<MonthlyMember> = emptyList()

        inner class VH(v: View) : RecyclerView.ViewHolder(v) {
            val plate: TextView = v.findViewById(R.id.plate)
            val who: TextView = v.findViewById(R.id.who)
            val date: TextView = v.findViewById(R.id.date)
            val time: TextView = v.findViewById(R.id.time)
            val mark: View = v.findViewById(R.id.mark)
        }

        @SuppressLint("NotifyDataSetChanged")
        fun submit(list: List<MonthlyMember>) { items = list; notifyDataSetChanged() }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
            VH(LayoutInflater.from(parent.context).inflate(R.layout.item_parked, parent, false))

        override fun getItemCount() = items.size

        override fun onBindViewHolder(h: VH, position: Int) {
            val m = items[position]
            val expired = m.validTill != null && m.validTill < System.currentTimeMillis()
            h.mark.setBackgroundColor(ContextCompat.getColor(this@MonthlyUsersActivity, if (expired) R.color.error else R.color.monthly))
            h.plate.text = IndianPlateParser.parse(m.plate)?.formatted ?: m.plate
            h.plate.setTextColor(ContextCompat.getColor(this@MonthlyUsersActivity, R.color.monthly_text))
            h.who.text = listOf(m.name, m.phone, m.notes).filter { it.isNotBlank() }.joinToString(" · ")
            h.date.text = if (m.validTill == null) "no end date" else (if (expired) "EXPIRED " else "till ") + dateFmt.format(Date(m.validTill))
            h.time.text = ""
            h.itemView.setOnClickListener { edit(m) }
        }
    }
}
