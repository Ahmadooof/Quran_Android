package com.readqurantoday.quran

import android.app.Activity
import android.view.ViewGroup
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView

// The index tab: surahs, juz, hizb and pages, a swipe apart; every row opens or recites at its page
class IndexPane(
    private val host: Activity,
    private val surahs: SurahAdapter,
    private val open: (Int) -> Unit,
    private val pickReciter: (Surahs.Surah?) -> Unit,
    private val played: () -> Unit
) {

    private val res = host.resources
    private val juz = division(Surahs.juzStarts(), { juzNamed(it, res) },
        { pageNamed(Surahs.juzStarts()[it - 1], res) }, { listOfNotNull(Openings.ofJuz(host, it)) }, true, null, Surahs::juzOfPage)
    private val hizb = division(Surahs.hizbStarts(), { host.getString(R.string.head_hizb, figures(it, res)) },
        { juzNamed((it + 1) / 2, res) }, { listOfNotNull(Openings.ofHizb(host, it)) }, false, ::quarters, Surahs::hizbOfPage)
    private val pages = division(IntArray(Mushaf.PAGES) { it + 1 }, { pageNamed(it, res) },
        ::juzSaid, { page -> Surahs.onPage(page).map { it.id } }, true, null) { it }

    private val lists: List<RecyclerView.Adapter<*>> = listOf(surahs, juz, hizb, pages)

    init {
        SwipeTabs(
            host.findViewById(R.id.lists), host.findViewById(R.id.segments),
            intArrayOf(R.string.list_surahs, R.string.tab_juz, R.string.list_hizb, R.string.list_pages), Lists()
        )
    }

    /** Rows show which part is being recited; called as the recitation moves or stops. */
    fun refresh() = lists.forEach { it.notifyDataSetChanged() }

    private fun division(starts: IntArray, title: (Int) -> String, where: (Int) -> String, surahsOf: (Int) -> List<Int>, surahsUnder: Boolean, quarters: ((Int) -> List<Int>)?, numberOf: (Int) -> Int) =
        DivisionAdapter(
            starts = starts,
            title = title,
            where = where,
            surahsOf = surahsOf,
            surahsUnder = surahsUnder,
            quarters = quarters,
            onOpen = open,
            onPlay = { page -> play(page, numberOf) },
            onReciter = { page -> pickReciter(Surahs.ofPage(page)) },
            // Read from where the recitation actually is, so every list agrees wherever it was started
            playing = { numberOf(Recite.playingPage(host)) }
        )

    // Where a hizb, its quarter, half and three-quarters begin, by page; the chips say it, so no line under the title
    private fun quarters(hizb: Int): List<Int> = Surahs.quartersOf(hizb)

    // The juz the page's own head names, so the list and the page agree
    private fun juzSaid(page: Int): String = juzNamed(Surahs.juzOfPage(page), res)

    // --- finding one's way down a long list ---

    // What the bubble beside a dragged handle says, for each list: the row at the top of the list
    private fun dragLabel(list: Int): (Int) -> String = when (list) {
        SURAHS -> { at -> Surahs.list().getOrNull(at)?.let { host.getString(R.string.surah_named, it.name) }.orEmpty() }
        JUZ -> { at -> juzNamed(at + 1, res) }
        HIZB -> { at -> host.getString(R.string.head_hizb, figures(at + 1, res)) }
        else -> { at ->
            host.getString(R.string.place_line, pageNamed(at + 1, res), juzSaid(at + 1))
        }
    }

    private fun play(page: Int, numberOf: (Int) -> Int) {
        val here = numberOf(page)
        // Already reciting this part: the button is a pause, as it is on a surah row
        if (here != 0 && here == numberOf(Recite.playingPage(host))) Recite.toggle()
        else Recite.startPage(host, page)
        if (Recite.wantsToPlay()) host.warnIfMuted()
        played()
    }

    private inner class Lists : RecyclerView.Adapter<Lists.Page>() {

        inner class Page(val list: RecyclerView) : RecyclerView.ViewHolder(list)

        override fun getItemCount() = lists.size

        // One view type a list, so each is made knowing which it is
        override fun getItemViewType(position: Int) = position

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = Page(
            (host.layoutInflater.inflate(R.layout.part_list, parent, false) as RecyclerView).apply {
                layoutManager = LinearLayoutManager(parent.context)
                keepToColumn()
                addHandle(dragLabel(viewType))
            }
        )

        override fun onBindViewHolder(holder: Page, position: Int) {
            holder.list.adapter = lists[position]
        }
    }

    private companion object {
        const val SURAHS = 0
        const val JUZ = 1
        const val HIZB = 2
        const val PAGES = 3
    }
}
