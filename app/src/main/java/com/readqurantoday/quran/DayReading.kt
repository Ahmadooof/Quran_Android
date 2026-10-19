package com.readqurantoday.quran

/** How many of [surah]'s pages this day read. */
fun Stats.Day.readOf(surah: Surahs.Surah): Int {
    val read = pages.toSet()
    return (surah.from..surah.to).count { it in read }
}

/** Every surah this day read whole, in the mushaf's order. */
fun Stats.Day.wholeSurahs(): List<Surahs.Surah> {
    val read = pages.toSet()
    return Surahs.list().filter { s -> (s.from..s.to).all { it in read } }
}
