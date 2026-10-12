package com.readqurantoday.quran

import android.os.Bundle
import android.view.View
import android.widget.TextView

/** Every page read today with the time it took: a screen of its own, since a day may hold hundreds. */
class PageTimesActivity : CardsActivity(R.string.stats_page_times) {

    private var longestFirst = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Surahs.load(this)
        build()
        sayBars()
    }

    private fun build() {
        cards.removeAllViews()
        val today = Stats.day(this, Stats.today())
        val sec = today.pages.associateWith { today.pageSec.getValue(it) }
        if (sec.isEmpty()) return

        // The two ends of the day; its total and pace are on the achievements tab already
        val quickest = sec.minBy { it.value }
        val longest = sec.maxBy { it.value }
        layoutInflater.card(cards, 0, listOf(
            row(getString(R.string.pt_fastest), spentExact(quickest.value, resources), where(quickest.key)),
            row(getString(R.string.pt_slowest), spentExact(longest.value, resources), where(longest.key))
        ))

        layoutInflater.card(cards, 0, listOf(sortRow()))

        val order = if (longestFirst) sec.entries.sortedByDescending { it.value } else sec.entries.sortedBy { it.key }
        layoutInflater.card(cards, 0, order.map { (page, s) ->
            row(pageNamed(page, resources), spentExact(s, resources), Surahs.ofPage(page)?.name.orEmpty())
        })
    }

    private fun sortRow(): View =
        row(getString(R.string.pt_sort), getString(if (longestFirst) R.string.pt_by_time else R.string.pt_by_page)).apply {
            opens {
                sheet(getString(R.string.pt_sort), listOf(
                    Choice(getString(R.string.pt_by_page), on = !longestFirst),
                    Choice(getString(R.string.pt_by_time), on = longestFirst)
                )) { i ->
                    longestFirst = i == 1
                    build()
                }
            }
        }

    // The page, then its surah after an Arabic comma: a dot beside a figure reads as a zero
    private fun where(page: Int): String {
        val at = pageNamed(page, resources)
        val surah = Surahs.ofPage(page)?.name ?: return at
        return getString(R.string.page_in_surah, at, surah)
    }

    private fun row(label: String, value: String, note: String = ""): View =
        layoutInflater.inflate(R.layout.row_progress, cards, false).apply {
            findViewById<TextView>(R.id.prog_label).text = label
            findViewById<TextView>(R.id.prog_value).text = value
            findViewById<TextView>(R.id.prog_note).apply { text = note; visibility = if (note.isEmpty()) View.GONE else View.VISIBLE }
        }
}
