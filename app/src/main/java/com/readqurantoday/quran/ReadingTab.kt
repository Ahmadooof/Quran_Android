package com.readqurantoday.quran

import android.content.Intent
import android.content.res.ColorStateList
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.readqurantoday.quran.StatCards.Companion.WEEK
import com.readqurantoday.quran.StatCards.Tile
import java.util.Calendar
import kotlin.math.roundToInt

/** The reading tab: today against the goal, time and speed, the month, and the week chart. */
class ReadingTab(private val cards: StatCards, private val rings: GoalRings, private val done: (Boolean) -> Unit) {

    private val host = cards.host
    private val res = cards.res
    private val blow = cards.blow

    fun fill(into: LinearLayout, today: Stats.Day, goal: Int, week: List<Stats.Day>) {
        blow.card(into, 0, goalRows(today, goal, into))
        // The goal's own surahs today, each with how much of it is read
        val surahs = Stats.goalPlan(host).surahsOn(weekdayOf(Stats.today()))
        if (surahs.isNotEmpty()) blow.card(into, R.string.goal_surahs_today, surahs.mapNotNull { id ->
            Surahs.list().firstOrNull { it.id == id }?.let { surahRow(it, today, into) }
        })
        blow.card(into, R.string.stats_today, todayRows(today))
        val thisMonth = Stats.monthDays(host)
        val monthPages = thisMonth.withIndex().sumOf { (i, d) -> d.counted(goalOn(Stats.today() - (thisMonth.size - 1 - i), goal)) }
        into.addView(cards.pair(
            Tile(R.drawable.ic_calendar, if (monthPages == 0) none(res) else pagesSaid(monthPages, res), host.getString(R.string.stats_pages_month)),
            Tile(R.drawable.ic_clock, spent(thisMonth.sumOf { it.readSec }, res), host.getString(R.string.stats_read_month))
        ))
        blow.card(into, host.getString(R.string.stats_last_days, figures(WEEK, res)), weekRows(week, goal))
    }

    private fun goalRows(today: Stats.Day, goal: Int, into: LinearLayout): List<View> {
        val plan = Stats.goalPlan(host)
        // A khatma's pages a day are only known for today, so any reading counts toward it on other days
        val view = rings.view(KEY, today.counted(goal), goal, R.string.stats_none_today, R.string.chart_read,
            want = { _, weekday -> if (plan.kind == Goal.Kind.KHATMA) 1 else plan.pagesOn(weekday) },
            done = { day -> Stats.day(host, day).let { if (it.doneByHand) Int.MAX_VALUE else it.pages.size } })
        val rows = mutableListOf(view, cards.changeRow(khatmaNote(plan, goal).ifEmpty { plan.said(host) }) { GoalActivity.open(host) })
        // The reader says today's goal is done, wherever it was read
        if (goal > 0 && today.pages.size < goal) rows += blow.switchRow(into, host.getString(R.string.paper_read), today.doneByHand) { on ->
            Stats.setDoneByHand(host, on)
            // The knob finishes its slide before the numbers move
            into.postDelayed({ done(on) }, SWITCH_SETTLE_MS)
        }
        return rows
    }

    // A khatma goal says what its daily pages are for
    private fun khatmaNote(plan: Goal, goal: Int): String =
        if (plan.kind != Goal.Kind.KHATMA || goal == 0) ""
        else host.getString(R.string.khatma_daily, pagesSaid(goal, res), dateSaid(host, plan.amount.toLong()))

    // Today's reading: how long, and how fast, said once as time a page with the hour's worth beneath it
    private fun todayRows(today: Stats.Day): List<View> {
        val rows = mutableListOf(cards.row(host.getString(R.string.stats_read_time), spent(today.readSec, res)), speedRow(today))
        if (today.pages.isNotEmpty()) rows += cards.row(host.getString(R.string.stats_page_times_open), "").apply {
            opens { host.startActivity(Intent(host, PageTimesActivity::class.java)) }
        }
        return rows
    }

    // A rate, not a promise: today's time a page, and how much an hour at that pace would be
    private fun speedRow(today: Stats.Day): View {
        val sec = today.pages.sumOf { today.pageSec.getValue(it) }
        if (sec == 0) return cards.row(host.getString(R.string.stats_speed_today), none(res), host.getString(R.string.stats_per_hour_note))
        val perHour = today.pages.size * 3600f / sec
        return cards.row(host.getString(R.string.stats_speed_today),
            host.getString(R.string.speed_per_page, spentExact(sec / today.pages.size, res)),
            host.getString(R.string.speed_per_hour, pagesSaid(perHour.roundToInt(), res), juzSaid(perHour, res)))
    }

    // The last seven days, each against its own goal
    private fun weekRows(week: List<Stats.Day>, goal: Int): List<View> {
        val goals = week.indices.map { i -> goalOn(Stats.today() - (week.size - 1 - i), goal) }
        val counts = week.mapIndexed { i, d -> d.counted(goals[i]) }
        val pad = res.getDimensionPixelSize(R.dimen.tile_pad)
        val chart = DayBars(host).apply {
            setPadding(pad, pad, pad, pad)
            show(counts, goals, ::dayName, grow = cards.growing)
            // A day held shows its card beside the finger, and the card goes when the finger lifts
            val tip = DayTip(this)
            onHold = { i ->
                val on = Stats.today() - (week.size - 1 - i)
                picked = i
                tip.show(centreOf(i), if (on == Stats.today()) host.getString(R.string.chart_today) else weekdayDateSaid(host, on), dayLines(week[i], on, goals[i]))
            }
            onLetGo = {
                picked = -1
                tip.hide()
            }
            contentDescription = host.getString(R.string.stats_chart_desc, figures(week.size, res), pagesSaid(counts.max(), res))
        }
        // The chart and the key to its colours are one row, with no seam between them
        val withKey = LinearLayout(host).apply {
            orientation = LinearLayout.VERTICAL
            addView(chart)
            addView(blow.inflate(R.layout.part_chart_legend, this, false).apply {
                findViewById<TextView>(R.id.legend_goal).text = goalKey(goals)
            })
        }
        return listOf(withKey, cards.row(host.getString(R.string.chart_total), pagesSaid(counts.sum(), res)))
    }

    // The goal said once, not under every day: the usual one, then any day that asks for another, by its name
    private fun goalKey(goals: List<Int>): String {
        val usual = goals.filter { it > 0 }.groupingBy { it }.eachCount().maxByOrNull { it.value }?.key
            ?: return host.getString(R.string.chart_goal_word)
        val said = host.getString(R.string.chart_goal_of, pagesSaid(usual, res))
        val last = goals.size - 1
        val others = goals.indices.filter { goals[it] > 0 && goals[it] != usual }
            .map { host.getString(R.string.chart_goal_day, dayName(last - it), figures(goals[it], res)) }
        return (listOf(said) + others).joinToString(host.getString(R.string.list_join))
    }

    // A goal surah: a tick once every page of it is read that day, else how far, with its bar
    private fun surahRow(surah: Surahs.Surah, day: Stats.Day, into: LinearLayout): View =
        blow.inflate(R.layout.row_place, into, false).apply {
            isClickable = false
            val all = surah.to - surah.from + 1
            val read = day.readOf(surah)
            findViewById<ImageView>(R.id.place_icon).apply {
                setImageResource(if (read == all) R.drawable.ic_check else R.drawable.ic_surahs)
                imageTintList = ColorStateList.valueOf(host.getColor(R.color.accent))
            }
            fillSurahTitle(findViewById(R.id.place_title), surah.id, R.dimen.surah_title_row)
            findViewById<TextView>(R.id.place_detail).text = surahSaid(read, all)
            findViewById<View>(R.id.place_track).fillTrack(read.toFloat(), all.toFloat())
        }

    private fun surahSaid(read: Int, all: Int): String =
        if (read == all) host.getString(R.string.surah_done) else host.getString(R.string.of_count, figures(read, res), pagesSaid(all, res))

    // A day of the chart: its pages, its goal, its goal surahs, and every other surah it read whole
    private fun dayLines(day: Stats.Day, on: Long, goal: Int): List<Pair<String, String>> {
        val lines = mutableListOf(host.getString(R.string.chart_read) to pagesSaid(day.pages.size, res))
        if (goal > 0) lines += host.getString(R.string.chart_goal_of, pagesSaid(goal, res)) to
            if (day.counted(goal) >= goal) host.getString(R.string.goal_met)
            else host.getString(R.string.of_count, figures(day.counted(goal), res), figures(goal, res))
        val goalSurahs = Stats.goalPlan(host).surahsOn(weekdayOf(on))
        for (s in goalSurahs.mapNotNull { id -> Surahs.list().firstOrNull { it.id == id } }) {
            lines += host.getString(R.string.surah_named, s.name) to surahSaid(day.readOf(s), s.to - s.from + 1)
        }
        val others = day.wholeSurahs().filter { it.id !in goalSurahs }
        if (others.isNotEmpty()) {
            lines += host.getString(R.string.day_other_whole) to figures(others.size, res)
            lines += others.joinToString(host.getString(R.string.list_join)) { it.name } to ""
        }
        return lines
    }

    private fun weekdayOf(day: Long): Int = Calendar.getInstance().apply { timeInMillis = Stats.noonOf(day) }.get(Calendar.DAY_OF_WEEK)

    // A day's goal by its weekday; a khatma's pages a day are only known for today
    private fun goalOn(day: Long, todayGoal: Int): Int {
        if (day == Stats.today()) return todayGoal
        val plan = Stats.goalPlan(host)
        if (plan.kind == Goal.Kind.KHATMA) return 0
        val cal = Calendar.getInstance()
        cal.timeInMillis = Stats.noonOf(day)
        return plan.pagesOn(cal.get(Calendar.DAY_OF_WEEK))
    }

    // Today by name, the days before it by their weekday
    private fun dayName(ago: Int): String {
        if (ago == 0) return host.getString(R.string.chart_today)
        val cal = Calendar.getInstance()
        cal.timeInMillis = Stats.noonOf(Stats.today() - ago)
        return res.getStringArray(R.array.weekdays_short)[cal.get(Calendar.DAY_OF_WEEK) - Calendar.SUNDAY]
    }

    private companion object {
        const val KEY = "today"
        const val SWITCH_SETTLE_MS = 250L
    }
}
