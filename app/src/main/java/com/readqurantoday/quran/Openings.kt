package com.readqurantoday.quran

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** The surah each juz and hizb begins in: not always the surah its first page opens with. */
object Openings {

    private var juz = IntArray(0)
    private var hizb = IntArray(0)

    private fun load(context: Context) {
        if (juz.isNotEmpty()) return
        val text = context.resources.openRawResource(R.raw.part_openings).use { String(it.readBytes(), Charsets.UTF_8) }
        val root = JSONObject(text)
        juz = surahs(root.getJSONArray("juz"))
        hizb = surahs(root.getJSONArray("hizb"))
    }

    // Each row is [surah, ayah]
    private fun surahs(rows: JSONArray) = IntArray(rows.length()) { rows.getJSONArray(it).getInt(0) }

    fun ofJuz(context: Context, n: Int): Int? = load(context).let { juz.getOrNull(n - 1) }

    fun ofHizb(context: Context, n: Int): Int? = load(context).let { hizb.getOrNull(n - 1) }
}
