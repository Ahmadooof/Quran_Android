package com.readqurantoday.quran

import android.content.Context
import android.view.View

// Recitation state and the word-highlight follower, kept out of ReaderActivity
class RecitationController(
    private val context: Context,
    private val tickView: View,
    private val pageCount: Int,
    private val currentPage: () -> Int,
    private val onChanged: () -> Unit,
    private val onNavigate: (Int) -> Unit,
    private val onLight: (Int, Int, Int) -> Unit,
    private val onStopped: () -> Unit
) {
    var reading: Timing? = null
    var readingSurah = 0
    var litAyah = 0
    var litWord = -1
    var until = 0
    var startedAt = 0

    // Null until the next tick anchors it on the current ayah's page
    private var loop: IntRange? = null

    // The 50ms follower passes on waiting changes so the spinner never outlasts the sound
    private var wasWaiting = false

    /** Let page repeat settle on wherever recitation now is, rather than where it was. */
    fun reanchor() {
        loop = null
    }

    /** Call on resume: re-sync if the reciter or surah changed while in the background. */
    fun syncWithRecite() {
        val nowSurah = Recite.playing
        if (nowSurah == 0) return
        val nowReading = Recite.chosen(context)?.id?.let { Timing.of(context, nowSurah, it) }
        if (nowSurah != readingSurah || nowReading !== reading) {
            readingSurah = nowSurah
            reading = nowReading
            litAyah = 0
            litWord = -1
            until = 0
            loop = null
        }
    }

    /** Load and start a surah, then begin following. */
    fun start(surah: Int, from: Int, andPlay: Boolean = true) {
        if (!Ayat.ready) Ayat.build(context)
        startedAt = from
        loop = null
        readingSurah = surah
        reading = Recite.chosen(context)?.id?.let { Timing.of(context, surah, it) }
        Recite.start(context, surah, from, andPlay)
        follow()
    }

    fun follow() {
        tickView.removeCallbacks(follower)
        tickView.post(follower)
    }

    fun stop() {
        tickView.removeCallbacks(follower)
        litAyah = 0
        litWord = -1
        until = 0
        loop = null
        onLight(-1, -1, -1)
        onChanged()
    }

    // Ayahs that open on the current page; a page inside one long ayah loops that ayah
    private fun pageLoop(timing: Timing): IntRange {
        loop?.let { return it }
        val surah = readingSurah
        val page = Ayat.pageOf(surah, litAyah)
        val count = Surahs.list().firstOrNull { it.id == surah }?.ayahs ?: litAyah
        var first = litAyah
        var last = litAyah
        if (page > 0) {
            while (first > 1 && Ayat.pageOf(surah, first - 1) == page) first--
            while (last < count && Ayat.pageOf(surah, last + 1) == page) last++
        }
        Recite.loopFrom = timing.startOf(first)
        return (first..last).also { loop = it }
    }

    private val follower = object : Runnable {
        override fun run() {
            if (Recite.playing == 0) {
                stop()
                onStopped()
                readingSurah = 0
                reading = null
                return
            }

            if (Recite.playing != readingSurah) {
                readingSurah = Recite.playing
                reading = Recite.chosen(context)?.id
                    ?.let { Timing.of(context, readingSurah, it) }
                litAyah = 0
                litWord = -1
                until = 0
                startedAt = 0
                loop = null
                onChanged()
            }

            val waiting = Recite.waiting()
            if (waiting != wasWaiting) {
                wasWaiting = waiting
                onChanged()
            }

            val timing = reading
            if (timing != null) {
                val at = Recite.at()

                if (until > 0 && at >= until) {
                    if (Recite.repeat == Recite.ONCE) {
                        Recite.toggle()
                        onChanged()
                        onLight(readingSurah, litAyah, litWord)
                        return
                    }
                    Recite.seek(startedAt)
                }

                // An end of 0 is an unrecorded timing and must not count as passed
                val span = if (Recite.repeat == Recite.PAGE && litAyah > 0) pageLoop(timing) else null
                val lastEnd = span?.let { timing.endOf(it.last) } ?: 0

                if (Recite.repeat == Recite.AYAH && litAyah > 0 && at > timing.endOf(litAyah)) {
                    Recite.seek(timing.startOf(litAyah))
                } else if (span != null && lastEnd > 0 && at > lastEnd) {
                    Recite.seek(timing.startOf(span.first))
                } else {
                    val ayah = timing.ayahAt(at, litAyah)
                    if (ayah > 0) {
                        if (ayah != litAyah) {
                            litAyah = ayah
                            onChanged()
                            val on = Ayat.pageOf(readingSurah, ayah)
                            if (on in 1..pageCount && on != currentPage()) onNavigate(on)
                        }
                        val w = timing.wordAt(ayah, at)
                        litWord = w
                        onLight(readingSurah, ayah, w)
                    }
                }
            }
            tickView.postDelayed(this, if (Recite.isPlaying()) 50 else 250)
        }
    }
}
