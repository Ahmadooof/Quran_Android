package com.readqurantoday.quran

import android.app.Activity
import android.content.Intent
import android.content.res.ColorStateList
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView

/** Index screen: surah list (with play+download), bookmarks, and settings. Returns a page number. */
class SurahListActivity : AppCompatActivity() {

    /* Pane index matches the nav order: 0=surahs, 1=marks, 2=settings. */
    private val paneIds = intArrayOf(R.id.pane_index, R.id.pane_marks, R.id.pane_settings)
    private val navIds  = intArrayOf(R.id.nav_surahs, R.id.nav_marks, R.id.nav_settings)

    private lateinit var panes: List<View>
    private lateinit var navPills: List<View>
    private lateinit var navIcons: List<ImageView>
    private lateinit var navLabels: List<TextView>

    private var tab = 0
    private var surahAdapter: SurahAdapter? = null
    private var watching = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Surahs.load(this)
        Recite.load(this)
        setContentView(R.layout.activity_index)

        panes     = paneIds.map { findViewById<View>(it) }
        navPills  = listOf(R.id.nav_pill_surahs, R.id.nav_pill_marks, R.id.nav_pill_settings)
            .map { findViewById<View>(it) }
        navIcons  = listOf(R.id.nav_icon_surahs, R.id.nav_icon_marks, R.id.nav_icon_settings)
            .map { findViewById<ImageView>(it) }
        navLabels = listOf(R.id.nav_label_surahs, R.id.nav_label_marks, R.id.nav_label_settings)
            .map { findViewById<TextView>(it) }

        navIds.forEachIndexed { i, id -> findViewById<View>(id).setOnClickListener { choose(i) } }
        choose(savedInstanceState?.getInt(TAB) ?: 0)

        buildSurahList()
        wireSearch()
        wireResume()
        settings()
        marks()
    }

    private fun choose(which: Int) {
        tab = which
        panes.forEachIndexed { i, pane ->
            pane.visibility = if (i == which) View.VISIBLE else View.GONE
        }
        /* Chosen tab stays accent; unselected items turn accent on press (no ripple background). */
        val accent = getColor(R.color.accent)
        val muted  = getColor(R.color.text_mute)
        for (i in navIds.indices) {
            val on = i == which
            val tint = if (on) {
                ColorStateList.valueOf(accent)
            } else {
                ColorStateList(
                    arrayOf(intArrayOf(android.R.attr.state_pressed), intArrayOf()),
                    intArrayOf(accent, muted)
                )
            }
            navIcons[i].imageTintList = tint
            navLabels[i].setTextColor(tint)
            /* Selected pill: solid fill. Unselected: ghost pill (press → same fill). */
            navPills[i].setBackgroundResource(
                if (on) R.drawable.nav_pill else R.drawable.nav_pill_press
            )
        }
    }

    override fun onResume() {
        super.onResume()
        Recite.onChange = { runOnUiThread { surahAdapter?.notifyDataSetChanged() } }
        /* Refresh resume card in case the last page changed while in the reader. */
        wireResume()
    }

    override fun onSaveInstanceState(out: Bundle) {
        super.onSaveInstanceState(out)
        out.putInt(TAB, tab)
    }

    override fun onDestroy() {
        super.onDestroy()
        Recite.onChange = null
    }

    private fun buildSurahList() {
        val adapter = SurahAdapter(
            all        = Surahs.list(),
            names      = Mushaf.nameTypeface(this),
            onOpen     = { s ->
                /* While actively playing: skip the first-page scroll; rc.follow() in
                   ReaderActivity.onResume will jump straight to the current word. */
                if (Recite.playing == s.id && Recite.wantsToPlay()) answer(0)
                else answer(s.from)
            },
            onPlay     = { s ->
                if (Recite.playing == s.id) { Recite.toggle(); surahAdapter?.notifyDataSetChanged() }
                else { Recite.start(this, s.id); answer(s.from) }
            },
            onDownload = { s -> keep(s.id) },
            onReciter  = { s -> pickReciter(s) },
            stateOf    = { s -> downloadState(s.id) },
            playingId  = { Recite.playing }
        )
        val list = findViewById<RecyclerView>(R.id.list)
        list.layoutManager = LinearLayoutManager(this)
        list.adapter = adapter
        surahAdapter = adapter
    }

    private fun wireSearch() {
        findViewById<EditText>(R.id.search).addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: Editable?) {
                surahAdapter?.filter(s?.toString().orEmpty())
            }
        })
    }

    private fun wireResume() {
        val card = findViewById<View>(R.id.card_resume)
        val page = Settings.lastPage(this)
        if (page !in 1..604) {
            card.visibility = View.GONE
            return
        }
        card.visibility = View.VISIBLE
        val surahName = Surahs.ofPage(page)?.name.orEmpty()
        findViewById<TextView>(R.id.resume_detail).text =
            getString(R.string.resume_where, surahName, page)
        card.setOnClickListener { answer(page) }
    }

    /* Toggle download: fetch if absent, delete if present. */
    private fun keep(surahId: Int) {
        val voice = Recite.chosen(this)?.id ?: return
        if (Downloads.has(this, surahId, voice)) {
            Downloads.remove(this, surahId, voice)
            surahAdapter?.notifyDataSetChanged()
            return
        }
        Downloads.start(this, surahId, voice)
        pollDownloads()
    }

    private fun downloadState(surahId: Int): Int {
        val voice = Recite.chosen(this)?.id ?: return SurahAdapter.AWAY
        return when {
            Downloads.has(this, surahId, voice)     -> SurahAdapter.KEPT
            Downloads.fetching(this, surahId, voice) -> SurahAdapter.COMING
            else                                      -> SurahAdapter.AWAY
        }
    }

    /* Poll every 1.5s while downloads are in progress; stop when the queue clears. */
    private fun pollDownloads() {
        if (watching) return
        watching = true
        val tick = object : Runnable {
            override fun run() {
                surahAdapter?.notifyDataSetChanged()
                if (Downloads.busy(this@SurahListActivity)) {
                    findViewById<View>(R.id.list).postDelayed(this, 1500)
                } else {
                    watching = false
                }
            }
        }
        findViewById<View>(R.id.list).postDelayed(tick, 1500)
    }

    /* Open the reciter sheet. s is the surah whose row was tapped (null = settings row). */
    private fun pickReciter(s: Surahs.Surah?) {
        val voices = Recite.reciters()
        val now = Recite.chosen(this)?.id
        sheet(
            getString(R.string.reciter),
            voices.map { Choice(it.nameAr, it.noteAr, it.id == now) }
        ) { i ->
            Recite.choose(this, voices[i].id)
            if (Recite.playing != 0) Recite.start(this, Recite.playing)
            surahAdapter?.notifyDataSetChanged()
        }
    }

    private fun settings() {
        /* Reciter row injected into settings pane. */
        val reciterContainer = findViewById<LinearLayout>(R.id.row_reciter)
        val reciterRow = layoutInflater.inflate(R.layout.row_setting, reciterContainer, false)
        reciterRow.findViewById<TextView>(R.id.set_label).setText(R.string.reciter)
        val who = reciterRow.findViewById<TextView>(R.id.set_value)

        fun sayReciter() {
            who.text = Recite.chosen(this)?.let { r ->
                if (r.noteAr.isEmpty()) r.nameAr else r.nameAr + " · " + r.noteAr
            }.orEmpty()
        }
        sayReciter()

        reciterRow.setOnClickListener {
            val voices = Recite.reciters()
            val now = Recite.chosen(this)?.id
            sheet(
                getString(R.string.reciter),
                voices.map { Choice(it.nameAr, it.noteAr, it.id == now) }
            ) { i ->
                Recite.choose(this, voices[i].id)
                sayReciter()
                if (Recite.playing != 0) Recite.start(this, Recite.playing)
                surahAdapter?.notifyDataSetChanged()
            }
        }
        reciterContainer.addView(reciterRow)

        /* Theme and language rows. */
        val themeContainer = findViewById<LinearLayout>(R.id.row_theme)

        val themeRow = layoutInflater.inflate(R.layout.row_setting, themeContainer, false)
        themeRow.findViewById<TextView>(R.id.set_label).setText(R.string.set_theme)
        val themeValue = themeRow.findViewById<TextView>(R.id.set_value)

        fun sayTheme() {
            themeValue.setText(
                when (Settings.theme(this)) {
                    Settings.LIGHT -> R.string.theme_light
                    Settings.DARK  -> R.string.theme_dark
                    else           -> R.string.theme_system
                }
            )
        }
        sayTheme()
        themeRow.setOnClickListener {
            Settings.setTheme(this, (Settings.theme(this) + 1) % 3)
            sayTheme()
        }
        themeContainer.addView(themeRow)

        val langRow = layoutInflater.inflate(R.layout.row_setting, themeContainer, false)
        langRow.findViewById<TextView>(R.id.set_label).setText(R.string.set_language)
        langRow.findViewById<TextView>(R.id.set_value).setText(
            if (Settings.language(this) == "en") R.string.lang_en else R.string.lang_ar
        )
        langRow.setOnClickListener {
            Settings.setLanguage(this, if (Settings.language(this) == "en") "ar" else "en")
        }
        themeContainer.addView(langRow)
    }

    private fun marks() {
        val recent = findViewById<LinearLayout>(R.id.marks_recent)
        recent.removeAllViews()
        val last = Settings.lastPage(this)
        if (last in 1..604) {
            recent.addView(markRow(recent, last, Surahs.ofPage(last)?.name.orEmpty()))
        }

        val saved = findViewById<LinearLayout>(R.id.marks_saved)
        saved.removeAllViews()
        val pages = Settings.marks(this)
        if (pages.isEmpty()) {
            val none = layoutInflater.inflate(R.layout.item_mark, saved, false)
            none.findViewById<TextView>(R.id.mark_surah).setText(R.string.no_marks)
            none.isClickable = false
            saved.addView(none)
            return
        }
        for (p in pages) saved.addView(markRow(saved, p, Surahs.ofPage(p)?.name.orEmpty()))
    }

    private fun markRow(into: LinearLayout, page: Int, said: String): View {
        val row = layoutInflater.inflate(R.layout.item_mark, into, false)
        row.findViewById<TextView>(R.id.mark_page).text = getString(R.string.page_named, page)
        row.findViewById<TextView>(R.id.mark_surah).text = said
        row.setOnClickListener { answer(page) }
        return row
    }

    @Suppress("OVERRIDE_DEPRECATION")
    override fun onBackPressed() {
        finishAffinity()
    }

    private fun answer(page: Int) {
        setResult(Activity.RESULT_OK, Intent().putExtra(PAGE, page))
        finish()
    }

    companion object {
        const val PAGE = "page"
        private const val TAB = "tab"
    }
}
