package com.readqurantoday.quran

import android.app.Activity
import android.content.res.ColorStateList
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView

/** A row with minus and plus a step at a time; the number opens a box to type any amount in [range]. */
fun Activity.stepperRow(parent: ViewGroup, label: Int, value: Int, range: IntRange, changed: (Int) -> Unit): View =
    layoutInflater.inflate(R.layout.row_stepper, parent, false).apply {
        val tint = ColorStateList.valueOf(getColor(R.color.accent))
        findViewById<TextView>(R.id.step_label).setText(label)
        findViewById<TextView>(R.id.step_value).apply {
            text = figures(value, resources)
            contentDescription = getString(label) + " " + figures(value, resources)
            setOnClickListener { askNumber(getString(label), getString(R.string.hifz_keep), value, range, changed) }
        }
        for ((id, step) in listOf(R.id.step_less to -1, R.id.step_more to 1)) findViewById<ImageView>(id).apply {
            val next = value + step
            imageTintList = tint
            isEnabled = next in range
            alpha = if (isEnabled) 1f else STEP_OFF
            setOnClickListener { changed(next) }
        }
    }

private const val STEP_OFF = 0.4f
