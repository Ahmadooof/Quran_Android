package com.readqurantoday.quran

import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import androidx.annotation.OptIn
import androidx.core.net.toUri
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.HttpDataSource
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.CacheWriter
import androidx.media3.datasource.cache.ContentMetadata
import androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.upstream.DefaultLoadErrorHandlingPolicy
import java.io.File
import java.util.concurrent.Executors
import java.util.Locale

// One word said on its own, from the word-by-word recordings; apart from Recite so a surah keeps its place
@OptIn(UnstableApi::class)
object WordVoice {

    private const val BASE = "https://audio.readqurantoday.com/wbw"

    /* A few thousand words; the least recently heard go first. */
    private const val CACHE_BYTES = 40L * 1024 * 1024

    /* Past this with no sound the connection is taken as gone, rather than left spinning. */
    private const val GIVE_UP_MS = 8000L
    private const val NET_TIMEOUT_MS = 5000

    private var cache: SimpleCache? = null
    private var sources: CacheDataSource.Factory? = null
    private var player: ExoPlayer? = null
    private val main = Handler(Looper.getMainLooper())
    private val ahead = Executors.newSingleThreadExecutor { r -> Thread(r, "word-ahead") }

    /** How a word ended: heard (or cut short), no recording of it exists, or it could not be fetched. */
    enum class End { HEARD, MISSING, UNREACHABLE }

    private var changed: (() -> Unit)? = null
    private var ended: ((End) -> Unit)? = null
    private val giveUp = Runnable { finish(End.UNREACHABLE) }

    /** Fetching: the button shows it, and a second tap is not a second request. */
    var loading = false
        private set

    val saying get() = ended != null

    // Latin digits whatever the phone's language: an Arabic-digit name is a file the server does not have
    private fun uri(surah: Int, ayah: Int, word: Int): Uri =
        "$BASE/%03d_%03d_%03d.mp3".format(Locale.ROOT, surah, ayah, word + 1).toUri()

    private fun sources(context: Context): CacheDataSource.Factory = sources ?: run {
        val app = context.applicationContext
        val held = SimpleCache(
            File(app.cacheDir, "words"), LeastRecentlyUsedCacheEvictor(CACHE_BYTES), StandaloneDatabaseProvider(app)
        )
        cache = held
        CacheDataSource.Factory()
            .setCache(held)
            // Files as well as the network: a word kept on the phone plays from there
            .setUpstreamDataSourceFactory(
                DefaultDataSource.Factory(app, DefaultHttpDataSource.Factory()
                    .setConnectTimeoutMs(NET_TIMEOUT_MS)
                    .setReadTimeoutMs(NET_TIMEOUT_MS))
            )
            .also { sources = it }
    }

    // Built once and kept: a new player per tap cost most of the wait
    private fun player(context: Context): ExoPlayer = player ?: run {
        val factory = DefaultMediaSourceFactory(sources(context))
            .setLoadErrorHandlingPolicy(DefaultLoadErrorHandlingPolicy(1))
        ExoPlayer.Builder(context.applicationContext).setMediaSourceFactory(factory).build().apply {
            addListener(object : Player.Listener {
                override fun onPlaybackStateChanged(state: Int) {
                    if (state == Player.STATE_READY && loading) {
                        loading = false
                        main.removeCallbacks(giveUp)
                        changed?.invoke()
                    }
                    if (state == Player.STATE_ENDED) finish(End.HEARD)
                }
                // A 404 is a word the recordings lack; anything else is the connection
                override fun onPlayerError(error: PlaybackException) {
                    val code = (error.cause as? HttpDataSource.InvalidResponseCodeException)?.responseCode
                    finish(if (code == 404) End.MISSING else End.UNREACHABLE)
                }
            })
        }.also { player = it }
    }

    /** Whether this word is already on the phone, so it plays at once and without internet. */
    fun held(context: Context, surah: Int, ayah: Int, word: Int): Boolean {
        sources(context)
        val c = cache ?: return false
        val key = uri(surah, ayah, word).toString()
        val length = ContentMetadata.getContentLength(c.getContentMetadata(key))
        return length != C.LENGTH_UNSET.toLong() && c.isCached(key, 0, length)
    }

    /** Fetches a word the reader is likely to ask for next, so Word has it before the tap. */
    fun warm(context: Context, surah: Int, ayah: Int, word: Int) {
        if (surah <= 0 || ayah <= 0 || word < 0 || held(context, surah, ayah, word)) return
        if (WordPack.file(context, surah, ayah, word) != null) return
        val source = sources(context).createDataSource()
        val spec = DataSpec(uri(surah, ayah, word))
        ahead.execute {
            try {
                CacheWriter(source, spec, null, null).cache()
            } catch (_: Exception) {
                // No connection: the tap will say so
            }
        }
    }

    /**
     * Says [word] (counted from 0, as the page counts) of [surah]:[ayah].
     * [changed] runs when loading ends; [done] is told how it ended.
     */
    fun say(context: Context, surah: Int, ayah: Int, word: Int, changed: () -> Unit, done: (End) -> Unit) {
        stop()
        this.changed = changed
        ended = done
        loading = true
        main.postDelayed(giveUp, GIVE_UP_MS)
        player(context).apply {
            // From the phone when every word was fetched for offline, else from the recordings online
            setMediaItem(MediaItem.fromUri(WordPack.file(context, surah, ayah, word)?.toUri() ?: uri(surah, ayah, word)))
            prepare()
            play()
        }
    }

    /** Cut short; the caller is told, as a word that ended. */
    fun stop() {
        if (saying) finish(End.HEARD)
    }

    private fun finish(how: End) {
        main.removeCallbacks(giveUp)
        player?.apply {
            stop()
            clearMediaItems()
        }
        loading = false
        changed = null
        val done = ended
        ended = null
        done?.invoke(how)
    }
}
