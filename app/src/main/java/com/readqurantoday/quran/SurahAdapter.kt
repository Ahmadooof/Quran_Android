package com.readqurantoday.quran

import android.content.res.ColorStateList
import android.graphics.Typeface
import android.text.Spannable
import android.text.SpannableString
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView

/**
 * The index's result list: open, play/pause, reciter. Downloads live in their own screen.
 *
 * With an empty search box every row is a surah. With something typed the list
 * becomes whatever Search made of it — titled sections, a page to jump to, or a
 * line saying nothing matched — so the rows are no longer all of one kind.
 */
class SurahAdapter(
    private val all: List<Surahs.Surah>,
    private val names: Typeface?,
    private val onOpen: (Surahs.Surah) -> Unit,
    private val onPage: (Int) -> Unit,
    private val onVerse: (Int, Int) -> Unit,
    private val onPlay: (Surahs.Surah) -> Unit,
    private val onReciter: (Surahs.Surah) -> Unit,
    private val playingId: () -> Int
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    companion object {
        private const val T_NAME = 0
        private const val T_HEAD = 1
        private const val T_PAGE = 2
        private const val T_NONE = 3
        private const val T_VERSE = 4
    }

    class Head(v: View) : RecyclerView.ViewHolder(v)
    class Page(v: View) : RecyclerView.ViewHolder(v)
    class None(v: View) : RecyclerView.ViewHolder(v)
    class Verse(v: View) : RecyclerView.ViewHolder(v)

    private val whole = all.map { Search.Hit.Name(it) }
    private var shown: List<Search.Hit> = whole

    /** Hand the box's contents over; an unnarrowed box shows the whole index. */
    fun submit(query: String) {
        val plan = Search.plan(query, all)
        shown = plan.ifEmpty { whole }
        notifyDataSetChanged()
    }

    override fun getItemCount() = shown.size

    override fun getItemViewType(position: Int) = when (shown[position]) {
        is Search.Hit.Name  -> T_NAME
        is Search.Hit.Head  -> T_HEAD
        is Search.Hit.Page  -> T_PAGE
        is Search.Hit.None  -> T_NONE
        is Search.Hit.Verse -> T_VERSE
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val blow = LayoutInflater.from(parent.context)
        return when (viewType) {
            T_HEAD  -> Head(blow.inflate(R.layout.item_search_head, parent, false))
            T_PAGE  -> Page(blow.inflate(R.layout.item_search_page, parent, false))
            T_NONE  -> None(blow.inflate(R.layout.item_search_none, parent, false))
            T_VERSE -> Verse(blow.inflate(R.layout.item_search_ayah, parent, false))
            else    -> SurahRow(blow.inflate(R.layout.item_surah, parent, false), names)
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (val hit = shown[position]) {
            is Search.Hit.None  -> Unit
            is Search.Hit.Head  -> (holder.itemView as TextView).setText(hit.title)
            is Search.Hit.Page  -> bindPage(holder.itemView, hit.page)
            is Search.Hit.Verse -> bindVerse(holder.itemView, hit)
            is Search.Hit.Name  -> bindSurah(holder as SurahRow, hit.surah, position)
        }
    }

    private fun bindVerse(row: View, hit: Search.Hit.Verse) {
        val ctx = row.context
        val a = hit.ayah

        /* Mark the part that was matched, so the eye lands on it rather than
           reading the whole line to find out why it is here. */
        val body = row.findViewById<TextView>(R.id.ayah_text)
        if (hit.at >= 0 && hit.at + hit.len <= a.text.length) {
            val span = SpannableString(a.text)
            span.setSpan(
                ForegroundColorSpan(ctx.getColor(R.color.accent)),
                hit.at, hit.at + hit.len, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
            )
            span.setSpan(
                StyleSpan(Typeface.BOLD),
                hit.at, hit.at + hit.len, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
            )
            body.text = span
        } else {
            body.text = a.text
        }

        val name = Surahs.list().firstOrNull { it.id == a.surah }?.name.orEmpty()
        row.findViewById<TextView>(R.id.ayah_ref).text =
            ctx.getString(R.string.search_ayah_ref, name, figures(a.ayah, ctx.resources))

        row.setOnClickListener { onVerse(a.surah, a.ayah) }
    }

    private fun bindPage(row: View, page: Int) {
        val ctx = row.context
        row.findViewById<TextView>(R.id.page_title).text =
            ctx.getString(R.string.search_page, figures(page, ctx.resources))
        val where = row.findViewById<TextView>(R.id.page_where)
        val surah = Surahs.ofPage(page)
        where.text = if (surah == null) "" else
            ctx.getString(R.string.search_page_in, surah.name)
        where.visibility = if (surah == null) View.GONE else View.VISIBLE
        row.setOnClickListener { onPage(page) }
    }

    private fun bindSurah(holder: SurahRow, s: Surahs.Surah, position: Int) {
        val ctx = holder.itemView.context
        holder.fill(s)
        /* The seam belongs between two surahs, not under the last of a section. */
        val next = shown.getOrNull(position + 1)
        holder.itemView.findViewById<View>(R.id.divider).visibility =
            if (next is Search.Hit.Name) View.VISIBLE else View.GONE

        holder.itemView.setOnClickListener { onOpen(s) }
        holder.playBtn?.setOnClickListener { onPlay(s) }
        holder.reciterBtn?.setOnClickListener { onReciter(s) }

        /* `playing` = this surah is the assigned track (even if paused).
           `active`  = it is currently running — drives the pause/play icon. */
        val playing = playingId() == s.id
        val active  = playing && Recite.wantsToPlay()

        /*
          The surah playing looks like every other row: the same number circle, the same
          soft play disc. Only what the button does changes — pause while it runs, a
          spinner while its audio is on its way — since that is what a tap on it does.
          It used to be filled in accent, number and disc both, which set one row apart
          in colour on a list meant to read evenly.
        */
        holder.num.setBackgroundResource(R.drawable.num_circle)
        holder.num.setTextColor(ctx.getColor(R.color.on_dark))

        val waiting = playing && Recite.waiting()
        val onDisc = ctx.getColor(R.color.accent)
        holder.play?.apply {
            setImageResource(if (active) R.drawable.ic_pause else R.drawable.ic_play)
            setBackgroundResource(R.drawable.chip_soft)
            imageTintList = ColorStateList.valueOf(onDisc)
            imageAlpha = if (waiting) 0 else 255
        }
        holder.playWait?.apply {
            indeterminateTintList = ColorStateList.valueOf(onDisc)
            visibility = if (waiting) android.view.View.VISIBLE else android.view.View.GONE
        }
    }
}
