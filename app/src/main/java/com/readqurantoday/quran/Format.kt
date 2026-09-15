package com.readqurantoday.quran

import android.content.res.Resources

/** Arabic-Eastern figures in Arabic locale, Latin digits elsewhere. */
fun figures(n: Int, resources: Resources): String {
    if (resources.configuration.locales[0].language != "ar") return n.toString()
    val sb = StringBuilder()
    for (c in n.toString()) sb.append(('٠' + (c - '0')))
    return sb.toString()
}

/** Time since, in the locale's figures and Arabic's count forms. */
fun ago(at: Long, resources: Resources): String {
    val minutes = ((System.currentTimeMillis() - at) / 60_000L).coerceAtLeast(0L).toInt()
    val hours = minutes / 60
    val days = hours / 24
    return when {
        minutes < 1 -> resources.getString(R.string.ago_now)
        hours < 1   -> resources.getQuantityString(R.plurals.ago_minutes, minutes, figures(minutes, resources))
        days < 1    -> resources.getQuantityString(R.plurals.ago_hours, hours, figures(hours, resources))
        else        -> resources.getQuantityString(R.plurals.ago_days, days, figures(days, resources))
    }
}
