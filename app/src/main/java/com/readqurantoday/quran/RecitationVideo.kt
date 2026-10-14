package com.readqurantoday.quran

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import androidx.annotation.OptIn
import androidx.core.content.edit
import androidx.core.graphics.createBitmap
import androidx.core.net.toUri
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.transformer.Composition
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.EditedMediaItemSequence
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.ProgressHolder
import androidx.media3.transformer.Transformer
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

/** A recitation of a run of ayahs made into an upright MP4, each word lit as it is recited. */
@OptIn(UnstableApi::class)
object RecitationVideo {

    data class Ask(
        val surah: Int, val from: Int, val to: Int, val reciter: String,
        val style: VideoFrames.Style, val look: VideoLook, val shape: Shape = Shape.TALL,
        // The pronunciation over the lit word, for the full page
        val phonetics: Boolean = false
    )

    /** Upright for status and reels, four by five and square for posts, wide for YouTube, and a strip for one line; [w]:[h] is said with it. */
    enum class Shape(val wide: Int, val tall: Int, val w: Int, val h: Int) {
        TALL(1080, 1920, 9, 16), POST(1080, 1350, 4, 5), SQUARE(1080, 1080, 1, 1), WIDE(1920, 1080, 16, 9), STRIP(1920, 400, 24, 5)
    }

    private data class Lit(val at: Int, val ayah: Int, val word: Int)

    private val main = Handler(Looper.getMainLooper())
    private val stopped = AtomicBoolean(false)
    private var transformer: Transformer? = null

    /** Off the main thread: [progress] hears 0 to 100 on the main thread, [done] the finished file, or null. */
    fun make(context: Context, ask: Ask, progress: (Int) -> Unit, done: (File?) -> Unit) {
        val app = context.applicationContext
        stopped.set(false)
        // A thread with a looper: the page view sets up its gestures as it is made
        val worker = HandlerThread(FRAMES).apply { start() }
        Handler(worker.looper).post {
            // The screen's own context draws the pages, so their heads are in the app's chosen language
            val made = runCatching { pictures(app, VideoFrames(context, ask.style, ask.look, ask.phonetics), ask) { main.post { progress(it * DRAWN / 100) } } }.getOrNull()
            worker.quitSafely()
            if (made == null || stopped.get()) main.post { done(null) }
            else main.post { join(app, ask, made, progress, done) }
        }
    }

    /** The video's first picture as [ask] would make it, drawn off the main thread; [done] hears it on the main thread. */
    fun preview(context: Context, ask: Ask, done: (Bitmap?) -> Unit) {
        val worker = HandlerThread(FRAMES).apply { start() }
        Handler(worker.looper).post {
            val shot = runCatching {
                Mushaf.load(context)
                Surahs.load(context)
                val timing = Timing.of(context, ask.surah, ask.reciter) ?: return@runCatching null
                val lit = lits(timing, ask).first()
                if (ask.phonetics) Translit.load(context)
                val frames = VideoFrames(context, ask.style, ask.look, ask.phonetics)
                val page = frames.pageOf(ask.surah, lit.ayah, lit.word)
                val out = createBitmap(ask.shape.wide, ask.shape.tall)
                if (page > 0) frames.draw(page, ask.surah, lit.ayah, lit.word, out)
                frames.release()
                out
            }.getOrNull()
            worker.quitSafely()
            main.post { done(shot) }
        }
    }

    /** The last video asked for, so the screen opens on it again; null before the first. */
    fun lastAsk(context: Context): Ask? {
        val kept = context.getSharedPreferences(ASKED, Context.MODE_PRIVATE)
        if (!kept.contains(SURAH)) return null
        val style = VideoFrames.Style.entries.getOrNull(kept.getInt(STYLE, -1)) ?: VideoFrames.Style.LINE
        val shape = Shape.entries.getOrNull(kept.getInt(SHAPE, -1)) ?: Shape.TALL
        return Ask(kept.getInt(SURAH, 1), kept.getInt(FROM, 1), kept.getInt(TO, 1), kept.getString(VOICE, "").orEmpty(), style,
            VideoLook.of(context), shape, kept.getBoolean(PHONETICS, false))
    }

    fun keepAsk(context: Context, ask: Ask) = context.getSharedPreferences(ASKED, Context.MODE_PRIVATE).edit {
        putInt(SURAH, ask.surah)
        putInt(FROM, ask.from)
        putInt(TO, ask.to)
        putString(VOICE, ask.reciter)
        putInt(STYLE, ask.style.ordinal)
        putInt(SHAPE, ask.shape.ordinal)
        putBoolean(PHONETICS, ask.phonetics)
    }

    fun stop() {
        stopped.set(true)
        transformer?.cancel()
        transformer = null
    }

    // The words as recited, each held until the next begins; the last until the run's last ayah ends
    private fun lits(timing: Timing, ask: Ask): List<Lit> = (ask.from..ask.to).flatMap { ayah ->
        timing.wordTimes(ayah).map { (at, word) -> Lit(at, ayah, word) }
            .ifEmpty { listOf(Lit(timing.startOf(ayah), ayah, 0)) }
    }

    private class Pictures(val files: List<File>, val heldMs: List<Long>, val audio: Uri, val fromMs: Long, val toMs: Long)

    private fun pictures(app: Context, frames: VideoFrames, ask: Ask, drawn: (Int) -> Unit): Pictures? {
        Mushaf.load(app)
        Surahs.load(app)
        if (ask.phonetics) Translit.load(app)
        val timing = Timing.of(app, ask.surah, ask.reciter) ?: return null
        val lits = lits(timing, ask)
        val fromMs = timing.startOf(ask.from).toLong()
        val toMs = timing.endOf(ask.to).toLong()
        if (lits.isEmpty() || toMs <= fromMs) return null

        val dir = File(app.cacheDir, FRAMES).apply { deleteRecursively(); mkdirs() }
        val out = createBitmap(ask.shape.wide, ask.shape.tall)
        val files = ArrayList<File>()
        val held = ArrayList<Long>()
        try {
            lits.forEachIndexed { i, lit ->
                if (stopped.get()) return null
                val start = if (i == 0) fromMs else lit.at.toLong()
                val end = lits.getOrNull(i + 1)?.at?.toLong() ?: toMs
                if (end <= start) return@forEachIndexed
                val page = frames.pageOf(ask.surah, lit.ayah, lit.word)
                if (page > 0) {
                    frames.draw(page, ask.surah, lit.ayah, lit.word, out)
                    val file = File(dir, "%05d.jpg".format(java.util.Locale.ROOT, files.size))
                    file.outputStream().use { out.compress(Bitmap.CompressFormat.JPEG, QUALITY, it) }
                    files += file
                    held += end - start
                } else if (held.isNotEmpty()) {
                    // A word not found on any page holds the picture before it
                    held[held.size - 1] = held.last() + (end - start)
                }
                drawn((i + 1) * 100 / lits.size)
            }
        } finally {
            frames.release()
            out.recycle()
        }
        if (files.isEmpty()) return null
        val audio = if (Downloads.has(app, ask.surah, ask.reciter)) Uri.fromFile(Downloads.file(app, ask.surah, ask.reciter))
                    else Recite.urlFor(app, ask.surah, ask.reciter).toUri()
        return Pictures(files, held, audio, fromMs, toMs)
    }

    // The pictures in order, each for its word's time, over the recitation cut to the same span
    private fun join(app: Context, ask: Ask, made: Pictures, progress: (Int) -> Unit, done: (File?) -> Unit) {
        val video = made.files.mapIndexed { i, file ->
            val item = MediaItem.Builder().setUri(Uri.fromFile(file)).setImageDurationMs(made.heldMs[i]).build()
            EditedMediaItem.Builder(item).setFrameRate(FRAME_RATE).build()
        }
        val sound = MediaItem.Builder().setUri(made.audio).setClippingConfiguration(
            MediaItem.ClippingConfiguration.Builder().setStartPositionMs(made.fromMs).setEndPositionMs(made.toMs).build()
        ).build()
        val composition = Composition.Builder(
            EditedMediaItemSequence.Builder(video).build(),
            EditedMediaItemSequence.Builder(EditedMediaItem.Builder(sound).setRemoveVideo(true).build()).build()
        ).build()

        val file = outFile(app, ask)
        val holder = ProgressHolder()
        val made3 = Transformer.Builder(app)
            .setVideoMimeType(MimeTypes.VIDEO_H264)
            .setAudioMimeType(MimeTypes.AUDIO_AAC)
            .addListener(object : Transformer.Listener {
                override fun onCompleted(composition: Composition, exportResult: ExportResult) {
                    transformer = null
                    File(app.cacheDir, FRAMES).deleteRecursively()
                    done(file)
                }

                override fun onError(composition: Composition, exportResult: ExportResult, exportException: ExportException) {
                    transformer = null
                    file.delete()
                    done(null)
                }
            })
            .build()
        transformer = made3
        made3.start(composition, file.path)
        // Its own progress, polled: the second half of the bar
        main.post(object : Runnable {
            override fun run() {
                val now = transformer ?: return
                if (now.getProgress(holder) == Transformer.PROGRESS_STATE_AVAILABLE) {
                    progress(DRAWN + holder.progress * (100 - DRAWN) / 100)
                }
                main.postDelayed(this, POLL_MS)
            }
        })
    }

    // "Al-Kahf 1-10.mp4": readable wherever it is shared or saved. Only the latest is kept here; the phone keeps them all
    private fun outFile(app: Context, ask: Ask): File {
        val dir = File(app.getExternalFilesDir(null), VIDEOS).apply { deleteRecursively(); mkdirs() }
        val name = Surahs.list().firstOrNull { it.id == ask.surah }?.english ?: ask.surah.toString()
        val clean = name.replace(Regex("[^A-Za-z0-9-]"), "")
        return File(dir, "$clean ${ask.from}-${ask.to}.mp4")
    }

    const val VIDEOS = "video"
    private const val ASKED = "video-ask"
    private const val SURAH = "surah"
    private const val FROM = "from"
    private const val TO = "to"
    private const val VOICE = "reciter"
    private const val STYLE = "style"
    private const val SHAPE = "shape"
    private const val PHONETICS = "phonetics"
    private const val FRAMES = "video_frames"
    private const val FRAME_RATE = 30
    private const val QUALITY = 92
    // Drawing the pictures is the first part of the bar; making the video the rest
    private const val DRAWN = 50
    private const val POLL_MS = 400L
}
