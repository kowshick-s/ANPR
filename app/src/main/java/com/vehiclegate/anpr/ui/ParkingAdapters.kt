package com.vehiclegate.anpr.ui

import android.annotation.SuppressLint
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import com.vehiclegate.anpr.R
import com.vehiclegate.anpr.core.ParkingFee
import com.vehiclegate.anpr.data.VehicleEntry
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val timeFmt = SimpleDateFormat("hh:mm a", Locale.getDefault())
private val dateFmt = SimpleDateFormat("dd MMM", Locale.getDefault())

/** "Ravi · Activa" / "12 · Activa" / "no token / name" */
fun VehicleEntry.whoText(): String {
    val parts = listOfNotNull(
        name?.takeIf { it.isNotBlank() } ?: token?.takeIf { it.isNotBlank() },
        model?.takeIf { it.isNotBlank() }
    )
    return parts.joinToString(" · ").ifEmpty { "no token / name" }
}

/**
 * CHECK-IN list (vehicles parked now). Token / Name / Model are typed directly in the row and
 * saved when the user presses Done/Next or leaves the box. Uses stable ids + DiffUtil so a row
 * being typed in stays with its vehicle when new check-ins arrive.
 */
class CheckInAdapter(
    private val onSave: (VehicleEntry, who: String, model: String) -> Unit,
    /** Model suggestions (common models + every model typed before). */
    private val modelSuggestions: () -> android.widget.ArrayAdapter<String>?,
    private val onPlateClick: (VehicleEntry) -> Unit,
    private val onLongClick: (VehicleEntry) -> Unit
) : androidx.recyclerview.widget.ListAdapter<VehicleEntry, CheckInAdapter.VH>(DIFF) {

    init { setHasStableIds(true) }

    inner class VH(v: View) : RecyclerView.ViewHolder(v) {
        val mark: View = v.findViewById(R.id.mark)
        val plate: TextView = v.findViewById(R.id.plate)
        val name: android.widget.EditText = v.findViewById(R.id.name)
        val model: android.widget.AutoCompleteTextView = v.findViewById(R.id.model)
        val whenText: TextView = v.findViewById(R.id.`when`)
        var entry: VehicleEntry? = null

        init {
            val save = {
                entry?.let { e ->
                    val n = name.text.toString().trim(); val m = model.text.toString().trim()
                    if (n != (e.name ?: "") || m != (e.model ?: "")) {
                        entry = e.copy(name = n, model = m)
                        onSave(e, n, m)
                    }
                }
            }
            model.setOnItemClickListener { _, _, _, _ -> save() }
            for (f in listOf(name, model)) {
                f.setOnFocusChangeListener { _, has -> if (!has) save() }
                f.setOnEditorActionListener { _, _, _ -> save(); false }
            }
        }
    }

    override fun getItemId(position: Int) = getItem(position).id

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
        VH(LayoutInflater.from(parent.context).inflate(R.layout.item_checkin, parent, false))

    override fun onBindViewHolder(h: VH, position: Int) {
        val e = getItem(position)
        val ctx = h.itemView.context
        h.entry = e
        h.mark.setBackgroundColor(ContextCompat.getColor(ctx, if (e.isMonthly) R.color.monthly else R.color.checkin))
        h.plate.text = e.plateFormatted + if (e.isMonthly) "\nMONTHLY" else ""
        h.plate.setTextColor(ContextCompat.getColor(ctx, if (e.isMonthly) R.color.monthly_text else R.color.text))
        // never overwrite what the user is typing
        if (!h.name.hasFocus()) h.name.setText(e.name?.takeIf { it.isNotBlank() } ?: e.token ?: "")
        if (!h.model.hasFocus()) h.model.setText(e.model ?: "", false)
        if (h.model.adapter == null) modelSuggestions()?.let { h.model.setAdapter(it) }
        h.whenText.text = "${dateFmt.format(Date(e.entryTime))}\n${timeFmt.format(Date(e.entryTime))}"
        h.plate.setOnClickListener { onPlateClick(e) }
        h.plate.setOnLongClickListener { onLongClick(e); true }
    }

    companion object {
        private val DIFF = object : androidx.recyclerview.widget.DiffUtil.ItemCallback<VehicleEntry>() {
            override fun areItemsTheSame(a: VehicleEntry, b: VehicleEntry) = a.id == b.id
            override fun areContentsTheSame(a: VehicleEntry, b: VehicleEntry) = a == b
        }
    }
}

/** Daily vehicles seen leaving: collect the amount, then Check out. ✕ = not actually leaving. */
class LeavingAdapter(
    private val amountFor: (VehicleEntry) -> Int,
    private val onPlateClick: (VehicleEntry) -> Unit,
    private val onCheckout: (VehicleEntry) -> Unit,
    private val onNotLeaving: (VehicleEntry) -> Unit
) : RecyclerView.Adapter<LeavingAdapter.VH>() {

    private var items: List<VehicleEntry> = emptyList()

    class VH(v: View) : RecyclerView.ViewHolder(v) {
        val plate: TextView = v.findViewById(R.id.plate)
        val details: TextView = v.findViewById(R.id.details)
        val checkout: Button = v.findViewById(R.id.checkout)
        val notLeaving: Button = v.findViewById(R.id.notLeaving)
    }

    @SuppressLint("NotifyDataSetChanged")
    fun submit(list: List<VehicleEntry>) { items = list; notifyDataSetChanged() }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
        VH(LayoutInflater.from(parent.context).inflate(R.layout.item_leaving, parent, false))

    override fun getItemCount() = items.size

    override fun onBindViewHolder(h: VH, position: Int) {
        val e = items[position]
        val out = e.exitDetectedAt ?: System.currentTimeMillis()
        h.plate.text = e.plateFormatted
        h.details.text = "${e.whoText()} · in ${timeFmt.format(Date(e.entryTime))}" +
            " · out ${timeFmt.format(Date(out))} · ${ParkingFee.duration(e.entryTime, out)}"
        h.plate.setOnClickListener { onPlateClick(e) }
        h.checkout.text = "Check out ₹${amountFor(e)}"
        h.checkout.setOnClickListener { onCheckout(e) }
        h.notLeaving.setOnClickListener { onNotLeaving(e) }
    }
}
