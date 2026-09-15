package com.readqurantoday.quran

import android.content.Context
import android.content.res.Configuration
import androidx.appcompat.app.AppCompatDelegate
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
        store(context).edit().putInt(THEME, mode).apply()
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
        store(context).edit().putStringSet(MARKS, kept.map { it.toString() }.toSet()).apply()
        return on
    }

    // --- language ---

    fun language(context: Context): String = store(context).getString(LANG, "ar") ?: "ar"

    fun setLanguage(context: Context, tag: String) {
        store(context).edit().putString(LANG, tag).apply()
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
        store(context).edit().putString(RECITER, id).apply()
    }

    // --- last page ---

    fun lastPage(context: Context) = store(context).getInt(LAST_PAGE, 0)

    fun setLastPage(context: Context, page: Int) {
        store(context).edit().putInt(LAST_PAGE, page).apply()
    }

    // --- notifications ---

    private const val NOTIFICATIONS_ASKED = "notifications-asked"

    fun notificationsAsked(context: Context) = store(context).getBoolean(NOTIFICATIONS_ASKED, false)

    fun setNotificationsAsked(context: Context) {
        store(context).edit().putBoolean(NOTIFICATIONS_ASKED, true).apply()
    }

    // --- page motion ---

    private const val PAGE_TURN = "page-turn"

    /* Turn pages like paper (true) or slide them (false, the default). */
    fun pageTurn(context: Context) = store(context).getBoolean(PAGE_TURN, false)

    fun setPageTurn(context: Context, on: Boolean) {
        store(context).edit().putBoolean(PAGE_TURN, on).apply()
    }

    // --- recently read ---

    /** A surah read lately: the page it was last left on, and when, in epoch ms (0 unknown). */
    data class Read(val surah: Int, val page: Int, val at: Long)

    private const val RECENT = "recent"

    // More than a few recent surahs becomes a list to search, not a place to return to
    const val RECENT_KEEP = 5

    // Newest first, one entry per surah, each at the page it was left on
    fun recent(ctx: Context): List<Read> {
        val saved = store(ctx).getString(RECENT, "").orEmpty()
        return saved.split(';').mapNotNull { entry ->
            val p = entry.split(':')
            if (p.size != 3) return@mapNotNull null
            val surah = p[0].toIntOrNull() ?: return@mapNotNull null
            val page = p[1].toIntOrNull() ?: return@mapNotNull null
            Read(surah, page, p[2].toLongOrNull() ?: 0L)
        }
    }

    /** Note that [page] of [surah] was just read. */
    fun noteRead(ctx: Context, surah: Int, page: Int) {
        if (surah <= 0 || page <= 0) return
        val now = Read(surah, page, System.currentTimeMillis())
        val kept = (listOf(now) + recent(ctx).filter { it.surah != surah }).take(RECENT_KEEP)
        store(ctx).edit()
            .putString(RECENT, kept.joinToString(";") { "${it.surah}:${it.page}:${it.at}" })
            .apply()
    }

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
        store(ctx).edit().putInt(themed(ctx, key), color).apply()
        restyled()
    }

    // Clears the old shared key too, or it would outrank the restored default
    private fun resetColour(ctx: Context, key: String, shared: String?) {
        store(ctx).edit().apply {
            remove(themed(ctx, key))
            if (shared != null) remove(shared)
        }.apply()
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
        store(ctx).edit().putInt(themed(ctx, key), weight).apply()
        restyled()
    }

    fun inkWeight(ctx: Context) = weight(ctx, INK_WEIGHT, BOLD_INK, R.integer.default_ink_weight)
    fun setInkWeight(ctx: Context, weight: Int) = setWeight(ctx, INK_WEIGHT, weight)

    fun litWeight(ctx: Context) = weight(ctx, LIT_WEIGHT, BOLD_LIT, R.integer.default_lit_weight)
    fun setLitWeight(ctx: Context, weight: Int) = setWeight(ctx, LIT_WEIGHT, weight)

    fun ayahWeight(ctx: Context) = weight(ctx, AYAH_WEIGHT, BOLD_AYAH, R.integer.default_ayah_weight)
    fun setAyahWeight(ctx: Context, weight: Int) = setWeight(ctx, AYAH_WEIGHT, weight)

    // Removes pre-per-theme keys too, or they would outrank the restored defaults
    fun resetStyle(ctx: Context) {
        store(ctx).edit().apply {
            for (key in listOf(HL_COLOR, AYAH_COLOR, INK_COLOR, PAPER_COLOR, INK_WEIGHT, LIT_WEIGHT, AYAH_WEIGHT)) {
                remove(themed(ctx, key))
            }
            for (old in listOf(HL_COLOR, AYAH_COLOR, INK_WEIGHT, LIT_WEIGHT, AYAH_WEIGHT, BOLD_LIT, BOLD_AYAH, BOLD_INK)) {
                remove(old)
            }
        }.apply()
        restyled()
    }
}
