package com.readqurantoday.quran

import android.content.Context
import android.content.res.Configuration
import android.os.Build
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/** Sends a report to readqurantoday.com's feedback endpoint. */
object Feedback {

    private const val ENDPOINT = "https://readqurantoday.com/api/feedback"
    const val MIN_MESSAGE = 5

    const val BUG = "bug"

    /** What the report is about, with its label; the keys are what the server accepts. */
    val KINDS = listOf(
        BUG to R.string.fb_kind_bug,
        "suggestion" to R.string.fb_kind_suggestion,
        "feature" to R.string.fb_kind_feature,
        "other" to R.string.fb_kind_other
    )

    /** How much a problem gets in the way; null is "not sure". */
    val SEVERITIES = listOf(
        null to R.string.fb_severity_none,
        "low" to R.string.fb_severity_low,
        "medium" to R.string.fb_severity_medium,
        "high" to R.string.fb_severity_high
    )

    data class Report(val kind: String, val severity: String?, val message: String, val email: String?)

    enum class Result { SENT, TOO_MANY, FAILED }

    /** The app and phone details sent with every report, and shown to the user as sent. */
    data class Details(
        val version: String, val android: String, val sdk: Int, val device: String, val language: String, val page: Int,
        val theme: String, val themeShown: String, val motion: String, val reciter: String?, val screen: String
    )

    fun details(context: Context): Details {
        val version = try {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName.orEmpty()
        } catch (_: Exception) {
            ""
        }
        val config = context.resources.configuration
        val theme = when (Settings.theme(context)) {
            Settings.LIGHT -> "light"
            Settings.DARK -> "dark"
            else -> "system"
        }
        val night = (config.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
        val orientation = if (config.orientation == Configuration.ORIENTATION_LANDSCAPE) "landscape" else "portrait"
        return Details(
            version, Build.VERSION.RELEASE, Build.VERSION.SDK_INT,
            "${Build.MANUFACTURER} ${Build.MODEL}", Settings.language(context), Settings.lastPage(context),
            theme = theme,
            themeShown = if (night) "dark" else "light",
            motion = if (Settings.pageTurn(context)) "turn" else "slide",
            reciter = Recite.chosen(context)?.name,
            screen = "$orientation ${config.screenWidthDp}x${config.screenHeightDp}"
        )
    }

    /** Blocking network call: run off the main thread. */
    fun send(context: Context, report: Report): Result {
        val d = details(context)
        val body = JSONObject()
            .put("source", "android")
            .put("kind", report.kind)
            .put("message", report.message)
            .apply {
                report.severity?.let { put("severity", it) }
                report.email?.let { put("email", it) }
            }
            .put("app", JSONObject()
                .put("version", d.version)
                .put("android", d.android)
                .put("sdk", d.sdk)
                .put("device", d.device)
                .put("language", d.language)
                .put("theme", d.theme)
                .put("themeShown", d.themeShown)
                .put("motion", d.motion)
                .put("screen", d.screen)
                .apply {
                    if (d.page in 1..604) put("page", d.page)
                    d.reciter?.let { put("reciter", it.take(80)) }
                })
            .toString()
            .toByteArray()

        return try {
            val c = URL(ENDPOINT).openConnection() as HttpURLConnection
            try {
                c.requestMethod = "POST"
                c.connectTimeout = 8000
                c.readTimeout = 8000
                c.doOutput = true
                c.setRequestProperty("Content-Type", "application/json")
                c.setFixedLengthStreamingMode(body.size)
                c.outputStream.use { it.write(body) }
                when (c.responseCode) {
                    201 -> Result.SENT
                    429 -> Result.TOO_MANY
                    else -> Result.FAILED
                }
            } finally {
                c.disconnect()
            }
        } catch (_: Exception) {
            Result.FAILED
        }
    }
}
