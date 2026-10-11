package com.readqurantoday.quran

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.appcompat.content.res.AppCompatResources
import androidx.recyclerview.widget.RecyclerView

// Juz, hizb or pages: each row opens, or recites from, the page it begins on
class DivisionAdapter(
    private val starts: IntArray,
    private val title: (Int) -> String,
    private val where: (Int) -> String,
    private val surahsOf: (Int) -> List<Int>,
    // Pages name their surahs on the quiet line, the page number alone above; juz and hizb beside the title
    private val surahsUnder: Boolean,
    private val quarters: ((Int) -> List<Int>)?,
    private val onOpen: (Int) -> Unit,
    private val onPlay: (Int) -> Unit,
    private val onReciter: (Int) -> Unit,
    private val playing: () -> Int
) : RecyclerView.Adapter<DivisionAdapter.Row>() {

    class Row(v: View) : RecyclerView.ViewHolder(v)

    override fun getItemCount() = starts.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
        Row(LayoutInflater.from(parent.context).inflate(R.layout.row_division, parent, false))

    override fun onBindViewHolder(holder: Row, position: Int) {
        val row = holder.itemView
        val number = position + 1
        val page = starts[position]

        row.findViewById<TextView>(R.id.div_num).text = figures(number, row.resources)
        row.findViewById<TextView>(R.id.div_title).text = title(number)
        val surahs = surahsOf(number)
        val beside = row.findViewById<ViewGroup>(R.id.div_surahs)
        val under = row.findViewById<ViewGroup>(R.id.div_surahs_under)
        fillSurahs(if (surahsUnder) under else beside, surahs)
        (if (surahsUnder) beside else under).removeAllViews()
        under.visibility = if (under.childCount > 0) View.VISIBLE else View.GONE
        val said = where(number)
        row.findViewById<TextView>(R.id.div_where).text = said
        row.findViewById<View>(R.id.div_under).visibility =
            if (said.isEmpty() && under.childCount == 0) View.GONE else View.VISIBLE
        fillQuarters(row, quarters?.invoke(number).orEmpty())

        // The seam belongs between two rows, not under the last one
        row.findViewById<View>(R.id.divider).visibility =
            if (position < starts.size - 1) View.VISIBLE else View.GONE

        row.setOnClickListener { onOpen(page) }
        row.findViewById<View>(R.id.btn_play).setOnClickListener { onPlay(page) }
        row.findViewById<View>(R.id.btn_reciter).setOnClickListener { onReciter(page) }

        // Only the row being recited shows pause, and only its own button waits for the audio
        val here = playing() == number
        sayPlayButton(row, playing = here && Recite.wantsToPlay(), waiting = here && Recite.waiting())
    }

    // Grey beside the title, so the part stays the name; several surahs on a page are named without سورة each time
    private fun fillSurahs(into: ViewGroup, surahs: List<Int>) {
        into.removeAllViews()
        val grey = into.context.getColor(R.color.text_mute)
        val gap = into.resources.getDimensionPixelSize(R.dimen.part_surah_gap)
        surahs.forEachIndexed { i, surah ->
            val title = LayoutInflater.from(into.context).inflate(R.layout.part_surah_title, into, false)
            fillSurahTitle(title, surah, R.dimen.surah_title_row)
            title.findViewById<TextView>(R.id.surah_word).apply {
                setTextColor(grey)
                if (surahs.size > 1) visibility = View.GONE
            }
            title.findViewById<TextView>(R.id.surah_name).setTextColor(grey)
            if (i > 0) (title.layoutParams as ViewGroup.MarginLayoutParams).marginStart = gap
            into.addView(title)
        }
    }

    // A chip for the start and each quarter in, a pie and its page, that opens there
    private fun fillQuarters(row: View, pages: List<Int>) {
        val line = row.findViewById<View>(R.id.div_quarters)
        if (pages.size < QUARTERS.size) {
            line.visibility = View.GONE
            return
        }
        val res = row.resources
        val size = res.getDimensionPixelSize(R.dimen.quarter_icon)
        val tint = row.context.getColor(R.color.accent)
        line.visibility = View.VISIBLE
        QUARTERS.forEachIndexed { i, (id, icon) ->
            row.findViewById<TextView>(id).apply {
                text = figures(pages[i], res)
                contentDescription = res.getString(R.string.hizb_quarter_at, res.getString(SAID[i]), figures(pages[i], res))
                setOnClickListener { onOpen(pages[i]) }
                val pie = AppCompatResources.getDrawable(context, icon)?.mutate()?.apply {
                    setBounds(0, 0, size, size)
                    setTint(tint)
                }
                setCompoundDrawablesRelative(pie, null, null, null)
            }
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
