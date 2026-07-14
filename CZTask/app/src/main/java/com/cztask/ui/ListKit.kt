package com.cztask.ui

import android.app.Activity
import android.app.RemoteInput
import android.content.Intent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import androidx.wear.input.RemoteInputIntentHelper
import androidx.wear.widget.WearableLinearLayoutManager
import androidx.wear.widget.WearableRecyclerView
import com.cztask.R

/**
 * The whole shell is lists. One generic adapter + one row vocabulary keeps the
 * per-screen code at "declare rows, wire actions" size and the object churn
 * low (96 MB heap): rows are plain data, rebinding reuses holders.
 */
sealed interface Row {
    /** Centered single-line row: menu entries, "+ Add" actions, empty states. */
    data class Center(val text: String, val dim: Boolean = false, val onTap: (() -> Unit)? = null) : Row

    /** Glyph + title + optional subtitle: tasks, reminders, presets. */
    data class Item(
        val glyph: String,
        val title: String,
        val subtitle: String? = null,
        val dimTitle: Boolean = false,
        val onTap: (() -> Unit)? = null,
        val onLongPress: (() -> Unit)? = null,
    ) : Row

    /** NOW hero card; bind configures the NowCardView on (re)bind. */
    data class Hero(val bind: (NowCardView) -> Unit) : Row

    /** Long-press arm state: ★ PIN | DELETE? with activity-managed revert. */
    data class Armed(val onPin: () -> Unit, val onDelete: () -> Unit) : Row
}

class RowAdapter : RecyclerView.Adapter<RowAdapter.Holder>() {

    private var rows: List<Row> = emptyList()

    fun submit(newRows: List<Row>) {
        rows = newRows
        // Lists are tens of rows; notifyDataSetChanged is simpler and cheaper
        // than DiffUtil allocation on this hardware.
        notifyDataSetChanged()
    }

    class Holder(view: View) : RecyclerView.ViewHolder(view)

    override fun getItemViewType(position: Int) = when (rows[position]) {
        is Row.Center -> R.layout.row_center_text
        is Row.Item -> R.layout.row_two_line
        is Row.Hero -> R.layout.row_hero
        is Row.Armed -> R.layout.row_armed
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder =
        Holder(LayoutInflater.from(parent.context).inflate(viewType, parent, false))

    override fun getItemCount() = rows.size

    override fun onBindViewHolder(holder: Holder, position: Int) {
        when (val row = rows[position]) {
            is Row.Hero -> row.bind(holder.itemView.findViewById(R.id.hero))
            is Row.Armed -> {
                holder.itemView.findViewById<TextView>(R.id.arm_pin)
                    .setOnClickListener { row.onPin() }
                holder.itemView.findViewById<TextView>(R.id.arm_delete)
                    .setOnClickListener { row.onDelete() }
            }
            is Row.Center -> {
                val tv = holder.itemView.findViewById<TextView>(R.id.text)
                tv.text = row.text
                tv.alpha = if (row.dim) 0.55f else 1f
                holder.itemView.setOnClickListener(row.onTap?.let { t -> View.OnClickListener { t() } })
                holder.itemView.isClickable = row.onTap != null
            }
            is Row.Item -> {
                holder.itemView.findViewById<TextView>(R.id.glyph).text = row.glyph
                holder.itemView.findViewById<TextView>(R.id.title).apply {
                    text = row.title
                    alpha = if (row.dimTitle) 0.45f else 1f
                }
                holder.itemView.findViewById<TextView>(R.id.subtitle).apply {
                    text = row.subtitle
                    visibility = if (row.subtitle == null) View.GONE else View.VISIBLE
                }
                holder.itemView.setOnClickListener(row.onTap?.let { t -> View.OnClickListener { t() } })
                holder.itemView.isClickable = row.onTap != null
                holder.itemView.setOnLongClickListener(row.onLongPress?.let { l ->
                    View.OnLongClickListener { l(); true }
                })
                holder.itemView.isLongClickable = row.onLongPress != null
            }
        }
    }
}

/** Curved, centered, crown-focused list — the standard screen body.
 *  centering=false for hero-first screens (the hero must sit at the top,
 *  not be curved off-axis). */
fun Activity.setUpWearList(centering: Boolean = true): RowAdapter {
    setContentView(R.layout.activity_list)
    val list = findViewById<WearableRecyclerView>(R.id.list)
    list.layoutManager =
        if (centering) WearableLinearLayoutManager(this)
        else androidx.recyclerview.widget.LinearLayoutManager(this)
    list.isEdgeItemsCenteringEnabled = centering
    val adapter = RowAdapter()
    list.adapter = adapter
    // Rotary events go to the FOCUSED view (measured in CZProbe).
    list.requestFocus()
    return adapter
}

/** Launches the Wear text-input flow (keyboard/handwriting on Wear OS 2). */
const val REMOTE_INPUT_KEY = "text"

fun Activity.launchTextInput(requestCode: Int, label: String) {
    val remoteInput = RemoteInput.Builder(REMOTE_INPUT_KEY).setLabel(label).build()
    val intent = RemoteInputIntentHelper.createActionRemoteInputIntent()
    RemoteInputIntentHelper.putRemoteInputsExtra(intent, listOf(remoteInput))
    startActivityForResult(intent, requestCode)
}

fun textInputResult(data: Intent?): String? =
    data?.let { RemoteInput.getResultsFromIntent(it)?.getCharSequence(REMOTE_INPUT_KEY)?.toString() }
        ?.trim()?.ifEmpty { null }
