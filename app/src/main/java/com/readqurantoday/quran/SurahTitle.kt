package com.readqurantoday.quran

import android.graphics.Paint
import android.util.TypedValue
import android.view.View
import android.widget.TextView
import androidx.annotation.DimenRes

/** Fill a part_surah_title with [surah]'s title at [sizeSp], drawn from the names font like the surah list. */
fun fillSurahTitle(title: View, surah: Int, @DimenRes size: Int) {
    val word = title.findViewById<TextView>(R.id.surah_word)
    val name = title.findViewById<TextView>(R.id.surah_name)
    val face = Mushaf.nameTypeface(title.context)
    for (t in listOf(word, name)) {
        t.typeface = face
        t.setTextSize(TypedValue.COMPLEX_UNIT_PX, title.resources.getDimension(size))
        // No hinting: same treatment as the page glyphs
        t.paint.hinting = Paint.HINTING_OFF
        t.paint.isLinearText = true
    }
    word.text = String(Character.toChars(Mushaf.SURAH_WORD))
    name.text = String(Character.toChars(Mushaf.nameCode(surah)))
    word.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
    Surahs.list().firstOrNull { it.id == surah }?.let {
        name.contentDescription = title.resources.getString(R.string.surah_named, it.name)
    }
}
