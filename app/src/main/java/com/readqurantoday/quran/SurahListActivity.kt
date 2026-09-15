package com.readqurantoday.quran

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Rect
import android.graphics.Typeface
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.addCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView

/** Index screen: surah list (with play+download), bookmarks, and settings. Returns a page number. */
class SurahListActivity : LanguageActivity() {

    /* Pane index matches the nav order: 0=surahs, 1=marks, 2=settings. */
    private val paneIds      = intArrayOf(R.id.pane_index, R.id.pane_marks, R.id.pane_settings)
    private val navIds       = intArrayOf(R.id.nav_surahs, R.id.nav_marks, R.id.nav_settings)
    private val iconsFilled  = intArrayOf(R.drawable.ic_surahs, R.drawable.ic_bookmark, R.drawable.ic_settings)
    private val iconsOutline = intArrayOf(R.drawable.ic_surahs_outline, R.drawable.ic_bookmark_outline, R.drawable.ic_settings_outline)

    // Nothing to do with the answer: the player and downloads simply show notifications if allowed
    private val askNotifications = registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    private lateinit var panes: List<View>
    private lateinit var navIcons: List<ImageView>
    private lateinit var navLabels: List<TextView>

    private var tab = 0

    /* This screen's player listener, kept so it can clear only itself from Recite's slot. */
    private val heard: () -> Unit = { runOnUiThread { surahAdapter?.notifyDataSetChanged() } }

    // Read before restyling so weight changes keep the theme's font family
    private val labelFace by lazy { navLabels[0].typeface }
    private var surahAdapter: SurahAdapter? = null

    /* The keyboard is up. */
    private var typing = false

    // Whether there is a page to resume, separate from whether the strip shows
    private var canResume = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Surahs.load(this)
        Recite.load(this)
        setContentView(R.layout.activity_index)
        // Back from the menu leaves the app rather than returning to the reader behind it
        onBackPressedDispatcher.addCallback(this) { finishAffinity() }
        if (savedInstanceState == null) askForNotificationsOnce()
        watchKeyboard()

        panes     = paneIds.map { findViewById<View>(it) }
        navIcons  = listOf(R.id.nav_icon_surahs, R.id.nav_icon_marks, R.id.nav_icon_settings)
            .map { findViewById<ImageView>(it) }
        navLabels = listOf(R.id.nav_label_surahs, R.id.nav_label_marks, R.id.nav_label_settings)
            .map { findViewById<TextView>(it) }

        navIds.forEachIndexed { i, id -> findViewById<View>(id).setOnClickListener { choose(i) } }
        choose(savedInstanceState?.getInt(TAB) ?: 0)

        buildSurahList()
        wireSearch()
        wireResume()
    }

    // Hides nav and resume strip while typing; measured from the window frame because insets read zero with adjustResize
    private fun watchKeyboard() {
        val root = findViewById<View>(R.id.index_root)
        val seen = Rect()
        root.viewTreeObserver.addOnGlobalLayoutListener {
            val whole = root.rootView.height
            if (whole > 0) {
                root.getWindowVisibleDisplayFrame(seen)
                // System bars never reach a fifth of the screen; a keyboard always does
                val up = whole - seen.height() > whole / 5
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
            if (canResume && !typing) View.VISIBLE else View.GONE
    }

    /* Active tab: filled icon + flat accent. Inactive: outlined icon + accent on press, muted at rest. */
    private fun choose(which: Int) {
        tab = which
        panes.forEachIndexed { i, pane ->
            pane.visibility = if (i == which) View.VISIBLE else View.GONE
        }
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
        /* Refresh resume card in case the last page changed while in the reader. */
        wireResume()
        /* Built on every return, so the reading style row shows colours just changed. */
        settings()
        marks()
    }

    override fun onSaveInstanceState(out: Bundle) {
        super.onSaveInstanceState(out)
        out.putInt(TAB, tab)
    }

    // Cleared on pause, not destroy, so it cannot wipe the reader's listener set in between
    override fun onPause() {
        super.onPause()
        if (Recite.onChange === heard) Recite.onChange = null
    }

    private fun buildSurahList() {
        val adapter = SurahAdapter(
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
                if (Recite.playing == s.id) Recite.toggle()
                else Recite.start(this, s.id)
                surahAdapter?.notifyDataSetChanged()
            },
            onReciter  = { s -> pickReciter(s) },
            playingId  = { Recite.playing }
        )
        val list = findViewById<RecyclerView>(R.id.list)
        list.layoutManager = LinearLayoutManager(this)
        list.adapter = adapter
        surahAdapter = adapter
    }

    // Exact once Ayat has walked the pages; until then the surah's first page
    private fun pageOfAyah(surah: Int, ayah: Int): Int {
        val exact = if (Ayat.ready) Ayat.pageOf(surah, ayah) else 0
        if (exact in 1..604) return exact
        return Surahs.list().firstOrNull { it.id == surah }?.from ?: 1
    }

    private fun wireSearch() {
        findViewById<EditText>(R.id.search).addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: Editable?) {
                surahAdapter?.submit(s?.toString().orEmpty())
            }
        })
    }

    private fun wireResume() {
        val card = findViewById<View>(R.id.card_resume)
        val page = Settings.lastPage(this)
        canResume = page in 1..604
        sayFooters()
        if (!canResume) return
        val surah = Surahs.ofPage(page)
        surah?.let { fillSurahTitle(findViewById(R.id.resume_title), it.id, RESUME_TITLE_SP) }
        val at = getString(R.string.head_page, figures(page, resources))
        // English name isolated so it does not pull the separator into its run on an Arabic line
        findViewById<TextView>(R.id.resume_detail).text = surah?.let {
            getString(R.string.surah_meta, android.text.BidiFormatter.getInstance().unicodeWrap(it.english), at)
        } ?: at
        card.setOnClickListener { answer(page) }
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

    /* Theme, language, and how a page looks: see SettingsPane. */
    private fun settings() {
        SettingsPane(this, findViewById(R.id.settings_groups)).general(
            openStyle = { startActivity(Intent(this, ReadingStyleActivity::class.java)) },
            openDownloads = { startActivity(Intent(this, DownloadsActivity::class.java)) }
        )
    }

    // Android 13+ hides player and download notifications until allowed; asked once, on first open
    private fun askForNotificationsOnce() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU || Settings.notificationsAsked(this)) return
        Settings.setNotificationsAsked(this)
        if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) return
        askNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
    }

    // Rebuilt on every return: reading changes the history and saved pages
    private fun marks() {
        PlacesPane(this, findViewById(R.id.places_groups)) { page -> answer(page) }.build()
    }

    // [surah] and [ayah] are set when an ayah was picked, so the reader can highlight it
    private fun answer(page: Int, surah: Int = 0, ayah: Int = 0) {
        setResult(Activity.RESULT_OK, Intent().putExtra(PAGE, page).putExtra(SURAH, surah).putExtra(AYAH, ayah))
        finish()
    }

    companion object {
        const val PAGE = "page"
        private const val RESUME_TITLE_SP = 26f
        const val SURAH = "surah"
        const val AYAH = "ayah"
        private const val TAB = "tab"
    }
}
