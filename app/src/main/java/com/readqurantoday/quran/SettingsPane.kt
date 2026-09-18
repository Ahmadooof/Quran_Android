package com.readqurantoday.quran

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.Typeface
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.net.toUri

// Settings screens built as titled cards: general, and reading style with a live page preview
class SettingsPane(private val host: Activity, private val into: LinearLayout) {

    private val blow = host.layoutInflater
    private var preview: MushafPageView? = null

    // A context in the theme being styled, so values and preview colours follow it
    private var look: Context = host

    /** The settings tab: General, ending in the ways through to reading style and downloads. */
    fun general(openStyle: () -> Unit, openDownloads: () -> Unit) {
        into.removeAllViews()

        group(R.string.set_group_general) { rows ->
            rows.add(valueRow(R.string.set_theme, {
                when (Settings.theme(host)) {
                    Settings.LIGHT -> R.string.theme_light
                    Settings.DARK  -> R.string.theme_dark
                    else           -> R.string.theme_system
                }
            }) {
                Settings.setTheme(host, (Settings.theme(host) + 1) % 3)
            })
            rows.add(valueRow(R.string.set_language, {
                if (Settings.language(host) == "en") R.string.lang_en else R.string.lang_ar
            }) {
                Settings.setLanguage(host, if (Settings.language(host) == "en") "ar" else "en")
            })
            rows.add(valueRow(R.string.set_page_motion, {
                if (Settings.pageTurn(host)) R.string.motion_turn else R.string.motion_slide
            }) {
                Settings.setPageTurn(host, !Settings.pageTurn(host))
            })
            rows.add(linkRow(R.string.set_group_reading, openStyle,
                listOf(Settings.highlightColor(host), Settings.resolvedAyahColor(host))))
            rows.add(linkRow(R.string.set_downloads, openDownloads, emptyList()))
        }

        group(R.string.set_group_help) { rows ->
            rows.add(siteRow())
            rows.add(linkRow(R.string.set_report, { host.startActivity(Intent(host, FeedbackActivity::class.java)) }, emptyList()))
            rows.add(linkRow(R.string.set_privacy, { openLink(PRIVACY_URL) }, emptyList()))
            rows.add(linkRow(R.string.set_credits, { host.notice(host.getString(R.string.credits_text)) }, emptyList()))
        }
    }

    // The address itself is the value, so the row reads as a link to the website
    private fun siteRow(): View {
        val row = blow.inflate(R.layout.row_setting, into, false)
        row.findViewById<TextView>(R.id.set_label).setText(R.string.set_website)
        row.findViewById<TextView>(R.id.set_value).setText(R.string.site_host)
        row.setOnClickListener { openLink(SITE_URL) }
        return row
    }

    private fun openLink(url: String) {
        try {
            host.startActivity(Intent(Intent.ACTION_VIEW, url.toUri()))
        } catch (_: ActivityNotFoundException) {
            host.notice(url)
        }
    }

    /** The reading style screen: the preview, then colour and weight for each styled thing. */
    fun readingStyle(night: Boolean = isNight(host)) {
        into.removeAllViews()
        look = inTheme(night)

        // Each theme keeps its own look; everything below edits the one picked here
        group(0) { rows ->
            rows.add(themeSwitch(night))
        }

        group(R.string.set_preview) { rows ->
            rows.add(previewLine())
        }

        // Page and ink first: they decide contrast for everything else
        group(R.string.set_group_page) { rows ->
            rows.add(colorRow(
                label = R.string.set_paper_color,
                current = { Settings.paperColor(look) },
                fallback = { look.getColor(R.color.paper) },
                picks = PAPER_PICKS,
                trial = { line, c -> line.tryOn(paper = c) },
                keep = { Settings.setPaperColor(look, it) },
                reset = { Settings.resetPaperColor(look) }
            ))
            rows.add(colorRow(
                label = R.string.set_ink_color,
                current = { Settings.inkColor(look) },
                fallback = { look.getColor(R.color.ink) },
                picks = INK_PICKS,
                trial = { line, c -> line.tryOn(ink = c) },
                keep = { Settings.setInkColor(look, it) },
                reset = { Settings.resetInkColor(look) }
            ))
            rows.add(choiceRow(
                label = R.string.set_ink_weight,
                options = WEIGHT_NAMES,
                current = { Settings.inkWeight(look) },
                choose = { Settings.setInkWeight(look, it) }
            ))
        }

        group(R.string.set_group_word) { rows ->
            rows.add(colorRow(
                label = R.string.set_highlight_color,
                current = { Settings.highlightColor(look) },
                fallback = { look.getColor(R.color.ink_lit) },
                picks = ACCENT_PICKS,
                trial = { line, c -> line.tryOn(lit = c) },
                keep = { Settings.setHighlightColor(look, it) },
                reset = { Settings.resetHighlightColor(look) }
            ))
            rows.add(choiceRow(
                label = R.string.set_lit_weight,
                options = WEIGHT_NAMES,
                current = { Settings.litWeight(look) },
                choose = { Settings.setLitWeight(look, it) }
            ))
        }

        group(R.string.set_group_ayah) { rows ->
            rows.add(colorRow(
                label = R.string.set_ayah_color,
                current = { Settings.resolvedAyahColor(look) },
                fallback = { look.getColor(R.color.ayah_mark) },
                picks = ACCENT_PICKS,
                trial = { line, c -> line.tryOn(mark = c) },
                keep = { Settings.setAyahColor(look, it) },
                reset = { Settings.resetAyahColor(look) }
            ))
            rows.add(choiceRow(
                label = R.string.set_ayah_weight,
                options = WEIGHT_NAMES,
                current = { Settings.ayahWeight(look) },
                choose = { Settings.setAyahWeight(look, it) }
            ))
        }

        // Asks first, naming the theme, since a stray tap would undo a lot of choices
        val themeName = host.getString(if (night) R.string.theme_dark else R.string.theme_light)
        group(0) { rows ->
            rows.add(actionRow(R.string.reset_all) {
                host.sheet(host.getString(R.string.reset_all_ask, themeName),
                    listOf(Choice(host.getString(R.string.reset_all_do)))) {
                    Settings.resetStyle(look)
                    readingStyle(night)
                }
            })
        }
    }

    // Title 0 means a card without a heading
    private fun group(title: Int, fill: (MutableList<View>) -> Unit) {
        val rows = ArrayList<View>()
        fill(rows)
        blow.card(into, title, rows)
    }

    /* A label and what it is set to. Tapping acts, then the row and preview re-read. */
    private fun valueRow(label: Int, value: () -> Int, act: () -> Unit): View {
        val row = blow.inflate(R.layout.row_setting, into, false)
        row.findViewById<TextView>(R.id.set_label).setText(label)
        val shown = row.findViewById<TextView>(R.id.set_value)
        shown.setText(value())
        row.setOnClickListener {
            act()
            shown.setText(value())
            preview?.invalidate()
        }
        return row
    }

    // Colour dots are read fresh on every build
    private fun linkRow(label: Int, open: () -> Unit, dots: List<Int>): View {
        val row = blow.inflate(R.layout.row_setting_link, into, false)
        row.findViewById<TextView>(R.id.set_label).setText(label)
        // No dots for a screen without colours
        listOf(R.id.set_dot, R.id.set_dot_2).forEachIndexed { i, id ->
            val dot = row.findViewById<ImageView>(id)
            val colour = dots.getOrNull(i)
            if (colour == null) dot.visibility = View.GONE
            else dot.imageTintList = ColorStateList.valueOf(colour)
        }
        row.findViewById<ImageView>(R.id.set_go).imageTintList =
            ColorStateList.valueOf(host.getColor(R.color.text_mute))
        row.setOnClickListener { open() }
        return row
    }

    // Indexes into options; picking re-reads the row and preview
    private fun choiceRow(
        label: Int,
        options: List<Int>,
        current: () -> Int,
        choose: (Int) -> Unit
    ): View {
        val row = blow.inflate(R.layout.row_setting, into, false)
        row.findViewById<TextView>(R.id.set_label).setText(label)
        val shown = row.findViewById<TextView>(R.id.set_value)
        fun say() = shown.setText(options.getOrElse(current()) { options[0] })
        say()
        row.setOnClickListener {
            val now = current()
            host.sheet(
                host.getString(label),
                options.mapIndexed { i, name -> Choice(host.getString(name), on = i == now) }
            ) { picked ->
                choose(picked)
                say()
                preview?.invalidate()
            }
        }
        return row
    }

    // Picking the other theme rebuilds the screen in it
    private fun themeSwitch(night: Boolean): View {
        val row = blow.inflate(R.layout.row_setting_segment, into, false)
        val day = row.findViewById<TextView>(R.id.seg_day)
        val dark = row.findViewById<TextView>(R.id.seg_night)
        for ((option, isOn) in listOf(day to !night, dark to night)) {
            option.setBackgroundResource(if (isOn) R.drawable.seg_on else R.drawable.row_flat)
            option.setTextColor(host.getColor(if (isOn) R.color.accent else R.color.text_mute))
            option.setTypeface(option.typeface, if (isOn) Typeface.BOLD else Typeface.NORMAL)
        }
        day.setOnClickListener { if (night) readingStyle(night = false) }
        dark.setOnClickListener { if (!night) readingStyle(night = true) }
        return row
    }

    // Settings keys and colour resources both follow the configuration's night bit
    private fun inTheme(night: Boolean): Context {
        val conf = Configuration(host.resources.configuration)
        conf.uiMode = (conf.uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or
            (if (night) Configuration.UI_MODE_NIGHT_YES else Configuration.UI_MODE_NIGHT_NO)
        return host.createConfigurationContext(conf)
    }

    private fun isNight(ctx: Context) =
        (ctx.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
            Configuration.UI_MODE_NIGHT_YES

    // The theme's current colours, offered as-is on the sheet
    private fun inUse() = intArrayOf(
        Settings.paperColor(look),
        Settings.inkColor(look),
        Settings.highlightColor(look),
        Settings.resolvedAyahColor(look)
    )

    /* A row that does one thing when tapped. */
    private fun actionRow(label: Int, act: () -> Unit): View {
        val row = blow.inflate(R.layout.row_setting_action, into, false)
        (row as TextView).setText(label)
        row.setOnClickListener { act() }
        return row
    }

    /* A label, the colour as it stands, and a reset. The row opens the colour sheet. */
    private fun colorRow(
        label: Int,
        current: () -> Int,
        fallback: () -> Int,
        picks: IntArray,
        trial: (MushafPageView, Int) -> Unit,
        keep: (Int) -> Unit,
        reset: () -> Unit
    ): View {
        val row = blow.inflate(R.layout.row_setting_color, into, false)
        row.findViewById<TextView>(R.id.set_label).setText(label)
        val dot = row.findViewById<ImageView>(R.id.set_dot)

        fun say() {
            dot.imageTintList = ColorStateList.valueOf(current())
            preview?.invalidate()
        }
        say()

        row.setOnClickListener {
            host.colorSheet(host.getString(label), look, current(), fallback(), picks, inUse(), trial) {
                keep(it)
                say()
            }
        }
        row.findViewById<View>(R.id.set_reset).setOnClickListener {
            reset()
            say()
        }
        return row
    }

    // Re-reads the style on its next draw via Settings.styleVersion
    private fun previewLine(): View {
        Mushaf.load(host)
        val line = MushafPageView(look).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                host.resources.getDimensionPixelSize(R.dimen.preview_line)
            )
            previewMode = true
            show(MushafPageView.PREVIEW_PAGE)
        }
        preview = line
        return line
    }

    private companion object {
        private const val PRIVACY_URL = "https://readqurantoday.com/privacy/"
        private const val SITE_URL = "https://readqurantoday.com/"

        /* In the order of Settings.WEIGHT_*, lightest first. */
        val WEIGHT_NAMES = listOf(
            R.string.weight_regular, R.string.weight_light, R.string.weight_medium, R.string.weight_bold
        )
    }
}
