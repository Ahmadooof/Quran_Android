package com.readqurantoday.quran

import android.content.Context
import android.view.View

/**
 * Tracks recitation state and drives the word-highlight follower.
 * Extracted from ReaderActivity so the activity only handles UI events and navigation.
 *
 * @param tickView   view to post/remove the follower Runnable on
 * @param pageCount  total mushaf page count
 * @param currentPage returns which page is currently visible
 * @param onChanged  player state changed — update the player bar UI
 * @param onNavigate turn to this page (recitation moved to a new ayah)
 * @param onLight    light this word on screen (surah, ayah, word)
 * @param onStopped  recitation ended — hide the player bar
 */
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

    /* The ayahs page repeat loops over, or null until the next tick anchors it on
       the page the ayah then playing opens on. */
    private var loop: IntRange? = null

    /* Whether the player was waiting for audio at the last tick. The player reports
       its own changes too, but the follower is already watching every 50ms, so a
       flip it sees is passed on — the spinner cannot outlast the sound starting. */
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

    /* The page being repeated, as a range of this surah's ayahs: every ayah that
       opens on the page the current one opens on. A page that sits wholly inside
       one long ayah opens none of its own, so it is that ayah alone. The player
       is told the loop's start in case the surah's audio runs out first. */
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

                /* Past the end of what is being repeated: back to its start. An end of
                   0 is a timing that was never recorded, and must not read as passed. */
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
