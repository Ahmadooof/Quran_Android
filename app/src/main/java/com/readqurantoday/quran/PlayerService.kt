package com.readqurantoday.quran

import android.app.*
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.support.v4.media.MediaMetadataCompat
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.media.app.NotificationCompat as MediaCompat

// Keeps the player notification and the system media controls alive while audio plays; playback itself lives in Recite
class PlayerService : Service() {

    private lateinit var session: MediaSessionCompat
    private var surahId = 0
    private var art: Bitmap? = null

    private val actionReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                ACTION_TOGGLE -> Recite.toggle()
                ACTION_NEXT   -> Recite.skipAyah(forward = true)
                ACTION_PREV   -> Recite.skipAyah(forward = false)
                ACTION_STOP   -> Recite.stop()
            }
        }
    }

    // Buttons in the shade's media controls, the lock screen, headphones and watches all arrive here
    private val controls = object : MediaSessionCompat.Callback() {
        override fun onPlay() = Recite.play()
        override fun onPause() = Recite.pause()
        override fun onStop() = Recite.stop()
        override fun onSkipToNext() = Recite.skipAyah(forward = true)
        override fun onSkipToPrevious() = Recite.skipAyah(forward = false)
        override fun onSeekTo(pos: Long) = Recite.seek(pos.toInt())
        override fun onCustomAction(action: String?, extras: Bundle?) {
            if (action == CUSTOM_CLOSE) Recite.stop()
        }
    }

    companion object {
        private const val CHANNEL  = "quran_player"
        const val NOTIF_ID = 1001

        private const val ACTION_TOGGLE = "com.readqurantoday.quran.player.TOGGLE"
        private const val ACTION_NEXT   = "com.readqurantoday.quran.player.NEXT"
        private const val ACTION_PREV   = "com.readqurantoday.quran.player.PREV"
        private const val ACTION_STOP   = "com.readqurantoday.quran.player.STOP"
        private const val CUSTOM_CLOSE  = "close"
        private const val EXTRA_SURAH   = "surah_id"
        private const val ART_PX = 512
        // The name fills about half the foreground, so this leaves it a third of the art's height
        private const val ART_SCALE = 0.7f

        private var running: PlayerService? = null

        fun show(context: Context, surah: Int) {
            if (surah <= 0) return
            val intent = Intent(context, PlayerService::class.java)
                .putExtra(EXTRA_SURAH, surah)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                context.startForegroundService(intent)
            else
                context.startService(intent)
        }

        /** Bring the notification and media controls up to date with Recite. */
        fun refresh() {
            running?.updateNotification()
        }

        fun dismiss(context: Context) {
            context.stopService(Intent(context, PlayerService::class.java))
        }
    }

    override fun onCreate() {
        super.onCreate()
        running = this
        Surahs.load(this)
        Recite.load(this)
        createChannel()
        session = MediaSessionCompat(this, "QuranPlayer").also {
            it.setCallback(controls)
            it.setSessionActivity(openApp())
            it.isActive = true
        }

        val filter = IntentFilter().apply {
            addAction(ACTION_TOGGLE)
            addAction(ACTION_NEXT)
            addAction(ACTION_PREV)
            addAction(ACTION_STOP)
        }
        ContextCompat.registerReceiver(
            this, actionReceiver, filter,
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val sid = intent?.getIntExtra(EXTRA_SURAH, 0) ?: 0
        if (sid > 0) surahId = sid

        updateSession()
        val notif = buildNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIF_ID, notif,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
        } else {
            startForeground(NOTIF_ID, notif)
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        if (running === this) running = null
        try { unregisterReceiver(actionReceiver) } catch (_: Exception) {}
        session.release()
    }

    fun updateNotification() {
        // Stopping also refreshes; a stopped player must not bring its notification back
        if (Recite.playing == 0) return
        surahId = Recite.playing
        updateSession()
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(NOTIF_ID, buildNotification())
    }

    // Names follow the app's language, which may differ from the phone's
    private fun words() = Settings.inLanguage(this)

    private fun arabic() = Settings.language(this) == "ar"

    private fun surahName(): String {
        val s = Surahs.list().firstOrNull { it.id == surahId } ?: return ""
        return words().getString(R.string.surah_named, if (arabic()) s.name else s.english)
    }

    private fun reciterName(): String {
        val voice = Recite.chosen(this) ?: return ""
        return if (arabic()) voice.nameAr else voice.name
    }

    private fun openApp(): PendingIntent = PendingIntent.getActivity(
        this, 0,
        Intent(this, ReaderActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        },
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )

    // Action buttons go to the BroadcastReceiver, not onStartCommand
    private fun broadcast(code: Int, action: String): PendingIntent = PendingIntent.getBroadcast(
        this, code,
        Intent(action).setPackage(packageName),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )

    private fun buildNotification(): Notification {
        val playing = Recite.wantsToPlay()
        val text = words()

        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_surahs)
            .setLargeIcon(icon())
            .setContentTitle(surahName())
            .setContentText(reciterName())
            .setContentIntent(openApp())
            .setDeleteIntent(broadcast(4, ACTION_STOP))
            .setOngoing(playing)
            .setShowWhen(false)
            .setSilent(true)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setCategory(NotificationCompat.CATEGORY_TRANSPORT)
            .addAction(R.drawable.ic_skip_previous, text.getString(R.string.previous_ayah), broadcast(2, ACTION_PREV))
            .addAction(
                if (playing) R.drawable.ic_pause else R.drawable.ic_play,
                text.getString(if (playing) R.string.stop else R.string.play),
                broadcast(1, ACTION_TOGGLE)
            )
            .addAction(R.drawable.ic_skip_next, text.getString(R.string.next_ayah), broadcast(3, ACTION_NEXT))
            .addAction(R.drawable.ic_close, text.getString(R.string.close), broadcast(5, ACTION_STOP))
            .setStyle(
                MediaCompat.MediaStyle()
                    .setMediaSession(session.sessionToken)
                    .setShowActionsInCompactView(0, 1, 2)
            )
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .build()
    }

    // Media controls crop the art to a wide strip, so the name sits small in the middle of the icon's ground
    private fun icon(): Bitmap {
        art?.let { return it }
        val made = Bitmap.createBitmap(ART_PX, ART_PX, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(made)
        canvas.drawColor(getColor(R.color.icon_ground))
        ContextCompat.getDrawable(this, R.drawable.ic_launcher_foreground)?.let { name ->
            val size = (ART_PX * ART_SCALE).toInt()
            val at = (ART_PX - size) / 2
            name.setBounds(at, at, at + size, at + size)
            name.draw(canvas)
        }
        art = made
        return made
    }

    private fun updateSession() {
        session.setMetadata(
            MediaMetadataCompat.Builder()
                .putString(MediaMetadataCompat.METADATA_KEY_TITLE, surahName())
                .putString(MediaMetadataCompat.METADATA_KEY_ARTIST, reciterName())
                .putString(MediaMetadataCompat.METADATA_KEY_ALBUM, words().getString(R.string.app_name))
                .putLong(MediaMetadataCompat.METADATA_KEY_DURATION, Recite.length().toLong())
                .putBitmap(MediaMetadataCompat.METADATA_KEY_ALBUM_ART, icon())
                .build()
        )
        val state = when {
            Recite.waiting()     -> PlaybackStateCompat.STATE_BUFFERING
            Recite.wantsToPlay() -> PlaybackStateCompat.STATE_PLAYING
            else                 -> PlaybackStateCompat.STATE_PAUSED
        }
        session.setPlaybackState(
            PlaybackStateCompat.Builder()
                // Speed 0 while silent, so the system's progress bar holds still
                .setState(state, Recite.at().toLong(), if (Recite.isPlaying()) 1f else 0f)
                .setActions(
                    PlaybackStateCompat.ACTION_PLAY or
                    PlaybackStateCompat.ACTION_PAUSE or
                    PlaybackStateCompat.ACTION_PLAY_PAUSE or
                    PlaybackStateCompat.ACTION_STOP or
                    PlaybackStateCompat.ACTION_SEEK_TO or
                    PlaybackStateCompat.ACTION_SKIP_TO_NEXT or
                    PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS
                )
                .addCustomAction(CUSTOM_CLOSE, words().getString(R.string.close), R.drawable.ic_close)
                .build()
        )
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(NotificationManager::class.java)
            if (nm.getNotificationChannel(CHANNEL) != null) return
            NotificationChannel(
                CHANNEL,
                getString(R.string.app_name),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                setShowBadge(false)
            }.also { nm.createNotificationChannel(it) }
        }
    }
}
