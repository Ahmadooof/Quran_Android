package com.readqurantoday.quran

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Intent
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.os.Bundle
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import androidx.activity.addCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.LinearSmoothScroller
import androidx.recyclerview.widget.PagerSnapHelper
import androidx.recyclerview.widget.RecyclerView
import kotlin.math.abs

/** Full-screen mushaf reader: 604 pages, edge-to-edge, chrome hidden while reading. */
class ReaderActivity : LanguageActivity() {

    private val pages = 604

    private lateinit var pager: RecyclerView
    private lateinit var lanes: LinearLayoutManager
    private lateinit var bar: View
    private lateinit var mark: ImageView
    private lateinit var markLabel: TextView
    private lateinit var barPlace: TextView
    private lateinit var player: View
    private lateinit var btnTheme: ImageView
    private lateinit var themeLabel: TextView
    private lateinit var btnTurn: ImageView
    private lateinit var turnLabel: TextView

    private lateinit var rc: RecitationController
    private lateinit var controls: ReaderPlayer

    // What the player asks of the page
    private val reader = object : ReaderPlayer.Reader {
        override fun go(page: Int) { if (page in 1..pages) this@ReaderActivity.go(page) }
        override fun pageInView() = this@ReaderActivity.pageInView()
        override fun shownWord() = this@ReaderActivity.shownWord
        override fun lit(surah: Int, ayah: Int, word: Int) = this@ReaderActivity.lit(surah, ayah, word)
        override fun redrawPages() = this@ReaderActivity.redrawPages()
        override fun showPlayer() = this@ReaderActivity.showPlayer()
        override fun closeSoon() = this@ReaderActivity.closeSoon()
        override fun armClose() = this@ReaderActivity.armClose()
    }

    /* Finished images of the page in view and its neighbours; see PageShots. */
    private lateinit var shots: PageShots

    // Reading time for the statistics
    private val clock by lazy { PageClock(this) }

    // --- page turn ---
    private lateinit var curl: PageCurlView
    private var turnPages = false
    private var dragNext: Boolean? = null

    // Kept as one instance so the reader can tell whether Recite's single listener slot still holds it
    private val heard: () -> Unit = {
        if (Recite.wantsToPlay()) rc.follow()
        clock.listening(Recite.isPlaying())
        controls.say()
    }

    private var bars: WindowInsetsControllerCompat? = null
    private var chrome = false
    private var fromBarEdge = false
    private var edgeDownX = 0f
    private var edgeDownY = 0f

    /* Status-bar height, settled once; everything about page layout follows from it. */
    private var band = 0
    private var bandSet = false

    /* Navigation-bar inset: keeps player above the nav bar when it is visible. */
    private var foot = 0

    // Kept apart: the page clears only the cutout, the controls clear both
    private var cutTop = 0
    private var cutLeft = 0
    private var cutRight = 0
    private var navLeft = 0
    private var navRight = 0

    /* The page last arrived at; 0 before the first. See arrived(). */
    private var current = 0

    private var lastTouchX = 0f
    private var lastTouchY = 0f

    private val fromIndex = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val page = result.data?.getIntExtra(SurahListActivity.PAGE, 0) ?: 0
            when {
                page in 1..pages -> {
                    go(page)
                    val surah = result.data?.getIntExtra(SurahListActivity.SURAH, 0) ?: 0
                    val ayah = result.data?.getIntExtra(SurahListActivity.AYAH, 0) ?: 0
                    if (surah > 0 && ayah > 0) {
                        flashAyah(page, surah, ayah)
                        // The player moves to the ayah's first word, playing only if it already was (see offer)
                        pager.post { controls.offer(surah, ayah, 0) }
                    }
                }
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
        onBackPressedDispatcher.addCallback(this) { toMenu() }
        pager  = findViewById(R.id.pager)
        bar    = findViewById(R.id.bar)
        mark      = findViewById(R.id.mark)
        markLabel = findViewById(R.id.mark_label)
        barPlace  = findViewById(R.id.bar_place)
        player    = findViewById(R.id.player)
        curl      = findViewById(R.id.curl)

        shots = PageShots(this) { page ->
            /* A page with a fresh shot redraws from it, so a swipe moves an image, not type. */
            for (i in 0 until pager.childCount) {
                (pager.getChildAt(i) as? MushafPageView)?.takeIf { it.page == page }?.invalidate()
            }
        }

        rc = RecitationController(
            context     = this,
            tickView    = pager,
            pageCount   = pages,
            currentPage = ::page,
            onChanged   = { controls.say() },
            onNavigate  = ::go,
            // A word being said alone keeps the mark; a paused recitation would re-mark its own word
            onLight     = { s, a, w -> if (!WordVoice.saying) lit(s, a, w) },
            onStopped   = {
                // An audio file that failed to load says so, instead of the player just leaving
                if (Recite.failed) notice(getString(R.string.recite_unheard))
                player.visibility = View.GONE
                sayBars()
                controls.clearPending()
                lit(-1, -1, -1)
            }
        )

        btnTheme = findViewById(R.id.btn_theme)
        themeLabel = findViewById(R.id.theme_label)

        dressWindow()
        EndPages.load(this)
        buildPager()

        sayThemeBtn()
        // click targets are the full-height containers, not the inner ImageViews
        findViewById<View>(R.id.btn_theme_wrap).setOnClickListener { cycleTheme() }
        btnTurn = findViewById(R.id.btn_turn)
        turnLabel = findViewById(R.id.turn_label)
        findViewById<View>(R.id.btn_turn_wrap).setOnClickListener {
            Settings.setPageTurn(this, !Settings.pageTurn(this))
            sayMotion()
        }
        findViewById<View>(R.id.btn_back).setOnClickListener { toMenu() }
        findViewById<View>(R.id.btn_mark).setOnClickListener {
            if (page() > pages) return@setOnClickListener
            Settings.toggleMark(this, page())
            sayPage(page())
        }

        controls = ReaderPlayer(this, player, rc, reader)

        val last = Settings.lastPage(this).let { if (it in 1..pages) it else 2 }
        // Opening behind the menu is not reading, so nothing is noted until a page is chosen
        go(last, note = false)
        // A theme change rebuilds this screen: the chosen word, the mark and the bars come back as they were
        savedInstanceState?.let { was ->
            controls.restore(was)
            rc.litAyah   = was.getInt(LIT_AYAH)
            rc.litWord   = was.getInt(LIT_WORD, -1)
            shownWord    = was.getIntArray(SHOWN_WORD)
        }
        showChrome(savedInstanceState?.getBoolean(CHROME) ?: false)
        // The player and the page's first word need laid-out pages, which the first pass has not got yet
        if (savedInstanceState != null) pager.post {
            shownWord?.let { lit(it[0], it[1], it[2]) }
            showChrome(chrome)
        }

        if (savedInstanceState == null) {
            fromIndex.launch(Intent(this, SurahListActivity::class.java))
        }
    }

    // --- pager ---

    /* RecyclerView + snap helper instead of ViewPager2: gives control over settle speed. */
    private fun buildPager() {
        // Sideways drags go to the curl instead of the pager while turning is on
        lanes = object : LinearLayoutManager(this, RecyclerView.HORIZONTAL, false) {
            override fun canScrollHorizontally() = super.canScrollHorizontally() && !turnsHere()
        }
        // Runs before the pager's own intercept, so the drag direction is known when it asks
        pager.addOnItemTouchListener(object : RecyclerView.SimpleOnItemTouchListener() {
            private var downX = 0f
            override fun onInterceptTouchEvent(rv: RecyclerView, e: android.view.MotionEvent): Boolean {
                when (e.actionMasked) {
                    android.view.MotionEvent.ACTION_DOWN -> {
                        downX = e.x
                        dragNext = null
                        // A new touch lands the turn still settling, so the next swipe starts from its page
                        curl.finish()
                    }
                    android.view.MotionEvent.ACTION_MOVE ->
                        if (dragNext == null && e.x != downX) dragNext = e.x > downX
                }
                return false
            }
        })
        pager.layoutManager = lanes
        pager.adapter = Pages()
        pager.setHasFixedSize(true)
        // Keeps pages just turned past laid out, since readers often go back
        pager.setItemViewCacheSize(3)
        Snap().attachToRecyclerView(pager)

        pager.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            private var warmedFor = -1

            // Warms the page being entered during the swipe, once per page
            override fun onScrolled(view: RecyclerView, dx: Int, dy: Int) {
                val first = lanes.findFirstVisibleItemPosition()
                val last = lanes.findLastVisibleItemPosition()
                if (first == RecyclerView.NO_POSITION) return
                val entering = (if (dx >= 0) last else first) + 1
                if (entering == warmedFor) return
                warmedFor = entering
                Mushaf.warm(this@ReaderActivity, entering)
            }

            private var dragFrom = 0

            override fun onScrollStateChanged(view: RecyclerView, state: Int) {
                if (state == RecyclerView.SCROLL_STATE_DRAGGING) dragFrom = page()
                if (state != RecyclerView.SCROLL_STATE_IDLE) return
                val at = lanes.findFirstCompletelyVisibleItemPosition()
                if (at == RecyclerView.NO_POSITION) return
                arrived(at + 1)
                // A page swiped to is reading resumed; a page the recitation moved to is not
                if (dragFrom != 0 && dragFrom != at + 1) closeChrome()
                dragFrom = 0
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
        // The Quran's pages, then its closing pages أ to ل
        override fun getItemCount() = pages + EndPages.pages.size
        override fun getItemViewType(position: Int) = if (position < pages) QURAN_PAGE else END_PAGE
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
            if (viewType == END_PAGE) return Holder(EndPageView(parent.context) { showChrome(!chrome) }.apply {
                layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            })
            val v = MushafPageView(parent.context)
            v.shots = shots::get
            v.turner = turner
            v.onCloseLook = { closeChrome() }
            v.layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            v.setOnClickListener { showChrome(!chrome) }
            v.setOnLongClickListener { view ->
                (view as MushafPageView).wordUnder(lastTouchX, lastTouchY)?.let { controls.offer(surah = it[0], ayah = it[1], w = it[2]) }
                true
            }
            v.setOnTouchListener { _, e -> lastTouchX = e.x; lastTouchY = e.y; false }
            return Holder(v)
        }
        override fun onBindViewHolder(holder: Holder, position: Int) {
            when (val v = holder.itemView) {
                is MushafPageView -> {
                    v.show(position + 1)
                    markAsNow(v)
                }
                is EndPageView -> v.show(position - pages)
            }
        }

        // A page kept aside off screen comes back without a bind, still holding the mark it had then
        override fun onViewAttachedToWindow(holder: Holder) {
            (holder.itemView as? MushafPageView)?.let { markAsNow(it) }
        }
    }

    // One word marked across every page: whatever lit() last chose, or none
    private fun markAsNow(view: MushafPageView) {
        val w = shownWord
        if (w == null) view.light(-1, -1, -1) else view.light(w[0], w[1], w[2])
    }

    class Holder(v: View) : RecyclerView.ViewHolder(v)

    // After a jump the layout still holds the old page, so the last arrival wins
    private fun page() = if (current > 0) current else
        (lanes.findFirstCompletelyVisibleItemPosition()
            .takeIf { it != RecyclerView.NO_POSITION } ?: 0) + 1

    private fun go(page: Int, note: Boolean = true) {
        lanes.scrollToPositionWithOffset(page - 1, 0)
        /* A jump fires no scroll state, so nothing downstream would learn of it. */
        arrived(page, note)
    }

    // Runs for swipes and jumps alike, so the top bar and last-read page stay current
    private fun arrived(page: Int, note: Boolean = true) {
        // A closing page is read about the mushaf, not in it: no reading time, no last place, no shots
        if (page > pages && page <= pages + EndPages.pages.size) {
            current = page
            clock.show(0)
            sayPage(page)
            return
        }
        if (page !in 1..pages) return
        current = page
        clock.show(page)
        sayPage(page)
        if (note) {
            Settings.setLastPage(this, page)
            Surahs.ofPage(page)?.let { Settings.noteRead(this, it.id, page) }
        }
        Mushaf.warm(this, page)
        /* Kept one page wider than warmed, both ways, so nothing warmed is dropped. */
        Mushaf.keepOnly((page - Mushaf.AHEAD - 1)..(page + Mushaf.AHEAD + 1))
        prepareShots(page)
    }

    // Highlight an ayah picked from search once its page has been laid out
    private fun flashAyah(page: Int, surah: Int, ayah: Int) {
        pager.post {
            for (i in 0 until pager.childCount) {
                (pager.getChildAt(i) as? MushafPageView)?.takeIf { it.page == page }?.flash(surah, ayah)
            }
        }
    }

    // --- page turning ---

    // Turning needs hardware shots, which arrived in Android 9
    private fun sayMotion() {
        turnPages = Settings.pageTurn(this) && android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P
        val on = Settings.pageTurn(this)
        // The button names the motion in use; tapping switches to the other
        btnTurn.setImageResource(if (on) R.drawable.ic_motion_turn else R.drawable.ic_motion_slide)
        btnTurn.imageTintList = ColorStateList.valueOf(getColor(R.color.accent))
        turnLabel.setText(if (on) R.string.motion_turn else R.string.motion_slide)
    }

    // Odd pages sit on the right, even on the left: only crossing between spreads flips
    private fun crossesSpread(from: Int, next: Boolean) = if (next) from % 2 == 0 else from % 2 == 1

    // Tall (scrolling) pages keep the slide
    private fun turnsHere(): Boolean {
        if (!turnPages) return false
        val next = dragNext ?: return false
        // The closing pages, and the step onto them, slide
        if (page() >= pages && (next || page() > pages)) return false
        if (!crossesSpread(page(), next)) return false
        val shown = (0 until pager.childCount).mapNotNull { pager.getChildAt(it) as? MushafPageView }
            .firstOrNull { it.page == page() }
        return shown?.scrolls != true
    }

    // Clear the curl two frames after the jump so the pager has drawn the new page (no blink)
    private val turner = object : MushafPageView.Turner {
        private var target = 0
        // Bumped per turn, so a finished turn's delayed clear never wipes the next one
        private var turnId = 0

        override fun begin(next: Boolean, x: Float, y: Float): Boolean {
            // Every page holds this turner, cached ones too, so the mode is read here at each drag
            if (!turnPages || !curl.finish()) return false
            turnId++
            val from = page()
            val to = if (next) from + 1 else from - 1
            if (to !in 1..pages || !crossesSpread(from, next)) return false
            val w = pager.width - pager.paddingLeft - pager.paddingRight
            val h = pager.height - pager.paddingTop - pager.paddingBottom
            val over = shots.now(from, w, h) ?: return false
            val under = shots.now(to, w, h) ?: return false
            target = to
            val at = android.graphics.RectF(
                pager.left + pager.paddingLeft.toFloat(), pager.top + pager.paddingTop.toFloat(),
                pager.left + pager.paddingLeft.toFloat() + w, pager.top + pager.paddingTop.toFloat() + h
            )
            curl.start(over, under, at, next, x, y)
            return true
        }

        override fun move(x: Float, y: Float) = curl.drag(x, y)

        override fun end(x: Float, y: Float, velocityX: Float, cancelled: Boolean) {
            curl.drag(x, y)
            val toNext = target > page()
            val fling = TURN_FLING_DP * resources.displayMetrics.density
            val flung = if (toNext) velocityX > fling else velocityX < -fling
            val complete = !cancelled && (curl.progress() > PageCurlView.PAST || flung)
            val id = turnId
            curl.settle(complete) { turned ->
                if (turned) {
                    go(target)
                    closeChrome()
                }
                curl.postOnAnimation { curl.postOnAnimation { if (id == turnId) curl.clear() } }
            }
        }
    }

    // Posted so the size is read after the layout the arrival triggered
    private fun prepareShots(page: Int) {
        pager.post {
            val w = pager.width - pager.paddingLeft - pager.paddingRight
            val h = pager.height - pager.paddingTop - pager.paddingBottom
            shots.around(page, w, h)
        }
    }

    // Rotation is handled here, so the page is noted first and re-seated after layout
    override fun onConfigurationChanged(newConfig: Configuration) {
        val at = page()
        super.onConfigurationChanged(newConfig)
        // Set now, whichever of this and the new insets arrives first
        padPage()
        pager.post { go(at) }
    }

    private fun toMenu() = fromIndex.launch(Intent(this, SurahListActivity::class.java))

    // --- window ---

    /* Edge-to-edge, as every screen is: system bars hidden while reading, shown on tap. Player is independent. */
    private fun dressWindow() {
        // The system and AppCompat layers around the page may still pad for the bars (some devices, or after a theme change), which made the page jump
        var layer = findViewById<View>(R.id.root).parent
        while (layer is View && layer !== window.decorView) {
            ViewCompat.setOnApplyWindowInsetsListener(layer) { v, insets ->
                v.setPadding(0, 0, 0, 0)
                insets
            }
            layer = layer.parent
        }

        /* Measure the status-bar height once; never recompute on inset change. */
        if (!bandSet) {
            bandSet = true
            band = topBand()
            padPage()
            liftBar()
        }

        // The page clears only the camera cutout; the bars are hidden while reading. Measured ignoring visibility so the page never jumps
        ViewCompat.setOnApplyWindowInsetsListener(pager) { _, insets ->
            val cut = insets.getInsets(WindowInsetsCompat.Type.displayCutout())
            val nav = insets.getInsetsIgnoringVisibility(WindowInsetsCompat.Type.navigationBars())
            if (cut.top > band) band = cut.top
            cutTop = cut.top
            cutLeft = cut.left
            cutRight = cut.right
            navLeft = nav.left
            navRight = nav.right
            padPage()
            liftBar()
            player.setPadding(maxOf(cutLeft, navLeft), 0, maxOf(cutRight, navRight), 0)
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

    // Shows only with the phone's bars, so it clears the status band and side cutouts or keys
    private fun liftBar() {
        bar.setPadding(maxOf(cutLeft, navLeft), band, maxOf(cutRight, navRight), 0)
    }

    // Upright the cutout is the status band; on its side it is one edge
    private fun padPage() {
        val upright = resources.configuration.orientation != Configuration.ORIENTATION_LANDSCAPE
        pager.setPadding(cutLeft, if (upright) band else cutTop, cutRight, 0)
    }

    /* Keeps the player above the navigation bar whenever it is visible. */
    private fun seatPlayer() {
        val seat = player.layoutParams as FrameLayout.LayoutParams
        if (seat.bottomMargin == foot) return
        seat.bottomMargin = foot
        player.requestLayout()
    }

    private fun closeChrome() {
        if (chrome) showChrome(false)
    }

    // Once Play is pressed the controls leave a moment after the sound starts, unless the reader touches the screen first
    private val autoClose = Runnable { if (Recite.wantsToPlay()) closeChrome() }
    private var closeWhenHeard = false

    private fun closeSoon() {
        pager.removeCallbacks(autoClose)
        closeWhenHeard = true
        armClose()
    }

    // Called on every player change, so the count starts when loading ends
    private fun armClose() {
        if (!closeWhenHeard || !Recite.isPlaying()) return
        closeWhenHeard = false
        pager.postDelayed(autoClose, AUTO_CLOSE_MS)
    }

    private fun keepOpen() {
        closeWhenHeard = false
        pager.removeCallbacks(autoClose)
    }

    // Controls left untouched leave on their own; a sheet over them takes focus, and they wait for it
    private val idleClose = Runnable { if (hasWindowFocus()) closeChrome() else armIdle() }

    private fun armIdle() {
        pager.removeCallbacks(idleClose)
        if (chrome) pager.postDelayed(idleClose, IDLE_CLOSE_MS)
    }

    /* One tap hides/shows all controls together: top bar and player bar. */
    private fun showChrome(on: Boolean) {
        chrome = on
        armIdle()
        bar.visibility = if (on) View.VISIBLE else View.GONE
        if (!on) {
            player.visibility = View.GONE
        } else {
            controls.offerPageStart()
            if (Recite.playing != 0 || controls.hasPending) showPlayer()
        }
        seatPlayer()
        sayBars()
        if (hasWindowFocus()) applyBars()
    }

    // Only with focus: without it the system records the request but never acts, leaving a bare navigation bar
    private fun applyBars() {
        bars?.let {
            if (chrome) it.show(WindowInsetsCompat.Type.systemBars())
            else        it.hide(WindowInsetsCompat.Type.systemBars())
        }
    }

    // Read once from the system on purpose: taken from insets, it arrives late and the page jumps
    @SuppressLint("DiscouragedApi", "InternalInsetResource")
    private fun topBand(): Int {
        val id = resources.getIdentifier("status_bar_height", "dimen", "android")
        return if (id > 0) resources.getDimensionPixelSize(id)
               else (24 * resources.displayMetrics.density).toInt()
    }

    /* The top bar's account of this page: which surah and where, and whether it is kept. */
    private fun sayPage(page: Int) {
        if (page > pages) {
            barPlace.text = EndPages.pages.getOrNull(page - pages - 1)?.letter.orEmpty()
            return
        }
        val juz = Surahs.juzOfPage(page)
        val at = pageNamed(page, resources)
        barPlace.text = if (juz > 0) {
            getString(R.string.bar_place, juzNamed(juz, resources), at)
        } else at

        // Icon fill shows the saved state; the label turns accent only when saved
        val marked = Settings.marked(this, page)
        mark.setImageResource(if (marked) R.drawable.ic_bookmark else R.drawable.ic_bookmark_off)
        mark.imageTintList = ColorStateList.valueOf(getColor(R.color.accent))
        markLabel.setTextColor(getColor(if (marked) R.color.accent else R.color.text_mute))
    }

    // --- recitation ---

    private fun pageInView(): MushafPageView? =
        (0 until pager.childCount).map { pager.getChildAt(it) }
            .firstOrNull { (it as? MushafPageView)?.page == page() } as? MushafPageView

    // The player never shows without the top bar; showChrome recurses here once
    private fun showPlayer() {
        seatPlayer()
        player.visibility = View.VISIBLE
        if (!chrome) showChrome(true) else sayBars()
    }

    // Bars take the controls' colours while they are up, the page colour while they are down
    private fun sayBars() {
        val paper = Settings.paperColor(this)
        paintBars(
            roof = if (chrome) groundOf(bar) ?: paper else paper,
            floor = if (chrome) groundOf(player) ?: paper else paper
        )
    }

    // --- theme toggle ---

    // System, light, dark, round again: the same three choices as in Settings
    private fun cycleTheme() {
        val next = when (Settings.theme(this)) {
            Settings.BY_SYSTEM -> Settings.LIGHT
            Settings.LIGHT -> Settings.DARK
            else -> Settings.BY_SYSTEM
        }
        Settings.setTheme(this, next)
        // Following the system may keep the look already shown, and then nothing is rebuilt
        sayThemeBtn()
    }

    /* The icon and label name the choice in force. */
    private fun sayThemeBtn() {
        val mode = Settings.theme(this)
        btnTheme.setImageResource(when (mode) {
            Settings.LIGHT -> R.drawable.ic_sun
            Settings.DARK -> R.drawable.ic_moon
            else -> R.drawable.ic_theme_system
        })
        btnTheme.imageTintList = ColorStateList.valueOf(getColor(R.color.accent))
        themeLabel.setText(when (mode) {
            Settings.LIGHT -> R.string.theme_light
            Settings.DARK -> R.string.theme_dark
            else -> R.string.theme_system
        })
    }

    private fun redrawPages() {
        for (i in 0 until pager.childCount) pager.getChildAt(i).invalidate()
        // The pictures shown mid-gesture are made again, so a page never flicks back to its other edition
        if (current in 1..pages) prepareShots(current)
    }

    /* The word marked on the page now, from recitation, a long-press, search or Word; null when none. */
    private var shownWord: IntArray? = null

    private fun lit(surah: Int, ayah: Int, word: Int) {
        shownWord = if (word >= 0) intArrayOf(surah, ayah, word) else null
        for (i in 0 until pager.childCount) {
            (pager.getChildAt(i) as? MushafPageView)?.light(surah, ayah, word)
        }
    }

    // --- lifecycle ---

    override fun onResume() {
        super.onResume()
        clock.resume()
        delegate.applyDayNight()
        sayMotion()
        sayPage(page())
        // Pages re-read their style on draw; they only need invalidating
        redrawPages()
        // The page colour also shows beside the camera and in the hidden bars
        findViewById<View>(R.id.root).setBackgroundColor(Settings.paperColor(this))
        sayBars()
        /* A style changed while away leaves the shots stale; they are made again. */
        if (current > 0) prepareShots(current)
        rc.syncWithRecite()

        Recite.onChange = heard
        // A page's coloured font arriving redraws the pages, and their pictures with them
        TajweedFonts.arrived = {
            Settings.tajweedArrived()
            redrawPages()
        }
        clock.listening(Recite.isPlaying())
        // The page opens clear even mid-recitation; the lit word shows where it is, a tap brings the player
        if (Recite.playing != 0) {
            rc.follow()
            if (chrome) showPlayer()
            controls.say()
        }
    }

    override fun onSaveInstanceState(out: Bundle) {
        super.onSaveInstanceState(out)
        out.putBoolean(CHROME, chrome)
        controls.save(out)
        out.putInt(LIT_AYAH, rc.litAyah)
        out.putInt(LIT_WORD, rc.litWord)
        shownWord?.let { out.putIntArray(SHOWN_WORD, it) }
    }

    // The old screen's timers and follower would otherwise act on views no longer shown
    override fun onDestroy() {
        pager.removeCallbacks(idleClose)
        pager.removeCallbacks(autoClose)
        rc.detach()
        super.onDestroy()
    }

    override fun onPause() {
        super.onPause()
        TajweedFonts.arrived = null
        clock.pause()
        WordVoice.stop()
        /* Only if it is still ours: see heard. */
        if (Recite.onChange === heard) Recite.onChange = null
    }

    // Back to the menu or out of the app with nothing sounding: the marks and a paused recitation are let go
    override fun onStop() {
        super.onStop()
        if (isChangingConfigurations || Recite.wantsToPlay()) return
        if (Recite.playing != 0) {
            Recite.stop()
            rc.stop()
        }
        controls.clearPending()
        lit(-1, -1, -1)
        player.visibility = View.GONE
    }

    /* Re-apply chrome state on every focus change; the request is dropped without focus. */
    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) showChrome(chrome)
    }

    // A swipe that pulls the hidden bars in starts on their edge; it must not turn or scroll the page, but a tap there still shows the controls
    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                keepOpen()
                pager.removeCallbacks(idleClose)
                fromBarEdge = !chrome && onBarEdge(ev.rawY)
                edgeDownX = ev.rawX
                edgeDownY = ev.rawY
            }
            MotionEvent.ACTION_UP -> {
                armIdle()
                if (fromBarEdge) {
                    val slop = android.view.ViewConfiguration.get(this).scaledTouchSlop
                    val still = abs(ev.rawX - edgeDownX) < slop && abs(ev.rawY - edgeDownY) < slop
                    if (still) showChrome(true)
                }
            }
        }
        return fromBarEdge || super.dispatchTouchEvent(ev)
    }

    private fun onBarEdge(y: Float): Boolean {
        val insets = ViewCompat.getRootWindowInsets(window.decorView) ?: return false
        val bars = insets.getInsetsIgnoringVisibility(WindowInsetsCompat.Type.systemBars())
        val gestures = insets.getInsets(WindowInsetsCompat.Type.systemGestures())
        val top = maxOf(bars.top, gestures.top)
        val bottom = maxOf(bars.bottom, gestures.bottom)
        return y < top || y > window.decorView.height - bottom
    }

    companion object {
        // The pager's two kinds of page
        private const val QURAN_PAGE = 0
        private const val END_PAGE = 1
        private const val CHROME = "chrome"
        private const val LIT_AYAH = "lit-ayah"
        private const val LIT_WORD = "lit-word"
        private const val SHOWN_WORD = "shown-word"
        private const val TURN_FLING_DP = 400f
        private const val AUTO_CLOSE_MS = 1500L
        private const val IDLE_CLOSE_MS = 6000L
    }
}
