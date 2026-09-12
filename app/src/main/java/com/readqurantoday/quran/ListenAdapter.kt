package com.readqurantoday.quran

import android.graphics.Typeface
import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.ImageView
import androidx.recyclerview.widget.RecyclerView

class ListenAdapter(
    private val surahs: List<Surahs.Surah>,
    private val names: Typeface?,
    private val onPlay: (Surahs.Surah) -> Unit,
    private val onKeep: (Surahs.Surah) -> Unit,
    private val stateOf: (Surahs.Surah) -> Int
) : RecyclerView.Adapter<SurahRow>() {

    companion object {
        const val AWAY   = 0
        const val COMING = 1
        const val KEPT   = 2
    }

    override fun getItemCount() = surahs.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
        SurahRow(
            LayoutInflater.from(parent.context).inflate(R.layout.item_listen, parent, false),
            names
        )

    override fun onBindViewHolder(holder: SurahRow, position: Int) {
        val s = surahs[position]
        holder.fill(s)
        holder.itemView.setOnClickListener { onPlay(s) }

        val get = holder.itemView.findViewById<ImageView>(R.id.get)
        when (stateOf(s)) {
            KEPT   -> { get.setImageResource(R.drawable.ic_downloaded); get.alpha = 1f }
            COMING -> { get.setImageResource(R.drawable.ic_download);   get.alpha = 0.4f }
            else   -> { get.setImageResource(R.drawable.ic_download);   get.alpha = 1f }
        }
        get.setOnClickListener { onKeep(s) }
    }
}
