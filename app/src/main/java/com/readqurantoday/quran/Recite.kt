package com.readqurantoday.quran

import android.annotation.SuppressLint
import android.content.Context
import android.net.Uri
import androidx.core.net.toUri
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import org.json.JSONObject

/** One ExoPlayer for the whole app; outlives any individual screen. */
object Recite {

    private const val BUCKET = "https://audio.readqurantoday.com"

    data class Reciter(val id: String, val name: String, val nameAr: String, val noteAr: String)

    private val all = ArrayList<Reciter>()
    private var fallback = ""

    private var player: ExoPlayer? = null

    @SuppressLint("StaticFieldLeak") // applicationContext only — no Activity reference
    private var app: Context? = null

    /* Where we seeked to, until currentPosition has caught up (avoids a flicker). */
    private var wanted = -1

    /* ExoPlayer reports rendered audio position, so no pipeline-delay correction needed. */
    private const val BEHIND = 0

    var playing = 0
        private set

    var onChange: (() -> Unit)? = null

    // Order matters: the repeat sheet maps choices to modes by position
    const val ONCE = 0
    const val AYAH = 1
    const val PAGE = 2
    const val SURAH = 3

    var repeat = ONCE

    // Where page repeat returns to if the audio ends mid-loop, in ms
    var loopFrom = 0

    fun at(): Int {
        val p = player ?: return if (wanted >= 0) wanted else 0

        val now = try {
            p.currentPosition.toInt()
        } catch (_: Exception) {
            0
        }

        if (wanted >= 0) {
            /* Seek lands on a frame; within 500ms counts as arrived. */
            if (kotlin.math.abs(now - wanted) < 500) {
                wanted = -1
                return if (isPlaying()) now + BEHIND else now
            }
            return wanted
        }
        return if (isPlaying()) now + BEHIND else now
    }

    fun seek(ms: Int) {
        wanted = ms.coerceAtLeast(0)
        player?.seekTo(wanted.toLong())
        PlayerService.refresh()
    }

    // The screen and the system media controls both follow every change
    private fun changed() {
        onChange?.invoke()
        PlayerService.refresh()
    }

    /** Length of the loaded surah in ms, or 0 while unknown. */
    fun length(): Int = player?.duration?.takeIf { it > 0 }?.toInt() ?: 0

    fun play() {
        if (!wantsToPlay()) toggle()
    }

    fun pause() {
        if (wantsToPlay()) toggle()
    }

    /** Jump to the next ayah, or back to the start of this one (the previous one if just begun). */
    fun skipAyah(forward: Boolean) {
        val where = app ?: return
        val voice = chosen(where) ?: return
        val timing = Timing.of(where, playing, voice.id) ?: return
        val at = at()
        val now = timing.ayahAt(at)
        if (forward && now >= timing.count) {
            if (playing < 114) start(where, playing + 1, 0, wantsToPlay())
            return
        }
        val target = when {
            forward -> now + 1
            at - timing.startOf(now) > RESTART_WITHIN -> now
            else -> now - 1
        }.coerceIn(1, timing.count)
        seek(timing.startOf(target))
        changed()
    }

    // Past this far into an ayah, "previous" restarts it rather than going back one
    private const val RESTART_WITHIN = 2000

    fun load(context: Context) {
        if (all.isNotEmpty()) return
        val text = context.assets.open("data/recitations.json").use { it.readBytes() }
        val root = JSONObject(String(text, Charsets.UTF_8))
        fallback = root.optString("default")

        val list = root.optJSONArray("recitations") ?: return
        for (i in 0 until list.length()) {
            val o = list.getJSONObject(i)
            all.add(
                Reciter(
                    id = o.getString("id"),
                    name = o.optString("name"),
                    nameAr = o.optString("nameAr"),
                    noteAr = o.optString("noteAr")
                )
            )
        }
    }

    fun reciters(): List<Reciter> = all

    fun chosen(context: Context): Reciter? {
        val id = Settings.reciter(context) ?: fallback
        return all.firstOrNull { it.id == id } ?: all.firstOrNull()
    }

    fun choose(context: Context, id: String) {
        Settings.setReciter(context, id)
    }

    /* Returns true while buffering too, unlike isPlaying() which is false until audio flows. */
    fun wantsToPlay() = playing != 0 && (player?.playWhenReady == true)

    /** Resolve the audio URL from the timing file, falling back to a default path. */
    fun urlFor(context: Context, surah: Int, reciter: String): String {
        val padded = surah.toString().padStart(3, '0')
        val path = try {
            val asset = "surah/$surah/$padded.$reciter.timing.json"
            val text = context.assets.open(asset).use { it.readBytes() }
            JSONObject(String(text, Charsets.UTF_8)).optString("audioPath")
        } catch (_: Exception) {
            ""
        }
        return "$BUCKET/${path.ifEmpty { "$reciter/$padded.mp3" }}"
    }

    fun start(context: Context, surah: Int, from: Int = 0, andPlay: Boolean = true) {
        if (surah <= 0) return
        val voice = chosen(context) ?: return
        stop()

        app = context.applicationContext
        playing = surah
        if (from > 0) wanted = from

        val uri = if (Downloads.has(context.applicationContext, surah, voice.id))
            Uri.fromFile(Downloads.file(context, surah, voice.id))
        else urlFor(context.applicationContext, surah, voice.id).toUri()

        player = ExoPlayer.Builder(context.applicationContext).build().apply {
            addListener(object : Player.Listener {
                override fun onPlaybackStateChanged(state: Int) {
                    if (state == Player.STATE_ENDED) {
                        // A page that ends the surah loops here too: the audio ends before the controller's check
                        if (repeat == SURAH || repeat == PAGE) {
                            val back = if (repeat == PAGE) loopFrom else 0
                            seekTo(back.toLong())
                            play()
                            wanted = back
                            changed()
                            return
                        }
                        val next = playing + 1
                        val where = app
                        if (next in 2..114 && where != null) {
                            start(where, next, 0, true)
                        } else {
                            stop()
                        }
                        return
                    }
                    changed()
                }

                override fun onPlayerError(error: PlaybackException) {
                    stop()
                }

                override fun onIsPlayingChanged(isPlaying: Boolean) {
                    app?.let { PlayerService.show(it, playing) }
                    changed()
                }
            })

            setMediaItem(MediaItem.fromUri(uri))

            /* Seek before prepare so ExoPlayer starts at the right position. */
            if (from > 0) seekTo(from.toLong())
            prepare()
            if (andPlay) play()
        }
        app?.let { PlayerService.show(it, surah) }
        changed()
    }

    fun toggle() {
        val p = player ?: return
        /* Check playWhenReady, not isPlaying — isPlaying is false while buffering. */
        if (wantsToPlay()) p.pause() else p.play()
        changed()
    }

    fun isPlaying() = player?.isPlaying == true

    /** The mushaf page the recitation is on, or 0 when nothing is playing. */
    fun playingPage(context: Context): Int {
        val surah = playing
        if (surah == 0) return 0
        val voice = chosen(context)?.id
        val timing = voice?.let { Timing.of(context, surah, it) }
        val ayah = timing?.ayahAt(at())?.coerceAtLeast(1) ?: 1
        val page = if (Ayat.ready) Ayat.pageOf(surah, ayah) else 0
        return if (page > 0) page else Surahs.list().firstOrNull { it.id == surah }?.from ?: 0
    }

    // Play pressed but no sound yet; shows the spinner
    fun waiting() = wantsToPlay() && !isPlaying()

    fun stop() {
        val ctx = app
        val p = player
        player = null
        playing = 0
        wanted = -1
        p?.release()
        ctx?.let { PlayerService.dismiss(it) }
        changed()
    }
}
