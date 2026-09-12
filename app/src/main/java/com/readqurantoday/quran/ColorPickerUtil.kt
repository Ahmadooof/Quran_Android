package com.readqurantoday.quran

import android.app.AlertDialog
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView

/* Quick picks, so the sliders are for tuning rather than for arriving. */
private val PRESETS = intArrayOf(
    0xFF1053A8.toInt(), 0xFF3F9BD1.toInt(), 0xFF128C8C.toInt(), 0xFF1E7A4C.toInt(),
    0xFFD9B95F.toInt(), 0xFFD2691E.toInt(), 0xFFB03030.toInt(), 0xFF6B3FA0.toInt()
)

/** RGB colour picker: live preview, three channel sliders, and quick presets. */
fun showColorPicker(ctx: Context, initial: Int, onPick: (Int) -> Unit) {
    val den = ctx.resources.displayMetrics.density
    fun dp(v: Float) = (v * den).toInt()

    var r = Color.red(initial)
    var g = Color.green(initial)
    var b = Color.blue(initial)

    val root = LinearLayout(ctx).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(22f), dp(20f), dp(22f), dp(6f))
    }

    root.addView(TextView(ctx).apply {
        setText(R.string.color_choose)
        setTextColor(ctx.getColor(R.color.text))
        textSize = 15f
        gravity = Gravity.CENTER
        typeface = Typeface.DEFAULT_BOLD
    })

    // --- preview: a wide chip that carries its own hex value ---
    val previewFill = GradientDrawable().apply {
        cornerRadius = 14 * den
        setColor(initial)
    }
    val hex = TextView(ctx).apply {
        textSize = 13f
        typeface = Typeface.MONOSPACE
    }
    root.addView(LinearLayout(ctx).apply {
        background = previewFill
        gravity = Gravity.CENTER
        addView(hex)
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, dp(58f)
        ).apply { topMargin = dp(16f); bottomMargin = dp(18f) }
    })

    fun current() = Color.rgb(r, g, b)

    fun refresh() {
        val c = current()
        previewFill.setColor(c)
        hex.text = String.format("#%06X", 0xFFFFFF and c)
        /* Label flips with the swatch's brightness so it stays legible on any pick. */
        val lum = (0.299 * r + 0.587 * g + 0.114 * b) / 255.0
        hex.setTextColor(if (lum > 0.6) Color.BLACK else Color.WHITE)
    }

    fun slider(value: Int, tint: Int) = SeekBar(ctx).apply {
        max = 255
        progress = value
        progressTintList = ColorStateList.valueOf(tint)
        thumbTintList = ColorStateList.valueOf(tint)
        progressBackgroundTintList = ColorStateList.valueOf(ctx.getColor(R.color.line))
        layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
    }

    val rBar = slider(r, 0xFFE04F5F.toInt())
    val gBar = slider(g, 0xFF3FA85F.toInt())
    val bBar = slider(b, 0xFF3F7FD1.toInt())

    fun syncBars() {
        rBar.progress = r
        gBar.progress = g
        bBar.progress = b
    }

    /* One channel: name, slider, and the value it is sitting at. */
    fun channelRow(name: String, bar: SeekBar, onVal: (Int) -> Unit): View {
        val readout = TextView(ctx).apply {
            text = bar.progress.toString()
            setTextColor(ctx.getColor(R.color.text_mute))
            textSize = 12f
            gravity = Gravity.END
            typeface = Typeface.MONOSPACE
            layoutParams = LinearLayout.LayoutParams(dp(30f), ViewGroup.LayoutParams.WRAP_CONTENT)
        }
        bar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar, p: Int, fromUser: Boolean) {
                onVal(p)
                readout.text = p.toString()
                refresh()
            }
            override fun onStartTrackingTouch(s: SeekBar) = Unit
            override fun onStopTrackingTouch(s: SeekBar) = Unit
        })
        return LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(2f) }
            addView(TextView(ctx).apply {
                text = name
                setTextColor(ctx.getColor(R.color.text_mute))
                textSize = 12f
                typeface = Typeface.DEFAULT_BOLD
                layoutParams = LinearLayout.LayoutParams(dp(18f), ViewGroup.LayoutParams.WRAP_CONTENT)
            })
            addView(bar)
            addView(readout)
        }
    }

    root.addView(channelRow("R", rBar) { r = it })
    root.addView(channelRow("G", gBar) { g = it })
    root.addView(channelRow("B", bBar) { b = it })

    // --- quick picks ---
    root.addView(LinearLayout(ctx).apply {
        orientation = LinearLayout.HORIZONTAL
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(16f) }
        for (c in PRESETS) addView(View(ctx).apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(c)
                setStroke(dp(1f), ctx.getColor(R.color.line))
            }
            layoutParams = LinearLayout.LayoutParams(0, dp(28f), 1f).apply {
                marginStart = dp(3f)
                marginEnd = dp(3f)
            }
            isClickable = true
            setOnClickListener {
                r = Color.red(c); g = Color.green(c); b = Color.blue(c)
                syncBars()
                refresh()
            }
        })
    })

    refresh()

    val dialog = AlertDialog.Builder(ctx)
        .setView(root)
        .setPositiveButton(android.R.string.ok) { _, _ -> onPick(current()) }
        .setNegativeButton(android.R.string.cancel, null)
        .create()

    /* Round the window itself, not an inner card: the button bar belongs to the
       window, and a rounded card inside a square one shows the corners. */
    dialog.window?.setBackgroundDrawable(GradientDrawable().apply {
        cornerRadius = 20 * den
        setColor(ctx.getColor(R.color.surface))
    })
    dialog.show()
    dialog.getButton(AlertDialog.BUTTON_POSITIVE)?.setTextColor(ctx.getColor(R.color.accent))
    dialog.getButton(AlertDialog.BUTTON_NEGATIVE)?.setTextColor(ctx.getColor(R.color.text_mute))
}
