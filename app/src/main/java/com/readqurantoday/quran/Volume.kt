package com.readqurantoday.quran

import android.app.Activity
import android.media.AudioManager

/** Said as sound starts with the media volume all the way down, so silence is not taken for a fault. */
fun Activity.warnIfMuted() {
    val audio = getSystemService(AudioManager::class.java) ?: return
    if (audio.getStreamVolume(AudioManager.STREAM_MUSIC) == 0) notice(getString(R.string.volume_off))
}
