package com.readqurantoday.quran

import android.app.Activity
import android.content.Intent
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.LinearSmoothScroller
import androidx.recyclerview.widget.PagerSnapHelper
import androidx.recyclerview.widget.RecyclerView

/** Full-screen mushaf reader: 604 pages, edge-to-edge, chrome hidden while reading. */
class ReaderActivity : AppCompatActivity() {

    private val pages = 604

    private lateinit var pager: RecyclerView
    private lateinit var lanes: LinearLayoutManager
    private lateinit var bar: View
    private lateinit var mark: ImageView
    private lateinit var player: View
    private lateinit var btnTheme: ImageView

    private lateinit var rc: RecitationController

    private var bars: WindowInsetsControllerCompat? = null
    private var chrome = false

    /* Status-bar height, settled once; everything about page layout follows from it. */
    private var band = 0
    private var bandSet = false

    /* Navigation-bar inset: keeps player above the nav bar when it is visible. */
    private var foot = 0

    /* Word chosen by long-press but not yet played. */
    private var pendingSurah = 0
    private var pendingFrom  = 0

    private var lastTouchX = 0f
    private var lastTouchY = 0f

    private val fromIndex = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val page = result.data?.getIntExtra(SurahListActivity.PAGE, 0) ?: 0
            when {
                page in 1..pages -> go(page)
                /* page == 0: caller wants us to follow the live audio position. */
                Recite.playing != 0 -> {
                    val on = Ayat.pageOf(rc.readingSurah, rc.litAyah.coerceAtLeast(1))
                    if (on in 1..pages) go(on)
                }
            }
        }
        showChrome(false)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Mushaf.load(this)
        Surahs.load(this)

        setContentView(R.layout.activity_reader)
        pager  = findViewById(R.id.pager)
        bar    = findViewById(R.id.bar)
        mark   = findViewById(R.id.mark)
        player = findViewById(R.id.player)
        player.elevation = 8 * resources.displayMetrics.density

        rc = RecitationController(
            context     = this,
            tickView    = pager,
            pageCount   = pages,
            currentPage = ::page,
            onChanged   = ::sayPlayer,
            onNavigate  = ::go,
            onLight     = ::lit,
            onStopped   = {
                player.visibility = View.GONE
                pendingSurah = 0
                lit(-1, -1, -1)
            }
        )

        btnTheme = findViewById(R.id.btn_theme)

        dressWindow()
        buildPager()
        buildSwatches()

        sayThemeBtn()
        btnTheme.setOnClickListener { cycleTheme() }

        findViewById<View>(R.id.btn_back).setOnClickListener { toMenu() }
        mark.setOnClickListener { Settings.toggleMark(this, page()); sayMark(page()) }

        wirePlayer()

        val last = Settings.lastPage(this).let { if (it in 1..pages) it else 2 }
        go(last)
        sayMark(last)
        showChrome(false)

        if (savedInstanceState == null) {
            fromIndex.launch(Intent(this, SurahListActivity::class.java))
        }
    }

    // --- pager ---

    /* RecyclerView + snap helper instead of ViewPager2: gives control over settle speed. */
    private fun buildPager() {
        lanes = LinearLayoutManager(this, RecyclerView.HORIZONTAL, false)
        pager.layoutManager = lanes
        pager.adapter = Pages()
        pager.setHasFixedSize(true)
        Snap().attachToRecyclerView(pager)

        pager.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            private var settled = -1
            override fun onScrollStateChanged(view: RecyclerView, state: Int) {
                if (state != RecyclerView.SCROLL_STATE_IDLE) return
                val at = lanes.findFirstCompletelyVisibleItemPosition()
                if (at == RecyclerView.NO_POSITION || at == settled) return
                settled = at
                val page = at + 1
                sayMark(page)
                Settings.setLastPage(this@ReaderActivity, page)
                Mushaf.warm(this@ReaderActivity, page)
                Mushaf.keepOnly((page - 3)..(page + 4))
            }
        })
    }

    private inner class Snap : PagerSnapHelper() {
        override fun createScroller(manager: RecyclerView.LayoutManager) =
            object : LinearSmoothScroller(this@ReaderActivity) {
                /* 25ms/inch ≈ 4× the default — pages arrive rather than drift. */
                override fun calculateSpeedPerPixel(metrics: android.util.DisplayMetrics) =
                    25f / metrics.densityDpi

                override fun onTargetFound(target: View, state: RecyclerView.State, action: Action) {
                    val move = calculateDxToMakeVisible(target, SNAP_TO_START)
                    val time = calculateTimeForDeceleration(Math.abs(move))
                    if (time > 0) action.update(-move, 0, time.coerceAtMost(220), mDecelerateInterpolator)
                }
            }
    }

    private inner class Pages : RecyclerView.Adapter<Holder>() {
        override fun getItemCount() = pages
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
            val v = MushafPageView(parent.context)
            v.layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            v.setOnClickListener { showChrome(!chrome) }
            v.setOnLongClickListener { view -> offer(view as MushafPageView, lastTouchX, lastTouchY); true }
            v.setOnTouchListener { _, e -> lastTouchX = e.x; lastTouchY = e.y; false }
            return Holder(v)
        }
        override fun onBindViewHolder(holder: Holder, position: Int) {
            (holder.itemView as MushafPageView).show(position + 1)
        }
    }

    class Holder(v: View) : RecyclerView.ViewHolder(v)

    private fun page() = (lanes.findFirstCompletelyVisibleItemPosition()
        .takeIf { it != RecyclerView.NO_POSITION } ?: 0) + 1

    private fun go(page: Int) {
        lanes.scrollToPositionWithOffset(page - 1, 0)
        Mushaf.warm(this, page)
    }

    private fun toMenu() = fromIndex.launch(Intent(this, SurahListActivity::class.java))

    // --- window ---

    /* Edge-to-edge: system bars hidden while reading, shown on tap. Player is independent. */
    private fun dressWindow() {
        WindowCompat.setDecorFitsSystemWindows(window, false)

        /* Measure the status-bar height once; never recompute on inset change. */
        if (!bandSet) {
            bandSet = true
            band = topBand()
            pager.setPadding(0, band, 0, 0)
            liftBar()
        }

        ViewCompat.setOnApplyWindowInsetsListener(bar) { _, insets ->
            val cut = insets.displayCutout?.safeInsetTop ?: 0
            if (cut > band) { band = cut; pager.setPadding(0, band, 0, 0); liftBar() }
            insets
        }

        ViewCompat.setOnApplyWindowInsetsListener(player) { _, insets ->
            foot = insets.getInsets(WindowInsetsCompat.Type.navigationBars()).bottom
            seatPlayer()
            insets
        }

        bars = WindowInsetsControllerCompat(window, window.decorView).apply {
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }

    private fun liftBar() {
        val air = (8 * resources.displayMetrics.density).toInt()
        bar.setPadding(bar.paddingLeft, band + air, bar.paddingRight, air)
    }

    /* Keeps the player above the navigation bar whenever it is visible. */
    private fun seatPlayer() {
        val seat = player.layoutParams as FrameLayout.LayoutParams
        if (seat.bottomMargin == foot) return
        seat.bottomMargin = foot
        player.requestLayout()
    }

    /* One tap hides/shows all controls together: top bar and player bar. */
    private fun showChrome(on: Boolean) {
        chrome = on
        bar.visibility = if (on) View.VISIBLE else View.GONE
        if (!on) {
            player.visibility = View.GONE
        } else if (Recite.playing != 0 || pendingSurah != 0) {
            showPlayer()
        }
        seatPlayer()

        val colour = getColor(if (on) R.color.chrome else R.color.paper)
        window.statusBarColor     = colour
        window.navigationBarColor = colour

        val light = !night()
        bars?.isAppearanceLightStatusBars     = light
        bars?.isAppearanceLightNavigationBars = light

        bars?.let {
            if (on) it.show(WindowInsetsCompat.Type.systemBars())
            else    it.hide(WindowInsetsCompat.Type.systemBars())
        }
    }

    private fun night() =
        (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
            Configuration.UI_MODE_NIGHT_YES

    private fun topBand(): Int {
        val id = resources.getIdentifier("status_bar_height", "dimen", "android")
        return if (id > 0) resources.getDimensionPixelSize(id)
               else (24 * resources.displayMetrics.density).toInt()
    }

    private fun sayMark(page: Int) {
        val marked = Settings.marked(this, page)
        mark.setImageResource(if (marked) R.drawable.ic_bookmark else R.drawable.ic_bookmark_off)
        mark.imageTintList = ColorStateList.valueOf(
            getColor(if (marked) R.color.accent else R.color.text_mute)
        )
    }

    // --- recitation ---

    /*
     * Long-press: highlight the pressed word and show the player bar.
     * Audio does NOT start — the user must tap Play.
     * If audio is already running, it seeks to the new word instead.
     */
    private fun offer(view: MushafPageView, x: Float, y: Float) {
        val word = view.wordUnder(x, y) ?: return
        val surah = word[0]; val ayah = word[1]; val w = word[2]
        if (surah <= 0 || ayah <= 0) return

        val voice = Recite.chosen(this)?.id ?: return
        val timing = Timing.of(this, surah, voice)
        if (timing == null) { notice(getString(R.string.no_timing)); return }

        /* Seek to the exact word so the highlight is immediate and correct. */
        val from = timing.wordSpan(ayah, w)?.get(0) ?: timing.startOf(ayah)

        lit(surah, ayah, w)
        rc.litAyah = ayah
        rc.litWord = w
        rc.until   = 0

        if (Recite.playing != 0) {
            /* Audio already running: seek to the new word without stopping. */
            if (Recite.playing == surah && rc.reading != null) {
                rc.startedAt = from
                Recite.seek(from)
                if (!Recite.wantsToPlay()) Recite.toggle()
                rc.follow()
            } else {
                rc.start(surah, from)
            }
        } else {
            /* Not yet playing: remember where to start; user will tap Play. */
            pendingSurah = surah
            pendingFrom  = from
        }

        showPlayer()
        sayPlayer()
    }

    private fun showPlayer() {
        seatPlayer()
        player.visibility = View.VISIBLE
    }

    private fun sayPlayer() {
        val isPlaying = Recite.wantsToPlay()
        findViewById<ImageView>(R.id.p_play_icon)
            .setImageResource(if (isPlaying) R.drawable.ic_pause else R.drawable.ic_play)
        findViewById<android.widget.TextView>(R.id.p_play_label)
            .setText(if (isPlaying) R.string.stop else R.string.play)
        val alpha = if (Recite.repeat != Recite.ONCE) 0xFF else 0x80
        val white = (alpha shl 24) or 0xFFFFFF
        findViewById<ImageView>(R.id.p_repeat_icon)
            .imageTintList = ColorStateList.valueOf(white)
    }

    private fun wirePlayer() {
        /* Tapping the bar body navigates to the current word's page. */
        player.setOnClickListener {
            if (Recite.playing == 0) return@setOnClickListener
            val on = Ayat.pageOf(rc.readingSurah, rc.litAyah.coerceAtLeast(1))
            if (on in 1..pages) go(on)
        }

        /* Play: start from the pending position, or toggle if already running. */
        findViewById<View>(R.id.p_play).setOnClickListener {
            if (Recite.playing == 0) {
                if (pendingSurah > 0) {
                    rc.start(pendingSurah, pendingFrom)
                    pendingSurah = 0
                    showPlayer()
                    sayPlayer()
                }
                return@setOnClickListener
            }
            Recite.toggle()
            if (Recite.wantsToPlay()) rc.follow()
            sayPlayer()
        }

        /* Locate: go to the current (or pending) word's page. */
        findViewById<View>(R.id.p_locate).setOnClickListener {
            val surah = if (Recite.playing != 0) rc.readingSurah else pendingSurah
            val ayah  = rc.litAyah.coerceAtLeast(1)
            val on = Ayat.pageOf(surah, ayah)
            if (on in 1..pages) go(on)
        }

        /* Reciter: pick a voice; if playing, restart from the current word. */
        findViewById<View>(R.id.p_reciter).setOnClickListener {
            val surah = if (Recite.playing != 0) rc.readingSurah else pendingSurah
            if (surah <= 0) return@setOnClickListener
            val voices = Recite.reciters()
            val now = Recite.chosen(this)?.id

            sheet(
                getString(R.string.reciter),
                voices.map { Choice(it.nameAr, it.noteAr, it.id == now) }
            ) { i ->
                val id = voices[i].id
                if (id == now) return@sheet
                Recite.choose(this, id)

                if (Recite.playing != 0) {
                    val keepAyah   = rc.litAyah
                    val keepWord   = rc.litWord
                    val wasPlaying = Recite.wantsToPlay()
                    val fresh = Timing.of(this, rc.readingSurah, id)
                    val span  = if (keepWord >= 0 && keepAyah > 0) fresh?.wordSpan(keepAyah, keepWord) else null
                    rc.until = 0
                    val from = span?.get(0) ?: if (keepAyah > 0) fresh?.startOf(keepAyah) ?: 0 else 0
                    rc.start(rc.readingSurah, from, wasPlaying)
                } else {
                    /* Recalculate pending start for the new voice. */
                    val fresh = Timing.of(this, surah, id)
                    pendingFrom = fresh?.wordSpan(rc.litAyah, rc.litWord)?.get(0)
                        ?: fresh?.startOf(rc.litAyah.coerceAtLeast(1)) ?: 0
                }
                sayPlayer()
            }
        }

        findViewById<View>(R.id.p_repeat).setOnClickListener {
            val modes = listOf(R.string.repeat_off, R.string.repeat_ayah, R.string.repeat_surah)
            sheet(
                getString(R.string.repeat),
                modes.mapIndexed { i, said -> Choice(getString(said), on = i == Recite.repeat) }
            ) { i ->
                Recite.repeat = i
                sayPlayer()
            }
        }

        findViewById<View>(R.id.p_close).setOnClickListener {
            Recite.stop()
            rc.stop()
            player.visibility = View.GONE
            pendingSurah = 0
        }
    }

    // --- highlight colours and reader controls ---

    private val swatchColors = intArrayOf(
        0xFFA4161A.toInt(),  // dark red (default)
        0xFF1053A8.toInt(),  // deep blue
        0xFF00695C.toInt(),  // teal
        0xFF2E7D32.toInt(),  // forest green
        0xFF6A1B9A.toInt(),  // purple
        0xFFE65100.toInt(),  // deep orange
    )

    private lateinit var swatchViews: List<ImageView>
    private lateinit var ayahCircle: ImageView

    private fun buildSwatches() {
        val row = findViewById<LinearLayout>(R.id.swatch_row)
        val d = resources.displayMetrics.density
        val sz  = (26 * d).toInt()
        val gap = (4 * d).toInt()

        /* --- text highlight presets --- */
        swatchViews = swatchColors.map { color ->
            ImageView(this).also { iv ->
                iv.setImageResource(R.drawable.ic_circle)
                iv.imageTintList = ColorStateList.valueOf(color)
                iv.layoutParams = LinearLayout.LayoutParams(sz, sz).apply { setMargins(gap, 0, gap, 0) }
                iv.setOnClickListener {
                    Settings.setHighlightColor(this, color)
                    applyHighlight(); markSwatch(color)
                }
                row.addView(iv)
            }
        }

        /* "+" — text colour custom picker */
        addPickerBtn(row, gap).setOnClickListener {
            showColorPicker(this, Settings.highlightColor(this)) { color ->
                Settings.setHighlightColor(this, color)
                applyHighlight(); markSwatch(color)
            }
        }

        row.addView(divider(d))

        /* --- Ayah number colour circle + picker --- */
        ayahCircle = ImageView(this).also { iv ->
            iv.setImageResource(R.drawable.ic_circle)
            iv.imageTintList = ColorStateList.valueOf(Settings.resolvedAyahColor(this))
            iv.layoutParams = LinearLayout.LayoutParams(sz, sz).apply { setMargins(gap * 2, 0, gap, 0) }
            row.addView(iv)
        }

        addPickerBtn(row, gap).setOnClickListener {
            showColorPicker(this, Settings.resolvedAyahColor(this)) { color ->
                Settings.setAyahColor(this, color)
                ayahCircle.imageTintList = ColorStateList.valueOf(color)
                applyAyahColor()
            }
        }

        markSwatch(Settings.highlightColor(this))
    }

    private fun addPickerBtn(row: LinearLayout, gap: Int): TextView {
        val d = resources.displayMetrics.density
        return TextView(this).apply {
            text = "+"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            setTextColor(0x99FFFFFF.toInt())
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(
                (28 * d).toInt(), LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { setMargins(0, 0, gap, 0) }
            row.addView(this)
        }
    }

    private fun divider(d: Float): View = View(this).apply {
        setBackgroundColor(0x33FFFFFF)
        layoutParams = LinearLayout.LayoutParams((1 * d).toInt(), (20 * d).toInt()).apply {
            gravity = Gravity.CENTER_VERTICAL
            setMargins((6 * d).toInt(), 0, (6 * d).toInt(), 0)
        }
    }

    private fun markSwatch(selected: Int) {
        swatchViews.forEachIndexed { i, iv ->
            iv.background = if (swatchColors[i] == selected)
                resources.getDrawable(R.drawable.swatch_ring, theme) else null
        }
    }

    private fun applyHighlight() {
        val color = Settings.highlightColor(this)
        for (i in 0 until pager.childCount) {
            (pager.getChildAt(i) as? MushafPageView)?.setHighlight(color)
        }
    }

    private fun applyAyahColor() {
        val color = Settings.resolvedAyahColor(this)
        for (i in 0 until pager.childCount) {
            (pager.getChildAt(i) as? MushafPageView)?.setAyahColor(color)
        }
    }

    // --- theme toggle ---

    private fun cycleTheme() {
        Settings.setTheme(this, if (night()) Settings.LIGHT else Settings.DARK)
        /* AppCompatDelegate triggers recreation; no further work needed here. */
    }

    /* The icon shows the side you would switch to, not the side you are on. */
    private fun sayThemeBtn() {
        btnTheme.setImageResource(if (night()) R.drawable.ic_sun else R.drawable.ic_moon)
    }

    private fun lit(surah: Int, ayah: Int, word: Int) {
        for (i in 0 until pager.childCount) {
            (pager.getChildAt(i) as? MushafPageView)?.light(surah, ayah, word)
        }
    }

    // --- lifecycle ---

    override fun onResume() {
        super.onResume()
        delegate.applyDayNight()
        sayMark(page())
        /* Settings may have changed the colours while we were away. */
        applyHighlight()
        applyAyahColor()
        markSwatch(Settings.highlightColor(this))
        rc.syncWithRecite()

        Recite.onChange = {
            if (Recite.wantsToPlay()) rc.follow()
            sayPlayer()
        }
        if (Recite.playing != 0) {
            rc.follow()
            showPlayer()
            sayPlayer()
        }
    }

    override fun onPause() {
        super.onPause()
        Recite.onChange = null
    }

    /* Re-apply chrome state on every focus change; the request is dropped without focus. */
    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) showChrome(chrome)
    }

    override fun onBackPressed() {
        toMenu()
    }
}
