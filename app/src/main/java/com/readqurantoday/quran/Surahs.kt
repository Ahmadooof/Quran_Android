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
    // The 240 quarters of the 60 hizb: every fourth begins a hizb
    private var rub = IntArray(0)
    private var hizb = IntArray(0)

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
        val root = JSONObject(String(mushaf, Charsets.UTF_8))
        juz = starts(root, "juzPages")
        rub = starts(root, "rubPages")
        hizb = IntArray(rub.size / 4) { rub[it * 4] }
    }

    private fun starts(root: JSONObject, key: String): IntArray {
        val pages = root.optJSONArray(key) ?: return IntArray(0)
        return IntArray(pages.length()) { pages.getInt(it) }
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

    /** First page of each juz, in order; empty until loaded. */
    fun juzStarts(): IntArray = juz

    /** First page of each hizb, in order; empty until loaded. */
    fun hizbStarts(): IntArray = hizb

    /** Which juz this page is in (1–30), or 0 if not yet loaded. */
    fun juzOfPage(page: Int): Int = countUpTo(juz, page)

    /** The surahs on [page], in order: one, or more where a surah ends or begins partway down it. */
    fun onPage(page: Int): List<Surah> = all.filter { page in it.from..it.to }

    /** Which hizb this page is in (1–60), or 0 if not yet loaded. */
    fun hizbOfPage(page: Int): Int = countUpTo(hizb, page)

    /** The page each quarter of [hizb] (1–60) begins on: the hizb itself, a quarter, half and three-quarters in. */
    fun quartersOf(hizb: Int): List<Int> = (0..3).mapNotNull { rub.getOrNull((hizb - 1) * 4 + it) }

    /** The hizb and quarter (0 its start, 1 a quarter, 2 half, 3 three-quarters) that begins on [page], if any. */
    fun quarterOn(page: Int): Pair<Int, Int>? {
        val i = rub.indexOf(page)
        return if (i < 0) null else (i / 4 + 1) to (i % 4)
    }

    private fun countUpTo(starts: IntArray, page: Int): Int {
        var n = 0
        for (i in starts.indices) if (starts[i] <= page) n = i + 1 else break
        return n
    }
}
