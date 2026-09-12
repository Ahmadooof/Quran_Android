package com.readqurantoday.quran

import android.content.res.Resources

/** Arabic-Eastern figures in Arabic locale, Latin digits elsewhere. */
fun figures(n: Int, resources: Resources): String {
    if (resources.configuration.locales[0].language != "ar") return n.toString()
    val sb = StringBuilder()
    for (c in n.toString()) sb.append(('٠' + (c - '0')))
    return sb.toString()
}
