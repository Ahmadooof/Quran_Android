package com.readqurantoday.quran

import android.app.Activity
import android.content.res.ColorStateList
import android.text.format.DateUtils
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView

/**
 * The places tab: where to go back to in the mushaf, built into a container as
 * titled cards, the way the settings are.
 *
 * Recently read comes first — the surahs read lately, each at the page it was left
 * on, with how far through the surah that is and how long ago it was — because it
 * is what a reader opening this tab usually wants. Saved pages follow, in mushaf
 * order, each with a way to drop it. [open] takes a page to go to.
 */
class PlacesPane(
    private val host: Activity,
    private val into: LinearLayout,
    private val open: (Int) -> Unit
) {

    private val blow = host.layoutInflater

    fun build() {
        into.removeAllViews()

        val recent = recent()
        blow.card(into, R.string.marks_col_recent,
            if (recent.isEmpty()) listOf(empty(R.string.no_recent))
            else recent.map { recentRow(it) })

        val saved = Settings.marks(host)
        blow.card(into, R.string.marks_col_saved,
            if (saved.isEmpty()) listOf(empty(R.string.no_marks))
            else saved.map { savedRow(it) })
    }

    /* The reading history; before any was kept, the one last page stands in for it. */
    private fun recent(): List<Settings.Read> {
        val kept = Settings.recent(host)
        if (kept.isNotEmpty()) return kept
        val last = Settings.lastPage(host)
        val surah = Surahs.ofPage(last) ?: return emptyList()
        return if (last in 1..604) listOf(Settings.Read(surah.id, last, 0L)) else emptyList()
    }

    private fun recentRow(read: Settings.Read): View {
        val row = place(R.drawable.ic_surahs, read.page)
        val surah = Surahs.list().firstOrNull { it.id == read.surah }
        fillSurahTitle(row.findViewById(R.id.place_title), read.surah, TITLE_SP)

        /* Where in the mushaf, as everywhere else in the app names a page; and how far
           through the surah that is, as the bar alone. Given as words too — "page 4
           of 48" — it sat beside the mushaf's own page number and read as a second
           page number that disagreed with the first. */
        row.findViewById<TextView>(R.id.place_detail).text = where(read.page)
        if (surah != null) {
            val of = (surah.to - surah.from + 1).coerceAtLeast(1)
            val at = (read.page - surah.from + 1).coerceIn(1, of)
            row.findViewById<View>(R.id.place_track).apply {
                visibility = View.VISIBLE
                contentDescription = host.getString(
                    R.string.place_progress, figures(at, host.resources), figures(of, host.resources)
                )
            }
            weigh(row.findViewById(R.id.place_fill), at.toFloat())
            weigh(row.findViewById(R.id.place_rest), (of - at).toFloat())
        }

        /* When: "5 minutes ago", "yesterday" — in the app's language. Unknown for the
           one page carried over from before times were kept, so left off. */
        if (read.at > 0L) {
            row.findViewById<TextView>(R.id.place_when).apply {
                text = DateUtils.getRelativeTimeSpanString(
                    read.at, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS,
                    DateUtils.FORMAT_ABBREV_RELATIVE
                )
                visibility = View.VISIBLE
            }
        }
        return row
    }

    private fun savedRow(page: Int): View {
        val row = place(R.drawable.ic_bookmark, page)
        Surahs.ofPage(page)?.let { fillSurahTitle(row.findViewById(R.id.place_title), it.id, TITLE_SP) }
        row.findViewById<TextView>(R.id.place_detail).text = where(page)
        row.findViewById<ImageView>(R.id.place_remove).apply {
            imageTintList = ColorStateList.valueOf(host.getColor(R.color.text_mute))
            visibility = View.VISIBLE
            /* Dropping a saved page rebuilds the tab, so the card closes up at once. */
            setOnClickListener {
                Settings.toggleMark(host, page)
                build()
            }
        }
        return row
    }

    /* The row both kinds share: the disc with its icon, and the tap that goes there. */
    private fun place(icon: Int, page: Int): View {
        val row = blow.inflate(R.layout.row_place, into, false)
        row.findViewById<ImageView>(R.id.place_icon).apply {
            setImageResource(icon)
            imageTintList = ColorStateList.valueOf(host.getColor(R.color.accent))
        }
        row.setOnClickListener { open(page) }
        return row
    }

    /* "Juz 1 · Page 3", in the figures the page itself uses. */
    private fun where(page: Int): String {
        val at = host.getString(R.string.head_page, figures(page, host.resources))
        val juz = Surahs.juzOfPage(page)
        return if (juz <= 0) at else host.getString(
            R.string.place_line, host.getString(R.string.head_juz, figures(juz, host.resources)), at
        )
    }

    private fun empty(said: Int): View =
        (blow.inflate(R.layout.row_empty, into, false) as TextView).apply { setText(said) }

    private fun weigh(v: View, weight: Float) {
        v.layoutParams = (v.layoutParams as LinearLayout.LayoutParams).apply { this.weight = weight }
    }

    private companion object {
        const val TITLE_SP = 22f
    }
}
