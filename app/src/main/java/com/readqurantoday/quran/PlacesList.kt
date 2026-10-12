package com.readqurantoday.quran

import android.graphics.Canvas
import android.graphics.Paint
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView

/** One of the places lists: rows made only as they scroll into view, or a quiet line when empty. */
class PlacesList : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    sealed class Item {
        data class Empty(val said: String) : Item()
        /** A row_place, filled by [fill] each time it is shown. */
        class Row(val fill: (View) -> Unit) : Item()
        /** A row that does something to the whole list, as a settings action reads. */
        class Action(val said: String, val act: () -> Unit) : Item()
    }

    private var items: List<Item> = emptyList()

    fun show(now: List<Item>) {
        items = now
        notifyDataSetChanged()
    }

    fun isRow(at: Int) = items.getOrNull(at) is Item.Row

    override fun getItemCount() = items.size

    override fun getItemViewType(position: Int) = when (items[position]) {
        is Item.Empty -> EMPTY
        is Item.Row -> ROW
        is Item.Action -> ACTION
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val layout = when (viewType) {
            EMPTY -> R.layout.row_empty
            ACTION -> R.layout.part_quiet_button
            else -> R.layout.row_place
        }
        return object : RecyclerView.ViewHolder(LayoutInflater.from(parent.context).inflate(layout, parent, false)) {}
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        val v = holder.itemView
        when (val item = items[position]) {
            is Item.Empty -> (v as TextView).text = item.said
            is Item.Action -> {
                (v as TextView).text = item.said
                v.setOnClickListener { item.act() }
            }
            is Item.Row -> {
                // A reused row may carry another list's time, bar or remove button
                v.findViewById<View>(R.id.place_when).visibility = View.GONE
                v.findViewById<View>(R.id.place_track).visibility = View.GONE
                v.findViewById<View>(R.id.place_remove).visibility = View.GONE
                item.fill(v)
            }
        }
    }

    private companion object {
        const val EMPTY = 0
        const val ROW = 1
        const val ACTION = 2
    }
}

/** The line between two rows of a places list, as the index lists draw theirs. */
class PlacesSeams(private val list: PlacesList, color: Int, private val thick: Float) : RecyclerView.ItemDecoration() {

    private val paint = Paint().apply { this.color = color }

    override fun onDraw(c: Canvas, parent: RecyclerView, state: RecyclerView.State) {
        for (i in 0 until parent.childCount) {
            val child = parent.getChildAt(i)
            val at = parent.getChildAdapterPosition(child)
            if (at == RecyclerView.NO_POSITION || !list.isRow(at) || !list.isRow(at + 1)) continue
            val y = child.bottom + child.translationY
            c.drawRect(child.left.toFloat(), y - thick, child.right.toFloat(), y, paint)
        }
    }
}
