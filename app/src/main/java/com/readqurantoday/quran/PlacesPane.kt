package com.readqurantoday.quran

import android.app.Activity
import android.content.res.ColorStateList
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.viewpager2.widget.ViewPager2

// Places tab: pages read lately, surahs read lately and saved pages, a swipe apart and never mixed
class PlacesPane(
    private val host: Activity,
    strip: View,
    pager: ViewPager2,
    private val open: (Int) -> Unit
) {

    private val blow = host.layoutInflater

    // One scrolling column a list
    private val pages = List(LISTS) { blow.inflate(R.layout.part_scroll_column, pager, false).apply { keepToColumn() } }
    private val columns = pages.map { it.findViewById<LinearLayout>(R.id.column) }

    private val tabs = SwipeTabs(
        pager, strip,
        intArrayOf(R.string.list_pages, R.string.list_surahs, R.string.marks_col_saved),
        // The slide between lists is their movement; a rise on top of it blinked the rows out mid-swipe
        ViewPages(pages)
    )

    // Rebuilt on every return: reading changes the history and saved pages
    fun build() {
        columns.forEach { it.removeAllViews() }
        val today = Settings.recentPages(host).filter { Stats.dayOf(it.at) == Stats.today() }
        fill(columns[PAGES], R.string.places_pages_head, today.map(::pageRow), R.string.no_recent_pages)
        fill(columns[SURAHS], R.string.places_surahs_head, recentSurahs().map(::surahRow), R.string.no_recent)
        fill(columns[SAVED], 0, Settings.marks(host).map(::savedRow), R.string.no_marks)
    }

    /** The list in view comes in, as every tab does when it is opened. */
    fun rise() = columns[tabs.current].riseChildren()

    private fun fill(into: LinearLayout, title: Int, rows: List<View>, none: Int) =
        blow.card(into, title, rows.ifEmpty { listOf(empty(into, none)) })

    /* The reading history; before any was kept, the one last page stands in for it. */
    private fun recentSurahs(): List<Settings.Read> {
        val kept = Settings.recent(host)
        if (kept.isNotEmpty()) return kept
        val last = Settings.lastPage(host)
        val surah = Surahs.ofPage(last) ?: return emptyList()
        return if (last in 1..604) listOf(Settings.Read(surah.id, last, 0L)) else emptyList()
    }

    // As the other two lists: the surah in the mushaf's hand, its juz and page beneath
    private fun pageRow(read: Settings.Read): View {
        val row = place(R.drawable.ic_surahs, read.page)
        Surahs.ofPage(read.page)?.let { fillSurahTitle(row.findViewById(R.id.place_title), it.id, R.dimen.surah_title_row) }
        row.findViewById<TextView>(R.id.place_detail).text = where(read.page)
        stamp(row, read.at)
        return row
    }

    private fun surahRow(read: Settings.Read): View {
        val row = place(R.drawable.ic_surahs, read.page)
        val surah = Surahs.list().firstOrNull { it.id == read.surah }
        fillSurahTitle(row.findViewById(R.id.place_title), read.surah, R.dimen.surah_title_row)

        // Progress as a bar only; a second page number in words read as a contradiction
        row.findViewById<TextView>(R.id.place_detail).text = where(read.page)
        if (surah != null) {
            val of = (surah.to - surah.from + 1).coerceAtLeast(1)
            val at = (read.page - surah.from + 1).coerceIn(1, of)
            row.findViewById<View>(R.id.place_track).apply {
                fillTrack(at.toFloat(), of.toFloat())
                contentDescription = host.getString(
                    R.string.place_progress, figures(at, host.resources), figures(of, host.resources)
                )
            }
        }
        stamp(row, read.at)
        return row
    }

    private fun savedRow(page: Int): View {
        val row = place(R.drawable.ic_bookmark, page)
        Surahs.ofPage(page)?.let { fillSurahTitle(row.findViewById(R.id.place_title), it.id, R.dimen.surah_title_row) }
        row.findViewById<TextView>(R.id.place_detail).text = where(page)
        row.findViewById<ImageView>(R.id.place_remove).apply {
            imageTintList = ColorStateList.valueOf(host.getColor(R.color.text_mute))
            visibility = View.VISIBLE
            /* Dropping a saved page rebuilds the lists, so the card closes up at once. */
            setOnClickListener {
                Settings.toggleMark(host, page)
                build()
            }
        }
        return row
    }

    // How long ago; entries carried over from before times were kept have none
    private fun stamp(row: View, at: Long) {
        if (at <= 0L) return
        row.findViewById<TextView>(R.id.place_when).apply {
            text = ago(at, host.resources)
            visibility = View.VISIBLE
        }
    }

    /* The row every list shares: the disc with its icon, and the tap that goes there. */
    private fun place(icon: Int, page: Int): View {
        val row = blow.inflate(R.layout.row_place, columns[0], false)
        row.findViewById<ImageView>(R.id.place_icon).apply {
            setImageResource(icon)
            imageTintList = ColorStateList.valueOf(host.getColor(R.color.accent))
        }
        row.setOnClickListener { open(page) }
        return row
    }

    /* "Juz 1 · Page 3", in the figures the page itself uses. */
    private fun where(page: Int): String {
        val at = host.getString(R.string.head_page, figures(page, host.resources))
        val juz = Surahs.juzOfPage(page)
        return if (juz <= 0) at else host.getString(
            R.string.place_line, host.getString(R.string.head_juz, figures(juz, host.resources)), at
        )
    }

    private fun empty(into: LinearLayout, said: Int): View =
        (blow.inflate(R.layout.row_empty, into, false) as TextView).apply { setText(said) }

    private companion object {
        const val LISTS = 3
        const val PAGES = 0
        const val SURAHS = 1
        const val SAVED = 2
    }
}
