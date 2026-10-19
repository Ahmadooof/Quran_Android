package com.readqurantoday.quran

import android.app.Activity
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Rect
import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.ImageView
import android.widget.TextView
import androidx.activity.addCallback
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView

/** The menu: the index lists, search, places, achievements and settings. Returns a page number. */
class SurahListActivity : LanguageActivity() {

    /* Pane index matches the nav order: 0=index, 1=search, 2=marks, 3=achievements, 4=settings. */
    private val paneIds      = intArrayOf(R.id.pane_index, R.id.pane_search, R.id.pane_marks, R.id.pane_stats, R.id.pane_settings)
    private val navIds       = intArrayOf(R.id.nav_surahs, R.id.nav_search, R.id.nav_marks, R.id.nav_stats, R.id.nav_settings)
    private val navNames     = intArrayOf(R.string.tab_index, R.string.tab_search, R.string.tab_marks, R.string.tab_achievements, R.string.tab_settings)
    private val iconsFilled  = intArrayOf(R.drawable.ic_surahs, R.drawable.ic_search_filled, R.drawable.ic_bookmark, R.drawable.ic_stats, R.drawable.ic_settings)
    private val iconsOutline = intArrayOf(R.drawable.ic_surahs_outline, R.drawable.ic_search, R.drawable.ic_bookmark_outline, R.drawable.ic_stats_outline, R.drawable.ic_settings_outline)

    // Kept, not made anew, so it remembers where its rings stood
    private val placesPane by lazy {
        PlacesPane(this, findViewById(R.id.marks_segments), findViewById(R.id.marks_pages)) { page -> answer(page) }
    }
    private val statsPane by lazy { StatsPane(this, findViewById(R.id.stats_segments), findViewById(R.id.stats_pages)) }

    private lateinit var panes: List<View>
    private lateinit var navIcons: List<ImageView>
    private lateinit var navLabels: List<TextView>

    private var tab = 0

    /* This screen's player listener, kept so it can clear only itself from Recite's slot. */
    private val heard: () -> Unit = { runOnUiThread { refreshLists() } }

    /* The juz, hizb and page being recited change as the recitation moves, which no state change announces. */
    private val follow = object : Runnable {
        override fun run() {
            if (Recite.playing == 0) return
            index.refresh()
            window.decorView.postDelayed(this, FOLLOW_MS)
        }
    }

    // Read before restyling so weight changes keep the theme's font family
    private val labelFace by lazy { navLabels[0].typeface }
    private lateinit var index: IndexPane
    // Search keeps its own surah list, so typing never disturbs the index
    private lateinit var found: SurahAdapter

    /* The keyboard is up. */
    private var typing = false

    // Whether there is a page to resume, separate from whether the strip shows
    private var canResume = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Surahs.load(this)
        Recite.load(this)
        setContentView(R.layout.activity_index)
        keepToColumn(R.id.search_head, R.id.segments, R.id.search_list, R.id.marks_segments, R.id.stats_segments, R.id.pane_settings, R.id.card_resume)
        // Back from the menu leaves the app rather than returning to the reader behind it
        onBackPressedDispatcher.addCallback(this) { finishAffinity() }
        watchKeyboard()

        panes     = paneIds.map { findViewById<View>(it) }
        val tabs  = navIds.map { findViewById<View>(it) }
        navIcons  = tabs.map { it.findViewById<ImageView>(R.id.nav_icon) }
        navLabels = tabs.map { it.findViewById<TextView>(R.id.nav_label) }
        navLabels.forEachIndexed { i, label -> label.setText(navNames[i]) }

        buildLists()
        wireSearch()
        // A tab already open stays as it is: no second entrance, no rebuild
        navIds.forEachIndexed { i, id -> findViewById<View>(id).setOnClickListener { if (i != tab) choose(i, enter = true) } }
        // The app opens on its first screen at once, without an entrance
        choose(savedInstanceState?.getInt(TAB) ?: 0, enter = false)
        wireResume()
    }

    // Hides nav and resume strip while typing; before Android 11 insets read zero with adjustResize, so the window frame is measured
    private fun watchKeyboard() {
        val root = findViewById<View>(R.id.index_root)
        val seen = Rect()
        root.viewTreeObserver.addOnGlobalLayoutListener {
            val whole = root.rootView.height
            if (whole > 0) {
                val insets = ViewCompat.getRootWindowInsets(root)
                val up = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && insets != null) {
                    insets.isVisible(WindowInsetsCompat.Type.ime())
                } else {
                    root.getWindowVisibleDisplayFrame(seen)
                    // System bars never reach a fifth of the screen; a keyboard always does
                    whole - seen.height() > whole / 5
                }
                if (up != typing) {
                    typing = up
                    sayFooters()
                }
            }
        }
    }

    private fun sayFooters() {
        findViewById<View>(R.id.bottom_nav).visibility =
            if (typing) View.GONE else View.VISIBLE
        findViewById<View>(R.id.card_resume).visibility =
            if (canResume && !typing && resources.getBoolean(R.bool.resume_strip)) View.VISIBLE else View.GONE
    }

    /* Active tab: filled icon + flat accent. Inactive: outlined icon + accent on press, muted at rest. */
    private fun choose(which: Int, enter: Boolean) {
        tab = which
        panes.forEachIndexed { i, pane ->
            pane.visibility = if (i == which) View.VISIBLE else View.GONE
        }
        // Every tab comes in the same way; achievements also fills its rings, so it brings itself in
        if (enter) when (paneIds[which]) {
            R.id.pane_stats -> statsPane.build(StatsPane.Motion.OPEN)
            R.id.pane_marks -> placesPane.rise()
            R.id.pane_settings -> findViewById<ViewGroup>(R.id.settings_groups).riseChildren()
            else -> panes[which].riseWhole()
        }
        if (paneIds[which] != R.id.pane_search) putKeyboardAway()
        val accent = getColor(R.color.accent)
        val muted  = getColor(R.color.text_mute)
        for (i in navIds.indices) {
            val selected = i == which
            navIcons[i].setImageResource(if (selected) iconsFilled[i] else iconsOutline[i])
            val tint = if (selected) {
                ColorStateList.valueOf(accent)
            } else {
                ColorStateList(
                    arrayOf(intArrayOf(android.R.attr.state_pressed), intArrayOf()),
                    intArrayOf(accent, muted)
                )
            }
            navIcons[i].imageTintList = tint
            navLabels[i].setTextColor(tint)
            // Null would reset the labels to the system font
            navLabels[i].setTypeface(labelFace, if (selected) Typeface.BOLD else Typeface.NORMAL)
        }
        /* Each pane has its own ground, so the bars are re-read per tab. */
        sayBars()
    }

    private fun sayBars() {
        showBars(
            roof = topOf(panes.getOrNull(tab)) ?: groundOf(findViewById(R.id.index_root)),
            floor = groundOf(findViewById(R.id.bottom_nav))
        )
    }

    // The reader hides the bars, so they are asked for again here
    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) sayBars()
    }

    override fun onResume() {
        super.onResume()
        Recite.onChange = heard
        refreshLists()
        /* Refresh resume card in case the last page changed while in the reader. */
        wireResume()
        /* Built on every return, so the reading style row shows colours just changed. */
        settings()
        marks()
        stats()
    }

    override fun onSaveInstanceState(out: Bundle) {
        super.onSaveInstanceState(out)
        out.putInt(TAB, tab)
    }

    // Cleared on pause, not destroy, so it cannot wipe the reader's listener set in between
    override fun onPause() {
        super.onPause()
        if (Recite.onChange === heard) Recite.onChange = null
        window.decorView.removeCallbacks(follow)
    }

    private fun buildLists() {
        index = IndexPane(this, surahList(), open = { page -> answer(page) }, pickReciter = ::pickReciter, played = ::refreshLists)
        found = surahList(blankShowsAll = false)
        findViewById<RecyclerView>(R.id.search_list).apply {
            layoutManager = LinearLayoutManager(this@SurahListActivity)
            adapter = found
        }
    }

    private fun surahList(blankShowsAll: Boolean = true) = SurahAdapter(
        all        = Surahs.list(),
        names      = Mushaf.nameTypeface(this),
        onOpen     = { s ->
            // Already playing: the reader jumps to the current word itself
            if (Recite.playing == s.id && Recite.wantsToPlay()) answer(0)
            else answer(s.from)
        },
        onPage     = { page -> answer(page) },
        onVerse    = { surah, ayah -> answer(pageOfAyah(surah, ayah), surah, ayah) },
        onPlay     = { s ->
            if (Recite.playing == s.id) Recite.toggle() else Recite.start(this, s.id)
            if (Recite.wantsToPlay()) warnIfMuted()
            refreshLists()
        },
        // A second tap on what is already reciting pauses it, as on every row
        onPlayPage = { page ->
            if (page == Recite.playingPage(this)) Recite.toggle() else Recite.startPage(this, page)
            if (Recite.wantsToPlay()) warnIfMuted()
            refreshLists()
        },
        onPlayVerse = { surah, ayah ->
            if (Recite.playing == surah && Recite.playingAyah(this) == ayah) Recite.toggle()
            else Recite.startAyah(this, surah, ayah)
            if (Recite.wantsToPlay()) warnIfMuted()
            refreshLists()
        },
        onReciter  = { s -> pickReciter(s) },
        playingId  = { Recite.playing },
        blankShowsAll = blankShowsAll
    )

    private fun refreshLists() {
        index.refresh()
        found.notifyDataSetChanged()
        wireResume()
        // Only follows while there is something to follow
        window.decorView.removeCallbacks(follow)
        if (Recite.playing != 0) window.decorView.postDelayed(follow, FOLLOW_MS)
    }

    // The search tab keeps its last results and waits for a tap on the box; other tabs put the keyboard away
    private fun putKeyboardAway() {
        val box = findViewById<EditText>(R.id.search)
        box.clearFocus()
        WindowInsetsControllerCompat(window, box).hide(WindowInsetsCompat.Type.ime())
    }


    // Exact once Ayat has walked the pages; until then the surah's first page
    private fun pageOfAyah(surah: Int, ayah: Int): Int {
        val exact = if (Ayat.ready) Ayat.pageOf(surah, ayah) else 0
        if (exact in 1..604) return exact
        return Surahs.list().firstOrNull { it.id == surah }?.from ?: 1
    }

    private fun wireSearch() {
        val box = findViewById<EditText>(R.id.search)
        val clear = findViewById<ImageView>(R.id.search_clear)
        clear.imageTintList = ColorStateList.valueOf(getColor(R.color.text_mute))
        val empty = findViewById<View>(R.id.search_empty)
        clear.setOnClickListener { box.text.clear() }
        box.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: Editable?) {
                found.submit(s?.toString().orEmpty())
                clear.visibility = if (s.isNullOrEmpty()) View.GONE else View.VISIBLE
                empty.visibility = if (s.isNullOrEmpty()) View.VISIBLE else View.GONE
            }
        })
    }

    // While a recitation plays the card leads to it instead, and says so; a paused one may be long behind the reader
    private fun wireResume() {
        val card = findViewById<View>(R.id.card_resume)
        val reciting = Recite.wantsToPlay()
        val page = if (reciting) Recite.playingPage(this) else Settings.lastPage(this)
        canResume = page in 1..604
        sayFooters()
        if (!canResume) return

        findViewById<TextView>(R.id.resume_label)
            .setText(if (reciting) R.string.resume_reciter else R.string.resume)

        val surah = Surahs.ofPage(page)
        surah?.let { fillSurahTitle(findViewById(R.id.resume_title), it.id, R.dimen.surah_title) }
        // The title already names the surah, so the line beside it is only the page
        findViewById<TextView>(R.id.resume_detail).text = pageNamed(page, resources)

        // Page 0 tells the reader to follow the live recitation rather than open a fixed page
        card.setOnClickListener { answer(if (reciting) 0 else page) }
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
            refreshLists()
        }
    }

    /* Theme, language, and how a page looks: see SettingsPane. */
    private fun settings() {
        SettingsPane(this, findViewById(R.id.settings_groups)).general(
            openStyle = { startActivity(Intent(this, ReadingStyleActivity::class.java)) },
            openDownloads = { startActivity(Intent(this, DownloadsActivity::class.java)) }
        )
    }


    // Rebuilt on every return: reading changes the history and saved pages
    private fun marks() = placesPane.build()

    // Rebuilt on every return: reading and listening move the numbers, and the rings move with them
    private fun stats() = statsPane.build()

    // [surah] and [ayah] are set when an ayah was picked, so the reader can highlight it
    private fun answer(page: Int, surah: Int = 0, ayah: Int = 0) {
        setResult(Activity.RESULT_OK, Intent().putExtra(PAGE, page).putExtra(SURAH, surah).putExtra(AYAH, ayah))
        finish()
    }

    companion object {
        // The juz, hizb and page rows follow the recitation across their boundaries
        private const val FOLLOW_MS = 1000L

        const val PAGE = "page"
        const val SURAH = "surah"
        const val AYAH = "ayah"
        private const val TAB = "tab"
    }
}
