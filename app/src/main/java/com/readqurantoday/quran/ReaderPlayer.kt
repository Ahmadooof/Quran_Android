package com.readqurantoday.quran

import android.app.Activity
import android.content.res.ColorStateList
import android.os.Bundle
import android.view.View
import android.widget.ImageView
import android.widget.TextView

/** The reader's player: the chosen start word, the buttons and their look; the page itself is [reader]'s. */
class ReaderPlayer(
    private val host: Activity,
    private val panel: View,
    private val rc: RecitationController,
    private val reader: Reader
) {

    /** What the player needs from the page it sits under. */
    interface Reader {
        fun go(page: Int)
        fun pageInView(): MushafPageView?
        /** The word marked now, as surah, ayah and word; null when none. */
        fun shownWord(): IntArray?
        fun lit(surah: Int, ayah: Int, word: Int)
        fun redrawPages()
        /** Shows the player, and the controls with it. */
        fun showPlayer()
        /** Lets the controls leave a moment after the sound starts. */
        fun closeSoon()
        /** Heard on every change, so a close waiting for the sound can start once it is heard. */
        fun armClose()
    }

    // Found once: every change to the recitation repaints these
    private val playIcon = panel.findViewById<ImageView>(R.id.p_play_icon)
    private val playWait = panel.findViewById<View>(R.id.p_play_wait)
    private val playLabel = panel.findViewById<TextView>(R.id.p_play_label)
    private val repeatIcon = panel.findViewById<ImageView>(R.id.p_repeat_icon)
    private val repeatLabel = panel.findViewById<TextView>(R.id.p_repeat_label)
    private val speedIcon = panel.findViewById<ImageView>(R.id.p_speed_icon)
    private val speedLabel = panel.findViewById<TextView>(R.id.p_speed_label)
    private val wordIcon = panel.findViewById<ImageView>(R.id.p_word_icon)
    private val wordLabel = panel.findViewById<TextView>(R.id.p_word_label)
    private val translitIcon = panel.findViewById<ImageView>(R.id.p_translit_icon)
    private val translitLabel = panel.findViewById<TextView>(R.id.p_translit_label)

    /* Word chosen by long-press but not yet played. */
    private var pendingSurah = 0
    private var pendingFrom = 0
    // Set when the start was picked for the reader, so a later page can pick again
    private var pendingAuto = false

    /** Whether a word waits to be played. */
    val hasPending get() = pendingSurah != 0

    init {
        wire()
    }

    fun clearPending() {
        pendingSurah = 0
        pendingAuto = false
    }

    fun save(out: Bundle) {
        out.putInt(PENDING_SURAH, pendingSurah)
        out.putInt(PENDING_FROM, pendingFrom)
        out.putBoolean(PENDING_AUTO, pendingAuto)
    }

    fun restore(was: Bundle) {
        pendingSurah = was.getInt(PENDING_SURAH)
        pendingFrom = was.getInt(PENDING_FROM)
        pendingAuto = was.getBoolean(PENDING_AUTO)
    }

    /** A word chosen: marked, and the player shown at it. A paused recitation stays paused there; one playing carries on from it. */
    fun offer(surah: Int, ayah: Int, w: Int) {
        if (surah <= 0 || ayah <= 0) return
        val play = Recite.wantsToPlay()

        // A new word chosen ends the one being said, and is fetched now for the Word button
        WordVoice.stop()
        reader.lit(surah, ayah, w)
        WordVoice.warm(host, surah, ayah, w)
        val voice = Recite.chosen(host)?.id ?: return
        val timing = Timing.of(host, surah, voice)
        if (timing == null) { host.notice(host.getString(R.string.no_timing)); return }

        /* Seek to the exact word so the highlight is immediate and correct. */
        val from = timing.wordSpan(ayah, w)?.get(0) ?: timing.startOf(ayah)

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
                if (play && !Recite.wantsToPlay()) Recite.toggle()
                rc.follow()
            } else {
                rc.start(surah, from, play)
            }
            if (play) reader.closeSoon()
        } else {
            /* Not yet playing: remember where to start; user will tap Play. */
            pendingSurah = surah
            pendingFrom  = from
            pendingAuto  = false
        }

        reader.showPlayer()
        say()
    }

    /** Nothing chosen yet: Play starts from the first word of the page in view, unlit until it plays. */
    fun offerPageStart() {
        if (Recite.playing != 0 || (pendingSurah != 0 && !pendingAuto)) return
        val (surah, ayah, w) = reader.pageInView()?.firstWord() ?: return
        WordVoice.warm(host, surah, ayah, w)
        val voice = Recite.chosen(host)?.id ?: return
        val timing = Timing.of(host, surah, voice) ?: return
        pendingSurah = surah
        pendingFrom  = timing.wordSpan(ayah, w)?.get(0) ?: timing.startOf(ayah)
        pendingAuto  = true
        rc.litAyah = ayah
        rc.litWord = w
        rc.until   = 0
        rc.reanchor()
    }

    /** The buttons repainted as the recitation, the word and the settings now stand. */
    fun say() {
        // The recitation going again, from here or the notification, ends a word said alone
        if (WordVoice.saying && Recite.wantsToPlay()) WordVoice.stop()
        reader.armClose()
        val isPlaying = Recite.wantsToPlay()
        // A spinner while audio is on its way, so the silence does not look like a dead button
        val waiting = Recite.waiting()
        playIcon.setImageResource(if (isPlaying) R.drawable.ic_pause else R.drawable.ic_play)
        playIcon.visibility = if (waiting) View.INVISIBLE else View.VISIBLE
        playWait.visibility = if (waiting) View.VISIBLE else View.GONE
        playLabel.setText(
            when {
                waiting   -> R.string.loading
                isPlaying -> R.string.stop
                else      -> R.string.play
            }
        )
        // Repeat reads like a selected tab: accent while on
        tint(repeatIcon, repeatLabel, Recite.repeat != Recite.ONCE)
        // Accent off the recorded speed, like repeat while it is on
        val speed = Settings.speed(host)
        tint(speedIcon, speedLabel, speed != 1f)
        speedLabel.setText(SPEED_NAMES.getOrElse(SPEEDS.indexOfFirst { it == speed }) { R.string.speed_normal })
        // Accent while the word is being said; faded while it is fetched
        tint(wordIcon, wordLabel, WordVoice.saying)
        wordIcon.imageAlpha = if (WordVoice.loading) 0x66 else 0xFF
        wordLabel.setText(if (WordVoice.loading) R.string.loading else R.string.label_word)
        tint(translitIcon, translitLabel, Settings.translit(host))
    }

    private fun tint(icon: ImageView, label: TextView, on: Boolean) {
        val colour = host.getColor(if (on) R.color.accent else R.color.text_mute)
        icon.imageTintList = ColorStateList.valueOf(colour)
        label.setTextColor(colour)
    }

    // --- buttons ---

    private fun wire() {
        /* Tapping the bar body navigates to the current word's page. */
        panel.setOnClickListener {
            if (Recite.playing == 0) return@setOnClickListener
            reader.go(Ayat.pageOf(rc.readingSurah, rc.litAyah.coerceAtLeast(1)))
        }
        panel.findViewById<View>(R.id.p_play).setOnClickListener { play() }
        /* Locate: go to the current (or pending) word's page. */
        panel.findViewById<View>(R.id.p_locate).setOnClickListener {
            val surah = if (Recite.playing != 0) rc.readingSurah else pendingSurah
            reader.go(Ayat.pageOf(surah, rc.litAyah.coerceAtLeast(1)))
        }
        panel.findViewById<View>(R.id.p_reciter).setOnClickListener { pickReciter() }
        panel.findViewById<View>(R.id.p_repeat).setOnClickListener { pickRepeat() }
        panel.findViewById<View>(R.id.p_word).setOnClickListener { sayWord() }
        panel.findViewById<View>(R.id.p_translit).setOnClickListener {
            val on = !Settings.translit(host)
            Settings.setTranslit(host, on)
            // With no word lit on this page the pronunciation would show nowhere; its first word is lit to show it
            val view = reader.pageInView()
            val lit = reader.shownWord()
            if (on && !Recite.wantsToPlay() && view != null && (lit == null || !view.holds(lit[0], lit[1], lit[2]))) {
                view.firstWord()?.let { (s, a, w) -> reader.lit(s, a, w) }
            }
            say()
            if (on && !Translit.ready) Thread { Translit.load(host); host.runOnUiThread { reader.redrawPages() } }.start()
            else reader.redrawPages()
        }
        /* Speed: each tap steps to the next, round to the start. */
        panel.findViewById<View>(R.id.p_speed).setOnClickListener {
            val now = SPEEDS.indexOfFirst { it == Settings.speed(host) }
            val next = SPEEDS[(now + 1) % SPEEDS.size]
            Settings.setSpeed(host, next)
            Recite.setSpeed(next)
            say()
        }
    }

    /* Play: start from the pending position, or toggle if already running. */
    private fun play() {
        WordVoice.stop()
        if (Recite.playing == 0) {
            // Menus shown before the page was laid out left no start; the page in view gives one now
            if (pendingSurah == 0) offerPageStart()
            if (pendingSurah > 0) {
                rc.start(pendingSurah, pendingFrom)
                host.warnIfMuted()
                pendingSurah = 0
                reader.showPlayer()
                say()
                reader.closeSoon()
            }
            return
        }
        Recite.toggle()
        if (Recite.wantsToPlay()) {
            host.warnIfMuted()
            rc.follow()
            reader.closeSoon()
        }
        say()
    }

    /* Reciter: pick a voice; if playing, restart from the current word. */
    private fun pickReciter() {
        // A voice can be chosen before anything is chosen to recite
        val surah = if (Recite.playing != 0) rc.readingSurah else pendingSurah
        val voices = Recite.reciters()
        val now = Recite.chosen(host)?.id
        host.sheet(host.getString(R.string.reciter), voices.map { Choice(it.nameAr, it.noteAr, it.id == now) }) { i ->
            val id = voices[i].id
            if (id == now) return@sheet
            Recite.choose(host, id)
            if (Recite.playing != 0) {
                val keepAyah   = rc.litAyah
                val keepWord   = rc.litWord
                val wasPlaying = Recite.wantsToPlay()
                val fresh = Timing.of(host, rc.readingSurah, id)
                val span  = if (keepWord >= 0 && keepAyah > 0) fresh?.wordSpan(keepAyah, keepWord) else null
                rc.until = 0
                val from = span?.get(0) ?: if (keepAyah > 0) fresh?.startOf(keepAyah) ?: 0 else 0
                rc.start(rc.readingSurah, from, wasPlaying)
            } else if (surah > 0) {
                /* Recalculate pending start for the new voice. */
                val fresh = Timing.of(host, surah, id)
                pendingFrom = fresh?.wordSpan(rc.litAyah, rc.litWord)?.get(0)
                    ?: fresh?.startOf(rc.litAyah.coerceAtLeast(1)) ?: 0
            }
            say()
        }
    }

    private fun pickRepeat() {
        /* In the order of Recite's modes, which a choice's position maps to. */
        val modes = listOf(R.string.repeat_off, R.string.repeat_ayah, R.string.repeat_page, R.string.repeat_surah)
        host.sheet(host.getString(R.string.repeat), modes.mapIndexed { i, said -> Choice(host.getString(said), on = i == Recite.repeat) }) { i ->
            Recite.repeat = i
            /* Choosing page repeat means the page recitation is on now. */
            rc.reanchor()
            say()
        }
    }

    /* Word: the chosen word alone; a running recitation pauses for it, and a second tap stops it. */
    private fun sayWord() {
        // Still fetching: a second tap would only ask again
        if (WordVoice.loading) return
        if (WordVoice.saying) {
            WordVoice.stop()
            return
        }
        // The marked word if it is on this page, else the page's first word
        val view = reader.pageInView()
        val (surah, ayah, w) = reader.shownWord()?.takeIf { view?.holds(it[0], it[1], it[2]) == true }
            ?: view?.firstWord() ?: return
        if (Recite.wantsToPlay()) Recite.toggle()
        reader.lit(surah, ayah, w)
        host.warnIfMuted()
        WordVoice.say(host, surah, ayah, w, changed = ::say) { how ->
            say()
            when (how) {
                WordVoice.End.MISSING -> host.notice(host.getString(R.string.word_missing))
                WordVoice.End.UNREACHABLE -> host.notice(host.getString(R.string.word_unheard))
                WordVoice.End.HEARD -> {}
            }
        }
        say()
    }

    private companion object {
        const val PENDING_SURAH = "pending-surah"
        const val PENDING_FROM = "pending-from"
        const val PENDING_AUTO = "pending-auto"

        /* The speeds a tap steps through, and what each is called; in step with each other. */
        val SPEEDS = floatArrayOf(0.75f, 1f, 1.25f)
        val SPEED_NAMES = listOf(R.string.speed_slow, R.string.speed_normal, R.string.speed_fast)
    }
}
