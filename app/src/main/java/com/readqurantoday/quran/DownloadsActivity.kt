package com.readqurantoday.quran

import android.Manifest
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.os.Bundle
import android.view.View
import android.widget.ImageView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity

/** Downloads: per reciter, what is kept for offline listening, and copies saved to the phone. */
class DownloadsActivity : AppCompatActivity() {

    /* What to run once the storage permission comes back granted: a save that asked. */
    private var afterGrant: (() -> Unit)? = null

    private val permission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        val next = afterGrant
        afterGrant = null
        if (granted) next?.invoke() else notice(getString(R.string.dl_save_denied))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Surahs.load(this)
        Recite.load(this)
        setContentView(R.layout.activity_downloads)

        findViewById<ImageView>(R.id.dl_back_icon).imageTintList =
            ColorStateList.valueOf(getColor(R.color.accent))
        findViewById<View>(R.id.dl_back).setOnClickListener { finish() }

        DownloadsPane(this, findViewById(R.id.dl_groups), ::whenMaySave).build()
        sayBars()
    }

    // Below Android 10 writing to Download needs the storage permission, asked on first save
    private fun whenMaySave(save: () -> Unit) {
        val needed = saveNeedsPermission &&
            checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED
        if (!needed) return save()
        afterGrant = save
        permission.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
    }

    private fun sayBars() {
        showBars(
            roof = groundOf(findViewById(R.id.dl_head)),
            floor = groundOf(findViewById(R.id.dl_root))
        )
    }

    /* Asked for and painted again with focus, as the other screens do: see showBars. */
    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) sayBars()
    }
}
