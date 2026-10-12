package com.readqurantoday.quran

import android.app.Activity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import androidx.viewpager2.widget.ViewPager2
import com.readqurantoday.quran.StatCards.Companion.WEEK

// Achievements: reading, memorising, khatma and listening, a swipe apart
class StatsPane(private val host: Activity, strip: View, pager: ViewPager2) {

    /** [OPEN] plays the whole entrance; [UPDATE] moves rings and figures from where they last stood. */
    enum class Motion { OPEN, UPDATE }

    // One scrolling column a tab
    private val pages = List(TABS) { host.layoutInflater.inflate(R.layout.part_scroll_column, pager, false).apply { keepToColumn() } }
    private val columns = pages.map { it.findViewById<LinearLayout>(R.id.column) }

    private val tabs = SwipeTabs(
        pager, strip,
        intArrayOf(R.string.sec_reading, R.string.sec_hifz, R.string.sec_khatma, R.string.sec_listening),
        // The slide between tabs is their movement; a rise on top of it blinked the cards out mid-swipe
        ViewPages(pages)
    )

    private val cards = StatCards(host, columns[0])
    private val rings = GoalRings(cards)
    private val reading = ReadingTab(cards, rings) { on ->
        build()
        if (on) celebrateOnce()
    }
    private val hifz = HifzTab(cards, rings)
    private val khatma = KhatmaTab(cards) { build() }
    private val listening = ListeningTab(cards)

    // Rebuilt whole after any change: the cards are few and every number may move together
    fun build(motion: Motion = Motion.UPDATE) {
        cards.begin(motion)
        columns.forEach { it.removeAllViews() }

        val today = Stats.day(host, Stats.today())
        val fortnight = Stats.lastDays(host, WEEK * 2)
        val week = fortnight.takeLast(WEEK)

        reading.fill(columns[READING], today, Stats.goal(host), week)
        hifz.fill(columns[HIFZ])
        khatma.fill(columns[KHATMA])
        listening.fill(columns[LISTENING], today, week, fortnight.take(WEEK))

        if (motion == Motion.OPEN) {
            columns[tabs.current].riseChildren()
            celebrateOnce()
        }
    }

    // The first look at the day's reached goal is met with a celebration; later looks are quiet
    private fun celebrateOnce() {
        val goal = Stats.goal(host)
        if (goal == 0 || Stats.day(host, Stats.today()).counted(goal) < goal || Stats.celebrated(host)) return
        Stats.setCelebrated(host)
        val over = host.findViewById<ViewGroup>(android.R.id.content)
        over.postDelayed({ over.celebrate(host.getString(R.string.goal_done)) }, CELEBRATE_AFTER_MS)
    }

    private companion object {
        const val TABS = 4
        const val READING = 0
        const val HIFZ = 1
        const val KHATMA = 2
        const val LISTENING = 3
        // Long enough for the cards to have come in first
        const val CELEBRATE_AFTER_MS = 450L
    }
}
