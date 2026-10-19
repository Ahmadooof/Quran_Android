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

/** A sheet of every surah, searched by name, English name or number, or by an ayah's words; [picked] hears the surah tapped, with the ayah when one was. */
fun Activity.pickSurah(title: String, current: Int, picked: (surah: Int, ayah: Int?) -> Unit) {
    val dialog = Dialog(this, R.style.SheetDialog)
    val view = layoutInflater.inflate(R.layout.part_surah_picker, null)
    view.findViewById<TextView>(R.id.picker_title).text = title
    view.findViewById<ImageView>(R.id.picker_search_icon).imageTintList = ColorStateList.valueOf(getColor(R.color.text_mute))

    val rows = SurahChoices(current) { surah, ayah ->
        dialog.dismiss()
        picked(surah, ayah)
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

// The surahs the search leaves, then the ayahs holding its words; each row made only as it scrolls into view
private class SurahChoices(private val current: Int, private val picked: (Int, Int?) -> Unit) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    class Row(v: View) : RecyclerView.ViewHolder(v) {
        val num: TextView = v.findViewById(R.id.pick_num)
        val title: View = v.findViewById(R.id.pick_title)
        val english: TextView = v.findViewById(R.id.pick_english)
        val juz: TextView = v.findViewById(R.id.pick_juz)
        val ayahs: TextView = v.findViewById(R.id.pick_ayahs)
        val tick: ImageView = v.findViewById(R.id.pick_tick)
    }

    class Plain(v: View) : RecyclerView.ViewHolder(v)

    private var shown: List<Search.Hit> = Surahs.list().map { Search.Hit.Name(it) }

    // By number, Arabic name (its marks set aside) or English name; words past a surah's name find ayahs too
    fun filter(typed: String) {
        val q = latinDigits(typed.trim())
        val folded = Search.fold(q)
        val surahs = if (q.isEmpty()) Surahs.list() else Surahs.list().filter {
            it.id.toString() == q || Search.fold(it.name).contains(folded) || it.english.contains(q, ignoreCase = true)
        }
        val verses = if (q.isEmpty() || q.toIntOrNull() != null) emptyList() else Search.verses(folded)
        shown = buildList {
            if (verses.isNotEmpty() && surahs.isNotEmpty()) add(Search.Hit.Head(R.string.search_surahs))
            surahs.forEach { add(Search.Hit.Name(it)) }
            if (verses.isNotEmpty()) add(Search.Hit.Head(R.string.search_ayahs))
            addAll(verses)
        }
        notifyDataSetChanged()
    }

    override fun getItemCount() = shown.size

    override fun getItemViewType(position: Int) = when (shown[position]) {
        is Search.Hit.Head -> T_HEAD
        is Search.Hit.Verse -> T_VERSE
        else -> T_SURAH
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val blow = LayoutInflater.from(parent.context)
        return when (viewType) {
            T_HEAD -> Plain(blow.inflate(R.layout.item_search_head, parent, false))
            // Only the ayah and where it is: playing belongs to the index, not to choosing
            T_VERSE -> Plain(blow.inflate(R.layout.item_search_ayah, parent, false).apply {
                findViewById<View>(R.id.btn_play).visibility = View.GONE
                findViewById<View>(R.id.btn_reciter).visibility = View.GONE
            })
            else -> Row(blow.inflate(R.layout.row_pick_surah, parent, false)).apply {
                tick.imageTintList = ColorStateList.valueOf(parent.context.getColor(R.color.accent))
            }
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (val hit = shown[position]) {
            is Search.Hit.Head -> (holder.itemView as TextView).setText(hit.title)
            is Search.Hit.Verse -> {
                fillAyahRow(holder.itemView, hit)
                holder.itemView.setOnClickListener { picked(hit.ayah.surah, hit.ayah.ayah) }
            }
            is Search.Hit.Name -> bindSurah(holder as Row, hit.surah)
            else -> Unit
        }
    }

    private fun bindSurah(holder: Row, s: Surahs.Surah) {
        val res = holder.itemView.resources
        holder.num.text = figures(s.id, res)
        fillSurahTitle(holder.title, s.id, R.dimen.surah_title_row)
        holder.english.text = res.getString(R.string.surah_english, s.english)
        holder.juz.text = juzOfSurah(s, res)
        holder.ayahs.text = res.getQuantityString(R.plurals.ayahs_count, s.ayahs, figures(s.ayahs, res))
        holder.tick.visibility = if (s.id == current) View.VISIBLE else View.INVISIBLE
        holder.itemView.setOnClickListener { picked(s.id, null) }
    }

    private companion object {
        const val T_SURAH = 0
        const val T_HEAD = 1
        const val T_VERSE = 2
    }
}
