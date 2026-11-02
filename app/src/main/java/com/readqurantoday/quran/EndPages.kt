package com.readqurantoday.quran

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** The mushaf's closing pages, أ to ل, line for line as printed: how it was written and marked. Built by tools/end_pages.py. */
object EndPages {

    /** One printed word of a Quranic example: the mushaf page it is drawn from and its glyphs there. */
    data class Word(val page: Int, val glyphs: String)

    /** A run of the page's text, or a Quranic example with the brackets it shows: "()", "(", ")" or none. */
    sealed interface Part {
        data class Text(val text: String) : Part
        data class Quran(val words: List<Word>, val brackets: String) : Part
    }

    /** A printed line: the Basmalah, a title, a justified line, or a paragraph's last line. */
    enum class Kind { BASMALAH, TITLE, LINE, END }

    data class Line(val kind: Kind, val parts: List<Part>)

    data class Page(val letter: String, val lines: List<Line>)

    @Volatile
    var pages: List<Page> = emptyList()
        private set

    fun load(context: Context) {
        if (pages.isNotEmpty()) return
        val json = context.assets.open("data/end-pages.json").use { String(it.readBytes(), Charsets.UTF_8) }
        val all = JSONObject(json).getJSONArray("pages")
        pages = (0 until all.length()).map { i ->
            val page = all.getJSONObject(i)
            val lines = page.getJSONArray("lines")
            Page(page.getString("letter"), (0 until lines.length()).map { line(lines.getJSONObject(it)) })
        }
    }

    private fun line(o: JSONObject): Line {
        val kind = when (o.getString("k")) {
            "basmalah" -> Kind.BASMALAH
            "title" -> Kind.TITLE
            "end" -> Kind.END
            else -> Kind.LINE
        }
        val parts = o.optJSONArray("parts") ?: JSONArray()
        return Line(kind, (0 until parts.length()).map { i ->
            val part = parts.optJSONObject(i)
            val q = part?.optJSONArray("q")
            if (q == null) Part.Text(parts.getString(i))
            else Part.Quran((0 until q.length()).map { j -> q.getJSONArray(j).let { Word(it.getInt(0), it.getString(1)) } },
                part.optString("b", "()"))
        })
    }
}
