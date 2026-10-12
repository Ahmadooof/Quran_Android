package com.readqurantoday.quran

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.content.res.AppCompatResources
import androidx.recyclerview.widget.RecyclerView

// Juz, hizb or pages: each row opens, or recites from, the page it begins on
class DivisionAdapter(
    private val starts: IntArray,
    private val title: (Int) -> String,
    private val where: (Int) -> String,
    private val surahsOf: (Int) -> List<Int>,
    // Juz and pages name their surahs on the quiet line, the number alone above; hizb beside the title
    private val surahsUnder: Boolean,
    private val quarters: ((Int) -> List<Int>)?,
    private val onOpen: (Int) -> Unit,
    private val onPlay: (Int) -> Unit,
    private val onReciter: (Int) -> Unit,
    private val playing: () -> Int
) : RecyclerView.Adapter<DivisionAdapter.Row>() {

    // Everything a bind touches, found once; a fling binds many rows a second
    class Row(v: View) : RecyclerView.ViewHolder(v) {
        val num: TextView = v.findViewById(R.id.div_num)
        val title: TextView = v.findViewById(R.id.div_title)
        val beside: LinearLayout = v.findViewById(R.id.div_surahs)
        val under: LinearLayout = v.findViewById(R.id.div_surahs_under)
        val underLine: View = v.findViewById(R.id.div_under)
        val where: TextView = v.findViewById(R.id.div_where)
        val whereBeside: TextView = v.findViewById(R.id.div_where_beside)
        val quarterLine: View = v.findViewById(R.id.div_quarters)
        val chips: List<TextView> = QUARTERS.map { (id, _) -> v.findViewById(id) }
        val divider: View = v.findViewById(R.id.divider)
        val play: View = v.findViewById(R.id.btn_play)
        val reciter: View = v.findViewById(R.id.btn_reciter)
    }

    override fun getItemCount() = starts.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Row {
        val row = Row(LayoutInflater.from(parent.context).inflate(R.layout.row_division, parent, false))
        // The pies are the same on every row, so they are set once, not on each bind
        if (quarters != null) {
            val size = parent.resources.getDimensionPixelSize(R.dimen.quarter_icon)
            val tint = parent.context.getColor(R.color.accent)
            row.chips.forEachIndexed { i, chip ->
                val pie = AppCompatResources.getDrawable(parent.context, QUARTERS[i].second)?.mutate()?.apply {
                    setBounds(0, 0, size, size)
                    setTint(tint)
                }
                chip.setCompoundDrawablesRelative(pie, null, null, null)
            }
        }
        return row
    }

    override fun onBindViewHolder(holder: Row, position: Int) {
        val res = holder.itemView.resources
        val number = position + 1
        val page = starts[position]

        holder.num.text = figures(number, res)
        holder.title.text = title(number)
        fillSurahs(if (surahsUnder) holder.under else holder.beside, surahsOf(number))
        // Where it lies follows its surahs, on whichever line they are
        val said = where(number)
        holder.where.text = if (surahsUnder) said else ""
        holder.whereBeside.text = if (surahsUnder) "" else said
        holder.whereBeside.visibility = if (surahsUnder || said.isEmpty()) View.GONE else View.VISIBLE
        holder.underLine.visibility = if (surahsUnder) View.VISIBLE else View.GONE
        fillQuarters(holder, quarters?.invoke(number).orEmpty())

        // The seam belongs between two rows, not under the last one
        holder.divider.visibility = if (position < starts.size - 1) View.VISIBLE else View.GONE

        holder.itemView.setOnClickListener { onOpen(page) }
        holder.play.setOnClickListener { onPlay(page) }
        holder.reciter.setOnClickListener { onReciter(page) }

        // Only the row being recited shows pause, and only its own button waits for the audio
        val here = playing() == number
        sayPlayButton(holder.itemView, playing = here && Recite.wantsToPlay(), waiting = here && Recite.waiting())
    }

    // Grey, so the part stays the name; several surahs on one page stand one under another, each named in full.
    // Titles already made are filled again, and only a page with more surahs than before makes another
    private fun fillSurahs(into: LinearLayout, surahs: List<Int>) {
        into.orientation = if (surahs.size > 1) LinearLayout.VERTICAL else LinearLayout.HORIZONTAL
        while (into.childCount < surahs.size) {
            val made = LayoutInflater.from(into.context).inflate(R.layout.part_surah_title, into, false)
            val grey = into.context.getColor(R.color.text_mute)
            made.findViewById<TextView>(R.id.surah_word).setTextColor(grey)
            made.findViewById<TextView>(R.id.surah_name).setTextColor(grey)
            into.addView(made)
        }
        for (i in 0 until into.childCount) {
            val title = into.getChildAt(i)
            title.visibility = if (i < surahs.size) View.VISIBLE else View.GONE
            if (i < surahs.size) fillSurahTitle(title, surahs[i], R.dimen.surah_title_row)
        }
    }

    // A chip for the start and each quarter in, its page, that opens there
    private fun fillQuarters(holder: Row, pages: List<Int>) {
        if (pages.size < QUARTERS.size) {
            holder.quarterLine.visibility = View.GONE
            return
        }
        val res = holder.itemView.resources
        holder.quarterLine.visibility = View.VISIBLE
        holder.chips.forEachIndexed { i, chip ->
            chip.text = figures(pages[i], res)
            chip.contentDescription = res.getString(R.string.hizb_quarter_at, res.getString(SAID[i]), figures(pages[i], res))
            chip.setOnClickListener { onOpen(pages[i]) }
        }
    }

    private companion object {
        val SAID = intArrayOf(R.string.quarter_0, R.string.quarter_1, R.string.quarter_2, R.string.quarter_3)
        val QUARTERS = listOf(
            R.id.div_q0 to R.drawable.ic_quarter_0,
            R.id.div_q1 to R.drawable.ic_quarter_1,
            R.id.div_q2 to R.drawable.ic_quarter_2,
            R.id.div_q3 to R.drawable.ic_quarter_3
        )
    }
}
