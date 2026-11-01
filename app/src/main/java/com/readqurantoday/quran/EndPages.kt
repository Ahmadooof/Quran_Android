package com.readqurantoday.quran

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** The mushaf's closing pages, أ to ل: how it was written and marked. Built by tools/end_pages.py. */
object EndPages {

    /** One printed word of a Quranic example: the mushaf page it is drawn from and its glyphs there. */
    data class Word(val page: Int, val glyphs: String)

    /** A run of the page's text, or a Quranic example. */
    sealed interface Part {
        data class Text(val text: String) : Part
        data class Quran(val words: List<Word>) : Part
    }

    enum class Kind { BASMALAH, TITLE, SUBTITLE, PARAGRAPH, ITEM }

    data class Block(val kind: Kind, val parts: List<Part>)

    data class Page(val letter: String, val blocks: List<Block>)

    @Volatile
    var pages: List<Page> = emptyList()
        private set

    fun load(context: Context) {
        if (pages.isNotEmpty()) return
        val json = context.assets.open("data/end-pages.json").use { String(it.readBytes(), Charsets.UTF_8) }
        val all = JSONObject(json).getJSONArray("pages")
        pages = (0 until all.length()).map { i ->
            val page = all.getJSONObject(i)
            val blocks = page.getJSONArray("blocks")
            Page(page.getString("letter"), (0 until blocks.length()).map { block(blocks.getJSONObject(it)) })
        }
    }

    private fun block(o: JSONObject): Block {
        val kind = when (o.getString("k")) {
            "basmalah" -> Kind.BASMALAH
            "h1" -> Kind.TITLE
            "h2" -> Kind.SUBTITLE
            "li" -> Kind.ITEM
            else -> Kind.PARAGRAPH
        }
        val parts = o.optJSONArray("parts") ?: JSONArray()
        return Block(kind, (0 until parts.length()).map { i ->
            val q = parts.optJSONObject(i)?.optJSONArray("q")
            if (q == null) Part.Text(parts.getString(i))
            else Part.Quran((0 until q.length()).map { j -> q.getJSONArray(j).let { Word(it.getInt(0), it.getString(1)) } })
        })
    }
}
