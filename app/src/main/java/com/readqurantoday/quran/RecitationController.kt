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
        }
    }

    /** Load and start a surah, then begin following. */
    fun start(surah: Int, from: Int, andPlay: Boolean = true) {
        if (!Ayat.ready) Ayat.build(context)
        startedAt = from
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
        onLight(-1, -1, -1)
        onChanged()
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

                if (Recite.repeat == Recite.AYAH && litAyah > 0 && at > timing.endOf(litAyah)) {
                    Recite.seek(timing.startOf(litAyah))
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
