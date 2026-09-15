package com.readqurantoday.quran

import android.app.Application

/**
 * Apply theme and language before any activity is created, so the first screen
 * opens in the correct state rather than flashing the system default.
 */
class App : Application() {
    override fun onCreate() {
        super.onCreate()
        Settings.applyTheme(this)
        Settings.applyLanguage(this)

        /* Build the ayah map in the background; it's needed before recitation starts.
           The searchable text follows it: nothing waits on it but the search box. */
        Thread {
            Mushaf.load(this)
            Ayat.build(this)
            Ayahs.load(this)
        }.apply { priority = Thread.MIN_PRIORITY }.start()
    }
}
