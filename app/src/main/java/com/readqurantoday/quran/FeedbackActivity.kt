package com.readqurantoday.quran

import android.content.res.ColorStateList
import android.os.Bundle
import android.text.InputType
import android.view.View
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

/** Report an issue or suggest something; sent to our own server, with email as the fallback. */
class FeedbackActivity : AppCompatActivity() {

    private lateinit var into: LinearLayout
    private var kind = Feedback.BUG
    private var severity: String? = null
    private var sending = false

    private lateinit var message: EditText
    private lateinit var email: EditText
    private lateinit var severityCard: View
    private val kindRows = HashMap<String, View>()
    private val severityRows = HashMap<String?, View>()
    private lateinit var send: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // The reciter list is read to name the chosen reciter in the report
        Recite.load(this)
        setContentView(R.layout.activity_feedback)

        findViewById<ImageView>(R.id.fb_back_icon).imageTintList =
            ColorStateList.valueOf(getColor(R.color.accent))
        findViewById<View>(R.id.fb_back).setOnClickListener { finish() }

        into = findViewById(R.id.fb_groups)
        build()
        sayBars()
    }

    private fun build() {
        val blow = layoutInflater

        blow.card(into, R.string.fb_kind, Feedback.KINDS.map { (key, label) ->
            choice(label) { kind = key; say() }.also { kindRows[key] = it }
        })

        blow.card(into, R.string.fb_severity, Feedback.SEVERITIES.map { (key, label) ->
            choice(label) { severity = key; say() }.also { severityRows[key] = it }
        })
        severityCard = into.getChildAt(into.childCount - 1)

        message = input(R.string.fb_message_hint, InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES).apply {
            minLines = 5
        }
        blow.card(into, R.string.fb_message, listOf(message))

        email = input(R.string.fb_email_hint, InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS)
        blow.card(into, R.string.fb_email, listOf(email, note(R.string.fb_details_note)))

        send = action(R.string.fb_send) { submit() }
        blow.card(into, 0, listOf(send, action(R.string.fb_by_email) { byEmail() }))

        say()
    }

    // Tick on the chosen rows; severity only matters for a problem
    private fun say() {
        for ((key, row) in kindRows) row.findViewById<TextView>(R.id.set_value).text = if (key == kind) "✓" else ""
        for ((key, row) in severityRows) row.findViewById<TextView>(R.id.set_value).text = if (key == severity) "✓" else ""
        severityCard.visibility = if (kind == Feedback.BUG) View.VISIBLE else View.GONE
        send.alpha = if (sending) 0.5f else 1f
        send.isEnabled = !sending
    }

    private fun submit() {
        val text = message.text.toString().trim()
        if (text.length < Feedback.MIN_MESSAGE) {
            notice(getString(R.string.fb_too_short))
            return
        }
        val address = email.text.toString().trim()
        if (address.isNotEmpty() && !android.util.Patterns.EMAIL_ADDRESS.matcher(address).matches()) {
            notice(getString(R.string.fb_bad_email))
            return
        }
        sending = true
        say()
        val report = Feedback.Report(kind, if (kind == Feedback.BUG) severity else null, text, address.ifEmpty { null })
        Thread {
            val result = Feedback.send(this, report)
            runOnUiThread {
                sending = false
                say()
                when (result) {
                    Feedback.Result.SENT -> {
                        notice(getString(R.string.fb_thanks))
                        message.setText("")
                    }
                    Feedback.Result.TOO_MANY -> notice(getString(R.string.fb_too_many))
                    Feedback.Result.FAILED -> sheet(
                        getString(R.string.fb_failed),
                        listOf(Choice(getString(R.string.fb_by_email)))
                    ) { byEmail() }
                }
            }
        }.start()
    }

    private fun byEmail() = reportIssue(message.text.toString().trim())

    private fun choice(label: Int, pick: () -> Unit): View =
        layoutInflater.inflate(R.layout.row_setting, into, false).also {
            it.findViewById<TextView>(R.id.set_label).setText(label)
            it.setOnClickListener { pick() }
        }

    private fun input(hint: Int, type: Int): EditText =
        (layoutInflater.inflate(R.layout.row_setting_input, into, false) as EditText).also {
            it.setHint(hint)
            it.inputType = type
        }

    private fun note(text: Int): View =
        (layoutInflater.inflate(R.layout.row_setting_note, into, false) as TextView).also { it.setText(text) }

    private fun action(label: Int, act: () -> Unit): TextView =
        (layoutInflater.inflate(R.layout.row_setting_action, into, false) as TextView).also {
            it.setText(label)
            it.setOnClickListener { act() }
        }

    private fun sayBars() {
        showBars(roof = groundOf(findViewById(R.id.fb_head)), floor = groundOf(findViewById(R.id.fb_root)))
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) sayBars()
    }
}
