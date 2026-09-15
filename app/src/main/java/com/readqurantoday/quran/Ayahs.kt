package com.readqurantoday.quran

import android.content.Context

// Searchable Quran text (Tanzil Simple Clean, surah|ayah|text): page glyphs cannot be matched against typed letters
object Ayahs {

    private const val ASSET = "data/quran-simple.txt"

    /** How many hits are worth showing. Past this the list is a wall, not an answer. */
    const val LIMIT = 60

    /** The shortest query searched at all. One letter is in nearly every ayah. */
    const val FLOOR = 2

    // Shorter queries must be whole words, or they match inside too many longer words
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

    // Matches that open a word come before ones buried inside a word
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

    // Prefers a whole-word or word-opening match so the row marks the word that was meant
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
