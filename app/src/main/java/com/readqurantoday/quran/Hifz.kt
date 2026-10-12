package com.readqurantoday.quran

import android.content.Context
import androidx.core.content.edit

/** Memorised pages per surah with the day learned; [BEFORE] marks old memorisation, never counted toward a goal. */
object Hifz {

    const val BEFORE = -1L

    private const val PREFS = "hifz"
    private const val SURAH_PAGES = "surah_pages"
    // Pages alone, before surahs were told apart
    private const val OLD_PAGES = "pages"

    private fun store(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** Each surah's memorised pages, each with the day it was learned or [BEFORE]. */
    fun bySurah(ctx: Context): Map<Int, Map<Int, Long>> {
        val prefs = store(ctx)
        if (!prefs.contains(SURAH_PAGES) && prefs.contains(OLD_PAGES)) migrate(ctx)
        val kept = HashMap<Int, HashMap<Int, Long>>()
        prefs.getString(SURAH_PAGES, "").orEmpty().split(',').forEach { entry ->
            val p = entry.split(':')
            val surah = p.getOrNull(0)?.toIntOrNull() ?: return@forEach
            val page = p.getOrNull(1)?.toIntOrNull() ?: return@forEach
            val day = p.getOrNull(2)?.toLongOrNull() ?: return@forEach
            kept.getOrPut(surah) { HashMap() }[page] = day
        }
        return kept
    }

    /** Memorised pages of the mushaf, each with the earliest day it was learned for any surah on it. */
    fun pages(ctx: Context): Map<Int, Long> {
        val all = HashMap<Int, Long>()
        bySurah(ctx).values.forEach { pages -> pages.forEach { (page, day) -> all[page] = minOf(all[page] ?: day, day) } }
        return all
    }

    /** How many of [surah]'s pages are memorised. */
    fun knownOf(surah: Surahs.Surah, kept: Map<Int, Map<Int, Long>>): Int = kept[surah.id]?.size ?: 0

    /** A whole surah known from before, or forgotten: its pages marked [BEFORE], or cleared. */
    fun setSurah(ctx: Context, surah: Surahs.Surah, known: Boolean) = change(ctx, surah) { own ->
        if (known) for (page in surah.from..surah.to) own.putIfAbsent(page, BEFORE) else own.clear()
    }

    /** Mark [marked] of [surah]'s pages as learned today, and unmark [unmarked]; pages already kept keep their day. */
    fun setPages(ctx: Context, surah: Surahs.Surah, marked: Collection<Int>, unmarked: Collection<Int>) = change(ctx, surah) { own ->
        marked.forEach { own.putIfAbsent(it, Stats.today()) }
        unmarked.forEach { own.remove(it) }
    }

    private fun change(ctx: Context, surah: Surahs.Surah, edit: (HashMap<Int, Long>) -> Unit) {
        val kept = bySurah(ctx).mapValues { HashMap(it.value) }.toMutableMap()
        val own = kept[surah.id] ?: HashMap()
        edit(own)
        if (own.isEmpty()) kept.remove(surah.id) else kept[surah.id] = own
        save(ctx, kept)
    }

    private fun save(ctx: Context, kept: Map<Int, Map<Int, Long>>) = store(ctx).edit {
        putString(SURAH_PAGES, kept.entries.sortedBy { it.key }.flatMap { (surah, pages) ->
            pages.entries.sortedBy { it.key }.map { "$surah:${it.key}:${it.value}" }
        }.joinToString(","))
        remove(OLD_PAGES)
    }

    // A shared page goes to the surah with other pages marked; failing that, to the first surah on it
    private fun migrate(ctx: Context) {
        Surahs.load(ctx)
        val old = store(ctx).getString(OLD_PAGES, "").orEmpty().split(',').mapNotNull { entry ->
            val p = entry.split(':')
            val page = p.getOrNull(0)?.toIntOrNull() ?: return@mapNotNull null
            val day = p.getOrNull(1)?.toLongOrNull() ?: return@mapNotNull null
            page to day
        }.toMap()
        val kept = HashMap<Int, HashMap<Int, Long>>()
        old.forEach { (page, day) ->
            val on = Surahs.list().filter { page in it.from..it.to }
            val owners = on.filter { s -> (s.from..s.to).any { it != page && it in old } }.ifEmpty { on.take(1) }
            owners.forEach { kept.getOrPut(it.id) { HashMap() }[page] = day }
        }
        save(ctx, kept)
    }
}
