package com.readqurantoday.quran

import android.content.Context
import androidx.core.content.edit

/** A recitation video's colours: the ground, the words, and the word being recited. Marks take the words' colour. */
data class VideoLook(val paper: Int, val ink: Int, val lit: Int) {

    companion object {
        private const val PREFS = "video-look"
        private const val PAPER = "paper"
        private const val INK = "ink"
        private const val LIT = "lit"

        /** The reader's own colours, which a video takes until it is given its own. */
        fun ofReader(context: Context) =
            VideoLook(Settings.paperColor(context), Settings.inkColor(context), Settings.highlightColor(context))

        // Ready-made looks, each a tap: light, dark and green; the reader's comes first, in the screen
        val LIGHT = VideoLook(0xFFFBF7EE.toInt(), 0xFF1A1A1A.toInt(), 0xFFB07A10.toInt())
        val DARK = VideoLook(0xFF0E1A26.toInt(), 0xFFEDE8DC.toInt(), 0xFFE8B84A.toInt())
        val GREEN = VideoLook(0xFF0F3B2E.toInt(), 0xFFF4F1E8.toInt(), 0xFFE8B84A.toInt())

        // Each colour kept on its own, so one set by hand leaves the others following the reader
        fun of(context: Context): VideoLook {
            val kept = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val reader = ofReader(context)
            return VideoLook(kept.getInt(PAPER, reader.paper), kept.getInt(INK, reader.ink), kept.getInt(LIT, reader.lit))
        }

        fun keep(context: Context, look: VideoLook) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit {
            putInt(PAPER, look.paper)
            putInt(INK, look.ink)
            putInt(LIT, look.lit)
        }
    }
}
