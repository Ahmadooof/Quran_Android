package com.readqurantoday.quran

import android.graphics.Paint
import android.graphics.Typeface
import android.view.View
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView

/** ViewHolder for the surah list. */
class SurahRow(v: View, names: Typeface?) : RecyclerView.ViewHolder(v) {

    val num: TextView  = v.findViewById(R.id.num)
    val word: TextView = v.findViewById(R.id.word)
    val name: TextView = v.findViewById(R.id.name)
    private val english: TextView = v.findViewById(R.id.english)
    private val metaJuz: TextView = v.findViewById(R.id.meta_juz)
    private val metaPages: TextView = v.findViewById(R.id.meta_pages)

    /* Containers are the click targets; ImageViews are for icon/tint changes. */
    val playBtn: View?     = v.findViewById(R.id.btn_play)
    val reciterBtn: View?  = v.findViewById(R.id.btn_reciter)

    val play: ImageView?     = v.findViewById(R.id.play)
    val playWait: ProgressBar? = v.findViewById(R.id.play_wait)
    val reciter: ImageView?  = v.findViewById(R.id.reciter)
    val playLabel: TextView? = v.findViewById(R.id.play_label)

    fun fill(s: Surahs.Surah) {
        val res = itemView.resources
        num.text = figures(s.id, res)
        word.text = String(Character.toChars(Mushaf.SURAH_WORD))
        name.text = String(Character.toChars(Mushaf.nameCode(s.id)))
        name.contentDescription = res.getString(R.string.surah_named, s.name)

        english.text = res.getString(R.string.surah_english, s.english)

        // Where it lies: its juz, then the pages it spans
        val from = Surahs.juzOfPage(s.from)
        val to = Surahs.juzOfPage(s.to)
        val juz = if (from == to) juzNamed(from, res)
            else res.getString(R.string.juz_range, figures(from, res), figures(to, res))
        val pages = if (s.from == s.to) pageNamed(s.from, res)
            else res.getString(R.string.pages_range, figures(s.from, res), figures(s.to, res))
        // The juz is for finding your way, so it takes the accent
        metaJuz.text = juz
        metaPages.text = pages
    }

    init {
        for (t in listOf(word, name)) {
            t.typeface = names
            /* No hinting: same treatment as the page glyphs. */
            t.paint.hinting = Paint.HINTING_OFF
            t.paint.isLinearText = true
        }
        /* One title to a screen reader, not two glyph runs. */
        word.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
    }
}
