package com.readqurantoday.quran

import android.app.Activity
import android.app.Dialog
import android.content.res.ColorStateList
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView

/** A sheet of every surah, searched by name, English name or number; [picked] hears the one tapped. */
fun Activity.pickSurah(title: String, current: Int, picked: (Int) -> Unit) {
    val dialog = Dialog(this, R.style.SheetDialog)
    val view = layoutInflater.inflate(R.layout.part_surah_picker, null)
    view.findViewById<TextView>(R.id.picker_title).text = title
    view.findViewById<ImageView>(R.id.picker_search_icon).imageTintList = ColorStateList.valueOf(getColor(R.color.text_mute))

    val rows = SurahChoices(current) { id ->
        dialog.dismiss()
        picked(id)
    }
    view.findViewById<RecyclerView>(R.id.picker_list).apply {
        layoutManager = LinearLayoutManager(this@pickSurah)
        adapter = rows
        // Opens at the surah chosen now, a row above it showing
        (layoutManager as LinearLayoutManager).scrollToPositionWithOffset((current - 2).coerceAtLeast(0), 0)
    }
    view.findViewById<EditText>(R.id.picker_search).addTextChangedListener(object : TextWatcher {
        override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
        override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
        override fun afterTextChanged(s: Editable?) = rows.filter(s?.toString().orEmpty())
    })
    raise(dialog, view)
}

// The surahs the search leaves, each row made only as it scrolls into view
private class SurahChoices(private val current: Int, private val picked: (Int) -> Unit) : RecyclerView.Adapter<SurahChoices.Row>() {

    class Row(v: View) : RecyclerView.ViewHolder(v) {
        val num: TextView = v.findViewById(R.id.pick_num)
        val title: View = v.findViewById(R.id.pick_title)
        val ayahs: TextView = v.findViewById(R.id.pick_ayahs)
        val tick: ImageView = v.findViewById(R.id.pick_tick)
    }

    private var shown = Surahs.list()

    // By number, Arabic name (its marks set aside) or English name
    fun filter(typed: String) {
        val q = latinDigits(typed.trim())
        val folded = Search.fold(q)
        shown = if (q.isEmpty()) Surahs.list() else Surahs.list().filter {
            it.id.toString() == q || Search.fold(it.name).contains(folded) || it.english.contains(q, ignoreCase = true)
        }
        notifyDataSetChanged()
    }

    override fun getItemCount() = shown.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
        Row(LayoutInflater.from(parent.context).inflate(R.layout.row_pick_surah, parent, false)).apply {
            tick.imageTintList = ColorStateList.valueOf(parent.context.getColor(R.color.accent))
        }

    override fun onBindViewHolder(holder: Row, position: Int) {
        val s = shown[position]
        val res = holder.itemView.resources
        holder.num.text = figures(s.id, res)
        fillSurahTitle(holder.title, s.id, R.dimen.surah_title_row)
        holder.ayahs.text = res.getQuantityString(R.plurals.ayahs_count, s.ayahs, figures(s.ayahs, res))
        holder.tick.visibility = if (s.id == current) View.VISIBLE else View.INVISIBLE
        holder.itemView.setOnClickListener { picked(s.id) }
    }
}
