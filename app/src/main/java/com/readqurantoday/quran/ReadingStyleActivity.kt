package com.readqurantoday.quran

import android.content.res.ColorStateList
import android.os.Bundle
import android.view.View
import android.widget.ImageView
import androidx.appcompat.app.AppCompatActivity

/** How a page looks: the preview line, and the colour and weight of what can be styled. */
class ReadingStyleActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_reading_style)

        findViewById<ImageView>(R.id.style_back_icon).imageTintList =
            ColorStateList.valueOf(getColor(R.color.accent))
        findViewById<View>(R.id.style_back).setOnClickListener { finish() }

        SettingsPane(this, findViewById(R.id.style_groups)).readingStyle()
        sayBars()
    }

    private fun sayBars() {
        showBars(
            roof = groundOf(findViewById(R.id.style_head)),
            floor = groundOf(findViewById(R.id.style_root))
        )
    }

    /* Asked for and painted again with focus, as the index does: see showBars. */
    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) sayBars()
    }
}
