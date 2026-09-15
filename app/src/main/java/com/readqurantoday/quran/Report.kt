package com.readqurantoday.quran

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri

/** Open the user's email app with [message] and the app and phone details filled in: the fallback when sending fails. */
fun Activity.reportIssue(message: String = "") {
    val d = Feedback.details(this)
    val details = getString(R.string.report_details, d.version, d.android, d.sdk, d.device, d.language)
    val to = getString(R.string.report_email)
    val mail = Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:"))
        .putExtra(Intent.EXTRA_SUBJECT, getString(R.string.report_subject, getString(R.string.app_name)))
        .putExtra(Intent.EXTRA_TEXT, message + details)
        .putExtra(Intent.EXTRA_EMAIL, arrayOf(to))
    try {
        startActivity(Intent.createChooser(mail, getString(R.string.set_report)))
    } catch (_: ActivityNotFoundException) {
        notice(getString(R.string.report_no_mail))
    }
}
