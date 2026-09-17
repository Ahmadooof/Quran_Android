package com.readqurantoday.quran

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
import androidx.core.view.WindowCompat
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
    private lateinit var btnTurn: ImageView
    private lateinit var turnLabel: TextView

    private lateinit var rc: RecitationController

    /* Finished images of the page in view and its neighbours; see PageShots. */
    private lateinit var shots: PageShots

    // --- page turn ---
    private lateinit var curl: PageCurlView
    private var turnPages = false
    private var dragNext: Boolean? = null

    // Kept as one instance so the reader can tell whether Recite's single listener slot still holds it
    private val heard: () -> Unit = {
        if (Recite.wantsToPlay()) rc.follow()
        sayPlayer()
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
                page in 1..pages -> {
                    go(page)
                    val surah = result.data?.getIntExtra(SurahListActivity.SURAH, 0) ?: 0
                    val ayah = result.data?.getIntExtra(SurahListActivity.AYAH, 0) ?: 0
                    if (surah > 0 && ayah > 0) flashAyah(page, surah, ayah)
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
            onChanged   = ::sayPlayer,
            onNavigate  = ::go,
            onLight     = ::lit,
            onStopped   = {
                player.visibility = View.GONE
                sayBars()
                pendingSurah = 0
                lit(-1, -1, -1)
            }
        )

        btnTheme = findViewById(R.id.btn_theme)

        dressWindow()
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
            Settings.toggleMark(this, page())
            sayPage(page())
        }

        wirePlayer()

        val last = Settings.lastPage(this).let { if (it in 1..pages) it else 2 }
        // Opening behind the menu is not reading, so nothing is noted until a page is chosen
        go(last, note = false)
        // restore chrome state on recreation (e.g. after theme toggle)
        showChrome(savedInstanceState?.getBoolean(CHROME) ?: false)

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
        override fun getItemCount() = pages
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
            val v = MushafPageView(parent.context)
            v.shots = shots::get
            v.turner = turner
            v.onCloseLook = { closeChrome() }
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
        if (page !in 1..pages) return
        current = page
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

    /* Edge-to-edge: system bars hidden while reading, shown on tap. Player is independent. */
    private fun dressWindow() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
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

    private fun night() =
        (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
            Configuration.UI_MODE_NIGHT_YES

    private fun topBand(): Int {
        val id = resources.getIdentifier("status_bar_height", "dimen", "android")
        return if (id > 0) resources.getDimensionPixelSize(id)
               else (24 * resources.displayMetrics.density).toInt()
    }

    /* The top bar's account of this page: which surah and where, and whether it is kept. */
    private fun sayPage(page: Int) {
        val juz = Surahs.juzOfPage(page)
        val at = getString(R.string.head_page, figures(page, resources))
        barPlace.text = if (juz > 0) {
            getString(R.string.bar_place, getString(R.string.head_juz, figures(juz, resources)), at)
        } else at

        // Icon fill shows the saved state; the label turns accent only when saved
        val marked = Settings.marked(this, page)
        mark.setImageResource(if (marked) R.drawable.ic_bookmark else R.drawable.ic_bookmark_off)
        mark.imageTintList = ColorStateList.valueOf(getColor(R.color.accent))
        markLabel.setTextColor(getColor(if (marked) R.color.accent else R.color.text_mute))
    }

    // --- recitation ---

    // Highlights the pressed word and shows the player without starting audio; seeks if already playing
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
        /* A long-press elsewhere moves page repeat to that word's page. */
        rc.reanchor()

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

    private fun sayPlayer() {
        armClose()
        val isPlaying = Recite.wantsToPlay()
        // A spinner while audio is on its way, so the silence does not look like a dead button
        val waiting = Recite.waiting()
        findViewById<ImageView>(R.id.p_play_icon).apply {
            setImageResource(if (isPlaying) R.drawable.ic_pause else R.drawable.ic_play)
            visibility = if (waiting) View.INVISIBLE else View.VISIBLE
        }
        findViewById<View>(R.id.p_play_wait).visibility = if (waiting) View.VISIBLE else View.GONE
        findViewById<TextView>(R.id.p_play_label).setText(
            when {
                waiting   -> R.string.loading
                isPlaying -> R.string.stop
                else      -> R.string.play
            }
        )
        // Repeat reads like a selected tab: accent while on
        val repeating = getColor(if (Recite.repeat != Recite.ONCE) R.color.accent else R.color.text_mute)
        findViewById<ImageView>(R.id.p_repeat_icon).imageTintList = ColorStateList.valueOf(repeating)
        findViewById<TextView>(R.id.p_repeat_label).setTextColor(repeating)
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
                    closeSoon()
                }
                return@setOnClickListener
            }
            Recite.toggle()
            if (Recite.wantsToPlay()) {
                rc.follow()
                closeSoon()
            }
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
            /* In the order of Recite's modes, which a choice's position maps to. */
            val modes = listOf(R.string.repeat_off, R.string.repeat_ayah, R.string.repeat_page, R.string.repeat_surah)
            sheet(
                getString(R.string.repeat),
                modes.mapIndexed { i, said -> Choice(getString(said), on = i == Recite.repeat) }
            ) { i ->
                Recite.repeat = i
                /* Choosing page repeat means the page recitation is on now. */
                rc.reanchor()
                sayPlayer()
            }
        }

        findViewById<View>(R.id.p_close).setOnClickListener {
            Recite.stop()
            rc.stop()
            player.visibility = View.GONE
            sayBars()
            pendingSurah = 0
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
        btnTheme.imageTintList = ColorStateList.valueOf(getColor(R.color.accent))
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
        sayMotion()
        sayPage(page())
        // Pages re-read their style on draw; they only need invalidating
        for (i in 0 until pager.childCount) pager.getChildAt(i).invalidate()
        // The page colour also shows beside the camera and in the hidden bars
        findViewById<View>(R.id.root).setBackgroundColor(Settings.paperColor(this))
        sayBars()
        /* A style changed while away leaves the shots stale; they are made again. */
        if (current > 0) prepareShots(current)
        rc.syncWithRecite()

        Recite.onChange = heard
        if (Recite.playing != 0) {
            rc.follow()
            showPlayer()
            sayPlayer()
        }
    }

    override fun onSaveInstanceState(out: Bundle) {
        super.onSaveInstanceState(out)
        out.putBoolean(CHROME, chrome)
    }

    override fun onPause() {
        super.onPause()
        /* Only if it is still ours: see heard. */
        if (Recite.onChange === heard) Recite.onChange = null
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
                fromBarEdge = !chrome && onBarEdge(ev.rawY)
                edgeDownX = ev.rawX
                edgeDownY = ev.rawY
            }
            MotionEvent.ACTION_UP -> if (fromBarEdge) {
                val slop = android.view.ViewConfiguration.get(this).scaledTouchSlop
                val still = abs(ev.rawX - edgeDownX) < slop && abs(ev.rawY - edgeDownY) < slop
                if (still) showChrome(true)
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
        private const val CHROME = "chrome"
        private const val TURN_FLING_DP = 400f
        private const val AUTO_CLOSE_MS = 3000L
    }
}
