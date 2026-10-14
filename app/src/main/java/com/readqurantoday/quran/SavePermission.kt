package com.readqurantoday.quran

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts

/** Below Android 10 a save to a shared folder needs the storage permission, asked on the first save. Made with the screen. */
class SavePermission(private val host: ComponentActivity) {

    // The save that asked, run once the permission comes back granted
    private var afterGrant: (() -> Unit)? = null

    private val ask = host.registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        val next = afterGrant
        afterGrant = null
        if (granted) next?.invoke() else host.notice(host.getString(R.string.dl_save_denied))
    }

    fun then(save: () -> Unit) {
        val needed = saveNeedsPermission &&
            host.checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED
        if (!needed) return save()
        afterGrant = save
        ask.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
    }
}
