package com.readqurantoday.quran

import java.text.Normalizer

// A number offers both page and surah; words match names in Arabic without marks, or English
object Search {

    /** One row of the result list. */
    sealed interface Hit {
        /** A section title. */
        data class Head(val title: Int) : Hit

        /** Go straight to a page. */
        data class Page(val page: Int) : Hit

        /** A surah, shown as the index's usual row. */
        data class Name(val surah: Surahs.Surah) : Hit

        /** An ayah whose text carries the query, with where in the query it sat. */
        data class Verse(val ayah: Ayahs.Ayah, val at: Int, val len: Int) : Hit

        /** Nothing matched. */
        data object None : Hit
    }

    private const val LAST_PAGE = 604

    // Some keyboards type hamza as a separate mark, so marks are stripped before matching
    private val marks = Regex("[\u064B-\u0655\u0670\u0640]")

    /* Both sets of Arabic digits, so ٤٨ and ۴۸ read as 48. */
    private fun digits(s: String) = buildString {
        for (c in s) append(
            when (c) {
                in '٠'..'٩' -> '0' + (c - '٠')
                in '۰'..'۹' -> '0' + (c - '۰')
                else -> c
            }
        )
    }

    // One spelling for letters readers do not distinguish; NFKC first undoes ligatures and joins typed hamza
    fun fold(s: String) = marks.replace(Normalizer.normalize(s, Normalizer.Form.NFKC), "")
        .replace('آ', 'ا')
        .replace('أ', 'ا')
        .replace('إ', 'ا')
        .replace('ٱ', 'ا')
        .replace('ؤ', 'و')
        .replace('ئ', 'ي')
        .replace('ى', 'ي')
        .replace('ة', 'ه')
        .trim()

    /** Rows for [raw]. An empty list means there is nothing to narrow: show the index. */
    fun plan(raw: String, all: List<Surahs.Surah>): List<Hit> {
        val q = digits(raw).trim()
        if (q.isBlank()) return emptyList()

        val hits = ArrayList<Hit>(8)

        // Page first: it is the finer place and the one a copied reference uses
        val n = q.toIntOrNull()
        if (n != null) {
            if (n in 1..LAST_PAGE) hits.add(Hit.Page(n))
            all.firstOrNull { it.id == n }?.let { hits.add(Hit.Name(it)) }
            if (hits.isNotEmpty()) hits.add(0, Hit.Head(R.string.search_jump))
            return hits.ifEmpty { listOf(Hit.None) }
        }

        val folded = fold(q)

        // Surah names before ayahs that merely contain the word
        val found = all.filter {
            fold(it.name).contains(folded) || it.english.contains(q, ignoreCase = true)
        }
        if (found.isNotEmpty()) {
            hits.add(Hit.Head(R.string.search_surahs))
            for (s in found) hits.add(Hit.Name(s))
        }

        val verses = Ayahs.find(folded)
        if (verses.isNotEmpty()) {
            hits.add(Hit.Head(R.string.search_ayahs))
            // Folding keeps positions, so matches can be marked; if lengths differ the row is left unmarked
            for (f in verses) {
                val at = if (f.ayah.folded.length == f.ayah.text.length) f.at else -1
                hits.add(Hit.Verse(f.ayah, at, folded.length))
            }
        }

        return hits.ifEmpty { listOf(Hit.None) }
    }
}
