package com.readqurantoday.quran

import android.view.View
import android.widget.LinearLayout
import android.widget.TextView

/** The memorising tab: the goal, if one is set, as rings; then what is memorised in all, and the way into the record. */
class HifzTab(private val cards: StatCards, private val rings: GoalRings) {

    private val host = cards.host
    private val res = cards.res

    fun fill(into: LinearLayout) {
        val plan = Stats.hifzPlan(host)
        val kept = Hifz.pages(host)
        if (plan == null) cards.blow.card(into, 0, listOf(cards.action(host.getString(R.string.hifz_add)) { GoalActivity.open(host, hifz = true) }))
        else cards.blow.card(into, 0, listOf(
            rings.view(KEY, kept.count { it.value == Stats.today() }, Stats.hifzGoal(host), R.string.hifz_none_today, R.string.ring_pages_learned,
                want = { _, weekday -> plan.pagesOn(weekday) },
                done = { day -> kept.count { it.value == day } }),
            cards.changeRow(plan.said(host)) { GoalActivity.open(host, hifz = true) }
        ))
        cards.blow.card(into, 0, knownRows(kept.size))
    }

    // What is memorised in all: its share of the Quran and its whole surahs as rings, then the way into the record
    private fun knownRows(known: Int): List<View> {
        val bySurah = Hifz.bySurah(host)
        val surahs = Surahs.list()
        val whole = surahs.count { Hifz.knownOf(it, bySurah) == it.to - it.from + 1 }
        val view = cards.rings().apply {
            cards.fillRing(findViewById(R.id.ring_today), KNOWN, cards.shareOf(known), 100, R.string.ring_known, R.string.ring_of_quran,
                unit = "", say = cards::percentSaid)
            cards.fillRing(findViewById(R.id.ring_week), KNOWN_WHOLE, whole, surahs.size, R.string.ring_whole, 0,
                unit = host.getString(R.string.ring_of, figures(surahs.size, res)))
            findViewById<View>(R.id.ring_month).visibility = View.GONE
            findViewById<TextView>(R.id.rings_line).text = if (known == 0) host.getString(R.string.hifz_mine_empty)
                else host.getString(R.string.stats_khatma_read, pagesSaid(known, res), figures(Mushaf.PAGES, res))
        }
        // The surahs memorised so far, one tap away, before the full record of all of them
        val some = surahs.count { Hifz.knownOf(it, bySurah) > 0 }
        val mine = cards.row(host.getString(R.string.hifz_known_surahs), figures(some, res))
            .apply { opens { MemorizedActivity.open(host, knownOnly = true) } }
        val record = cards.row(host.getString(R.string.hifz_mine), if (known == 0) host.getString(R.string.hifz_mine_add) else "")
            .apply { opens { MemorizedActivity.open(host) } }
        return if (some > 0) listOf(view, mine, record) else listOf(view, record)
    }

    private companion object {
        const val KEY = "hifz"
        const val KNOWN = "known"
        const val KNOWN_WHOLE = "known.whole"
    }
}
