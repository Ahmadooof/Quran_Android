package com.readqurantoday.quran

import android.content.Context
import androidx.core.content.edit
import org.json.JSONObject
import java.util.TimeZone

/** Reading and listening, kept on this phone only: seconds per page and per surah each day, the khatma, the goal. */
object Stats {

    /** A page counts as read once it has been on screen this long in a day. */
    private const val READ_FROM_SEC = 10

    // Pages 1 and 2 are the mushaf's two half pages, about seven lines each, so half the time reads them
    private const val SHORT_PAGES = 2
    private const val SHORT_READ_FROM_SEC = 5

    /** How long [page] must be on screen in a day to count as read. */
    fun readFrom(page: Int) = if (page <= SHORT_PAGES) SHORT_READ_FROM_SEC else READ_FROM_SEC

    /** One stay on a page counts for at most this long; a page left open is not being read. */
    const val STAY_CAP_SEC = 300

    /** Days of history the statistics screen draws. */
    const val CHART_DAYS = 30

    // Pace looks back two weeks: long enough to steady, short enough to follow a change of habit
    private const val PACE_DAYS = 14

    // Fewer days than this give a pace too unsteady to name a date by
    private const val PACE_FROM_DAYS = 3

    private const val KEEP_DAYS = 60
    private const val DAY_MS = 86_400_000L

    internal const val PREFS = "stats"
    private const val DAY = "d."
    private const val KHATMA = "khatma"
    private const val KHATMA_FROM = "khatma_from"
    private const val KHATMAS = "khatmas"
    private const val GOAL = "goal"
    private const val GOAL_PLAN = "goal_plan"
    private const val HIFZ_GOAL = "hifz_goal"
    private const val SINCE = "since"
    private const val HIJRI = "hijri"
    private const val CELEBRATED = "celebrated"
    private const val HEARD = "heard"

    /**
     * One day: seconds on each page, seconds heard from each surah, and whether the reader said the goal was read
     * elsewhere, with how many pages had been read here when they said so.
     */
    data class Day(
        val pageSec: Map<Int, Int>,
        val surahSec: Map<Int, Int>,
        val doneByHand: Boolean = false,
        val handFrom: Int = 0
    ) {
        val pages: List<Int> get() = pageSec.filter { (page, sec) -> sec >= readFrom(page) }.keys.sorted()

        /** Pages the day counts against [goal]: those read here, or the goal and every page read here after it was said done. */
        fun counted(goal: Int): Int = if (doneByHand) goal + maxOf(0, pages.size - handFrom) else pages.size

        val readSec: Int get() = pageSec.values.sum()
        val listenSec: Int get() = surahSec.values.sum()
    }

    /** The khatma under way: distinct pages read since [fromDay], and how many were finished before it. */
    data class Khatma(val read: Int, val fromDay: Long, val done: Int)

    private fun store(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** Days since 1970 on this phone's clock, so a day turns at local midnight. */
    fun today(): Long = dayOf(System.currentTimeMillis())

    /** The day [at] fell on, counted as [today] counts. */
    fun dayOf(at: Long): Long = Math.floorDiv(at + TimeZone.getDefault().getOffset(at), DAY_MS)

    /** Local midday of [day] in epoch ms, for formatting a date. */
    fun noonOf(day: Long): Long {
        val noon = day * DAY_MS + DAY_MS / 2
        return noon - TimeZone.getDefault().getOffset(noon)
    }

    // --- recording ---

    /** Credit [sec] to [page]. */
    fun addRead(ctx: Context, page: Int, sec: Int) {
        if (page !in 1..Mushaf.PAGES || sec <= 0) return
        val day = day(ctx, today())
        val total = (day.pageSec[page] ?: 0) + sec
        val after = day.copy(pageSec = day.pageSec + (page to total))
        save(ctx, today(), after)
        if (total >= readFrom(page)) markRead(ctx, page)
    }

    fun addHeard(ctx: Context, surah: Int, sec: Int) {
        if (surah !in 1..114 || sec <= 0) return
        val day = day(ctx, today())
        save(ctx, today(), day.copy(surahSec = day.surahSec + (surah to (day.surahSec[surah] ?: 0) + sec)))
        val total = heardInAll(ctx)
        store(ctx).edit { putString(HEARD, JSONObject((total + (surah to (total[surah] ?: 0) + sec)).mapKeys { it.key.toString() }).toString()) }
    }

    /** Seconds heard of each surah since statistics began; before they were kept, the days still on the phone stand in. */
    fun heardInAll(ctx: Context): Map<Int, Int> {
        val prefs = store(ctx)
        prefs.getString(HEARD, null)?.let { kept -> return counts(runCatching { JSONObject(kept) }.getOrNull()) }
        val sum = HashMap<Int, Int>()
        prefs.all.keys.filter { it.startsWith(DAY) }.mapNotNull { it.removePrefix(DAY).toLongOrNull() }.forEach { d ->
            day(ctx, d).surahSec.forEach { (s, sec) -> sum[s] = (sum[s] ?: 0) + sec }
        }
        return sum
    }

    // A finished khatma is counted and the next one starts at once
    private fun markRead(ctx: Context, page: Int) {
        val prefs = store(ctx)
        val read = khatmaPages(ctx)
        if (read[page - 1] == '1') return
        read[page - 1] = '1'
        prefs.edit {
            if (!prefs.contains(KHATMA_FROM)) putLong(KHATMA_FROM, today())
            if (read.all { it == '1' }) {
                putInt(KHATMAS, prefs.getInt(KHATMAS, 0) + 1)
                putString(KHATMA, "")
                putLong(KHATMA_FROM, today())
            } else {
                putString(KHATMA, String(read))
            }
        }
    }

    // --- reading back ---

    fun day(ctx: Context, day: Long): Day {
        val o = store(ctx).getString(DAY + day, null)?.let { runCatching { JSONObject(it) }.getOrNull() }
            ?: return Day(emptyMap(), emptyMap())
        return Day(counts(o.optJSONObject("r")), counts(o.optJSONObject("l")), o.optBoolean("m"), o.optInt("h"))
    }

    /** Today's reading goal said done by the reader, for reading from a printed mushaf. */
    fun setDoneByHand(ctx: Context, on: Boolean) {
        val now = day(ctx, today())
        save(ctx, today(), now.copy(doneByHand = on, handFrom = if (on) now.pages.size else 0))
    }

    /** The last [n] days, oldest first, today last. */
    fun lastDays(ctx: Context, n: Int): List<Day> {
        val end = today()
        return (end - n + 1..end).map { day(ctx, it) }
    }

    fun khatma(ctx: Context): Khatma {
        val prefs = store(ctx)
        return Khatma(
            read = khatmaPages(ctx).count { it == '1' },
            fromDay = prefs.getLong(KHATMA_FROM, today()),
            done = prefs.getInt(KHATMAS, 0)
        )
    }

    /** Days with statistics kept, today included: only these count toward an average. */
    fun daysKept(ctx: Context): Int =
        (today() - store(ctx).getLong(SINCE, today()) + 1).coerceAtLeast(1L).toInt()

    /** Pages read a day, averaged over the last [n] days or as many as have been kept. */
    fun average(ctx: Context, n: Int): Float {
        val span = minOf(n, daysKept(ctx))
        return lastDays(ctx, span).sumOf { it.pages.size } / span.toFloat()
    }

    /** Pages a day lately, or 0 until there are enough days to tell. */
    fun pace(ctx: Context): Float = if (daysKept(ctx) < PACE_FROM_DAYS) 0f else average(ctx, PACE_DAYS)

    /** This month from its first day to today, oldest first, today last. */
    fun monthDays(ctx: Context): List<Day> = (monthStart(today(), hijri(ctx))..today()).map { day(ctx, it) }

    /** Months counted by the Hijri calendar, or the Gregorian. */
    fun hijri(ctx: Context) = store(ctx).getBoolean(HIJRI, true)

    fun setHijri(ctx: Context, on: Boolean) = store(ctx).edit { putBoolean(HIJRI, on) }

    /** Whether today's reached goal has had its celebration on the achievements tab. */
    fun celebrated(ctx: Context) = store(ctx).getLong(CELEBRATED, -1L) == today()

    fun setCelebrated(ctx: Context) = store(ctx).edit { putLong(CELEBRATED, today()) }

    /** Pages to read today by the reader's goal; 0 on a day the goal leaves free. */
    fun goal(ctx: Context): Int {
        Surahs.load(ctx)
        val plan = goalPlan(ctx)
        return if (plan.kind == Goal.Kind.KHATMA) khatmaToday(ctx, plan.amount.toLong()) else plan.pagesToday()
    }

    // What is left of the khatma when today began, shared over the days until it is to end
    private fun khatmaToday(ctx: Context, endDay: Long): Int {
        val leftAtDawn = Mushaf.PAGES - khatma(ctx).read + day(ctx, today()).pages.size
        val days = (endDay - today() + 1).coerceAtLeast(1L)
        return Math.ceil(leftAtDawn.coerceAtLeast(0) / days.toDouble()).toInt()
    }

    /** Pages to learn by heart today by the memorisation goal; 0 without one, or on a day it leaves free. */
    fun hifzGoal(ctx: Context): Int = hifzPlan(ctx)?.pagesToday() ?: 0

    /** The memorisation goal, beside the reading goal; null until one is chosen. */
    fun hifzPlan(ctx: Context): Goal? = Goal.decoded(store(ctx).getString(HIFZ_GOAL, null))

    fun setHifzPlan(ctx: Context, goal: Goal?) = store(ctx).edit {
        if (goal == null) remove(HIFZ_GOAL) else putString(HIFZ_GOAL, goal.encoded())
    }

    /** The goal as chosen; a goal kept as a plain number from before is that many pages every day. */
    fun goalPlan(ctx: Context): Goal {
        val prefs = store(ctx)
        Goal.decoded(prefs.getString(GOAL_PLAN, null))?.let { return it }
        val pages = prefs.getInt(GOAL, 0)
        return if (pages > 0) Goal(Goal.Kind.PAGES, pages, emptyList(), Goal.ALL_DAYS, kahf = false) else Goal.DEFAULT
    }

    fun setGoalPlan(ctx: Context, goal: Goal) = store(ctx).edit {
        putString(GOAL_PLAN, goal.encoded())
        remove(GOAL)
    }

    /** Start the khatma over; finished ones stay counted. */
    fun newKhatma(ctx: Context) = store(ctx).edit {
        putString(KHATMA, "")
        putLong(KHATMA_FROM, today())
    }

    // --- sample data, written by the debug build only ---

    internal fun writeDay(ctx: Context, day: Long, record: Day) = save(ctx, day, record)

    internal fun writeKhatma(ctx: Context, pages: Set<Int>, fromDay: Long, done: Int) = store(ctx).edit {
        putString(KHATMA, String(CharArray(Mushaf.PAGES) { if (it + 1 in pages) '1' else '0' }))
        putLong(KHATMA_FROM, fromDay)
        putInt(KHATMAS, done)
    }

    internal fun writeSince(ctx: Context, day: Long) = store(ctx).edit { putLong(SINCE, day) }

    // --- storage ---

    private fun khatmaPages(ctx: Context): CharArray {
        val kept = store(ctx).getString(KHATMA, "").orEmpty()
        return CharArray(Mushaf.PAGES) { kept.getOrNull(it) ?: '0' }
    }

    private fun counts(o: JSONObject?): Map<Int, Int> {
        if (o == null) return emptyMap()
        return o.keys().asSequence().mapNotNull { k -> k.toIntOrNull()?.let { it to o.optInt(k) } }.toMap()
    }

    private fun save(ctx: Context, day: Long, record: Day) {
        val o = JSONObject()
            .put("r", JSONObject(record.pageSec.mapKeys { it.key.toString() }))
            .put("l", JSONObject(record.surahSec.mapKeys { it.key.toString() }))
        if (record.doneByHand) o.put("m", true).put("h", record.handFrom)
        val prefs = store(ctx)
        prefs.edit {
            if (!prefs.contains(SINCE)) putLong(SINCE, day)
            putString(DAY + day, o.toString())
            // Only a new day can push one out of the window, so old ones are dropped then
            if (!prefs.contains(DAY + day)) {
                prefs.all.keys.filter { it.startsWith(DAY) }
                    .filter { (it.removePrefix(DAY).toLongOrNull() ?: day) < day - KEEP_DAYS }
                    .forEach { remove(it) }
            }
        }
    }
}
