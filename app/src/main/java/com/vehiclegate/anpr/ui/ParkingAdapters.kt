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
        /** Long-press: "same vehicle as …" to join two cards. */
        fun mergeMenu(c: Capture, anchor: View)
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
        val chevron: ImageView = v.findViewById(R.id.chevron)
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
        /** The parked vehicle this card matches (from any of its readings). */
        var cardParked: VehicleEntry? = null
        /** Staff changed the number (typed or picked a reading). */
        var userEdited = false
        private val lookupNow = Runnable { runLookup() }

        init {
            afterChange(editPlate) {
                if (editPlate.hasFocus()) {
                    userEdited = true
                    editPlate.removeCallbacks(lookupNow); editPlate.postDelayed(lookupNow, 500)
                }
            }
            // Check in / Out ₹ is on the row itself: one tap. With the editor open it uses the edited fields.
            confirm.setOnClickListener {
                val c = capture ?: return@setOnClickListener
                val p = parked
                when {
                    p != null -> host.checkOut(c, p)
                    editorFor == c.id -> host.checkIn(c, editPlate.text.toString(), editName.text.toString(), editModel.text.toString())
                    else -> host.checkIn(c, c.plateFormatted, "", "")
                }
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
                val cp = cardParked
                when {
                    hint is ParkingDesk.Hint.Parked -> { editHint.text = text; showParked(hint.entry) }
                    !userEdited && cp != null -> {
                        // the best reading is wrong but another reading matches a parked vehicle
                        editHint.text = "Same vehicle as parked ${cp.plateFormatted} (${cp.whoText()}) – leaving"
                        showParked(cp)
                    }
                    else -> { editHint.text = text; showParked(null) }
                }
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
                confirm.text = if (p.isMonthly) "Out" else "Out ₹${host.amountFor(p)}"
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
            h.alts.text = "vehicles the cameras can't see"
            h.time.text = ""
        } else {
            h.thumb.visibility = View.VISIBLE
            host.loadThumb(com.vehiclegate.anpr.Pictures.thumbPath(c.snapshotPath), h.thumb)
            h.plate.text = c.plateFormatted
            val others = readings.filter { it.compact != c.plate }.map { it.formatted }
            h.alts.text = if (others.isEmpty()) "" else "also " + others.joinToString(" · ")
            h.time.text = "${shortTime(c.firstSeen)} · ${c.reads}×"
        }
        val parked = if (manual) null else host.parkedFor(c)
        h.cardParked = parked
        if (parked != null) h.alts.text = "≈ parked ${parked.plateFormatted}"
        h.badge.visibility = if (parked != null) View.VISIBLE else View.GONE
        if (parked != null) h.badge.text = "Parked · leaving?"
        if (h.editorFor != c.id || !h.userEdited) h.showParked(parked)
        h.head.setOnClickListener { if (!manual) expand(if (expandedId == c.id) null else c.id) }
        h.head.setOnLongClickListener { if (!manual) host.mergeMenu(c, h.head); !manual }
        h.chevron.rotation = if (open) 180f else 0f
        h.chevron.visibility = if (manual) View.GONE else View.VISIBLE

        h.editor.visibility = if (open) View.VISIBLE else View.GONE
        if (!open) { h.editorFor = null; h.userEdited = false; return }

        if (h.editorFor != c.id) {
            h.editorFor = c.id
            h.userEdited = false
            h.editPlate.setText(if (manual) "" else c.plateFormatted)
            h.editName.setText("")
            h.editModel.setText("", false)
            h.editHint.text = if (manual) "Type the number – regulars and monthly users are recognised" else ""
            h.ignore.text = if (manual) "Cancel" else "Ignore"
            if (h.editModel.adapter == null) host.modelSuggestions()?.let { h.editModel.setAdapter(it) }
            // other readings as chips: tap = use that number
            h.chips.removeAllViews()
            if (!manual && readings.size > 1) for (r in readings) {
                val chip = LayoutInflater.from(h.itemView.context).inflate(R.layout.item_chip, h.chips, false) as TextView
                chip.text = r.formatted
                chip.setOnClickListener {
                    h.userEdited = true
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
 * Parked vehicles: number plate, Token/Name, date & time, Out (tap twice). Nothing is editable by a
 * stray tap: ▾ shows the plate picture, model and photo, and **Edit** opens number, Token/Name and
 * model with Save / Cancel.
 */
class CheckInAdapter(
    /** Edit saved: corrected number (may be unchanged), Token/Name, model. */
    private val onSave: (VehicleEntry, plate: String, who: String, model: String) -> Unit,
    private val modelSuggestions: () -> ArrayAdapter<String>?,
    private val amountFor: (VehicleEntry) -> Int,
    private val onCheckOut: (VehicleEntry) -> Unit,
    private val onPhoto: (VehicleEntry) -> Unit,
    private val onLongClick: (VehicleEntry) -> Unit,
    private val loadThumb: (String?, ImageView) -> Unit,
    private val hideKeyboard: () -> Unit
) : ListAdapter<VehicleEntry, CheckInAdapter.VH>(DIFF) {

    init { setHasStableIds(true) }

    private var expandedId: Long? = null
    private var editingId: Long? = null

    private fun refresh(vararg ids: Long?) {
        for (id in ids) if (id != null) currentList.indexOfFirst { it.id == id }.takeIf { it >= 0 }?.let { notifyItemChanged(it) }
    }

    private fun toggle(id: Long) {
        val old = expandedId
        expandedId = if (old == id) null else id
        if (editingId != expandedId) editingId = null
        refresh(old, id)
    }

    inner class VH(v: View) : RecyclerView.ViewHolder(v) {
        val plateBox: View = v.findViewById(R.id.plateBox)
        val plateText: TextView = v.findViewById(R.id.plateText)
        val nameText: TextView = v.findViewById(R.id.nameText)
        val whenText: TextView = v.findViewById(R.id.`when`)
        val out: Button = v.findViewById(R.id.out)
        val chevron: ImageView = v.findViewById(R.id.chevron)
        val more: View = v.findViewById(R.id.more)
        val plateThumb: ImageView = v.findViewById(R.id.plateThumb)
        val modelText: TextView = v.findViewById(R.id.modelText)
        val photo: View = v.findViewById(R.id.photo)
        val edit: Button = v.findViewById(R.id.edit)
        val editBox: View = v.findViewById(R.id.editBox)
        val plate: EditText = v.findViewById(R.id.plate)
        val name: EditText = v.findViewById(R.id.name)
        val model: AutoCompleteTextView = v.findViewById(R.id.model)
        val save: Button = v.findViewById(R.id.save)
        val cancel: Button = v.findViewById(R.id.cancel)
        var entry: VehicleEntry? = null
        var armedUntil = 0L
        val disarm = Runnable { armedUntil = 0; entry?.let { setOutText(it) } }

        fun setOutText(e: VehicleEntry) {
            out.text = if (e.isMonthly) "Out" else "Out ₹${amountFor(e)}"
        }

        init {
            out.setOnClickListener {
                val e = entry ?: return@setOnClickListener
                val now = SystemClock.uptimeMillis()
                if (now < armedUntil) {
                    armedUntil = 0; out.removeCallbacks(disarm)
                    onCheckOut(e)
                } else {
                    armedUntil = now + 4000
                    out.text = if (e.isMonthly) "Tap again" else "₹${amountFor(e)} · tap again"
                    out.removeCallbacks(disarm); out.postDelayed(disarm, 4000)
                }
            }
            edit.setOnClickListener {
                val e = entry ?: return@setOnClickListener
                editingId = e.id; expandedId = e.id
                refresh(e.id)
            }
            cancel.setOnClickListener {
                editingId = null; hideKeyboard(); entry?.let { refresh(it.id) }
            }
            save.setOnClickListener {
                val e = entry ?: return@setOnClickListener
                onSave(e, plate.text.toString().trim(), name.text.toString().trim(), model.text.toString().trim())
                editingId = null; hideKeyboard(); refresh(e.id)
            }
        }
    }

    override fun getItemId(position: Int) = getItem(position).id

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
        VH(LayoutInflater.from(parent.context).inflate(R.layout.item_checkin, parent, false))

    override fun onBindViewHolder(h: VH, position: Int) {
        val e = getItem(position)
        val ctx = h.itemView.context
        val wasEditing = h.entry?.id == e.id && h.editBox.visibility == View.VISIBLE
        h.entry = e
        h.plateText.text = e.plateFormatted
        h.plateBox.setBackgroundResource(if (e.isMonthly) R.drawable.plate_small_monthly_bg else R.drawable.plate_small_bg)
        val who = e.name?.takeIf { it.isNotBlank() } ?: e.token?.takeIf { it.isNotBlank() }
        h.nameText.text = who ?: "–"
        h.nameText.setTextColor(ContextCompat.getColor(ctx, if (who != null) R.color.text else R.color.text_dim))
        val now = System.currentTimeMillis()
        h.whenText.text = "${dateTimeFmt.format(Date(e.entryTime))}\n${ParkingFee.duration(e.entryTime, now)} parked" +
            (if (e.isMonthly) " · monthly" else "") +
            (if (e.cameraSummary == ParkingDesk.MANUAL) " · manual" else "")
        h.whenText.setTextColor(ContextCompat.getColor(ctx, if (e.isMonthly) R.color.monthly_text else R.color.text_dim))
        h.whenText.setOnLongClickListener { onLongClick(e); true }
        h.plateBox.setOnLongClickListener { onLongClick(e); true }
        h.plateBox.setOnClickListener { toggle(e.id) }
        h.nameText.setOnClickListener { toggle(e.id) }
        h.chevron.setOnClickListener { toggle(e.id) }
        if (SystemClock.uptimeMillis() >= h.armedUntil) h.setOutText(e)

        val open = e.id == expandedId
        val editing = e.id == editingId
        h.chevron.rotation = if (open) 180f else 0f
        h.more.visibility = if (open && !editing) View.VISIBLE else View.GONE
        h.editBox.visibility = if (editing) View.VISIBLE else View.GONE
        if (open && !editing) {
            val plateFile = com.vehiclegate.anpr.Pictures.plateFile(e.snapshotPath)
            h.plateThumb.visibility = if (plateFile != null) View.VISIBLE else View.GONE
            if (plateFile != null) loadThumb(plateFile.absolutePath, h.plateThumb)
            h.plateThumb.setOnClickListener { onPhoto(e) }
            h.modelText.text = e.model?.takeIf { it.isNotBlank() }?.let { "Model: $it" } ?: "Model: –"
            h.photo.visibility = if (e.snapshotPath != null) View.VISIBLE else View.GONE
            h.photo.setOnClickListener { onPhoto(e) }
        }
        if (editing && !wasEditing) {
            h.plate.setText(e.plateFormatted)
            h.name.setText(who ?: "")
            h.model.setText(e.model ?: "", false)
            if (h.model.adapter == null) modelSuggestions()?.let { h.model.setAdapter(it) }
            h.name.requestFocus()
        }
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
