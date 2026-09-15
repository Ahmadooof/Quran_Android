package com.readqurantoday.quran

import android.app.Application

// Theme and language are applied here so the first screen does not flash the defaults
class App : Application() {
    override fun onCreate() {
        super.onCreate()
        Settings.applyTheme(this)
        Settings.applyLanguage(this)

        // The ayah map is needed before recitation; the search text only by the search box
        Thread {
            Mushaf.load(this)
            Ayat.build(this)
            Ayahs.load(this)
        }.apply { priority = Thread.MIN_PRIORITY }.start()
    }
}
