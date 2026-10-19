package com.readqurantoday.quran

import android.animation.ValueAnimator
import android.content.Context
import android.os.Build
import android.view.View
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator
import android.widget.TextView

/** How long a ring fills or a figure counts. */
const val FILL_MS = 800L

private const val RISE_MS = 320L
private const val RISE_DP = 12f

// A beat between one card and the next as a screen comes in
private const val STAGGER_MS = 60L

/** False when the phone's animations are turned off, so everything then simply appears. */
fun motionOn(context: Context): Boolean =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) ValueAnimator.areAnimatorsEnabled()
    else android.provider.Settings.Global.getFloat(
        context.contentResolver, android.provider.Settings.Global.ANIMATOR_DURATION_SCALE, 1f
    ) > 0f

/** Fades the view in as it lifts the last few dp into place, after [delay] ms. */
fun View.riseIn(delay: Long) {
    alpha = 0f
    translationY = RISE_DP * resources.displayMetrics.density
    animate().alpha(1f).translationY(0f)
        .setStartDelay(delay).setDuration(RISE_MS)
        .setInterpolator(DecelerateInterpolator())
}

/** Brings in each child, a beat apart, top first: the one entrance every screen of cards makes. */
fun ViewGroup.riseChildren() {
    if (!motionOn(context)) return
    for (i in 0 until childCount) getChildAt(i).riseIn(i * STAGGER_MS)
}

/** Brings a whole pane in at once, for one that is a single list rather than cards. */
fun View.riseWhole() {
    if (motionOn(context)) riseIn(0L)
}

/** Counts the text from [from] up or down to [to], each step written by [say]. */
fun TextView.countTo(from: Int, to: Int, say: (Int) -> String) {
    text = say(from)
    ValueAnimator.ofInt(from, to).apply {
        duration = FILL_MS
        interpolator = DecelerateInterpolator()
        addUpdateListener { text = say(it.animatedValue as Int) }
        start()
    }
}
