package com.readqurantoday.quran

import android.content.Intent
import android.content.res.ColorStateList
import android.os.Bundle
import android.graphics.Bitmap
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.core.content.FileProvider
import java.io.File

/** A recitation video: a surah, its ayahs from and to, a reciter, and how the words are shown. */
class VideoActivity : CardsActivity(R.string.video_title) {

    private var ask = RecitationVideo.Ask(1, 1, 1, "", VideoFrames.Style.LINE, VideoLook(0, 0, 0))
        // A change to what is asked leaves the last video made for something else
        set(value) {
            if (value != field) made = null
            field = value
            RecitationVideo.keepAsk(this, value)
        }
    private var making = -1
    // The preview as last drawn, and what it was drawn for; a newer ask redraws it
    private var shown: Bitmap? = null
    private var shownFor: RecitationVideo.Ask? = null
    private var drawing: RecitationVideo.Ask? = null
    private var made: File? = null
    // Where the made video stands on its way to the phone's own folder
    private enum class Kept { SAVING, SAVED, NOT }
    private var kept = Kept.NOT
    private val mayKeep = SavePermission(this)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Surahs.load(this)
        Recite.load(this)
        // Opens on the last video asked for; the first time, on the surah last read with the reciter last chosen
        val last = RecitationVideo.lastAsk(this)
        val surah = Surahs.list().firstOrNull { it.id == last?.surah } ?: Surahs.ofPage(Settings.lastPage(this)) ?: Surahs.list().first()
        val from = (last?.from ?: 1).coerceIn(1, surah.ayahs)
        val to = (last?.to ?: START_AYAHS).coerceIn(from, minOf(surah.ayahs, from + MOST_AYAHS - 1))
        ask = RecitationVideo.Ask(surah.id, from, to, reciterFor(surah.id, last?.reciter ?: Recite.chosen(this)?.id),
            last?.style ?: VideoFrames.Style.LINE, VideoLook.of(this), last?.shape ?: RecitationVideo.Shape.TALL)
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
        layoutInflater.card(cards, R.string.video_shape, listOf(shapes()))
        val reader = VideoLook.ofReader(this)
        layoutInflater.card(cards, R.string.video_colors, listOf(
            looks(reader),
            colorRow(R.string.video_paper, ask.look.paper, reader.paper, PAPER_PICKS) { ask.look.copy(paper = it) },
            colorRow(R.string.video_ink, ask.look.ink, reader.ink, INK_PICKS) { ask.look.copy(ink = it) },
            colorRow(R.string.video_lit, ask.look.lit, reader.lit, ACCENT_PICKS) { ask.look.copy(lit = it) }
        ))
        layoutInflater.card(cards, R.string.video_preview, listOf(preview()))
        // Made: where it was saved, and a way to send it; a change to any choice brings back the button to make one
        val video = made
        if (video == null) {
            cards.addView(makeButton())
            return
        }
        val said = when (kept) {
            Kept.SAVING -> R.string.video_saving
            Kept.SAVED -> R.string.video_saved
            Kept.NOT -> R.string.dl_save_failed
        }
        layoutInflater.card(cards, 0, listOf((layoutInflater.inflate(R.layout.row_setting_note, cards, false) as TextView).apply { setText(said) }))
        cards.addView((layoutInflater.inflate(R.layout.part_primary_button, cards, false) as TextView).apply {
            setText(R.string.video_share)
            setOnClickListener { share(video) }
        })
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

    // The first picture of the video as it stands, upright as it will be shared; drawn again as the choices change
    private fun preview(): View = ImageView(this).apply {
        layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, resources.getDimensionPixelSize(R.dimen.video_preview_height))
        scaleType = ImageView.ScaleType.FIT_CENTER
        contentDescription = getString(R.string.video_preview)
        shown?.let { setImageBitmap(it) }
        val wanted = ask
        if (wanted != shownFor && wanted != drawing && wanted.reciter.isNotEmpty()) {
            drawing = wanted
            RecitationVideo.preview(this@VideoActivity, wanted) { shot ->
                if (isDestroyed) return@preview
                drawing = null
                shown = shot
                shownFor = wanted
                if (ask == wanted) cards.findViewWithTag<ImageView>(PREVIEW)?.setImageBitmap(shot) else build()
            }
        }
        tag = PREVIEW
    }

    // Upright, square or wide, for where the video will go
    private fun shapes(): View = layoutInflater.inflate(R.layout.part_segments, cards, false).apply {
        background = null
        val face = findViewById<TextView>(R.id.seg_1).typeface
        listOf(
            R.id.seg_1 to (RecitationVideo.Shape.TALL to R.string.shape_tall),
            R.id.seg_2 to (RecitationVideo.Shape.POST to R.string.shape_post),
            R.id.seg_3 to (RecitationVideo.Shape.SQUARE to R.string.shape_square),
            R.id.seg_4 to (RecitationVideo.Shape.WIDE to R.string.shape_wide)
        ).forEach { (id, choice) ->
            findViewById<TextView>(id).apply {
                // The name, and beside it the proportions the apps ask for
                text = getString(R.string.shape_said, getString(choice.second),
                    getString(R.string.shape_ratio, figures(choice.first.w, resources), figures(choice.first.h, resources)))
                markSegment(ask.shape == choice.first, face)
                setOnClickListener {
                    ask = ask.copy(shape = choice.first)
                    build()
                }
            }
        }
    }

    // Ready-made colours a tap away: the reader's own, light, dark and green
    private fun looks(reader: VideoLook): View = layoutInflater.inflate(R.layout.part_segments, cards, false).apply {
        background = null
        val face = findViewById<TextView>(R.id.seg_1).typeface
        listOf(
            R.id.seg_1 to (reader to R.string.look_reader),
            R.id.seg_2 to (VideoLook.LIGHT to R.string.look_light),
            R.id.seg_3 to (VideoLook.DARK to R.string.look_dark),
            R.id.seg_4 to (VideoLook.GREEN to R.string.look_green)
        ).forEach { (id, choice) ->
            findViewById<TextView>(id).apply {
                setText(choice.second)
                markSegment(ask.look == choice.first, face)
                setOnClickListener { setLook(choice.first) }
            }
        }
    }

    // One of the video's colours: the picker shows it on a line of the page, with the others as they stand
    private fun colorRow(label: Int, current: Int, reader: Int, picks: IntArray, with: (Int) -> VideoLook): View =
        layoutInflater.inflate(R.layout.row_setting_color, cards, false).apply {
            findViewById<TextView>(R.id.set_label).setText(label)
            findViewById<ImageView>(R.id.set_dot).imageTintList = ColorStateList.valueOf(current)
            setOnClickListener {
                val inUse = intArrayOf(ask.look.paper, ask.look.ink, ask.look.lit)
                colorSheet(getString(label), this@VideoActivity, current, reader, picks, inUse, trial = { line, c ->
                    with(c).let { line.tryOn(lit = it.lit, mark = it.ink, ink = it.ink, paper = it.paper) }
                }) { setLook(with(it)) }
            }
            findViewById<View>(R.id.set_reset).setOnClickListener { setLook(with(reader)) }
        }

    private fun setLook(look: VideoLook) {
        VideoLook.keep(this, look)
        ask = ask.copy(look = look)
        build()
    }

    private fun makeButton(): View = (layoutInflater.inflate(R.layout.part_primary_button, cards, false) as TextView).apply {
        tag = MAKE
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
            cards.findViewWithTag<TextView>(MAKE)?.text = getString(R.string.video_making, figures(done, resources))
        }) { file ->
            if (isDestroyed) return@make
            making = -1
            made = file
            kept = Kept.NOT
            build()
            if (file == null) notice(getString(R.string.video_failed)) else save(file)
        }
    }

    // Every video is kept in the phone's own folder, where the gallery shows it and it can be deleted
    private fun save(file: File) = mayKeep.then {
        kept = Kept.SAVING
        build()
        Thread {
            val ok = saveVideoToPhone(this, file)
            runOnUiThread {
                if (made != file || isDestroyed) return@runOnUiThread
                kept = if (ok) Kept.SAVED else Kept.NOT
                build()
            }
        }.start()
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
        const val MAKE = "make"
        const val PREVIEW = "preview"
    }
}
