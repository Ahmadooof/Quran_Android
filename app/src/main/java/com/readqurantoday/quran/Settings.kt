package com.readqurantoday.quran

import android.content.Context
import android.content.res.Configuration
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
    /* The three old on/off bolds, superseded by the weights below them; still read
       until a weight is chosen, so an old "on" arrives as bold. See weight(). */
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

    /* How many surahs are remembered. Enough to go back to what was being read in
       the last few sittings; more is a list to search rather than a place to return. */
    const val RECENT_KEEP = 5

    /*
      The surahs read lately, newest first, one entry each: returning to Al-Baqarah
      after reading Yusuf goes to the page Al-Baqarah was left on, not to its start
      and not to Yusuf's. Empty until something is read with this kept; the places
      tab stands the old single last page in for it until then.
    */
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

    /*
      Bumped by every write that changes how a page is drawn. A page compares it
      with the number it last dressed at and re-reads the style when they differ,
      so a change reaches every page — on screen, cached off to the side, or not
      yet drawn — without any screen having to go round telling them.

      A new style setting only has to call restyled() in its setter to be covered.
    */
    @Volatile
    var styleVersion = 0
        private set

    private fun restyled() { styleVersion++ }

    // --- reading style ---

    /*
      Every reading style setting is kept once for day and once for night, and each
      theme's defaults come from resources — values/ by day, values-night/ by night
      — so a theme never set shows its own designed look, not the other's.

      Page and text colours first made this necessary: dark ink chosen on cream by
      day was dark ink on dark paper the moment the theme turned. The accents and
      weights followed, so the two themes can be tuned apart.

      Which theme is meant is read off [ctx]'s configuration. The reader passes
      itself and gets the theme it is in; the reading style screen passes a context
      set to whichever theme is being edited.
    */
    private fun themed(ctx: Context, key: String): String = key + if (isNight(ctx)) "-night" else "-day"

    private fun isNight(ctx: Context) =
        (ctx.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
            Configuration.UI_MODE_NIGHT_YES

    /* A colour for this theme: its own if set; else one saved before colours were
       kept per theme ([shared], where there was such a key, and 0 meant unset); else
       the theme's default resource. */
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

    /* The theme's own value and any shared one from before: left, the shared value
       would step straight back in ahead of the default being restored. */
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

    /*
      A weight for this theme: its own if set; else one chosen before weights were
      kept per theme ([shared]); else the old on/off bold it replaced ([old]), only
      if that was ever actually switched; else the theme's default resource.
    */
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

    /**
     * Every reading style setting back to its default for [ctx]'s theme. The values
     * kept from before settings were per theme go too: left, they would outrank the
     * defaults being restored.
     */
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
