package com.readqurantoday.quran

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.TextView
import androidx.appcompat.content.res.AppCompatResources

/** What is memorised: every surah, known whole or in part, page by page; or only the surahs memorised so far. */
class MemorizedActivity : CardsActivity(R.string.hifz_mine) {

    private lateinit var shown: List<Surahs.Surah>
    private lateinit var rows: List<View>

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Surahs.load(this)
        // The surahs known when the screen opened stay listed, so one unmarked here can be marked again
        val knownOnly = intent.getBooleanExtra(KNOWN_ONLY, false)
        val kept = Hifz.bySurah(this)
        shown = if (knownOnly) Surahs.list().filter { Hifz.knownOf(it, kept) > 0 } else Surahs.list()
        if (knownOnly) findViewById<TextView>(R.id.cards_title).setText(R.string.hifz_known_surahs)
        rows = shown.map { surah ->
            layoutInflater.inflate(R.layout.row_hifz_surah, cards, false).apply {
                fillSurahTitle(findViewById(R.id.hifz_title), surah.id, R.dimen.surah_title_row)
            }
        }
        layoutInflater.card(cards, 0, rows)
        refresh()
        sayBars()
    }

    // Filled again in place after each change, so the list keeps where it was scrolled to
    private fun refresh() {
        val kept = Hifz.bySurah(this)
        shown.forEachIndexed { i, surah -> fill(rows[i], surah, kept) }
    }

    private fun fill(row: View, surah: Surahs.Surah, kept: Map<Int, Map<Int, Long>>) {
        val all = surah.to - surah.from + 1
        val done = Hifz.knownOf(surah, kept)
        val whole = done == all
        row.findViewById<TextView>(R.id.hifz_count).text =
            if (done == 0 || whole) pagesSaid(all, resources)
            else getString(R.string.of_count, figures(done, resources), pagesSaid(all, resources))
        row.findViewById<View>(R.id.hifz_track).apply {
            if (done > 0) fillTrack(done.toFloat(), all.toFloat()) else visibility = View.GONE
        }
        // Whole: known from before, all at once. In part: its pages one by one, and those count toward today
        row.findViewById<TextView>(R.id.hifz_full).apply {
            mark(this, whole)
            contentDescription = getString(R.string.hifz_full_said, surah.name)
            setOnClickListener {
                Hifz.setSurah(this@MemorizedActivity, surah, known = !whole)
                refresh()
            }
        }
        row.findViewById<TextView>(R.id.hifz_part).apply {
            // A surah of one page is known whole or not at all
            visibility = if (all == 1) View.GONE else View.VISIBLE
            mark(this, done in 1 until all)
            contentDescription = getString(R.string.hifz_part_said, surah.name)
            setOnClickListener { pickPages(surah) }
        }
    }

    private fun mark(button: TextView, on: Boolean) {
        markChoice(button, on)
        button.setCompoundDrawablesRelative(if (on) tick() else null, null, null, null)
    }

    private fun tick() = AppCompatResources.getDrawable(this, R.drawable.ic_check)?.mutate()?.apply {
        val size = resources.getDimensionPixelSize(R.dimen.mark_button_icon)
        setBounds(0, 0, size, size)
        setTint(getColor(R.color.on_dark))
    }

    private fun pickPages(surah: Surahs.Surah) {
        val was = Hifz.bySurah(this)[surah.id]?.keys.orEmpty()
        val pages = (surah.from..surah.to).toList()
        pickPages(getString(R.string.surah_named, surah.name), getString(R.string.hifz_pages_ask), pages, was) { now ->
            Hifz.setPages(this, surah, marked = now - was, unmarked = was - now)
            refresh()
        }
    }

    companion object {
        private const val KNOWN_ONLY = "known_only"

        fun open(context: Context, knownOnly: Boolean = false) =
            context.startActivity(Intent(context, MemorizedActivity::class.java).putExtra(KNOWN_ONLY, knownOnly))
    }
}
