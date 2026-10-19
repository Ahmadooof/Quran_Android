package com.readqurantoday.quran

import android.os.Bundle
import android.view.View
import android.widget.TextView

/** Every page read today with the time it took: a screen of its own, since a day may hold hundreds. */
class PageTimesActivity : CardsActivity(R.string.stats_page_times) {

    // The order the pages are listed in, switched with one tap
    private enum class Order { PAGE, SLOWEST, QUICKEST }
    private var order = Order.PAGE

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

        val listed = when (order) {
            Order.PAGE -> sec.entries.sortedBy { it.key }
            Order.SLOWEST -> sec.entries.sortedByDescending { it.value }
            Order.QUICKEST -> sec.entries.sortedBy { it.value }
        }
        layoutInflater.card(cards, R.string.pt_sort, listOf(orders()) + listed.map { (page, s) ->
            row(pageNamed(page, resources), spentExact(s, resources), Surahs.ofPage(page)?.name.orEmpty())
        })
    }

    // By page, slowest first or quickest first, the one in use marked
    private fun orders(): View = layoutInflater.inflate(R.layout.part_segments, cards, false).apply {
        background = null
        val face = findViewById<TextView>(R.id.seg_1).typeface
        findViewById<View>(R.id.seg_4).visibility = View.GONE
        listOf(
            R.id.seg_1 to (Order.PAGE to R.string.pt_by_page),
            R.id.seg_2 to (Order.SLOWEST to R.string.pt_by_time),
            R.id.seg_3 to (Order.QUICKEST to R.string.pt_by_quick)
        ).forEach { (id, choice) ->
            findViewById<TextView>(id).apply {
                setText(choice.second)
                markSegment(order == choice.first, face)
                setOnClickListener {
                    order = choice.first
                    build()
                }
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
