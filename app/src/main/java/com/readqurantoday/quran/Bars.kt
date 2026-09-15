package com.readqurantoday.quran

import android.app.Activity
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.LayerDrawable
import android.os.Build
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat

// Bar colours are read from the views behind them, so a background change needs no second edit

/** Paint the status bar [roof] and the navigation bar [floor], icons to suit. Null leaves a bar as it is. */
fun Activity.paintBars(roof: Int?, floor: Int?) {
    val under = floor ?: roof
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.VANILLA_ICE_CREAM) {
        // Bar colours are ignored from Android 15, so coloured strips sit behind the bars instead
        window.isNavigationBarContrastEnforced = false
        window.isStatusBarContrastEnforced = false
        val content = findViewById<ViewGroup>(android.R.id.content)
        if (roof != null) strip(content, Edge.TOP).setBackgroundColor(roof)
        if (under != null) for (edge in listOf(Edge.BOTTOM, Edge.LEFT, Edge.RIGHT)) strip(content, edge).setBackgroundColor(under)
    } else {
        @Suppress("DEPRECATION")
        if (roof != null) window.statusBarColor = roof
        @Suppress("DEPRECATION")
        if (under != null) window.navigationBarColor = under
    }

    // Icon contrast follows the colour, so a new palette stays legible
    WindowInsetsControllerCompat(window, window.decorView).apply {
        if (roof != null) isAppearanceLightStatusBars = pale(roof)
        if (under != null) isAppearanceLightNavigationBars = pale(under)
    }
}

// Asks for the bars back before painting: an immersive screen underneath leaves them bare. Needs window focus
fun Activity.showBars(roof: Int?, floor: Int?) {
    WindowInsetsControllerCompat(window, window.decorView).apply {
        systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_DEFAULT
        show(WindowInsetsCompat.Type.systemBars())
    }
    paintBars(roof, floor)
}

private enum class Edge { TOP, BOTTOM, LEFT, RIGHT }

// One strip per edge, added once and sized to whatever system bar sits there
private fun strip(content: ViewGroup, edge: Edge): View {
    val tag = "bar-strip-$edge"
    content.findViewWithTag<View>(tag)?.let { return it }
    val gravity = when (edge) {
        Edge.TOP -> Gravity.TOP
        Edge.BOTTOM -> Gravity.BOTTOM
        Edge.LEFT -> Gravity.START
        Edge.RIGHT -> Gravity.END
    }
    val view = View(content.context).apply { this.tag = tag }
    val vertical = edge == Edge.TOP || edge == Edge.BOTTOM
    content.addView(view, FrameLayout.LayoutParams(
        if (vertical) ViewGroup.LayoutParams.MATCH_PARENT else 0,
        if (vertical) 0 else ViewGroup.LayoutParams.MATCH_PARENT,
        gravity
    ))
    ViewCompat.setOnApplyWindowInsetsListener(view) { v, insets ->
        val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
        val size = when (edge) {
            Edge.TOP -> bars.top
            Edge.BOTTOM -> bars.bottom
            Edge.LEFT -> bars.left
            Edge.RIGHT -> bars.right
        }
        v.layoutParams = v.layoutParams.apply { if (vertical) height = size else width = size }
        insets
    }
    ViewCompat.requestApplyInsets(view)
    return view
}

/** What touches the top of the screen in [pane]: its first child if painted, else the pane's own ground. */
fun topOf(pane: View?): Int? = groundOf((pane as? ViewGroup)?.getChildAt(0)) ?: groundOf(pane)

/** The flat colour a view is painted, looking inside layered and rippled backgrounds. */
fun groundOf(v: View?): Int? = v?.background?.let { flat(it) }

private fun flat(d: Drawable): Int? = when (d) {
    is ColorDrawable -> d.color
    /* Covers RippleDrawable too — it is a LayerDrawable underneath. */
    is LayerDrawable -> (0 until d.numberOfLayers).firstNotNullOfOrNull { flat(d.getDrawable(it)) }
    else -> null
}

/* Light enough to need dark icons on it. */
private fun pale(c: Int) =
    (0.299 * Color.red(c) + 0.587 * Color.green(c) + 0.114 * Color.blue(c)) / 255.0 > 0.5
