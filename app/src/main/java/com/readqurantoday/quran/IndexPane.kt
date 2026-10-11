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
    private val juz = division(Surahs.juzStarts(), { host.getString(R.string.head_juz, figures(it, res)) },
        { partWhere(Openings.ofJuz(host, it), Surahs.juzStarts()[it - 1]) }, { Openings.ofJuz(host, it)?.surah }, null, Surahs::juzOfPage)
    private val hizb = division(Surahs.hizbStarts(), { host.getString(R.string.head_hizb, figures(it, res)) },
        { partWhere(Openings.ofHizb(host, it), Surahs.hizbStarts()[it - 1]) }, { Openings.ofHizb(host, it)?.surah }, ::quarters, Surahs::hizbOfPage)
    private val pages = division(IntArray(Mushaf.PAGES) { it + 1 }, { host.getString(R.string.head_page, figures(it, res)) },
        ::surahAndJuz, null, null) { it }

    private val lists: List<RecyclerView.Adapter<*>> = listOf(surahs, juz, hizb, pages)

    init {
        SwipeTabs(
            host.findViewById(R.id.lists), host.findViewById(R.id.segments),
            intArrayOf(R.string.list_surahs, R.string.tab_juz, R.string.list_hizb, R.string.list_pages), Lists()
        )
    }

    /** Rows show which part is being recited; called as the recitation moves or stops. */
    fun refresh() = lists.forEach { it.notifyDataSetChanged() }

    private fun division(starts: IntArray, title: (Int) -> String, where: (Int) -> String, surahOf: ((Int) -> Int?)?, quarters: ((Int) -> List<Int>)?, numberOf: (Int) -> Int) =
        DivisionAdapter(
            starts = starts,
            title = title,
            where = where,
            surahOf = surahOf,
            quarters = quarters,
            onOpen = open,
            onPlay = { page -> play(page, numberOf) },
            onReciter = { page -> pickReciter(Surahs.ofPage(page)) },
            // Read from where the recitation actually is, so every list agrees wherever it was started
            playing = { numberOf(Recite.playingPage(host)) }
        )

    // Where a hizb's quarter, half and three-quarters begin, by page
    private fun quarters(hizb: Int): List<Int> = Surahs.quartersOf(hizb).drop(1)

    // Beside its surah, the ayah it begins at and its page; a surah's first ayah needs no number
    private fun partWhere(at: Openings.Opening?, page: Int): String {
        val onPage = host.getString(R.string.head_page, figures(page, res))
        if (at == null || at.ayah == 1) return onPage
        return host.getString(R.string.place_line, host.getString(R.string.ayah_at, figures(at.ayah, res)), onPage)
    }

    // Every surah on the page; its juz is the pinned heading's, said here only where a juz begins partway down it
    private fun surahAndJuz(page: Int): String {
        val surahs = Surahs.onPage(page)
        val named = when (surahs.size) {
            0 -> ""
            1 -> host.getString(R.string.surah_named, surahs[0].name)
            else -> surahs.joinToString(host.getString(R.string.list_join)) { it.name }
        }
        val span = Surahs.juzSpanOf(page)
        if (span.first == span.last) return named
        return if (named.isEmpty()) juzSaid(page) else host.getString(R.string.place_line, named, juzSaid(page))
    }

    private fun juzSaid(page: Int): String {
        val span = Surahs.juzSpanOf(page)
        return if (span.first == span.last) host.getString(R.string.head_juz, figures(span.first, res))
        else host.getString(R.string.head_juz, host.getString(R.string.range, figures(span.first, res), figures(span.last, res)))
    }

    // --- finding one's way down a long list ---

    // What the bubble beside a dragged handle says, for each list: the row at the top of the list
    private fun dragLabel(list: Int): (Int) -> String = when (list) {
        SURAHS -> { at -> Surahs.list().getOrNull(at)?.let { host.getString(R.string.surah_named, it.name) }.orEmpty() }
        JUZ -> { at -> host.getString(R.string.head_juz, figures(at + 1, res)) }
        HIZB -> { at -> host.getString(R.string.head_hizb, figures(at + 1, res)) }
        else -> { at ->
            host.getString(R.string.place_line, host.getString(R.string.head_page, figures(at + 1, res)), juzSaid(at + 1))
        }
    }

    // The pages list is grouped by the juz each page opens in, its heading pinned over the rows
    private val pageJuz by lazy { IntArray(Mushaf.PAGES) { Surahs.juzSpanOf(it + 1).first } }

    private fun juzHead(juz: Int): String {
        val first = pageJuz.indexOfFirst { it == juz } + 1
        val last = pageJuz.indexOfLast { it == juz } + 1
        return host.getString(R.string.place_line, host.getString(R.string.head_juz, figures(juz, res)),
            host.getString(R.string.pages_range_short, figures(first, res), figures(last, res)))
    }

    private fun play(page: Int, numberOf: (Int) -> Int) {
        val here = numberOf(page)
        // Already reciting this part: the button is a pause, as it is on a surah row
        if (here != 0 && here == numberOf(Recite.playingPage(host))) Recite.toggle()
        else Recite.startPage(host, page)
        played()
    }

    private inner class Lists : RecyclerView.Adapter<Lists.Page>() {

        // The list sits in a holder that keeps a slanted scroll from turning to the next list
        inner class Page(val list: RecyclerView) : RecyclerView.ViewHolder(UpDownFirst(list.context, list))

        override fun getItemCount() = lists.size

        // One view type a list, so each is made knowing which it is
        override fun getItemViewType(position: Int) = position

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = Page(
            (host.layoutInflater.inflate(R.layout.part_list, parent, false) as RecyclerView).apply {
                layoutManager = LinearLayoutManager(parent.context)
                keepToColumn()
                addHandle(dragLabel(viewType))
                if (viewType == PAGES) addPinnedHead(part = { pageJuz[it] }, said = ::juzHead)
                // Two hizb to a juz: the hizb list is grouped under its juz as the pages are
                if (viewType == HIZB) addPinnedHead(part = { it / 2 + 1 }, said = { host.getString(R.string.head_juz, figures(it, res)) })
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
