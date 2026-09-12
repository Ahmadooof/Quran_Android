package com.readqurantoday.quran

import android.content.res.ColorStateList
import android.graphics.Typeface
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView

/** Surah list adapter: open, play/pause, download, reciter. */
class SurahAdapter(
    private val all: List<Surahs.Surah>,
    private val names: Typeface?,
    private val onOpen: (Surahs.Surah) -> Unit,
    private val onPlay: (Surahs.Surah) -> Unit,
    private val onDownload: (Surahs.Surah) -> Unit,
    private val onReciter: (Surahs.Surah) -> Unit,
    private val stateOf: (Surahs.Surah) -> Int,
    private val playingId: () -> Int
) : RecyclerView.Adapter<SurahRow>() {

    companion object {
        const val AWAY   = 0
        const val COMING = 1
        const val KEPT   = 2
    }

    private var shown = all.toMutableList()

    fun filter(q: String) {
        shown = if (q.isBlank()) all.toMutableList()
        else all.filter { it.english.contains(q, ignoreCase = true) || it.name.contains(q) }
            .toMutableList()
        notifyDataSetChanged()
    }

    override fun getItemCount() = shown.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
        SurahRow(
            LayoutInflater.from(parent.context).inflate(R.layout.item_surah, parent, false),
            names
        )

    override fun onBindViewHolder(holder: SurahRow, position: Int) {
        val s   = shown[position]
        val ctx = holder.itemView.context
        holder.fill(s)

        holder.itemView.setOnClickListener { onOpen(s) }
        holder.playBtn?.setOnClickListener { onPlay(s) }
        holder.downloadBtn?.setOnClickListener { onDownload(s) }
        holder.reciterBtn?.setOnClickListener { onReciter(s) }

        /* `playing` = this surah is the assigned track (even if paused).
           `active`  = it is currently running — drives the pause/play icon. */
        val playing = playingId() == s.id
        val active  = playing && Recite.wantsToPlay()

        /* Number circle: filled while this surah owns the player. */
        holder.num.setBackgroundResource(
            if (playing) R.drawable.bg_circle_accent else R.drawable.num_circle
        )
        holder.num.setTextColor(ctx.getColor(if (playing) R.color.on_dark else R.color.accent))

        /* Disc button: pause icon while running, play icon while paused or idle. */
        holder.play?.apply {
            setImageResource(if (active) R.drawable.ic_pause else R.drawable.ic_play)
            setBackgroundResource(
                if (playing) R.drawable.bg_circle_accent else R.drawable.chip_soft
            )
            imageTintList = ColorStateList.valueOf(
                ctx.getColor(if (playing) R.color.on_dark else R.color.accent)
            )
        }

        val dl = holder.download
        if (dl != null) {
            when (stateOf(s)) {
                KEPT   -> {
                    dl.setImageResource(R.drawable.ic_downloaded)
                    dl.imageTintList = ColorStateList.valueOf(ctx.getColor(R.color.accent))
                    dl.alpha = 1f
                }
                COMING -> {
                    dl.setImageResource(R.drawable.ic_download)
                    dl.imageTintList = ColorStateList.valueOf(ctx.getColor(R.color.text_mute))
                    dl.alpha = 0.4f
                }
                else   -> {
                    dl.setImageResource(R.drawable.ic_download)
                    dl.imageTintList = ColorStateList.valueOf(ctx.getColor(R.color.text_mute))
                    dl.alpha = 1f
                }
            }
        }
    }
}
