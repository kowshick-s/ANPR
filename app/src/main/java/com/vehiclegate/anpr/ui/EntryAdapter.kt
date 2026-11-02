package com.vehiclegate.anpr.ui

import android.annotation.SuppressLint
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import com.vehiclegate.anpr.R
import com.vehiclegate.anpr.data.VehicleEntry
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class EntryAdapter : RecyclerView.Adapter<EntryAdapter.VH>() {

    private var items: List<VehicleEntry> = emptyList()
    private val dateFmt = SimpleDateFormat("dd MMM yyyy", Locale.getDefault())
    private val timeFmt = SimpleDateFormat("hh:mm:ss a", Locale.getDefault())

    class VH(v: View) : RecyclerView.ViewHolder(v) {
        val plate: TextView = v.findViewById(R.id.plate)
        val meta: TextView = v.findViewById(R.id.meta)
        val date: TextView = v.findViewById(R.id.date)
        val time: TextView = v.findViewById(R.id.time)
    }

    @SuppressLint("NotifyDataSetChanged")
    fun submit(list: List<VehicleEntry>) {
        items = list
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
        VH(LayoutInflater.from(parent.context).inflate(R.layout.item_entry, parent, false))

    override fun getItemCount() = items.size

    override fun onBindViewHolder(h: VH, position: Int) {
        val e = items[position]
        val ctx = h.itemView.context
        h.plate.text = e.plateFormatted
        h.plate.setTextColor(ContextCompat.getColor(ctx, if (position == 0) R.color.accent else R.color.text))
        h.meta.text = "${(e.confidence * 100).toInt()}% • ${e.cameraCount} cam • ${e.totalReads} reads"
        h.date.text = dateFmt.format(Date(e.entryTime))
        h.time.text = timeFmt.format(Date(e.entryTime))
    }
}
