package com.readqurantoday.quran

import android.content.res.Resources

/** Arabic-Eastern figures in Arabic locale, Latin digits elsewhere. */
fun figures(n: Int, resources: Resources): String = localDigits(n.toString(), resources)

/** [text] with Arabic-Indic digits made Latin, so a number typed on an Arabic keyboard can be read. */
fun latinDigits(text: String): String = text.map {
    when (it) {
        in '٠'..'٩' -> '0' + (it - '٠')
        // The Persian and Urdu keyboards' own digits
        in '۰'..'۹' -> '0' + (it - '۰')
        else -> it
    }
}.joinToString("")

/** [text] with its Latin digits written as the locale writes them, e.g. a system-formatted date. */
fun localDigits(text: String, resources: Resources): String {
    if (resources.configuration.locales[0].language != "ar") return text
    return text.map { if (it in '0'..'9') '٠' + (it - '0') else it }.joinToString("")
}

/** "Page 9" / «الصفحة ٩». */
fun pageNamed(page: Int, resources: Resources): String = resources.getString(R.string.head_page, figures(page, resources))

/** "Juz 1" / «الجزء ١». */
fun juzNamed(juz: Int, resources: Resources): String = resources.getString(R.string.head_juz, figures(juz, resources))

/** The juz [surah] lies in: «الجزء ٢», or «الجزء ١–٣» for one that runs through several. */
fun juzOfSurah(surah: Surahs.Surah, resources: Resources): String {
    val from = Surahs.juzOfPage(surah.from)
    val to = Surahs.juzOfPage(surah.to)
    return if (from == to) juzNamed(from, resources)
        else resources.getString(R.string.juz_range, figures(from, resources), figures(to, resources))
}

/** A file's size in one short style for every size, so a small file and a large one read alike. */
fun fileSize(bytes: Long, resources: Resources): String {
    val tenths = (bytes + 50_000_000L) / 100_000_000L
    val said = when {
        bytes >= 1_000_000_000L -> {
            val mark = if (resources.configuration.locales[0].language == "ar") "٫" else "."
            resources.getString(R.string.size_gb, "${tenths / 10}$mark${tenths % 10}")
        }
        bytes >= 1_000_000L -> resources.getString(R.string.size_mb, ((bytes + 500_000L) / 1_000_000L).toString())
        else -> resources.getString(R.string.size_kb, ((bytes + 999L) / 1000L).coerceAtLeast(1L).toString())
    }
    return localDigits(said, resources)
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

/** "12 pages", in the locale's figures and Arabic's count forms; none is a dash. */
fun pagesSaid(n: Int, resources: Resources): String =
    if (n == 0) none(resources) else resources.getQuantityString(R.plurals.pages_count, n, figures(n, resources))

/** Nothing yet, as a dash: an Arabic zero is a lone dot, which reads as stray punctuation. */
fun none(resources: Resources): String = resources.getString(R.string.none_yet)

/** Time spent, to the minute, in words: "24 minutes", or "2 hours and 10 minutes" past an hour. */
fun spent(sec: Int, resources: Resources): String {
    val minutes = (sec + 30) / 60
    if (minutes == 0) return if (sec > 0) resources.getString(R.string.time_under_minute) else none(resources)
    if (minutes < 60) return counted(R.plurals.minutes_count, minutes, resources)
    return joined(counted(R.plurals.hours_count, minutes / 60, resources), R.plurals.minutes_count, minutes % 60, resources)
}

/** Time to the second, for one page: "45 seconds", or "3 minutes and 51 seconds". */
fun spentExact(sec: Int, resources: Resources): String {
    if (sec < 60) return counted(R.plurals.seconds_count, sec, resources)
    return joined(counted(R.plurals.minutes_count, sec / 60, resources), R.plurals.seconds_count, sec % 60, resources)
}

private fun counted(plural: Int, n: Int, resources: Resources): String =
    resources.getQuantityString(plural, n, figures(n, resources))

// The larger amount, then "and" the smaller one unless it is nothing
private fun joined(first: String, plural: Int, n: Int, resources: Resources): String =
    if (n == 0) first else resources.getString(R.string.time_and, first, counted(plural, n, resources))

/** [pages] as the parts a reader counts by: "about 4 juz", "about 4 and a half juz", or quarters of a hizb under a juz. */
fun juzSaid(pages: Float, resources: Resources): String {
    val juz = pages / (Mushaf.PAGES / 30f)
    if (juz < 1f) {
        // A hizb is counted in its quarters, as the mushaf marks them: a quarter, half, three-quarters…
        val quarters = Math.round(pages / (Mushaf.PAGES / 240f))
        if (quarters < 1) return resources.getString(R.string.under_quarter)
        val parts = resources.getStringArray(R.array.hizb_parts)
        return resources.getString(R.string.about, parts[(quarters - 1).coerceAtMost(parts.size - 1)])
    }
    // To the nearest half, as one would say it aloud
    val halves = Math.round(juz * 2)
    val whole = counted(R.plurals.juz_count, halves / 2, resources)
    return resources.getString(R.string.about, if (halves % 2 == 0) whole else resources.getString(R.string.and_half, whole))
}
