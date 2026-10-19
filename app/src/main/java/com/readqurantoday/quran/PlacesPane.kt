package com.readqurantoday.quran

import android.app.Activity
import android.content.res.ColorStateList
import android.view.View
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.viewpager2.widget.ViewPager2

// Places tab: pages read lately, surahs read lately and saved pages, a swipe apart and never mixed
class PlacesPane(
    private val host: Activity,
    strip: View,
    pager: ViewPager2,
    private val open: (Int) -> Unit
) {

    // Recycling lists, so a long history builds only the rows in view; each tab's name says what its list holds
    private val lists = List(LISTS) { PlacesList() }
    private val pages = lists.map { list ->
        (host.layoutInflater.inflate(R.layout.part_places_list, pager, false) as RecyclerView).apply {
            layoutManager = LinearLayoutManager(host)
            adapter = list
            addItemDecoration(PlacesSeams(list, host.getColor(R.color.line), resources.displayMetrics.density))
            keepToColumn()
        }
    }

    private val tabs = SwipeTabs(
        pager, strip,
        intArrayOf(R.string.places_tab_today, R.string.places_tab_places, R.string.marks_col_saved),
        // The slide between lists is their movement; a rise on top of it blinked the rows out mid-swipe
        ViewPages(pages)
    )

    // Rebuilt on every return: reading changes the history and saved pages
    fun build() {
        fill(PAGES, readToday().map { read -> PlacesList.Item.Row { pageRow(it, read) } }, R.string.no_recent_pages)
        fill(SURAHS, recentSurahs().map { read -> PlacesList.Item.Row { surahRow(it, read) } }, R.string.no_recent)
        val saved = Settings.marks(host)
        val clear = if (saved.size > 1) listOf(PlacesList.Item.Action(host.getString(R.string.marks_clear), ::askClear)) else emptyList()
        fill(SAVED, saved.map { page -> PlacesList.Item.Row { savedRow(it, page) } } + clear, R.string.no_marks)
    }

    // Every saved page goes at once, after a yes: the one delete that asks first
    private fun askClear() {
        val count = Settings.marks(host).size
        host.sheet(host.getString(R.string.marks_clear_ask, figures(count, host.resources)), listOf(Choice(host.getString(R.string.dl_delete_do)))) {
            Settings.clearMarks(host)
            build()
        }
    }

    /** The list in view comes in, as every tab does when it is opened. */
    fun rise() = pages[tabs.current].riseWhole()

    private fun fill(list: Int, rows: List<PlacesList.Item>, none: Int) {
        lists[list].show(rows.ifEmpty { listOf(PlacesList.Item.Empty(host.getString(none))) })
    }

    /* The reading history; before any was kept, the one last page stands in for it. */
    private fun recentSurahs(): List<Settings.Read> {
        val kept = Settings.recent(host)
        if (kept.isNotEmpty()) return kept
        val last = Settings.lastPage(host)
        val surah = Surahs.ofPage(last) ?: return emptyList()
        return if (last in 1..604) listOf(Settings.Read(surah.id, last, 0L)) else emptyList()
    }

    // Every page today counted as read, as the achievements count them; newest first where the time is known
    private fun readToday(): List<Settings.Read> {
        val at = Settings.recentPages(host).associate { it.page to it.at }
        return Stats.day(host, Stats.today()).pages
            .map { page -> Settings.Read(Surahs.ofPage(page)?.id ?: 0, page, at[page] ?: 0L) }
            .sortedWith(compareByDescending<Settings.Read> { it.at }.thenBy { it.page })
    }

    // As the other two lists: the surah in the mushaf's hand, its juz and page beneath
    private fun pageRow(row: View, read: Settings.Read) {
        place(row, R.drawable.ic_surahs, read.page)
        Surahs.ofPage(read.page)?.let { fillSurahTitle(row.findViewById(R.id.place_title), it.id, R.dimen.surah_title) }
        row.findViewById<TextView>(R.id.place_detail).text = where(read.page)
        stamp(row, read.at)
    }

    private fun surahRow(row: View, read: Settings.Read) {
        place(row, R.drawable.ic_surahs, read.page)
        val surah = Surahs.list().firstOrNull { it.id == read.surah }
        fillSurahTitle(row.findViewById(R.id.place_title), read.surah, R.dimen.surah_title)

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
    }

    private fun savedRow(row: View, page: Int) {
        place(row, R.drawable.ic_bookmark, page)
        Surahs.ofPage(page)?.let { fillSurahTitle(row.findViewById(R.id.place_title), it.id, R.dimen.surah_title) }
        row.findViewById<TextView>(R.id.place_detail).text = where(page)
        row.findViewById<ImageView>(R.id.place_remove_icon).imageTintList =
            ColorStateList.valueOf(host.getColor(R.color.icon_btn))
        row.findViewById<View>(R.id.place_remove).apply {
            visibility = View.VISIBLE
            /* Dropping a saved page rebuilds the lists, so the list closes up at once. */
            setOnClickListener {
                Settings.toggleMark(host, page)
                build()
            }
        }
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
    private fun place(row: View, icon: Int, page: Int) {
        row.findViewById<ImageView>(R.id.place_icon).apply {
            setImageResource(icon)
            imageTintList = ColorStateList.valueOf(host.getColor(R.color.accent))
        }
        row.setOnClickListener { open(page) }
    }

    /* "Juz 1 · Page 3", in the figures the page itself uses. */
    private fun where(page: Int): String {
        val at = pageNamed(page, host.resources)
        val juz = Surahs.juzOfPage(page)
        return if (juz <= 0) at else host.getString(
            R.string.place_line, juzNamed(juz, host.resources), at
        )
    }

    private companion object {
        const val LISTS = 3
        const val PAGES = 0
        const val SURAHS = 1
        const val SAVED = 2
    }
}
