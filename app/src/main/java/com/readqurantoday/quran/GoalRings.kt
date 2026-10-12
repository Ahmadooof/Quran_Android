package com.readqurantoday.quran

import android.view.View
import android.widget.TextView
import com.readqurantoday.quran.StatCards.Companion.WEEK
import java.util.Calendar

/** A goal at a glance: today in pages, the week and month in days the goal was met. */
class GoalRings(private val cards: StatCards) {

    private val host = cards.host
    private val res = cards.res

    /** [want] gives a weekday's goal and [done] what a day did; [nothingYet] is today's line before any. */
    fun view(key: String, todayDone: Int, goal: Int, nothingYet: Int, todaySaid: Int,
             want: (day: Long, weekday: Int) -> Int, done: (day: Long) -> Int): View =
        cards.rings().apply {
            cards.fillRing(findViewById(R.id.ring_today), "$key.day", todayDone, goal, R.string.ring_today, todaySaid,
                unit = if (goal > 0) host.getString(R.string.ring_of, figures(goal, res))
                    else res.getQuantityString(R.plurals.pages_unit, todayDone))
            for ((id, span, label) in listOf(
                Triple(R.id.ring_week, weekSpan(), R.string.ring_week),
                Triple(R.id.ring_month, monthSpan(), R.string.ring_month)
            )) {
                val (met, had) = metDays(span, want, done)
                cards.fillRing(findViewById(id), "$key.$label", met, had, label, R.string.ring_days_met,
                    unit = if (had > 0) host.getString(R.string.ring_of, figures(had, res)) else "")
            }
            findViewById<TextView>(R.id.rings_line).text = todayLine(todayDone, goal, nothingYet)
        }

    // This week, Saturday to Friday as the app's week runs
    private fun weekSpan(): LongRange {
        val cal = Calendar.getInstance()
        cal.timeInMillis = Stats.noonOf(Stats.today())
        val start = Stats.today() - (cal.get(Calendar.DAY_OF_WEEK) - Calendar.SATURDAY + WEEK) % WEEK
        return start until start + WEEK
    }

    // The whole of this month by the chosen calendar; it ends where the next one starts
    private fun monthSpan(): LongRange {
        val hijri = Stats.hijri(host)
        val start = monthStart(Stats.today(), hijri)
        return start until monthStart(start + LONGEST_MONTH, hijri)
    }

    // Days of [span] that have a goal, and how many of those up to today met it
    private fun metDays(span: LongRange, want: (day: Long, weekday: Int) -> Int, done: (day: Long) -> Int): Pair<Int, Int> {
        val cal = Calendar.getInstance()
        var had = 0
        var met = 0
        for (day in span) {
            cal.timeInMillis = Stats.noonOf(day)
            val wanted = want(day, cal.get(Calendar.DAY_OF_WEEK))
            if (wanted > 0) {
                had++
                if (day <= Stats.today() && done(day) >= wanted) met++
            }
        }
        return met to had
    }

    // Today in a line: what is left, that the goal is reached, or by how much it was passed
    private fun todayLine(done: Int, goal: Int, nothingYet: Int): String = when {
        goal == 0 -> if (done == 0) host.getString(R.string.goal_none_today) else pagesSaid(done, res)
        done == 0 -> host.getString(nothingYet)
        done < goal -> host.getString(R.string.goal_left, pagesSaid(goal - done, res))
        done == goal -> host.getString(R.string.stats_goal_reached)
        else -> host.getString(R.string.goal_over, pagesSaid(done - goal, res))
    }

    private companion object {
        // Past the end of any month, Hijri or Gregorian, counted from its first day
        const val LONGEST_MONTH = 31
    }
}
