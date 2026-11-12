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

/** "Token 12 · Ravi" / "Ravi" / "no token" */
fun VehicleEntry.whoText(): String {
    val parts = listOfNotNull(
        token?.takeIf { it.isNotBlank() }?.let { "Token $it" },
        name?.takeIf { it.isNotBlank() }
    )
    return parts.joinToString(" · ").ifEmpty { "no token / name" }
}

/** Vehicles currently parked. Tap = edit token/name or check out; long-press = delete wrong read. */
class ParkedAdapter(
    private val onClick: (VehicleEntry) -> Unit,
    private val onLongClick: (VehicleEntry) -> Unit
) : RecyclerView.Adapter<ParkedAdapter.VH>() {

    private var items: List<VehicleEntry> = emptyList()

    class VH(v: View) : RecyclerView.ViewHolder(v) {
        val mark: View = v.findViewById(R.id.mark)
        val plate: TextView = v.findViewById(R.id.plate)
        val who: TextView = v.findViewById(R.id.who)
        val date: TextView = v.findViewById(R.id.date)
        val time: TextView = v.findViewById(R.id.time)
    }

    @SuppressLint("NotifyDataSetChanged")
    fun submit(list: List<VehicleEntry>) { items = list; notifyDataSetChanged() }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
        VH(LayoutInflater.from(parent.context).inflate(R.layout.item_parked, parent, false))

    override fun getItemCount() = items.size

    override fun onBindViewHolder(h: VH, position: Int) {
        val e = items[position]
        val ctx = h.itemView.context
        val color = ContextCompat.getColor(ctx, if (e.isMonthly) R.color.monthly_text else R.color.text)
        h.mark.setBackgroundColor(ContextCompat.getColor(ctx, if (e.isMonthly) R.color.monthly else R.color.accent))
        h.plate.text = e.plateFormatted + if (e.isMonthly) "  · MONTHLY" else ""
        h.plate.setTextColor(color)
        h.who.text = e.whoText()
        h.date.text = dateFmt.format(Date(e.entryTime))
        h.time.text = timeFmt.format(Date(e.entryTime))
        h.itemView.setOnClickListener { onClick(e) }
        h.itemView.setOnLongClickListener { onLongClick(e); true }
    }
}

/** Daily vehicles seen leaving: collect the amount, then Check out. ✕ = not actually leaving. */
class LeavingAdapter(
    private val amountFor: (VehicleEntry) -> Int,
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
        h.checkout.text = "Check out ₹${amountFor(e)}"
        h.checkout.setOnClickListener { onCheckout(e) }
        h.notLeaving.setOnClickListener { onNotLeaving(e) }
    }
}
