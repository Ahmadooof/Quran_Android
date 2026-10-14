package com.readqurantoday.quran

import android.os.Bundle

/** Downloads: per reciter, what is kept for offline listening, and copies saved to the phone. */
class DownloadsActivity : CardsActivity(R.string.set_downloads) {

    private val mayKeep = SavePermission(this)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Surahs.load(this)
        Recite.load(this)

        DownloadsPane(this, cards, mayKeep::then).build()
        sayBars()
    }
}
