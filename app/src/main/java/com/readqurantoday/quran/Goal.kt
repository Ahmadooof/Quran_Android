package com.readqurantoday.quran

import android.content.Context
import android.content.res.Resources
import java.util.Calendar

/**
 * A reading goal: an amount of pages, juz or surahs, on chosen days of the week, with Al-Kahf added on
 * Fridays if [kahf]. Days are a mask, Sunday the lowest bit, as Calendar counts them.
 */
data class Goal(val kind: Kind, val amount: Int, val surahs: List<Int>, val days: Int, val kahf: Boolean) {

    /** KHATMA keeps the day the khatma is to end in [amount]; its pages a day follow from what is left. */
    enum class Kind { PAGES, JUZ, SURAHS, KHATMA }

    /** Pages to read on [weekday] (Calendar.SUNDAY..SATURDAY); 0 is a day without a goal. */
    fun pagesOn(weekday: Int): Int {
        val base = if (days and bit(weekday) != 0) basePages() else 0
        return base + if (kahf && weekday == Calendar.FRIDAY) surahPages(KAHF) else 0
    }

    fun pagesToday(): Int = pagesOn(Calendar.getInstance().get(Calendar.DAY_OF_WEEK))

    fun everyDay() = days == ALL_DAYS

    fun basePages(): Int = when (kind) {
        Kind.PAGES -> amount
        Kind.JUZ -> Math.round(amount * Mushaf.PAGES / 30f)
        Kind.SURAHS -> surahs.sumOf { surahPages(it) }
        Kind.KHATMA -> 0
    }

    /** What the goal is, as the goal row says it: "One juz every day, and Al-Kahf on Friday". */
    fun said(ctx: Context): String {
        val res = ctx.resources
        if (kind == Kind.KHATMA) return res.getString(R.string.goal_khatma_by, dateSaid(ctx, amount.toLong()))
        val what = when (kind) {
            Kind.PAGES -> pagesSaid(amount, res)
            Kind.JUZ -> res.getQuantityString(R.plurals.juz_count, amount, figures(amount, res))
            Kind.SURAHS -> surahs.mapNotNull { id -> Surahs.list().firstOrNull { it.id == id }?.name }
                .joinToString(res.getString(R.string.and_join))
            Kind.KHATMA -> ""
        }
        val plan = if (everyDay()) res.getString(R.string.goal_plan, what, res.getString(R.string.goal_every_day))
            else res.getString(R.string.goal_plan_days, what, daysSaid(res))
        return if (kahf) res.getString(R.string.goal_with_kahf, plan) else plan
    }

    // "Monday and Thursday", "Saturday, Monday and Thursday": commas between, "and" before the last
    private fun daysSaid(res: Resources): String {
        val names = WEEK.filter { days and bit(it) != 0 }.map { weekdayName(it, res) }
        if (names.size == 1) return names[0]
        return names.dropLast(1).joinToString(res.getString(R.string.list_join)) + res.getString(R.string.and_join) + names.last()
    }

    /** As stored: "JUZ|1||127|1". */
    fun encoded(): String = listOf(kind.name, amount, surahs.joinToString(","), days, if (kahf) 1 else 0).joinToString("|")

    companion object {
        const val ALL_DAYS = 0b1111111
        private const val KAHF = 18

        /** Al-Baqarah and Al-Imran every day, and Al-Kahf on Friday as the Sunnah has it. */
        val DEFAULT = Goal(Kind.SURAHS, 0, listOf(2, 3), ALL_DAYS, kahf = true)

        /** Saturday first, the week as it is lived where Friday is the day of gathering. */
        val WEEK = listOf(
            Calendar.SATURDAY, Calendar.SUNDAY, Calendar.MONDAY, Calendar.TUESDAY,
            Calendar.WEDNESDAY, Calendar.THURSDAY, Calendar.FRIDAY
        )

        fun bit(weekday: Int) = 1 shl (weekday - Calendar.SUNDAY)

        fun decoded(text: String?): Goal? {
            val p = text?.split('|') ?: return null
            if (p.size != 5) return null
            val kind = runCatching { Kind.valueOf(p[0]) }.getOrNull() ?: return null
            return Goal(
                kind, p[1].toIntOrNull() ?: return null,
                p[2].split(',').mapNotNull { it.toIntOrNull() },
                p[3].toIntOrNull() ?: return null, p[4] == "1"
            )
        }

        fun weekdayName(weekday: Int, res: Resources): String =
            res.getStringArray(R.array.weekdays)[weekday - Calendar.SUNDAY]

        private fun surahPages(id: Int): Int = Surahs.list().firstOrNull { it.id == id }?.let { it.to - it.from + 1 } ?: 0
    }
}
