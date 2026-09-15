package com.readqurantoday.quran

import android.content.Context
import org.json.JSONObject

// Word timings for one surah in one voice: per-ayah spans and (ms, word) pairs
class Timing private constructor(
    val ayat: List<IntArray>,
    private val words: List<IntArray>
) {

    val count get() = ayat.size

    /** Pauses between ayahs credited to the preceding one — highlight doesn't blink out. */
    fun ayahAt(ms: Int, was: Int = 0): Int {
        if (ayat.isEmpty()) return 0

        if (was in 1..ayat.size) {
            val i = was - 1
            if (ms >= ayat[i][0] && ms < ayat[i][1]) return was
            if (i + 1 < ayat.size && ms >= ayat[i + 1][0] && ms < ayat[i + 1][1]) return was + 1
        }

        var lo = 0
        var hi = ayat.size - 1
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            when {
                ms < ayat[mid][0] -> hi = mid - 1
                ms >= ayat[mid][1] -> lo = mid + 1
                else -> return mid + 1
            }
        }
        /* Between two ayahs: credit the one just ended. */
        return lo.coerceIn(1, ayat.size)
    }

    /** Walk from the end — times are ayah-relative and may go backwards on repeated phrases. */
    fun wordAt(ayah: Int, ms: Int): Int {
        val run = words.getOrNull(ayah - 1) ?: return -1
        if (run.isEmpty()) return -1

        val since = ms - startOf(ayah)
        var i = run.size - 2
        while (i >= 0) {
            if (since >= run[i]) return run[i + 1]
            i -= 2
        }
        return run[1]
    }

    /** The span [start, end] for a word in the recording's absolute time. */
    fun wordSpan(ayah: Int, word: Int): IntArray? {
        val run = words.getOrNull(ayah - 1) ?: return null
        val opens = startOf(ayah)

        var i = 0
        while (i + 1 < run.size) {
            if (run[i + 1] == word) {
                val from = opens + run[i]
                val to = if (i + 2 < run.size) opens + run[i + 2] else endOf(ayah)
                return intArrayOf(from, to)
            }
            i += 2
        }
        return null
    }

    fun startOf(ayah: Int) = ayat.getOrNull(ayah - 1)?.get(0) ?: 0
    fun endOf(ayah: Int) = ayat.getOrNull(ayah - 1)?.get(1) ?: 0

    companion object {

        private val held = HashMap<String, Timing?>()

        @Synchronized
        fun of(context: Context, surah: Int, reciter: String): Timing? {
            val key = "$reciter/$surah"
            if (held.containsKey(key)) return held[key]

            val timing = try {
                val padded = surah.toString().padStart(3, '0')
                val asset = "surah/$surah/$padded.$reciter.timing.json"
                val text = context.assets.open(asset).use { it.readBytes() }
                read(JSONObject(String(text, Charsets.UTF_8)))
            } catch (_: Exception) {
                null
            }

            /* Cache null too — avoids repeated asset lookups for missing timings. */
            held[key] = timing
            return timing
        }

        /** Re-label by time order when word numbers are out of sequence in the source data. */
        private fun ordered(run: IntArray): IntArray {
            val n = run.size / 2
            if (n < 2) return run

            val idx = IntArray(n) { run[it * 2 + 1] }
            val seen = idx.toHashSet()
            if (seen.size != n) return run        // genuine repeat — don't touch
            for (k in 0 until n) if (!seen.contains(k)) return run  // unexpected numbering

            var sound = true
            for (k in 1 until n) if (idx[k] < idx[k - 1]) { sound = false; break }
            if (sound) return run

            val order = (0 until n).sortedBy { run[it * 2] }
            val out = IntArray(run.size)
            for (k in 0 until n) {
                out[k * 2] = run[order[k] * 2]
                out[k * 2 + 1] = k
            }
            return out
        }

        private fun read(root: JSONObject): Timing {
            val spans = ArrayList<IntArray>()
            root.optJSONArray("ayah")?.let { arr ->
                for (i in 0 until arr.length()) {
                    val a = arr.getJSONArray(i)
                    spans.add(intArrayOf(a.getInt(0), a.getInt(1)))
                }
            }

            val runs = ArrayList<IntArray>()
            root.optJSONArray("word")?.let { arr ->
                for (i in 0 until arr.length()) {
                    val a = arr.getJSONArray(i)
                    runs.add(ordered(IntArray(a.length()) { a.getInt(it) }))
                }
            }

            return Timing(spans, runs)
        }
    }
}
