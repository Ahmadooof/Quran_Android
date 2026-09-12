package com.readqurantoday.quran

import android.content.Context

/** Opening ayah/word state for each page, built by walking all 604 pages once. */
object Ayat {

    private val atSurah = IntArray(606)
    private val atAyah = IntArray(606)
    private val atWord = IntArray(606)

    private val opensOn = HashMap<Int, Int>(6300)

    @Volatile
    var ready = false
        private set

    private fun key(surah: Int, ayah: Int) = surah * 1000 + ayah

    /** Walk all 604 pages once (~78 000 words). Run off the main thread. */
    fun build(context: Context) {
        if (ready) return
        val ctx = context.applicationContext

        var s = 0
        var v = 0
        var w = 0

        for (p in 1..604) {
            atSurah[p] = s
            atAyah[p] = v
            atWord[p] = w

            val marks = Mushaf.marksOn(p)
            for (line in Mushaf.lines(p)) {
                if (line.kind == "surah") {
                    s = line.surah
                    v = 1
                    w = 0
                }
                if (line.kind != "ayah") continue

                for (parts in line.words) {
                    val word = parts.joinToString(" ")
                    if (marks.contains(word)) {
                        v++
                        w = 0
                    } else {
                                if (w == 0) opensOn.getOrPut(key(s, v)) { p }
                        w++
                    }
                }
            }
        }
        Mushaf.keepOnly(1..2)
        ready = true
    }

    fun surahAt(page: Int) = atSurah.getOrElse(page) { 0 }
    fun ayahAt(page: Int) = atAyah.getOrElse(page) { 0 }
    fun wordAt(page: Int) = atWord.getOrElse(page) { 0 }

    /** The page this ayah opens on, or 0 if not yet known. */
    fun pageOf(surah: Int, ayah: Int) = opensOn[key(surah, ayah)] ?: 0
}
