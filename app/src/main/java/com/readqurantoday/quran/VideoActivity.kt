package com.readqurantoday.quran

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.TextView
import androidx.core.content.FileProvider
import java.io.File

/** A recitation video: a surah, its ayahs from and to, a reciter, and how the words are shown. */
class VideoActivity : CardsActivity(R.string.video_title) {

    private var ask = RecitationVideo.Ask(1, 1, 1, "", VideoFrames.Style.LINE)
    private var making = -1

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Surahs.load(this)
        Recite.load(this)
        // Opens on the surah last read, with the reciter last chosen
        val surah = Surahs.ofPage(Settings.lastPage(this)) ?: Surahs.list().first()
        ask = ask.copy(surah = surah.id, from = 1, to = minOf(surah.ayahs, START_AYAHS), reciter = reciterFor(surah.id, Recite.chosen(this)?.id))
        build()
        sayBars()
    }

    override fun onDestroy() {
        if (making >= 0) RecitationVideo.stop()
        super.onDestroy()
    }

    private fun surah() = Surahs.list().first { it.id == ask.surah }

    // Only reciters with word timings for the surah can light its words
    private fun voices(surah: Int) = Recite.reciters().filter { Timing.of(this, surah, it.id) != null }

    private fun reciterFor(surah: Int, wanted: String?): String {
        val voices = voices(surah)
        return voices.firstOrNull { it.id == wanted }?.id ?: voices.firstOrNull()?.id.orEmpty()
    }

    private fun build() {
        cards.removeAllViews()
        val ayahs = surah().ayahs
        layoutInflater.card(cards, 0, listOf(
            pickRow(R.string.video_surah, surah().name) {
                sheet(getString(R.string.video_surah), Surahs.list().map { Choice(it.name, on = it.id == ask.surah) }) { i ->
                    val s = Surahs.list()[i]
                    ask = ask.copy(surah = s.id, from = 1, to = minOf(s.ayahs, START_AYAHS), reciter = reciterFor(s.id, ask.reciter))
                    build()
                }
            },
            stepperRow(cards, R.string.video_from, ask.from, 1..ayahs) {
                ask = ask.copy(from = it, to = ask.to.coerceIn(it, minOf(ayahs, it + MOST_AYAHS - 1)))
                build()
            },
            stepperRow(cards, R.string.video_to, ask.to, ask.from..minOf(ayahs, ask.from + MOST_AYAHS - 1)) {
                ask = ask.copy(to = it)
                build()
            },
            pickRow(R.string.reciter, Recite.reciters().firstOrNull { it.id == ask.reciter }?.nameAr.orEmpty()) {
                val voices = voices(ask.surah)
                sheet(getString(R.string.reciter), voices.map { Choice(it.nameAr, it.noteAr, it.id == ask.reciter) }) { i ->
                    ask = ask.copy(reciter = voices[i].id)
                    build()
                }
            }
        ))
        layoutInflater.card(cards, R.string.video_style, listOf(styles()))
        cards.addView(makeButton())
    }

    private fun pickRow(label: Int, value: String, pick: () -> Unit): View =
        layoutInflater.inflate(R.layout.row_progress, cards, false).apply {
            findViewById<TextView>(R.id.prog_label).setText(label)
            findViewById<TextView>(R.id.prog_value).text = value
            opens(pick)
        }

    // Word by word, line by line, or the whole page
    private fun styles(): View = layoutInflater.inflate(R.layout.part_segments, cards, false).apply {
        background = null
        val face = findViewById<TextView>(R.id.seg_1).typeface
        findViewById<View>(R.id.seg_4).visibility = View.GONE
        listOf(
            R.id.seg_1 to (VideoFrames.Style.WORD to R.string.video_word),
            R.id.seg_2 to (VideoFrames.Style.LINE to R.string.video_line),
            R.id.seg_3 to (VideoFrames.Style.PAGE to R.string.video_page)
        ).forEach { (id, choice) ->
            findViewById<TextView>(id).apply {
                setText(choice.second)
                markSegment(ask.style == choice.first, face)
                setOnClickListener {
                    ask = ask.copy(style = choice.first)
                    build()
                }
            }
        }
    }

    private fun makeButton(): View = (layoutInflater.inflate(R.layout.part_primary_button, cards, false) as TextView).apply {
        text = if (making >= 0) getString(R.string.video_making, figures(making, resources)) else getString(R.string.video_make)
        isEnabled = making < 0 && ask.reciter.isNotEmpty()
        alpha = if (isEnabled) 1f else WAITING
        setOnClickListener { make() }
    }

    private fun make() {
        making = 0
        build()
        RecitationVideo.make(this, ask, progress = { done ->
            if (making < 0 || isDestroyed) return@make
            making = done
            (cards.getChildAt(cards.childCount - 1) as TextView).text = getString(R.string.video_making, figures(done, resources))
        }) { file ->
            if (isDestroyed) return@make
            making = -1
            build()
            if (file == null) notice(getString(R.string.video_failed)) else share(file)
        }
    }

    private fun share(file: File) {
        val uri = FileProvider.getUriForFile(this, "$packageName.share", file)
        val send = Intent(Intent.ACTION_SEND).setType("video/mp4").putExtra(Intent.EXTRA_STREAM, uri)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        startActivity(Intent.createChooser(send, getString(R.string.video_share)))
    }

    private companion object {
        const val START_AYAHS = 5
        // A run long enough for any share, short enough to make in a minute or two
        const val MOST_AYAHS = 50
        const val WAITING = 0.6f
    }
}
