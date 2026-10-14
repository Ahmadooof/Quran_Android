package com.readqurantoday.quran

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import androidx.annotation.OptIn
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

    data class Ask(val surah: Int, val from: Int, val to: Int, val reciter: String, val style: VideoFrames.Style)

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
            val made = runCatching { pictures(app, VideoFrames(context, ask.style), ask) { main.post { progress(it * DRAWN / 100) } } }.getOrNull()
            worker.quitSafely()
            if (made == null || stopped.get()) main.post { done(null) }
            else main.post { join(app, ask, made, progress, done) }
        }
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
        val timing = Timing.of(app, ask.surah, ask.reciter) ?: return null
        val lits = lits(timing, ask)
        val fromMs = timing.startOf(ask.from).toLong()
        val toMs = timing.endOf(ask.to).toLong()
        if (lits.isEmpty() || toMs <= fromMs) return null

        val dir = File(app.cacheDir, FRAMES).apply { deleteRecursively(); mkdirs() }
        val out = createBitmap(VideoFrames.WIDE, VideoFrames.TALL)
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

    // "Al-Kahf 1-10.mp4": readable wherever it is shared or saved
    private fun outFile(app: Context, ask: Ask): File {
        val dir = File(app.getExternalFilesDir(null), VIDEOS).apply { mkdirs() }
        val name = Surahs.list().firstOrNull { it.id == ask.surah }?.english ?: ask.surah.toString()
        val clean = name.replace(Regex("[^A-Za-z0-9-]"), "")
        return File(dir, "$clean ${ask.from}-${ask.to}.mp4")
    }

    const val VIDEOS = "video"
    private const val FRAMES = "video_frames"
    private const val FRAME_RATE = 30
    private const val QUALITY = 92
    // Drawing the pictures is the first part of the bar; making the video the rest
    private const val DRAWN = 50
    private const val POLL_MS = 400L
}
