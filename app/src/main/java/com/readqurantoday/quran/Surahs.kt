package com.readqurantoday.quran

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

object Surahs {

    data class Surah(
        val id: Int,
        val name: String,      // الفاتحة
        val english: String,   // Al-Fatihah
        val ayahs: Int,
        val from: Int,         // first page
        val to: Int            // last page
    )

    private val all = ArrayList<Surah>(114)

    private var juz = IntArray(0)

    fun load(context: Context) {
        if (all.isNotEmpty()) return

        val text = context.assets.open("data/surahs.json").use { it.readBytes() }
        val arr = JSONArray(String(text, Charsets.UTF_8))

        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            all.add(
                Surah(
                    id = o.getInt("id"),
                    name = o.getString("name"),
                    english = o.optString("en"),
                    ayahs = o.optInt("v"),
                    from = o.getInt("from"),
                    to = o.getInt("to")
                )
            )
        }

        val mushaf = context.assets.open("data/mushaf.json").use { it.readBytes() }
        val pages = JSONObject(String(mushaf, Charsets.UTF_8)).optJSONArray("juzPages")
        if (pages != null) juz = IntArray(pages.length()) { pages.getInt(it) }
    }

    fun list(): List<Surah> = all

    /** The surah whose first page is this page or earlier. */
    fun ofPage(page: Int): Surah? {
        var found: Surah? = null
        for (s in all) {
            if (s.from <= page) found = s else break
        }
        return found
    }

    // The surah the page opens in: none when several start here, the previous one when a surah starts mid-page
    fun headOfPage(page: Int): Surah? {
        val lines = Mushaf.lines(page)
        val titles = lines.filter { it.kind == "surah" && it.surah > 0 }
        if (titles.size > 1) return null
        val title = titles.firstOrNull() ?: return ofPage(page)
        val opensWithTitle = lines.firstOrNull { it.kind == "surah" || it.kind == "ayah" } === title
        return all.firstOrNull { it.id == if (opensWithTitle) title.surah else title.surah - 1 }
    }

    /** Which juz this page is in (1–30), or 0 if not yet loaded. */
    fun juzOfPage(page: Int): Int {
        var n = 0
        for (i in juz.indices) if (juz[i] <= page) n = i + 1 else break
        return n
    }
}
