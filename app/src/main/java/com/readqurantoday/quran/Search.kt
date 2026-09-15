package com.readqurantoday.quran

import java.text.Normalizer

/**
 * Reads the search box.
 *
 * A number is a place rather than a word, and there is no way to know which
 * place: 48 is a page and it is also a surah. Both are offered and the reader
 * picks. Words are a name to find, matched with the Arabic marks stripped and
 * against the English too, so either keyboard arrives at the same surah.
 */
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

    /* Harakat, the madda and hamza marks, the superscript alef, and tatweel: typed
       or not, it is one word. The hamza marks matter because some keyboards write
       أ as ا followed by a separate hamza, which no letter swap would ever reach. */
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

    /**
     * One spelling for letters a reader will not think to tell apart: every alef
     * with or without its hamza or madda, the hamza's seats on waw and ya, ya and
     * alef maqsura, ta-marbuta and ha. Whether someone types مؤمن or مومن, بئس or
     * بيس, they mean the same word and should get the same ayahs.
     *
     * NFKC goes first. It undoes presentation forms — the لا and لأ ligatures some
     * keyboards type as a single character — and joins a separately typed hamza to
     * its letter, so the swaps below see ordinary letters either way.
     */
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

        /* A bare number: the two places it could name, page first — it is the
           finer of the two, and the one a reader copying a reference has. */
        val n = q.toIntOrNull()
        if (n != null) {
            if (n in 1..LAST_PAGE) hits.add(Hit.Page(n))
            all.firstOrNull { it.id == n }?.let { hits.add(Hit.Name(it)) }
            if (hits.isNotEmpty()) hits.add(0, Hit.Head(R.string.search_jump))
            return hits.ifEmpty { listOf(Hit.None) }
        }

        val folded = fold(q)

        /* Names first: a reader typing a surah's name wants the surah, not every
           ayah that happens to contain the word. */
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
            /* The fold only swaps letters on this edition, so a position in the
               folded text is the same position in the original and the match can be
               marked. Were that ever untrue, the length would say so, and the row
               shows the text unmarked instead. */
            for (f in verses) {
                val at = if (f.ayah.folded.length == f.ayah.text.length) f.at else -1
                hits.add(Hit.Verse(f.ayah, at, folded.length))
            }
        }

        return hits.ifEmpty { listOf(Hit.None) }
    }
}
