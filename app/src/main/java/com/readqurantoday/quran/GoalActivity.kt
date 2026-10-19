package com.readqurantoday.quran

import android.app.Activity
import android.content.Intent
import android.content.res.ColorStateList
import android.os.Bundle
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView

/** One screen to set a goal: ready-made ones, a custom one, and the goal written out over the save button. */
class GoalActivity : CardsActivity(R.string.goal_title) {

    private val hifz by lazy { intent.getBooleanExtra(HIFZ, false) }
    private lateinit var draft: Goal

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Surahs.load(this)
        if (hifz) findViewById<TextView>(R.id.cards_title).setText(R.string.hifz_goal_title)
        val kept = if (hifz) Stats.hifzPlan(this) ?: presets().first().first else Stats.goalPlan(this)
        draft = if (intent.getBooleanExtra(KHATMA, false) && kept.kind != Goal.Kind.KHATMA) startOf(Goal.Kind.KHATMA) else kept
        build()
        sayBars()
    }

    // Ready-made goals, each with what it comes to beside it if that needs saying
    private fun presets(): List<Pair<Goal, String>> =
        if (hifz) listOf(pages(1) to "", pages(2) to "")
        else listOf(
            Goal.DEFAULT to "",
            withKahf(BAQARAH) to "",
            Goal(Goal.Kind.JUZ, 1, emptyList(), Goal.ALL_DAYS, kahf = true) to getString(R.string.stats_goal_month)
        )

    private fun pages(n: Int) = Goal(Goal.Kind.PAGES, n, emptyList(), Goal.ALL_DAYS, kahf = false)
    private fun withKahf(surah: Int) = Goal(Goal.Kind.SURAHS, 0, listOf(surah), Goal.ALL_DAYS, kahf = true)

    // A kind's own starting amount, keeping the days already chosen
    private fun startOf(kind: Goal.Kind): Goal = when (kind) {
        Goal.Kind.PAGES -> draft.copy(kind = kind, amount = 10, surahs = emptyList())
        Goal.Kind.JUZ -> draft.copy(kind = kind, amount = 1, surahs = emptyList())
        Goal.Kind.SURAHS -> draft.copy(kind = kind, amount = 0, surahs = listOf(BAQARAH))
        Goal.Kind.KHATMA -> Goal(kind, khatmaEnd(KHATMA_MONTH), emptyList(), Goal.ALL_DAYS, kahf = false)
    }

    // A khatma that ends [days] from today, today counted
    private fun khatmaEnd(days: Int) = (Stats.today() + days - 1).toInt()

    // Rebuilt on each change; the summary sits last so nothing above it moves under the finger
    private fun build() {
        cards.removeAllViews()
        layoutInflater.card(cards, R.string.goal_ready, presets().map { (goal, note) -> readyRow(goal, note) })
        layoutInflater.card(cards, R.string.goal_own, ownRows())
        layoutInflater.card(cards, 0, listOf(summary()))
        cards.addView(saveButton())
        if (hifz && Stats.hifzPlan(this) != null) layoutInflater.card(cards, 0, listOf(
            (layoutInflater.inflate(R.layout.row_setting_action, cards, false) as TextView).apply {
                setText(R.string.hifz_stop)
                setOnClickListener {
                    Stats.setHifzPlan(this@GoalActivity, null)
                    finish()
                }
            }
        ))
    }

    private fun summary(): View = layoutInflater.inflate(R.layout.part_goal_summary, cards, false).apply {
        findViewById<TextView>(R.id.goal_summary).text = if (valid()) draft.said(this@GoalActivity) else getString(R.string.goal_pick_day)
        // A khatma's pages a day follow from its end; said here so the choice is not blind
        if (draft.kind == Goal.Kind.KHATMA) findViewById<TextView>(R.id.goal_summary_note).apply {
            visibility = View.VISIBLE
            text = getString(R.string.khatma_per_day, pagesSaid(khatmaDaily(), resources))
        }
    }

    private fun khatmaDaily(): Int {
        val left = Mushaf.PAGES - Stats.khatma(this).read
        val days = (draft.amount - Stats.today() + 1).coerceAtLeast(1L)
        return Math.ceil(left / days.toDouble()).toInt()
    }

    private fun readyRow(goal: Goal, note: String): View = layoutInflater.inflate(R.layout.item_sheet_row, cards, false).apply {
        findViewById<TextView>(R.id.sheet_label).text = goal.said(this@GoalActivity)
        findViewById<TextView>(R.id.sheet_note).apply { text = note; visibility = if (note.isEmpty()) View.GONE else View.VISIBLE }
        findViewById<View>(R.id.sheet_tick).visibility = if (goal == draft) View.VISIBLE else View.INVISIBLE
        setOnClickListener {
            draft = goal
            build()
        }
    }

    // --- a goal of one's own ---

    private fun ownRows(): List<View> {
        val rows = mutableListOf<View>()
        if (!hifz) rows += kinds()
        rows += when (draft.kind) {
            Goal.Kind.PAGES -> stepper(R.string.goal_amount_pages, draft.amount, 1..Mushaf.PAGES) { draft = draft.copy(amount = it) }
            Goal.Kind.JUZ -> stepper(R.string.goal_amount_juz, draft.amount, 1..JUZ) { draft = draft.copy(amount = it) }
            Goal.Kind.SURAHS -> surahRow()
            Goal.Kind.KHATMA -> stepper(R.string.goal_amount_days, (draft.amount - Stats.today() + 1).toInt().coerceIn(1, KHATMA_LONGEST),
                1..KHATMA_LONGEST) { draft = draft.copy(amount = khatmaEnd(it)) }
        }
        // A khatma runs every day until it ends, so it has no days to pick
        if (draft.kind != Goal.Kind.KHATMA) {
            rows += days()
            if (!hifz) rows += layoutInflater.switchRow(cards, getString(R.string.goal_add_kahf), draft.kahf) { on ->
                draft = draft.copy(kahf = on)
                cards.postDelayed({ build() }, SWITCH_SETTLE_MS)
            }
        }
        return rows
    }

    // What to read: pages, juz, a surah or a khatma
    private fun kinds(): View = layoutInflater.inflate(R.layout.part_segments, cards, false).apply {
        background = null
        val face = findViewById<TextView>(R.id.seg_1).typeface
        listOf(
            R.id.seg_1 to Goal.Kind.PAGES, R.id.seg_2 to Goal.Kind.JUZ,
            R.id.seg_3 to Goal.Kind.SURAHS, R.id.seg_4 to Goal.Kind.KHATMA
        ).forEach { (id, kind) ->
            findViewById<TextView>(id).apply {
                setText(KIND_NAMES.getValue(kind))
                markSegment(draft.kind == kind, face)
                setOnClickListener {
                    if (draft.kind != kind) {
                        draft = startOf(kind)
                        build()
                    }
                }
            }
        }
    }

    private fun stepper(label: Int, value: Int, range: IntRange, set: (Int) -> Unit): View =
        stepperRow(cards, label, value, range) { set(it); build() }

    private fun surahRow(): View = layoutInflater.inflate(R.layout.row_progress, cards, false).apply {
        findViewById<TextView>(R.id.prog_label).setText(R.string.goal_amount_surah)
        findViewById<TextView>(R.id.prog_value).text = draft.surahs.mapNotNull { Surahs.of(it)?.name }
            .joinToString(getString(R.string.and_join))
        opens {
            sheet(getString(R.string.goal_amount_surah), Surahs.list().map { Choice(it.name, on = draft.surahs == listOf(it.id)) }) { i ->
                draft = draft.copy(surahs = listOf(Surahs.list()[i].id))
                build()
            }
        }
    }

    // The days of the week, Saturday first, each a button that is filled when chosen
    private fun days(): View = layoutInflater.inflate(R.layout.part_goal_days, cards, false).apply {
        val line = findViewById<LinearLayout>(R.id.goal_days)
        val names = resources.getStringArray(R.array.weekdays_short)
        Goal.WEEK.forEach { weekday ->
            val bit = Goal.bit(weekday)
            val on = draft.days and bit != 0
            line.addView((layoutInflater.inflate(R.layout.item_day_choice, line, false) as TextView).apply {
                text = names[weekday - java.util.Calendar.SUNDAY]
                contentDescription = Goal.weekdayName(weekday, resources)
                markChoice(this, on)
                setOnClickListener {
                    draft = draft.copy(days = draft.days xor bit)
                    build()
                }
            })
        }
    }

    // --- keeping it ---

    // A goal on no day at all is no goal
    private fun valid() = draft.kind == Goal.Kind.KHATMA || draft.days != 0 || draft.kahf

    private fun saveButton(): View = (layoutInflater.inflate(R.layout.part_primary_button, cards, false) as TextView).apply {
        setText(R.string.goal_keep)
        isEnabled = valid()
        alpha = if (isEnabled) 1f else DISABLED
        setOnClickListener {
            if (hifz) Stats.setHifzPlan(this@GoalActivity, draft) else Stats.setGoalPlan(this@GoalActivity, draft)
            finish()
        }
    }

    companion object {
        private const val HIFZ = "hifz"
        private const val KHATMA = "khatma"
        private const val BAQARAH = 2
        private const val JUZ = 30
        private const val KHATMA_MONTH = 30
        private const val KHATMA_LONGEST = 365
        private const val DISABLED = 0.4f
        // The knob finishes its slide before the screen is rebuilt
        private const val SWITCH_SETTLE_MS = 250L
        private val KIND_NAMES = mapOf(
            Goal.Kind.PAGES to R.string.kind_pages, Goal.Kind.JUZ to R.string.kind_juz,
            Goal.Kind.SURAHS to R.string.kind_surah, Goal.Kind.KHATMA to R.string.kind_khatma
        )

        /** Opens the reading goal, or the memorising one with [hifz]; [khatma] starts on a khatma. */
        fun open(from: Activity, hifz: Boolean = false, khatma: Boolean = false) =
            from.startActivity(Intent(from, GoalActivity::class.java).putExtra(HIFZ, hifz).putExtra(KHATMA, khatma))
    }
}
