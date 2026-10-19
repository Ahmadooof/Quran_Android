package com.readqurantoday.quran

import android.app.Activity
import android.content.res.ColorStateList
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView

/** A row with minus and plus [step] at a time; the number opens a box to type any amount in [range]. */
fun Activity.stepperRow(parent: ViewGroup, label: Int, value: Int, range: IntRange, step: Int = 1, changed: (Int) -> Unit): View =
    layoutInflater.inflate(R.layout.row_stepper, parent, false).apply {
        val tint = ColorStateList.valueOf(getColor(R.color.accent))
        val number = findViewById<TextView>(R.id.step_value)
        val less = findViewById<ImageView>(R.id.step_less)
        val more = findViewById<ImageView>(R.id.step_more)
        var shown = value
        val settle = Runnable { changed(shown) }

        fun show() {
            number.text = figures(shown, resources)
            number.contentDescription = getString(label) + " " + figures(shown, resources)
            for ((button, by) in listOf(less to -step, more to step)) {
                button.isEnabled = (shown + by).coerceIn(range) != shown
                button.alpha = if (button.isEnabled) 1f else STEP_OFF
            }
        }

        findViewById<TextView>(R.id.step_label).setText(label)
        number.setOnClickListener { askNumber(getString(label), getString(R.string.hifz_keep), shown, range, changed) }
        for ((button, by) in listOf(less to -step, more to step)) button.apply {
            imageTintList = tint
            // The number moves at once; the screen is rebuilt only when the taps pause, so each tap's ripple is seen
            setOnClickListener {
                shown = (shown + by).coerceIn(range)
                show()
                number.removeCallbacks(settle)
                number.postDelayed(settle, STEP_SETTLE)
            }
        }
        show()
    }

private const val STEP_OFF = 0.4f
private const val STEP_SETTLE = 350L
