package com.readqurantoday.quran

import android.graphics.Paint
import android.graphics.Typeface
import android.view.View
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView

/** ViewHolder for the surah list. */
class SurahRow(v: View, names: Typeface?) : RecyclerView.ViewHolder(v) {

    val num: TextView     = v.findViewById(R.id.num)
    val word: TextView    = v.findViewById(R.id.word)
    val name: TextView    = v.findViewById(R.id.name)
    val english: TextView = v.findViewById(R.id.english)
    val ayahs: TextView?  = v.findViewById(R.id.ayahs)

    /* Containers are the click targets; ImageViews are for icon/tint changes. */
    val playBtn: View?     = v.findViewById(R.id.btn_play)
    val downloadBtn: View? = v.findViewById(R.id.btn_get)
    val reciterBtn: View?  = v.findViewById(R.id.btn_reciter)

    val play: ImageView?     = v.findViewById(R.id.play)
    val download: ImageView? = v.findViewById(R.id.get)
    val reciter: ImageView?  = v.findViewById(R.id.reciter)

    fun fill(s: Surahs.Surah) {
        num.text = s.id.toString()
        word.text = String(Character.toChars(Mushaf.SURAH_WORD))
        name.text = String(Character.toChars(Mushaf.nameCode(s.id)))
        name.contentDescription = s.name
        english.text = s.english
        ayahs?.text = s.ayahs.toString()
    }

    init {
        for (t in listOf(word, name)) {
            t.typeface = names
            /* No hinting: same treatment as the page glyphs. */
            t.paint.hinting = Paint.HINTING_OFF
            t.paint.isLinearText = true
        }
    }
}
