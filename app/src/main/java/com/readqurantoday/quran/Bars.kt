package com.readqurantoday.quran

import android.app.Activity
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.LayerDrawable
import android.view.View
import android.view.ViewGroup
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat

/*
  The strip beside the camera, and the one under the navigation keys, take their
  colour from whatever is actually behind them — read off the views rather than
  named a second time in code.

  It has to be read, not fixed, because the ground differs from screen to screen
  and pane to pane. And reading it means a future change to any of those
  backgrounds carries up to the bars on its own, with nothing to remember to
  update.
*/

/** Paint the status bar [roof] and the navigation bar [floor], icons to suit. Null leaves a bar as it is. */
fun Activity.paintBars(roof: Int?, floor: Int?) {
    val under = floor ?: roof
    if (roof != null) window.statusBarColor = roof
    if (under != null) window.navigationBarColor = under

    /* Icon contrast from the colour itself, so a new palette needs no second edit
       to stay legible. */
    WindowInsetsControllerCompat(window, window.decorView).apply {
        if (roof != null) isAppearanceLightStatusBars = pale(roof)
        if (under != null) isAppearanceLightNavigationBars = pale(under)
    }
}

/**
 * For a screen that always shows the phone's bars: ask for them back as ordinary
 * bars, then paint them. Call it whenever the window gains focus.
 *
 * The reader hides the bars while reading, in immersive mode, where they only come
 * back briefly and see-through on a swipe. A screen opened over it can inherit that
 * state, and a navigation bar shown that way ignores its colour — it came up bare
 * under the index until a tab tap happened to paint it again. Painting alone is not
 * enough; the bars have to be asked for. And only with focus: without it the request
 * is dropped, which is why the reader re-applies its own bars on focus too.
 */
fun Activity.showBars(roof: Int?, floor: Int?) {
    WindowInsetsControllerCompat(window, window.decorView).apply {
        systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_DEFAULT
        show(WindowInsetsCompat.Type.systemBars())
    }
    paintBars(roof, floor)
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
