package com.readqurantoday.quran

import android.graphics.Paint
import android.graphics.Typeface
import android.text.BidiFormatter
import android.text.SpannableString
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
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
    val meta: TextView = v.findViewById(R.id.meta)

    /* Containers are the click targets; ImageViews are for icon/tint changes. */
    val playBtn: View?     = v.findViewById(R.id.btn_play)
    val reciterBtn: View?  = v.findViewById(R.id.btn_reciter)

    val play: ImageView?     = v.findViewById(R.id.play)
    val playWait: ProgressBar? = v.findViewById(R.id.play_wait)
    val reciter: ImageView?  = v.findViewById(R.id.reciter)

    fun fill(s: Surahs.Surah) {
        val res = itemView.resources
        num.text = figures(s.id, res)
        word.text = String(Character.toChars(Mushaf.SURAH_WORD))
        name.text = String(Character.toChars(Mushaf.nameCode(s.id)))
        name.contentDescription = res.getString(R.string.surah_named, s.name)

        /* The English name is isolated before it joins an Arabic line, or the bidi
           algorithm pulls the juz into its run and the line reads back to front. */
        val english = BidiFormatter.getInstance().unicodeWrap(s.english)
        val juz = res.getString(R.string.head_juz, figures(Surahs.juzOfPage(s.from), res))
        // The juz is for finding your way, so it stands out in the accent
        val line = SpannableString(res.getString(R.string.surah_meta_juz, english, juz))
        val at = line.lastIndexOf(juz)
        line.setSpan(ForegroundColorSpan(itemView.context.getColor(R.color.accent)), at, at + juz.length, 0)
        line.setSpan(StyleSpan(Typeface.BOLD), at, at + juz.length, 0)
        meta.text = line
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
