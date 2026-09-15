package com.readqurantoday.quran

import android.app.Activity
import android.content.Intent
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
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

    /*
      What the reader does when the player's state changes. One instance, kept, so
      the reader can tell whether Recite's single listener slot still holds it.
      Recite has one slot and two screens use it: returning from the surah list, the
      reader resumes before the list is destroyed, and a list that cleared the slot
      unconditionally wiped the reader's fresh listener — audio started and the
      player bar never heard, its spinner turning on after the sound had begun.
    */
    private val heard: () -> Unit = {
        if (Recite.wantsToPlay()) rc.follow()
        sayPlayer()
    }

    private var bars: WindowInsetsControllerCompat? = null
    private var chrome = false

    /* Status-bar height, settled once; everything about page layout follows from it. */
    private var band = 0
    private var bandSet = false

    /* Navigation-bar inset: keeps player above the nav bar when it is visible. */
    private var foot = 0

    /* The camera cutout's reach from each edge, and the navigation keys' at the sides.
       Kept apart: the page keeps clear of the cutout only, the controls of both. */
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
        go(last)
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
        /* Pages just turned past stay laid out, so turning back to one does not bind
           and lay it out again mid-swipe. Past the default two, since readers go back. */
        pager.setItemViewCacheSize(3)
        Snap().attachToRecyclerView(pager)

        pager.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            private var warmedFor = -1

            /* Warm as the swipe moves, not once it has settled: by settling, the page it
               was bringing in has already bound, loaded or not. Keyed on the page being
               entered, so a swipe asks once rather than every frame. */
            override fun onScrolled(view: RecyclerView, dx: Int, dy: Int) {
                val first = lanes.findFirstVisibleItemPosition()
                val last = lanes.findLastVisibleItemPosition()
                if (first == RecyclerView.NO_POSITION) return
                val entering = (if (dx >= 0) last else first) + 1
                if (entering == warmedFor) return
                warmedFor = entering
                Mushaf.warm(this@ReaderActivity, entering)
            }

            override fun onScrollStateChanged(view: RecyclerView, state: Int) {
                if (state != RecyclerView.SCROLL_STATE_IDLE) return
                val at = lanes.findFirstCompletelyVisibleItemPosition()
                if (at == RecyclerView.NO_POSITION) return
                arrived(at + 1)
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

    /* The page the reader is on: the one it last arrived at. Read from the layout
       only before any arrival, since just after a jump the layout still holds the
       page being left — which is what the top bar used to show. */
    private fun page() = if (current > 0) current else
        (lanes.findFirstCompletelyVisibleItemPosition()
            .takeIf { it != RecyclerView.NO_POSITION } ?: 0) + 1

    private fun go(page: Int) {
        lanes.scrollToPositionWithOffset(page - 1, 0)
        /* A jump fires no scroll state, so nothing downstream would learn of it. */
        arrived(page)
    }

    /*
      Everything that follows from being on a page, whichever way the reader got
      there: a swipe settling, or a jump — follow the reciter, recitation turning the
      page, a pick from the index, a turn of the phone. Jumps used to skip it, since
      only the scroll listener called it and a jump never scrolls: the page moved and
      the top bar went on naming the one before, and the last-read page was not kept.
    */
    private fun arrived(page: Int) {
        if (page !in 1..pages) return
        current = page
        sayPage(page)
        Settings.setLastPage(this, page)
        Surahs.ofPage(page)?.let { Settings.noteRead(this, it.id, page) }
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
                if (turned) go(target)
                curl.postOnAnimation { curl.postOnAnimation { if (id == turnId) curl.clear() } }
            }
        }
    }

    /* Have this page and the ones either side ready as images, at the size a page is
       shown. Posted, so the size is read after any layout the arrival set off. */
    private fun prepareShots(page: Int) {
        pager.post {
            val w = pager.width - pager.paddingLeft - pager.paddingRight
            val h = pager.height - pager.paddingTop - pager.paddingBottom
            shots.around(page, w, h)
        }
    }

    /* The reader takes rotation itself rather than being rebuilt, so every page
       changes width under the pager. Note the page first, and seat it squarely once
       the new layout has run, so a turn never leaves the reader between two pages. */
    override fun onConfigurationChanged(newConfig: Configuration) {
        val at = page()
        super.onConfigurationChanged(newConfig)
        /* Upright and on its side keep clear of the camera differently; set it for the
           new way round now, whichever of this and the new insets arrives first. */
        padPage()
        pager.post { go(at) }
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
            padPage()
            liftBar()
        }

        /*
          What the page must keep clear of is the camera, and only the camera. The
          phone's own bars are hidden while reading, so the space they would take is
          the page's; the controls, which only ever show together with those bars,
          are the ones that step clear of them.

          Upright, the camera sits in the status band, so the page keeps below the
          band as it always has. On its side the camera moves to one edge, the band
          over the page is empty, and the page takes the top and the side away from
          the camera — only the camera's side is given up.

          Everything is measured ignoring visibility, as the band is fixed once:
          controls showing and hiding take the phone's bars with them, and a page that
          stepped in and out each time would move under the reader's eye.
        */
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

    /* The top bar only shows with the phone's bars, so it clears all of them: the
       status band above, and a side camera or side keys at either end. */
    private fun liftBar() {
        bar.setPadding(maxOf(cutLeft, navLeft), band, maxOf(cutRight, navRight), 0)
    }

    /* The page clears the camera and nothing else. Upright that is the status band;
       on its side it is one edge, and the page runs to the top. */
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

    /*
      Show or hide the phone's bars to match the controls — only ever with focus.

      Returning from the index, the result hid the bars and onResume's player showed
      them again, both before the reader had focus. Without focus the system never
      acts on either, but the window still records each request; so when focus came
      and the bars were asked for once more, the window already had them down as
      shown and did nothing. The strip the window paints under the navigation bar is
      sized from those insets, and it stayed at nothing: a bare navigation bar with
      the page showing through, until the controls were toggled by hand. Kept to
      focus, every request is one the system acts on, and onWindowFocusChanged
      applies whatever the controls came to want in the meantime.
    */
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

        /* Filled or outlined carries the saved state; the icon is accent either way,
           and the label turns accent only while the page is kept. */
        val marked = Settings.marked(this, page)
        mark.setImageResource(if (marked) R.drawable.ic_bookmark else R.drawable.ic_bookmark_off)
        mark.imageTintList = ColorStateList.valueOf(getColor(R.color.accent))
        markLabel.setTextColor(getColor(if (marked) R.color.accent else R.color.text_mute))
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

    /* The controls are one set: the player never comes up without the top bar.
       showChrome sets the flag before it reaches back here, so this recurses once
       and stops. */
    private fun showPlayer() {
        seatPlayer()
        player.visibility = View.VISIBLE
        if (!chrome) showChrome(true) else sayBars()
    }

    /*
      Both bars follow the controls, together. While they are up, the status bar
      takes the top bar's ground and the navigation bar the player strip's — the
      same surface, so the phone's own bars read as part of the controls. While they
      are down, both take the page's paper.

      The navigation bar follows the controls, not the player. Keyed on the player
      being visible, it went back to paper whenever the controls were up without
      audio, and sat cream under a white top bar. The phone only shows its bars
      while the controls are up anyway, so that is the state that decides.
    */
    private fun sayBars() {
        val paper = Settings.paperColor(this)
        paintBars(
            roof = if (chrome) groundOf(bar) ?: paper else paper,
            floor = if (chrome) groundOf(player) ?: paper else paper
        )
    }

    private fun sayPlayer() {
        val isPlaying = Recite.wantsToPlay()
        /* Pressed but not yet heard: a spinner where the icon is, and say so, so the
           silence while the audio arrives does not read as a button that did nothing. */
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
        /* Repeat is a state, so it reads the way a selected tab does: accent while
           on, muted while off. */
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
        /* Pages re-read their style themselves when it has changed; they only need
           asking to draw. The pager's cached pages redraw when they come back. */
        for (i in 0 until pager.childCount) pager.getChildAt(i).invalidate()
        /* The page colour is also what shows above the pages, beside the camera,
           and in the phone's bars while the controls are down. */
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

    override fun onBackPressed() {
        toMenu()
    }

    companion object {
        private const val CHROME = "chrome"
        private const val TURN_FLING_DP = 400f
    }
}
