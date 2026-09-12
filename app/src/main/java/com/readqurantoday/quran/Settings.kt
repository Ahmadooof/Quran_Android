package com.readqurantoday.quran

import android.content.Context
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat

object Settings {

    private const val PREFS = "reader"
    private const val THEME = "theme"
    private const val MARKS = "bookmarks"
    private const val LAST_PAGE = "last-page"
    private const val RECITER = "reciter"
    private const val LANG = "language"
    private const val HL_COLOR      = "hl-color"
    private const val AYAH_COLOR    = "ayah-color"

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

    // --- highlight colour (reader word highlight) ---

    /* Unset falls through to ink_lit, which is themed — so the reset lands on a
       red that is legible on cream by day and on dark paper by night. */
    fun highlightColor(ctx: Context): Int =
        store(ctx).getInt(HL_COLOR, ctx.getColor(R.color.ink_lit))

    fun setHighlightColor(ctx: Context, color: Int) {
        store(ctx).edit().putInt(HL_COLOR, color).apply()
    }

    fun resetHighlightColor(ctx: Context) {
        store(ctx).edit().remove(HL_COLOR).apply()
    }

    // ayah marker colour on the page (rings and mark glyphs; 0 = accent)
    fun ayahColor(ctx: Context): Int = store(ctx).getInt(AYAH_COLOR, 0)
    fun setAyahColor(ctx: Context, color: Int) { store(ctx).edit().putInt(AYAH_COLOR, color).apply() }
    fun resolvedAyahColor(ctx: Context): Int {
        val s = ayahColor(ctx)
        return if (s != 0) s else ctx.getColor(R.color.accent)
    }
}
