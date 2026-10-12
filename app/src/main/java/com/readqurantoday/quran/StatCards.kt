package com.readqurantoday.quran

import android.app.Activity
import android.content.res.ColorStateList
import android.content.res.Resources
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView

/** Building parts for the achievements tabs; remembers where each figure stood so rebuilds animate from there. */
class StatCards(val host: Activity, private val parent: ViewGroup) {

    val res: Resources = host.resources
    val blow: LayoutInflater = host.layoutInflater

    private val shares = HashMap<String, Float>()
    private val figuresShown = HashMap<String, Int>()

    private var motion = StatsPane.Motion.UPDATE
    private var moving = false

    /** Called before each build: [motion] says whether this one plays the whole entrance. */
    fun begin(motion: StatsPane.Motion) {
        this.motion = motion
        moving = motionOn(host)
    }

    /** Whether things that grow in, such as the week's bars, should grow this time. */
    val growing get() = moving && motion == StatsPane.Motion.OPEN

    fun row(label: String, value: String, note: String = ""): View =
        blow.inflate(R.layout.row_progress, parent, false).apply {
            findViewById<TextView>(R.id.prog_label).text = label
            findViewById<TextView>(R.id.prog_value).text = value
            findViewById<TextView>(R.id.prog_note).apply { text = note; visibility = if (note.isEmpty()) View.GONE else View.VISIBLE }
        }

    fun action(label: String, act: () -> Unit): View =
        (blow.inflate(R.layout.row_setting_action, parent, false) as TextView).also {
            it.text = label
            it.setOnClickListener { act() }
        }

    /** The goal as written, with the way to change it. */
    fun changeRow(said: String, change: () -> Unit): View =
        row(host.getString(R.string.goal_title), host.getString(R.string.goal_change), said).apply { opens(change) }

    // --- gauges ---

    /** A single ring with its figure inside and words beside it. */
    fun hero(key: String, count: Int, say: (Int) -> String, unit: String, title: String, line: String, note: String): View =
        blow.inflate(R.layout.part_stat_hero, parent, false).apply {
            countFigure(findViewById(R.id.hero_value), key, count, say)
            findViewById<TextView>(R.id.hero_unit).apply { text = unit; visibility = if (unit.isEmpty()) View.GONE else View.VISIBLE }
            findViewById<TextView>(R.id.hero_title).apply { text = title; visibility = if (title.isEmpty()) View.GONE else View.VISIBLE }
            findViewById<TextView>(R.id.hero_line).text = line
            findViewById<TextView>(R.id.hero_note).apply { text = note; visibility = if (note.isEmpty()) View.GONE else View.VISIBLE }
        }

    fun fillHero(hero: View, key: String, done: Float, total: Float) = fillRing(hero.findViewById(R.id.hero_ring), key, done, total)

    /** Up to three rings side by side, with a line beneath; fill them with [fillRing]. */
    fun rings(): View = blow.inflate(R.layout.part_rings, parent, false)

    /** One of [rings]: [label] says when or what it is; [said], beneath it, what its figure counts, if that needs saying. */
    fun fillRing(ring: View, key: String, done: Int, total: Int, label: Int, said: Int, unit: String,
                 say: (Int) -> String = { if (it == 0) none(res) else figures(it, res) }) {
        countFigure(ring.findViewById(R.id.ring_value), key, done, say)
        ring.findViewById<TextView>(R.id.ring_unit).apply { text = unit; visibility = if (unit.isEmpty()) View.GONE else View.VISIBLE }
        ring.findViewById<TextView>(R.id.ring_label).setText(label)
        ring.findViewById<TextView>(R.id.ring_said).apply { if (said == 0) visibility = View.GONE else setText(said) }
        fillRing(ring.findViewById(R.id.ring), key, done.toFloat(), total.toFloat())
    }

    // From empty when the tab opens, from where it stood on an update, at once when motion is off
    private fun fillRing(ring: RingView, key: String, done: Float, total: Float) {
        val share = if (total > 0f) (done / total).coerceIn(0f, 1f) else 0f
        // A first build, with nothing shown before it, simply shows the value
        val from = if (!moving) null else if (motion == StatsPane.Motion.OPEN) 0f else shares[key]
        ring.show(done, total, from)
        shares[key] = share
    }

    private fun countFigure(view: TextView, key: String, value: Int, say: (Int) -> String) {
        val from = if (motion == StatsPane.Motion.OPEN) 0 else figuresShown[key] ?: value
        if (moving && from != value) view.countTo(from, value, say) else view.text = say(value)
        figuresShown[key] = value
    }

    /** A share of the whole mushaf in hundredths; a page or two already shows, never as nought. */
    fun shareOf(pages: Int): Int = if (pages == 0) 0 else maxOf(1, pages * 100 / Mushaf.PAGES)

    fun percentSaid(n: Int): String = if (n == 0) none(res) else host.getString(R.string.percent, figures(n, res))

    // --- tiles ---

    class Tile(val icon: Int, val value: String, val label: String, val trend: ((View) -> Unit)? = null)

    /** Two tiles side by side, as tall as each other. */
    fun pair(a: Tile, b: Tile): View =
        blow.inflate(R.layout.part_stat_pair, parent, false).apply {
            fill(findViewById(R.id.tile_a), a)
            fill(findViewById(R.id.tile_b), b)
        }

    private fun fill(tile: View, t: Tile) {
        tile.findViewById<ImageView>(R.id.tile_icon).apply {
            setImageResource(t.icon)
            imageTintList = ColorStateList.valueOf(host.getColor(R.color.accent))
        }
        tile.findViewById<TextView>(R.id.tile_value).text = t.value
        tile.findViewById<TextView>(R.id.tile_label).text = t.label
        t.trend?.invoke(tile.findViewById(R.id.tile_trend))
    }

    companion object {
        const val WEEK = 7
    }
}
