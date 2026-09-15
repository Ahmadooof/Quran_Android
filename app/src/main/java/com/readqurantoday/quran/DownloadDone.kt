package com.readqurantoday.quran

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Copies surahs marked "save to phone" out as soon as their downloads finish, even with the app closed. */
class DownloadDone : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val app = context.applicationContext
        val result = goAsync()
        Thread {
            try {
                saveFinishedDownloads(app)
            } finally {
                result.finish()
            }
        }.start()
    }
}
