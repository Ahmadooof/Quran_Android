package com.readqurantoday.quran

import android.content.Context
import android.content.res.Configuration
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.content.edit
import androidx.core.os.LocaleListCompat
import java.util.Locale

object Settings {

    private const val PREFS = "reader"
    private const val THEME = "theme"
    private const val MARKS = "bookmarks"
    private const val LAST_PAGE = "last-page"
    private const val RECITER = "reciter"
    private const val LANG = "language"
    private const val HL_COLOR      = "hl-color"
    private const val AYAH_COLOR    = "ayah-color"
    // Old on/off bold keys, still read until a weight is chosen
    private const val BOLD_LIT      = "bold-lit"
    private const val BOLD_AYAH     = "bold-ayah"
    private const val BOLD_INK      = "bold-ink"
    private const val INK_WEIGHT    = "ink-weight"
    private const val LIT_WEIGHT    = "lit-weight"
    private const val AYAH_WEIGHT   = "ayah-weight"
    private const val LABEL_WEIGHT  = "label-weight"
    /* Suffixed -day or -night: see themed(). */
    private const val INK_COLOR     = "ink-color"
    private const val PAPER_COLOR   = "paper-color"

    const val BY_SYSTEM = 0
    const val LIGHT = 1
    const val DARK = 2

    private fun store(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    // --- theme ---

    fun theme(context: Context) = store(context).getInt(THEME, BY_SYSTEM)

    fun setTheme(context: Context, mode: Int) {
        store(context).edit { putInt(THEME, mode) }
        apply(mode)
    }

    fun applyTheme(context: Context) = apply(theme(context))

    private fun apply(mode: Int) {
        AppCompatDelegate.setDefaultNightMode(
            when (mode) {
                LIGHT -> AppCompatDelegate.MODE_NIGHT_NO
                DARK -> AppCompatDelegate.MODE_NIGHT_YES
                else -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
            }
        )
    }

    // --- saved pages ---

    fun marks(context: Context): List<Int> =
        store(context).getStringSet(MARKS, emptySet())
            .orEmpty()
            .mapNotNull { it.toIntOrNull() }
            .sorted()

    fun marked(context: Context, page: Int) = marks(context).contains(page)

    fun toggleMark(context: Context, page: Int): Boolean {
        val kept = marks(context).toMutableSet()
        val on = kept.add(page)
        if (!on) kept.remove(page)
        store(context).edit { putStringSet(MARKS, kept.map { it.toString() }.toSet()) }
        return on
    }

    // --- language ---

    fun language(context: Context): String = store(context).getString(LANG, "ar") ?: "ar"

    fun setLanguage(context: Context, tag: String) {
        store(context).edit { putString(LANG, tag) }
        speak(tag)
    }

    fun applyLanguage(context: Context) = speak(language(context))

    /** [base] with the chosen language, when the system has not applied it yet. */
    fun inLanguage(base: Context): Context {
        val want = Locale.forLanguageTag(language(base))
        val config = base.resources.configuration
        if (config.locales[0].language == want.language) return base
        // Views set to follow the locale take their direction from the default, not the context
        Locale.setDefault(want)
        return base.createConfigurationContext(Configuration(config).apply { setLocale(want) })
    }

    private fun speak(tag: String) {
        AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags(tag))
    }

    // --- reciter ---

    fun reciter(context: Context): String? = store(context).getString(RECITER, null)

    fun setReciter(context: Context, id: String) {
        store(context).edit { putString(RECITER, id) }
    }

    // --- last page ---

    fun lastPage(context: Context) = store(context).getInt(LAST_PAGE, 0)

    fun setLastPage(context: Context, page: Int) {
        store(context).edit { putInt(LAST_PAGE, page) }
    }

    // --- page motion ---

    private const val PAGE_TURN = "page-turn"

    /* Turn pages like paper (true) or slide them (false, the default). */
    fun pageTurn(context: Context) = store(context).getBoolean(PAGE_TURN, false)

    fun setPageTurn(context: Context, on: Boolean) {
        store(context).edit { putBoolean(PAGE_TURN, on) }
    }

    // --- recitation speed ---

    private const val SPEED = "speed"

    /* How fast recitation plays; 1 is as recorded. */
    fun speed(context: Context) = store(context).getFloat(SPEED, 1f)

    fun setSpeed(context: Context, speed: Float) {
        store(context).edit { putFloat(SPEED, speed) }
    }

    // --- transliteration ---

    private const val TRANSLIT = "translit"

    /* A Latin reading over the lit word; on until the reader turns it off. */
    fun translit(context: Context) = store(context).getBoolean(TRANSLIT, true)

    fun setTranslit(context: Context, on: Boolean) {
        store(context).edit { putBoolean(TRANSLIT, on) }
    }

    // --- recently read ---

    /** A place read lately: its surah, the page, and when, in epoch ms (0 unknown). */
    data class Read(val surah: Int, val page: Int, val at: Long)

    private const val RECENT = "recent"
    private const val RECENT_PAGES = "recent_pages"

    // Every surah read, once each; pages only lately, or the list would grow toward the whole mushaf
    private const val SURAHS_KEPT = 114
    private const val PAGES_KEPT = 20

    /** Surahs read lately, newest first, each at the page it was left on. */
    fun recent(ctx: Context): List<Read> = reads(ctx, RECENT)

    /** Pages read lately, newest first, each once. Until any were kept, the surahs' last pages stand in. */
    fun recentPages(ctx: Context): List<Read> =
        reads(ctx, RECENT_PAGES).ifEmpty { recent(ctx).sortedByDescending { it.at } }.take(PAGES_KEPT)

    /** Note that [page] of [surah] was just read. */
    fun noteRead(ctx: Context, surah: Int, page: Int) {
        if (surah <= 0 || page <= 0) return
        val now = Read(surah, page, System.currentTimeMillis())
        store(ctx).edit {
            putString(RECENT, said((listOf(now) + recent(ctx).filter { it.surah != surah }).take(SURAHS_KEPT)))
            putString(RECENT_PAGES, said((listOf(now) + recentPages(ctx).filter { it.page != page }).take(PAGES_KEPT)))
        }
    }

    private fun reads(ctx: Context, key: String): List<Read> =
        store(ctx).getString(key, "").orEmpty().split(';').mapNotNull { entry ->
            val p = entry.split(':')
            if (p.size != 3) return@mapNotNull null
            val surah = p[0].toIntOrNull() ?: return@mapNotNull null
            val page = p[1].toIntOrNull() ?: return@mapNotNull null
            Read(surah, page, p[2].toLongOrNull() ?: 0L)
        }

    private fun said(reads: List<Read>) = reads.joinToString(";") { "${it.surah}:${it.page}:${it.at}" }

    // --- page style ---

    // Bumped by any style change; pages re-read their style when it differs
    @Volatile
    var styleVersion = 0
        private set

    private fun restyled() { styleVersion++ }

    // --- reading style ---

    // Style is stored per theme so dark ink chosen by day does not land on dark paper at night
    private fun themed(ctx: Context, key: String): String = key + if (isNight(ctx)) "-night" else "-day"

    private fun isNight(ctx: Context) =
        (ctx.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
            Configuration.UI_MODE_NIGHT_YES

    // Own value, else a colour saved before per-theme keys (0 = unset), else the theme default
    private fun colour(ctx: Context, key: String, shared: String?, default: Int): Int {
        val s = store(ctx)
        val own = themed(ctx, key)
        return when {
            s.contains(own) -> s.getInt(own, 0)
            shared != null && s.getInt(shared, 0) != 0 -> s.getInt(shared, 0)
            else -> ctx.getColor(default)
        }
    }

    private fun setColour(ctx: Context, key: String, color: Int) {
        store(ctx).edit { putInt(themed(ctx, key), color) }
        restyled()
    }

    // Clears the old shared key too, or it would outrank the restored default
    private fun resetColour(ctx: Context, key: String, shared: String?) {
        store(ctx).edit {
            remove(themed(ctx, key))
            if (shared != null) remove(shared)
        }
        restyled()
    }

    // --- colours ---

    /* The lit word. ink_lit is themed: a red legible on cream by day, a quiet blue by night. */
    fun highlightColor(ctx: Context) = colour(ctx, HL_COLOR, HL_COLOR, R.color.ink_lit)
    fun setHighlightColor(ctx: Context, color: Int) = setColour(ctx, HL_COLOR, color)
    fun resetHighlightColor(ctx: Context) = resetColour(ctx, HL_COLOR, HL_COLOR)

    /* The ayah markers. ayah_mark is themed: the accent blue by day, a lighter blue by night. */
    fun resolvedAyahColor(ctx: Context) = colour(ctx, AYAH_COLOR, AYAH_COLOR, R.color.ayah_mark)
    fun setAyahColor(ctx: Context, color: Int) = setColour(ctx, AYAH_COLOR, color)
    fun resetAyahColor(ctx: Context) = resetColour(ctx, AYAH_COLOR, AYAH_COLOR)

    fun inkColor(ctx: Context) = colour(ctx, INK_COLOR, null, R.color.ink)
    fun setInkColor(ctx: Context, color: Int) = setColour(ctx, INK_COLOR, color)
    fun resetInkColor(ctx: Context) = resetColour(ctx, INK_COLOR, null)

    fun paperColor(ctx: Context) = colour(ctx, PAPER_COLOR, null, R.color.paper)
    fun setPaperColor(ctx: Context, color: Int) = setColour(ctx, PAPER_COLOR, color)
    fun resetPaperColor(ctx: Context) = resetColour(ctx, PAPER_COLOR, null)

    // --- weights ---

    const val WEIGHT_REGULAR = 0
    const val WEIGHT_LIGHT = 1
    const val WEIGHT_MEDIUM = 2
    const val WEIGHT_BOLD = 3

    // Own value, else a pre-per-theme weight, else the old bold flag if ever set, else the default
    private fun weight(ctx: Context, key: String, old: String, default: Int): Int {
        val s = store(ctx)
        val own = themed(ctx, key)
        return when {
            s.contains(own) -> s.getInt(own, WEIGHT_REGULAR)
            s.contains(key) -> s.getInt(key, WEIGHT_REGULAR)
            s.contains(old) -> if (s.getBoolean(old, false)) WEIGHT_BOLD else WEIGHT_REGULAR
            else -> ctx.resources.getInteger(default)
        }
    }

    private fun setWeight(ctx: Context, key: String, weight: Int) {
        store(ctx).edit { putInt(themed(ctx, key), weight) }
        restyled()
    }

    fun inkWeight(ctx: Context) = weight(ctx, INK_WEIGHT, BOLD_INK, R.integer.default_ink_weight)
    fun setInkWeight(ctx: Context, weight: Int) = setWeight(ctx, INK_WEIGHT, weight)

    fun litWeight(ctx: Context) = weight(ctx, LIT_WEIGHT, BOLD_LIT, R.integer.default_lit_weight)
    fun setLitWeight(ctx: Context, weight: Int) = setWeight(ctx, LIT_WEIGHT, weight)

    fun ayahWeight(ctx: Context) = weight(ctx, AYAH_WEIGHT, BOLD_AYAH, R.integer.default_ayah_weight)
    fun setAyahWeight(ctx: Context, weight: Int) = setWeight(ctx, AYAH_WEIGHT, weight)

    /** The juz, hizb and page numbers around the page; the surah name keeps its own calligraphy. */
    fun labelWeight(ctx: Context) = weight(ctx, LABEL_WEIGHT, LABEL_WEIGHT, R.integer.default_label_weight)
    fun setLabelWeight(ctx: Context, weight: Int) = setWeight(ctx, LABEL_WEIGHT, weight)

    // Removes pre-per-theme keys too, or they would outrank the restored defaults
    fun resetStyle(ctx: Context) {
        store(ctx).edit {
            for (key in listOf(HL_COLOR, AYAH_COLOR, INK_COLOR, PAPER_COLOR, INK_WEIGHT, LIT_WEIGHT, AYAH_WEIGHT, LABEL_WEIGHT)) {
                remove(themed(ctx, key))
            }
            for (old in listOf(HL_COLOR, AYAH_COLOR, INK_WEIGHT, LIT_WEIGHT, AYAH_WEIGHT, BOLD_LIT, BOLD_AYAH, BOLD_INK)) {
                remove(old)
            }
        }
        restyled()
    }
}
