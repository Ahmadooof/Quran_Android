package com.readqurantoday.quran

import android.app.Activity
import android.content.res.ColorStateList
import android.text.format.Formatter
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView

// Downloads screen for one reciter at a time; picking one here does not change the player's reciter
class DownloadsPane(
    private val host: Activity,
    private val into: LinearLayout,
    private val askToSave: (() -> Unit) -> Unit
) {

    private val blow = host.layoutInflater
    private var reciter: Recite.Reciter = Recite.chosen(host) ?: Recite.reciters().first()

    // Updated in place: rebuilding 114 rows every second would reset scroll and flicker
    private lateinit var reciterValue: TextView
    private lateinit var keptValue: TextView
    private lateinit var wholeValue: TextView
    private lateinit var getAll: TextView
    private lateinit var stopAll: TextView
    private lateinit var saveAll: TextView
    private lateinit var deleteAll: TextView
    private lateinit var shareAll: TextView
    private lateinit var openFolder: TextView
    private val rows = HashMap<Int, View>(Downloads.SURAHS)

    private var ticking = false
    private var saving = false

    fun build() {
        into.removeAllViews()
        rows.clear()

        blow.card(into, R.string.reciter, listOf(infoRow(R.string.reciter).also {
            reciterValue = it.findViewById(R.id.set_value)
            it.setOnClickListener { pickReciter() }
        }))

        blow.card(into, 0, listOf(note(R.string.dl_about)))

        blow.card(into, R.string.dl_group_space, listOf(
            infoRow(R.string.dl_kept).also { keptValue = it.findViewById(R.id.set_value) },
            infoRow(R.string.dl_whole).also { wholeValue = it.findViewById(R.id.set_value) }
        ))

        blow.card(into, R.string.dl_group_actions, listOf(
            action(R.string.dl_get_all) { confirmGetAll() }.also { getAll = it },
            action(R.string.dl_stop_all) {
                for (s in Downloads.inFlight(host, reciter.id).keys) saveAfterDownload(host, s, reciter.id, false)
                Downloads.stopAll(host, reciter.id)
                refresh()
            }.also { stopAll = it },
            action(R.string.dl_save_all) { saveAllToPhone() }.also { saveAll = it },
            action(R.string.dl_share_all) { confirmShareAll() }.also { shareAll = it },
            action(R.string.dl_open_folder) { host.openPhoneFolder(reciter) }.also { openFolder = it },
            action(R.string.dl_delete_all) { confirmDeleteAll() }.also { deleteAll = it }
        ))

        blow.card(into, R.string.search_surahs, Surahs.list().map { s ->
            blow.inflate(R.layout.row_download, into, false).also { row ->
                fillSurahTitle(row.findViewById(R.id.dl_title), s.id, R.dimen.surah_title_row)
                rows[s.id] = row
            }
        })

        refresh()
        // Sizes not known yet are fetched in the background
        Downloads.learnSizes(host, reciter.id) { if (!host.isFinishing) refresh() }
    }

    /* Everything that can have changed: what is kept, what is on its way, the sizes. */
    private fun refresh() {
        val kept = Downloads.kept(host, reciter.id)
        val flying = Downloads.inFlight(host, reciter.id)

        reciterValue.text = reciter.nameAr

        // Nothing kept is said in words: zero surahs and zero bytes read as stray dots in Arabic figures
        keptValue.text = if (kept.isEmpty()) host.getString(R.string.dl_kept_none) else host.getString(
            R.string.dl_kept_value,
            figures(kept.size, host.resources), figures(Downloads.SURAHS, host.resources),
            bytes(Downloads.keptBytes(host, reciter.id, kept))
        )

        // Marked as still counting until every surah size is known
        val sizes = (1..Downloads.SURAHS).map { Downloads.size(host, it, reciter.id) }
        val known = sizes.count { it > 0 }
        wholeValue.text = if (known == Downloads.SURAHS) bytes(sizes.sum())
            else host.getString(R.string.dl_counting, bytes(sizes.sum()))

        val missing = (1..Downloads.SURAHS).filter { it !in kept && it !in flying }
        getAll.visibility = if (missing.isEmpty()) View.GONE else View.VISIBLE
        stopAll.visibility = if (flying.isEmpty()) View.GONE else View.VISIBLE
        saveAll.isEnabled = !saving
        saveAll.alpha = if (saving) 0.5f else 1f
        deleteAll.visibility = if (kept.isEmpty()) View.GONE else View.VISIBLE
        val anySaved = kept.any { isOnPhone(host, it, reciter.id) }
        shareAll.visibility = if (anySaved) View.VISIBLE else View.GONE
        openFolder.visibility = shareAll.visibility
        /* A hidden action's seam goes with it, so a card never shows two lines in a row. */
        for (a in listOf(getAll, stopAll, shareAll, openFolder, deleteAll)) seamBefore(a)?.visibility = a.visibility

        for ((surah, row) in rows) sayRow(surah, row, surah in kept, flying[surah])

        if (flying.isNotEmpty()) tick()
        catchUpSaves()
    }

    // Every row has the same three buttons: save to phone and share wait, greyed, until the surah is downloaded
    private fun sayRow(surah: Int, row: View, isKept: Boolean, flight: Downloads.Flight?) {
        val detail = row.findViewById<TextView>(R.id.dl_detail)
        val act = row.findViewById<ImageView>(R.id.dl_action)
        val actButton = row.findViewById<View>(R.id.dl_action_wrap)
        val actLabel = row.findViewById<TextView>(R.id.dl_action_label)
        val size = Downloads.size(host, surah, reciter.id)
        val sizeText = if (size > 0) bytes(size) else host.getString(R.string.dl_size_unknown)
        val onPhone = isKept && isOnPhone(host, surah, reciter.id)

        when {
            flight != null -> {
                val total = if (flight.total > 0) flight.total else size
                detail.text = if (total > 0) host.getString(
                    R.string.dl_progress,
                    figures((flight.done * 100 / total).toInt().coerceIn(0, 100), host.resources),
                    bytes(total)
                ) else host.getString(R.string.dl_waiting)
                act.setImageResource(R.drawable.ic_close)
                say(actButton, act, actLabel, R.string.dl_stop_one, ready = true)
                actButton.setOnClickListener {
                    Downloads.stop(host, surah, reciter.id)
                    saveAfterDownload(host, surah, reciter.id, false)
                    refresh()
                }
            }
            isKept -> {
                detail.text = if (onPhone) host.getString(R.string.dl_kept_saved, sizeText) else sizeText
                // Only a mark: removing is done for all surahs at once
                act.setImageResource(R.drawable.ic_downloaded)
                say(actButton, act, actLabel, R.string.dl_done_short, ready = true)
                actButton.setOnClickListener(null)
                actButton.isClickable = false
            }
            else -> {
                detail.text = sizeText
                act.setImageResource(R.drawable.ic_download)
                say(actButton, act, actLabel, R.string.dl_get_one, ready = true)
                actButton.setOnClickListener { Downloads.start(host, surah, reciter.id); refresh() }
            }
        }

        // Share hands over the downloaded file itself; saving puts a copy in the phone's Download folder
        val share = row.findViewById<View>(R.id.dl_share_wrap)
        say(share, row.findViewById(R.id.dl_share), row.findViewById(R.id.dl_share_label), R.string.dl_share, ready = isKept)
        share.setOnClickListener { if (isKept) host.shareDownloads(listOf(surah), reciter) else host.notice(host.getString(R.string.dl_get_first)) }

        val save = row.findViewById<View>(R.id.dl_save_wrap)
        val saveIcon = row.findViewById<ImageView>(R.id.dl_save)
        saveIcon.setImageResource(if (onPhone) R.drawable.ic_check else R.drawable.ic_save_phone)
        say(save, saveIcon, row.findViewById(R.id.dl_save_label), if (onPhone) R.string.dl_saved_short else R.string.dl_save_short,
            ready = isKept && !onPhone)
        save.setOnClickListener {
            when {
                onPhone -> Unit
                isKept -> saveToPhoneOne(surah)
                else -> host.notice(host.getString(R.string.dl_get_first))
            }
        }
    }

    // --- actions ---

    private fun pickReciter() {
        val voices = Recite.reciters()
        host.sheet(host.getString(R.string.reciter), voices.map {
            Choice(it.nameAr, it.noteAr, it.id == reciter.id)
        }) { i ->
            reciter = voices[i]
            build()
        }
    }

    /* Before fetching gigabytes: how many surahs, how big, and over which network. */
    private fun confirmGetAll() {
        val kept = Downloads.kept(host, reciter.id)
        val flying = Downloads.inFlight(host, reciter.id).keys
        val missing = (1..Downloads.SURAHS).filter { it !in kept && it !in flying }
        val sizes = missing.map { Downloads.size(host, it, reciter.id) }
        val total = sizes.sum()
        val size = if (sizes.all { it > 0 }) bytes(total) else host.getString(R.string.dl_counting, bytes(total))
        host.sheet(
            host.getString(R.string.dl_get_all_ask, figures(missing.size, host.resources), size),
            listOf(Choice(host.getString(R.string.dl_get_all_do)))
        ) {
            Downloads.startAll(host, reciter.id)
            refresh()
        }
    }

    private fun confirmDeleteAll() {
        val kept = Downloads.kept(host, reciter.id)
        host.sheet(
            host.getString(
                R.string.dl_delete_all_ask,
                figures(kept.size, host.resources), bytes(Downloads.keptBytes(host, reciter.id, kept))
            ),
            listOf(Choice(host.getString(R.string.dl_delete_do)))
        ) {
            Downloads.removeAll(host, reciter.id)
            refresh()
        }
    }

    /* Every downloaded surah at once: sizes add up fast, so say how much before the share sheet. */
    private fun confirmShareAll() {
        val voice = reciter
        val kept = Downloads.kept(host, voice.id).sorted()
        if (kept.isEmpty()) {
            host.notice(host.getString(R.string.dl_get_first))
            return
        }
        val total = kept.sumOf { Downloads.file(host, it, voice.id).length() }
        host.sheet(
            host.getString(R.string.dl_share_all_ask, figures(kept.size, host.resources), bytes(total)),
            listOf(Choice(host.getString(R.string.dl_share)))
        ) { host.shareDownloads(kept, voice) }
    }

    private fun saveToPhoneOne(surah: Int) = askToSave {
        val voice = reciter
        Thread {
            val ok = saveToPhone(host, surah, voice)
            host.runOnUiThread {
                refresh()
                host.notice(host.getString(if (ok) R.string.dl_saved_one else R.string.dl_save_failed))
            }
        }.start()
    }

    /* Every kept surah not already saved, copied out off the main thread. */
    private fun saveAllToPhone() {
        val voice = reciter
        val kept = Downloads.kept(host, voice.id)
        val flying = Downloads.inFlight(host, voice.id).keys
        val missing = (1..Downloads.SURAHS).filter { it !in kept && it !in flying }
        if (missing.isEmpty() && flying.isEmpty() && kept.all { isOnPhone(host, it, voice.id) }) {
            host.notice(host.getString(R.string.dl_save_done))
            return
        }
        if (missing.isEmpty()) {
            askToSave {
                for (s in flying) saveAfterDownload(host, s, voice.id, true)
                saveUnsaved()
            }
            return
        }
        // Some are not downloaded: say how much will come down before it all lands on the phone
        val sizes = missing.map { Downloads.size(host, it, voice.id) }
        val size = if (sizes.all { it > 0 }) bytes(sizes.sum()) else host.getString(R.string.dl_counting, bytes(sizes.sum()))
        host.sheet(
            host.getString(R.string.dl_save_all_ask, figures(missing.size, host.resources), size),
            listOf(Choice(host.getString(R.string.dl_save_all_do)))
        ) {
            askToSave {
                for (s in missing + flying) saveAfterDownload(host, s, voice.id, true)
                Downloads.startAll(host, voice.id)
                saveUnsaved()
            }
        }
    }

    private fun saveUnsaved() {
        if (saving) return
        saving = true
        refresh()
        val voice = reciter
        val kept = Downloads.kept(host, voice.id).filter { !isOnPhone(host, it, voice.id) }.sorted()
        Thread {
            val done = kept.count { saveToPhone(host, it, voice) }
            host.runOnUiThread {
                saving = false
                refresh()
                if (kept.isNotEmpty()) host.notice(host.getString(R.string.dl_saved_all, figures(done, host.resources)))
            }
        }.start()
    }

    /* Saves the download receiver has not got to yet, done here too while the screen is open. */
    private var catchingUp = false

    private fun catchUpSaves() {
        if (catchingUp || saving) return
        catchingUp = true
        Thread {
            val done = saveFinishedDownloads(host)
            host.runOnUiThread {
                catchingUp = false
                if (done > 0 && !host.isFinishing) refresh()
            }
        }.start()
    }

    // --- pieces ---

    /* Once a second while anything is downloading; stops itself when nothing is. */
    private fun tick() {
        if (ticking) return
        ticking = true
        into.postDelayed({
            ticking = false
            if (!host.isFinishing && into.isAttachedToWindow) refresh()
        }, 1000)
    }

    private fun note(text: Int): View =
        (blow.inflate(R.layout.row_setting_note, into, false) as TextView).also { it.setText(text) }

    private fun infoRow(label: Int): View =
        blow.inflate(R.layout.row_setting, into, false).also {
            it.findViewById<TextView>(R.id.set_label).setText(label)
        }

    private fun action(label: Int, act: () -> Unit): TextView =
        (blow.inflate(R.layout.row_setting_action, into, false) as TextView).also {
            it.setText(label)
            it.setOnClickListener { act() }
        }

    /* The seam card() put before [row], if it has one. */
    private fun seamBefore(row: View): View? {
        val parent = row.parent as? LinearLayout ?: return null
        val i = parent.indexOfChild(row)
        return if (i > 0) parent.getChildAt(i - 1) else null
    }

    // The icon in the accent, the label in plain text; greyed, both, while the button waits for the download.
    // The label also names the button for screen readers
    private fun say(button: View, icon: ImageView, label: TextView, text: Int, ready: Boolean) {
        tint(icon, if (ready) R.color.accent else R.color.text_mute)
        label.setText(text)
        label.setTextColor(host.getColor(if (ready) R.color.text else R.color.text_mute))
        button.alpha = if (ready) 1f else WAITING
        button.isClickable = true
        button.contentDescription = label.text
    }

    private fun tint(v: ImageView, colour: Int) {
        v.imageTintList = ColorStateList.valueOf(host.getColor(colour))
    }

    private fun bytes(n: Long): String = Formatter.formatShortFileSize(host, n)

    private companion object {
        const val WAITING = 0.45f
    }
}
