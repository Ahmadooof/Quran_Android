package com.readqurantoday.quran

import android.graphics.Typeface
import android.text.Spannable
import android.text.SpannableString
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
import android.view.View
import android.widget.TextView

/** An ayah found by search in [row] (item_search_ayah): its text with the match marked, then where it is. */
fun fillAyahRow(row: View, hit: Search.Hit.Verse) {
    val ctx = row.context
    val a = hit.ayah

    // Highlights the matched part so the eye finds it at once
    val body = row.findViewById<TextView>(R.id.ayah_text)
    if (hit.at >= 0 && hit.at + hit.len <= a.text.length) {
        val span = SpannableString(a.text)
        span.setSpan(
            ForegroundColorSpan(ctx.getColor(R.color.accent)),
            hit.at, hit.at + hit.len, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
        )
        span.setSpan(
            StyleSpan(Typeface.BOLD),
            hit.at, hit.at + hit.len, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
        )
        body.text = span
    } else {
        body.text = a.text
    }

    val name = Surahs.of(a.surah)?.name.orEmpty()
    row.findViewById<TextView>(R.id.ayah_ref).text =
        ctx.getString(R.string.search_ayah_ref, name, figures(a.ayah, ctx.resources))
}
