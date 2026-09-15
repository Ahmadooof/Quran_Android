package com.readqurantoday.quran

import android.content.Context

/**
 * The Quran as searchable text, which is the one thing the mushaf data cannot
 * give: a page stores its words as glyphs of its own font, ligated and private
 * to it, so there is nothing in it to match a typed letter against.
 *
 * Tanzil's Simple Clean edition, one ayah to a line as `surah|ayah|text`. It is
 * carried for searching and for naming what was found — never for drawing, which
 * stays the page's own business. Absent, search keeps its other answers and
 * simply offers no ayahs.
 */
object Ayahs {

    private const val ASSET = "data/quran-simple.txt"

    /** How many hits are worth showing. Past this the list is a wall, not an answer. */
    const val LIMIT = 60

    /** The shortest query searched at all. One letter is in nearly every ayah. */
    const val FLOOR = 2

    /* Below this a query must be a whole word. Two letters as a substring hide in
       too much else: قد sits inside قدير and قدر in 561 ayahs, but stands as a word
       of its own in 122 — and those 122 are what someone typing قد is after. */
    private const val LOOSE_FROM = 3

    class Ayah(
        val surah: Int,
        val ayah: Int,
        val text: String,
        /** The same text folded, so a query can be matched without re-folding 6236 lines. */
        val folded: String
    )

    private val all = ArrayList<Ayah>(6240)

    @Volatile
    var ready = false
        private set

    /** Reads the asset if it is there. Run off the main thread. */
    fun load(context: Context) {
        if (ready) return
        val found = try {
            context.applicationContext.assets.open(ASSET).use { stream ->
                stream.bufferedReader(Charsets.UTF_8).readLines()
            }
        } catch (_: Exception) {
            /* No asset, no ayah search. Every other answer still stands. */
            ready = true
            return
        }

        for (line in found) {
            /* Tanzil's files carry their licence as # comments. */
            if (line.isBlank() || line.startsWith("#")) continue
            val a = line.indexOf('|')
            if (a <= 0) continue
            val b = line.indexOf('|', a + 1)
            if (b <= a) continue

            val surah = line.substring(0, a).trim().toIntOrNull() ?: continue
            val ayah = line.substring(a + 1, b).trim().toIntOrNull() ?: continue
            val text = line.substring(b + 1).trim()
            if (text.isEmpty()) continue

            all.add(Ayah(surah, ayah, text, Search.fold(text)))
        }
        ready = true
    }

    /** An ayah that matched, and where in its folded text the match begins. */
    data class Found(val ayah: Ayah, val at: Int)

    /**
     * Ayahs containing [folded], which must already be folded the way the index is.
     * A match that opens a word comes before one buried inside it: searching for a
     * word should find the word before it finds the middle of a longer one.
     */
    fun find(folded: String): List<Found> {
        if (folded.length < FLOOR || all.isEmpty()) return emptyList()
        val whole = folded.length < LOOSE_FROM

        val opens = ArrayList<Found>(LIMIT)
        val buried = ArrayList<Found>(LIMIT)

        for (a in all) {
            val at = firstMatch(a.folded, folded, whole)
            if (at < 0) continue
            if (opensWord(a.folded, at)) opens.add(Found(a, at)) else buried.add(Found(a, at))
            if (opens.size >= LIMIT) break
        }

        if (opens.size >= LIMIT) return opens
        return opens + buried.take(LIMIT - opens.size)
    }

    /* The first place [q] fits. When [whole], only where it is a word by itself —
       and the first such place, not merely the first place the letters occur, so
       the row marks the word that was actually meant. Otherwise a word-opening
       match is preferred over an earlier one buried inside a longer word. */
    private fun firstMatch(text: String, q: String, whole: Boolean): Int {
        var from = 0
        var buried = -1
        while (true) {
            val at = text.indexOf(q, from)
            if (at < 0) return if (whole) -1 else buried
            if (opensWord(text, at)) {
                if (!whole || closesWord(text, at + q.length)) return at
            } else if (!whole && buried < 0) {
                buried = at
            }
            from = at + 1
        }
    }

    private fun opensWord(text: String, at: Int) = at == 0 || text[at - 1] == ' '

    private fun closesWord(text: String, end: Int) = end == text.length || text[end] == ' '
}
