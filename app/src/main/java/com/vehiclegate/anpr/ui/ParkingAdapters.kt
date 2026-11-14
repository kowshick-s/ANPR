package com.vehiclegate.anpr.ui

import android.os.SystemClock
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.AutoCompleteTextView
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.vehiclegate.anpr.ParkingDesk
import com.vehiclegate.anpr.R
import com.vehiclegate.anpr.core.CaptureLogic
import com.vehiclegate.anpr.core.ParkingFee
import com.vehiclegate.anpr.data.Capture
import com.vehiclegate.anpr.data.VehicleEntry
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

private val timeFmt = SimpleDateFormat("hh:mm a", Locale.getDefault())
private val dateTimeFmt = SimpleDateFormat("dd MMM, hh:mm a", Locale.getDefault())

private fun isToday(ms: Long): Boolean {
    val a = Calendar.getInstance().apply { timeInMillis = ms }
    val b = Calendar.getInstance()
    return a.get(Calendar.YEAR) == b.get(Calendar.YEAR) && a.get(Calendar.DAY_OF_YEAR) == b.get(Calendar.DAY_OF_YEAR)
}

/** "10:07 AM" today, "12 Nov, 10:07 AM" otherwise. */
fun shortTime(ms: Long): String = if (isToday(ms)) timeFmt.format(Date(ms)) else dateTimeFmt.format(Date(ms))

/** "Ravi · Activa" / "12 · Activa" / "no token / name" */
fun VehicleEntry.whoText(): String {
    val parts = listOfNotNull(
        name?.takeIf { it.isNotBlank() } ?: token?.takeIf { it.isNotBlank() },
        model?.takeIf { it.isNotBlank() }
    )
    return parts.joinToString(" · ").ifEmpty { "no token / name" }
}

private fun afterChange(f: EditText, block: () -> Unit) = f.addTextChangedListener(object : TextWatcher {
    override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
    override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
    override fun afterTextChanged(s: Editable?) = block()
})

// =====================================================================================
// CAPTURED waiting list
// =====================================================================================

/**
 * Waiting-list cards. Tap a card → the check-in editor opens inside the card (number with the other
 * readings as chips, Token/Name, Model, Check in / Ignore). While the number is typed the counter
 * looks it up (monthly user → checked in automatically, parked vehicle → Check out, regular → name).
 * A card with id [MANUAL_ID] is the manual check-in form.
 */
class CaptureAdapter(private val host: Host) : ListAdapter<Capture, CaptureAdapter.VH>(DIFF) {

    interface Host {
        fun parkedFor(c: Capture): VehicleEntry?
        fun amountFor(e: VehicleEntry): Int
        fun loadThumb(path: String?, into: ImageView)
        fun modelSuggestions(): ArrayAdapter<String>?
        /** Look up a typed number; [show] runs on the main thread (not called if the host acted itself). */
        fun lookup(c: Capture, typed: String, show: (String, ParkingDesk.Hint) -> Unit)
        fun checkIn(c: Capture, plate: String, who: String, model: String)
        fun checkOut(c: Capture, parked: VehicleEntry)
        fun ignore(c: Capture)
        fun photo(c: Capture)
    }

    var expandedId: Long? = null
        private set

    init { setHasStableIds(true) }

    fun expand(id: Long?) {
        val old = expandedId
        expandedId = id
        currentList.indexOfFirst { it.id == old }.takeIf { it >= 0 }?.let { notifyItemChanged(it) }
        currentList.indexOfFirst { it.id == id }.takeIf { it >= 0 }?.let { notifyItemChanged(it) }
    }

    override fun getItemId(position: Int) = getItem(position).id

    inner class VH(v: View) : RecyclerView.ViewHolder(v) {
        val head: View = v.findViewById(R.id.head)
        val thumb: ImageView = v.findViewById(R.id.thumb)
        val plate: TextView = v.findViewById(R.id.plate)
        val alts: TextView = v.findViewById(R.id.alts)
        val time: TextView = v.findViewById(R.id.time)
        val badge: TextView = v.findViewById(R.id.badge)
        val editor: View = v.findViewById(R.id.editor)
        val chips: LinearLayout = v.findViewById(R.id.chips)
        val editPlate: EditText = v.findViewById(R.id.editPlate)
        val editName: EditText = v.findViewById(R.id.editName)
        val editModel: AutoCompleteTextView = v.findViewById(R.id.editModel)
        val editHint: TextView = v.findViewById(R.id.editHint)
        val ignore: Button = v.findViewById(R.id.ignore)
        val confirm: Button = v.findViewById(R.id.confirm)

        var capture: Capture? = null
        /** Capture the editor fields were filled for (so rebinding never wipes what is being typed). */
        var editorFor: Long? = null
        var parked: VehicleEntry? = null
        private val lookupNow = Runnable { runLookup() }

        init {
            afterChange(editPlate) {
                if (editPlate.hasFocus()) { editPlate.removeCallbacks(lookupNow); editPlate.postDelayed(lookupNow, 500) }
            }
            confirm.setOnClickListener {
                val c = capture ?: return@setOnClickListener
                val p = parked
                if (p != null) host.checkOut(c, p)
                else host.checkIn(c, editPlate.text.toString(), editName.text.toString(), editModel.text.toString())
            }
            ignore.setOnClickListener { capture?.let { host.ignore(it) } }
            thumb.setOnClickListener { capture?.let { host.photo(it) } }
        }

        fun runLookup() {
            val c = capture ?: return
            val typed = editPlate.text.toString()
            val forId = c.id
            host.lookup(c, typed) { text, hint ->
                if (capture?.id != forId) return@lookup
                editHint.text = text
                showParked((hint as? ParkingDesk.Hint.Parked)?.entry)
                if (hint is ParkingDesk.Hint.Regular) {
                    if (editName.text.isNullOrBlank() && !editName.hasFocus()) editName.setText(hint.name)
                    if (editModel.text.isNullOrBlank() && !editModel.hasFocus()) editModel.setText(hint.model, false)
                }
            }
        }

        fun showParked(p: VehicleEntry?) {
            parked = p
            val ctx = itemView.context
            if (p != null) {
                confirm.text = if (p.isMonthly) "Check out" else "Check out ₹${host.amountFor(p)}"
                confirm.setBackgroundResource(R.drawable.btn_checkout_bg)
                confirm.setTextColor(ContextCompat.getColor(ctx, R.color.on_checkout))
            } else {
                confirm.text = "Check in"
                confirm.setBackgroundResource(R.drawable.btn_filled_bg)
                confirm.setTextColor(ContextCompat.getColor(ctx, R.color.on_accent))
            }
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
        VH(LayoutInflater.from(parent.context).inflate(R.layout.item_capture, parent, false))

    override fun onBindViewHolder(h: VH, position: Int) {
        val c = getItem(position)
        val manual = c.id == MANUAL_ID
        val open = manual || c.id == expandedId
        h.capture = c
        h.itemView.isActivated = open

        val readings = CaptureLogic.decode(c.readings)
        if (manual) {
            h.thumb.visibility = View.GONE
            h.plate.text = "Manual check-in"
            h.alts.text = "For vehicles the cameras can't see (e.g. parked outside)"
            h.time.text = ""
        } else {
            h.thumb.visibility = View.VISIBLE
            host.loadThumb(c.snapshotPath, h.thumb)
            h.plate.text = c.plateFormatted
            val others = readings.filter { it.compact != c.plate }.map { it.formatted }
            h.alts.text = if (others.isEmpty()) "Read ${c.reads}×" else "Also read: " + others.joinToString(" · ")
            h.time.text = "${shortTime(c.firstSeen)}\n${c.reads} reads"
        }
        val parked = if (manual) null else host.parkedFor(c)
        h.badge.visibility = if (parked != null) View.VISIBLE else View.GONE
        if (parked != null) h.badge.text = "Parked · leaving?"
        h.head.setOnClickListener { if (!manual) expand(if (expandedId == c.id) null else c.id) }

        h.editor.visibility = if (open) View.VISIBLE else View.GONE
        if (!open) { h.editorFor = null; return }

        if (h.editorFor != c.id) {
            h.editorFor = c.id
            h.editPlate.setText(if (manual) "" else c.plateFormatted)
            h.editName.setText("")
            h.editModel.setText("", false)
            h.editHint.text = if (manual) "Type the number – regulars and monthly users are recognised" else ""
            h.ignore.text = if (manual) "Cancel" else "Ignore"
            if (h.editModel.adapter == null) host.modelSuggestions()?.let { h.editModel.setAdapter(it) }
            h.showParked(parked)
            // other readings as chips: tap = use that number
            h.chips.removeAllViews()
            if (!manual && readings.size > 1) for (r in readings) {
                val chip = LayoutInflater.from(h.itemView.context).inflate(R.layout.item_chip, h.chips, false) as TextView
                chip.text = r.formatted
                chip.setOnClickListener {
                    h.editPlate.setText(r.formatted)
                    h.runLookup()
                }
                h.chips.addView(chip)
            }
            h.chips.visibility = if (h.chips.childCount > 0) View.VISIBLE else View.GONE
            if (manual) h.editPlate.requestFocus() else h.runLookup()
        }
    }

    companion object {
        const val MANUAL_ID = -1L
        private val DIFF = object : DiffUtil.ItemCallback<Capture>() {
            override fun areItemsTheSame(a: Capture, b: Capture) = a.id == b.id
            override fun areContentsTheSame(a: Capture, b: Capture) = a == b
        }
    }
}

// =====================================================================================
// PARKED list
// =====================================================================================

/**
 * Parked vehicles. Number, Token/Name and Model are typed directly in the row and saved
 * automatically. Check out = tap twice (first tap shows the amount to confirm).
 */
class CheckInAdapter(
    private val onSave: (VehicleEntry, who: String, model: String) -> Unit,
    private val modelSuggestions: () -> ArrayAdapter<String>?,
    private val onPlateEdit: (VehicleEntry, String) -> Unit,
    private val amountFor: (VehicleEntry) -> Int,
    private val onCheckOut: (VehicleEntry) -> Unit,
    private val onPhoto: (VehicleEntry) -> Unit,
    private val onLongClick: (VehicleEntry) -> Unit
) : ListAdapter<VehicleEntry, CheckInAdapter.VH>(DIFF) {

    init { setHasStableIds(true) }

    inner class VH(v: View) : RecyclerView.ViewHolder(v) {
        val mark: View = v.findViewById(R.id.mark)
        val plate: EditText = v.findViewById(R.id.plate)
        val name: EditText = v.findViewById(R.id.name)
        val model: AutoCompleteTextView = v.findViewById(R.id.model)
        val whenText: TextView = v.findViewById(R.id.`when`)
        val photo: View = v.findViewById(R.id.photo)
        val out: Button = v.findViewById(R.id.out)
        var entry: VehicleEntry? = null
        var armedUntil = 0L
        val disarm = Runnable { armedUntil = 0; entry?.let { setOutText(it) } }

        fun setOutText(e: VehicleEntry) {
            out.text = if (e.isMonthly) "Check out" else "Check out ₹${amountFor(e)}"
        }

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
            val savePlate = {
                entry?.let { e ->
                    val p = plate.text.toString().trim()
                    if (p.isNotEmpty() && p.filter { it.isLetterOrDigit() }.uppercase() != e.plate) onPlateEdit(e, p)
                }
            }
            plate.setOnFocusChangeListener { _, has -> if (!has) savePlate() }
            plate.setOnEditorActionListener { _, _, _ -> savePlate(); false }
            model.setOnItemClickListener { _, _, _, _ -> save() }
            val autoSave = Runnable { save() }
            for (f in listOf(name, model)) {
                f.setOnFocusChangeListener { _, has -> if (!has) save() }
                f.setOnEditorActionListener { _, _, _ -> save(); false }
                afterChange(f) { if (f.hasFocus()) { f.removeCallbacks(autoSave); f.postDelayed(autoSave, 800) } }
            }
            out.setOnClickListener {
                val e = entry ?: return@setOnClickListener
                val now = SystemClock.uptimeMillis()
                if (now < armedUntil) {
                    armedUntil = 0; out.removeCallbacks(disarm)
                    itemView.clearFocus()
                    onCheckOut(e)
                } else {
                    armedUntil = now + 4000
                    out.text = if (e.isMonthly) "Tap again" else "Collect ₹${amountFor(e)} · tap again"
                    out.removeCallbacks(disarm); out.postDelayed(disarm, 4000)
                }
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
        // never overwrite what the user is typing
        if (!h.plate.hasFocus()) h.plate.setText(e.plateFormatted)
        h.plate.setTextColor(ContextCompat.getColor(ctx, if (e.isMonthly) R.color.monthly_text else R.color.text))
        if (!h.name.hasFocus()) h.name.setText(e.name?.takeIf { it.isNotBlank() } ?: e.token ?: "")
        if (!h.model.hasFocus()) h.model.setText(e.model ?: "", false)
        if (h.model.adapter == null) modelSuggestions()?.let { h.model.setAdapter(it) }
        val now = System.currentTimeMillis()
        h.whenText.text = "In ${shortTime(e.entryTime)}\n${ParkingFee.duration(e.entryTime, now)}" +
            (if (e.isMonthly) " · MONTHLY" else "") +
            (if (e.cameraSummary == ParkingDesk.MANUAL) " · manual" else "")
        h.whenText.setTextColor(ContextCompat.getColor(ctx, if (e.isMonthly) R.color.monthly_text else R.color.text_dim))
        h.whenText.setOnLongClickListener { onLongClick(e); true }
        h.whenText.setOnClickListener { onPhoto(e) }
        h.photo.visibility = if (e.snapshotPath != null) View.VISIBLE else View.INVISIBLE
        h.photo.setOnClickListener { onPhoto(e) }
        if (SystemClock.uptimeMillis() >= h.armedUntil) h.setOutText(e)
    }

    companion object {
        private val DIFF = object : DiffUtil.ItemCallback<VehicleEntry>() {
            override fun areItemsTheSame(a: VehicleEntry, b: VehicleEntry) = a.id == b.id
            override fun areContentsTheSame(a: VehicleEntry, b: VehicleEntry) = a == b
        }
    }
}

// =====================================================================================
// CHECKED OUT (recent)
// =====================================================================================

class OutAdapter(
    private val onUndo: (VehicleEntry) -> Unit,
    private val onPhoto: (VehicleEntry) -> Unit
) : ListAdapter<VehicleEntry, OutAdapter.VH>(object : DiffUtil.ItemCallback<VehicleEntry>() {
    override fun areItemsTheSame(a: VehicleEntry, b: VehicleEntry) = a.id == b.id
    override fun areContentsTheSame(a: VehicleEntry, b: VehicleEntry) = a == b
}) {
    class VH(v: View) : RecyclerView.ViewHolder(v) {
        val plate: TextView = v.findViewById(R.id.plate)
        val amount: TextView = v.findViewById(R.id.amount)
        val details: TextView = v.findViewById(R.id.details)
        val undo: Button = v.findViewById(R.id.undo)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
        VH(LayoutInflater.from(parent.context).inflate(R.layout.item_out, parent, false))

    override fun onBindViewHolder(h: VH, position: Int) {
        val e = getItem(position)
        val out = e.exitTime ?: e.entryTime
        h.plate.text = e.plateFormatted
        h.amount.text = if (e.isMonthly) "Monthly" else "₹${e.amount ?: 0}"
        h.details.text = "${e.whoText()}\nIn ${shortTime(e.entryTime)} → Out ${shortTime(out)} · ${ParkingFee.duration(e.entryTime, out)}"
        h.itemView.setOnClickListener { onPhoto(e) }
        h.undo.setOnClickListener { onUndo(e) }
    }
}
