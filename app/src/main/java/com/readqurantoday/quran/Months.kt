package com.readqurantoday.quran

import android.content.Context
import android.icu.util.Calendar
import android.icu.util.GregorianCalendar
import android.icu.util.IslamicCalendar
import android.icu.util.TimeZone
import android.icu.util.ULocale
import android.text.format.DateUtils

// Months by the Hijri (Umm al-Qura, as the Saudi calendar counts it) or the Gregorian calendar

private fun calendar(hijri: Boolean): Calendar =
    if (hijri) IslamicCalendar(TimeZone.getDefault(), ULocale.ROOT).apply {
        calculationType = IslamicCalendar.CalculationType.ISLAMIC_UMALQURA
    } else GregorianCalendar(TimeZone.getDefault(), ULocale.ROOT)

/** The first day, in days since 1970, of the month [day] falls in. */
fun monthStart(day: Long, hijri: Boolean): Long {
    val cal = calendar(hijri)
    cal.timeInMillis = Stats.noonOf(day)
    return day - (cal.get(Calendar.DAY_OF_MONTH) - 1)
}

/** A day as the phone writes a date, "12 October", with the year once it is far off; in the app's figures. */
fun dateSaid(ctx: Context, day: Long): String {
    val far = day - Stats.today() > 300
    val flags = DateUtils.FORMAT_SHOW_DATE or if (far) DateUtils.FORMAT_SHOW_YEAR else DateUtils.FORMAT_NO_YEAR
    return localDigits(DateUtils.formatDateTime(ctx, Stats.noonOf(day), flags), ctx.resources)
}

/** [day] with its weekday: "Tue, 14 Oct" / «الثلاثاء، ١٤ أكتوبر». */
fun weekdayDateSaid(ctx: Context, day: Long): String {
    val flags = DateUtils.FORMAT_SHOW_WEEKDAY or DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_NO_YEAR
    return localDigits(DateUtils.formatDateTime(ctx, Stats.noonOf(day), flags), ctx.resources)
}

