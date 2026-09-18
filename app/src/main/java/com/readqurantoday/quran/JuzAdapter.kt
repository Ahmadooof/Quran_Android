package com.readqurantoday.quran

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView

// The thirty juz, each opening — or reciting — at the page it begins on
class JuzAdapter(
    private val onOpen: (Int) -> Unit,
    private val onPlay: (Int) -> Unit,
    private val onReciter: (Int) -> Unit,
    private val playingJuz: () -> Int
) : RecyclerView.Adapter<JuzAdapter.Row>() {

    class Row(v: View) : RecyclerView.ViewHolder(v)

    private val starts = Surahs.juzStarts()

    override fun getItemCount() = starts.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
        Row(LayoutInflater.from(parent.context).inflate(R.layout.row_juz, parent, false))

    override fun onBindViewHolder(holder: Row, position: Int) {
        val row = holder.itemView
        val res = row.resources
        val juz = position + 1
        val page = starts[position]

        row.findViewById<TextView>(R.id.juz_num).text = figures(juz, res)
        row.findViewById<TextView>(R.id.juz_title).text =
            res.getString(R.string.head_juz, figures(juz, res))

        // Where it begins: the surah, then the page
        val surah = Surahs.ofPage(page)
        val at = res.getString(R.string.head_page, figures(page, res))
        row.findViewById<TextView>(R.id.juz_where).text =
            if (surah == null) at
            else res.getString(R.string.place_line, res.getString(R.string.surah_named, surah.name), at)

        // The seam belongs between two juz, not under the last one
        row.findViewById<View>(R.id.divider).visibility =
            if (position < starts.size - 1) View.VISIBLE else View.GONE

        row.setOnClickListener { onOpen(page) }
        row.findViewById<View>(R.id.btn_play).setOnClickListener { onPlay(page) }
        row.findViewById<View>(R.id.btn_reciter).setOnClickListener { onReciter(page) }
        sayPlay(row, juz)
    }

    // Only the juz being recited shows pause, and only its own button waits for the audio
    private fun sayPlay(row: View, juz: Int) {
        val here = playingJuz() == juz
        val playing = here && Recite.wantsToPlay()
        val waiting = here && Recite.waiting()

        row.findViewById<ImageView>(R.id.play).apply {
            setImageResource(if (playing) R.drawable.ic_pause else R.drawable.ic_play)
            visibility = if (waiting) View.INVISIBLE else View.VISIBLE
        }
        row.findViewById<View>(R.id.play_wait).visibility = if (waiting) View.VISIBLE else View.GONE
        row.findViewById<TextView>(R.id.play_label).setText(if (playing) R.string.stop else R.string.play)
    }
}
