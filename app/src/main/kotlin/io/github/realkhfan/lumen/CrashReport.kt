package io.github.realkhfan.lumen

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import io.github.realkhfan.lumen.compat.Compat
import java.io.File

/**
 * If Lumen ever crashes, the error is saved on the phone (never sent anywhere) and shown the
 * next time you open Lumen, with a button to copy it.
 */
object CrashReport {
    private const val FILE = "last_crash.txt"

    fun install(context: Context) {
        val app = context.applicationContext ?: context
        val version = Compat.versionName(app)
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            try {
                val text = "Lumen " + version + " on Android " + Build.VERSION.RELEASE +
                    " (API " + Build.VERSION.SDK_INT + "), " + Build.MANUFACTURER + " " + Build.MODEL +
                    "\nThread: " + thread.name + "\n\n" + Log.getStackTraceString(error)
                File(app.filesDir, FILE).writeText(text)
            } catch (ignored: Throwable) {
                // Nothing more we can do while crashing.
            }
            previous?.uncaughtException(thread, error)
        }
    }

    fun pending(context: Context): String? = try {
        File(context.filesDir, FILE).takeIf { it.isFile }?.readText()
    } catch (e: java.io.IOException) {
        null
    }

    fun clear(context: Context) {
        File(context.filesDir, FILE).delete()
    }
}

/** Shows the saved crash report. Deliberately plain, so it works even if the rest is broken. */
class CrashActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val report = CrashReport.pending(this)
        if (report == null) {
            finish()
            return
        }
        val pad = (20 * resources.displayMetrics.density).toInt()
        val title = TextView(this).apply {
            setText(R.string.crash_title)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 24f)
        }
        val body = TextView(this).apply {
            setText(R.string.crash_body)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            setPadding(0, pad / 2, 0, pad)
        }
        val trace = TextView(this).apply {
            text = report
            typeface = Typeface.MONOSPACE
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            setTextIsSelectable(true)
        }
        val copy = Button(this).apply {
            setText(R.string.crash_copy)
            setOnClickListener {
                getSystemService(ClipboardManager::class.java)
                    ?.setPrimaryClip(ClipData.newPlainText("Lumen crash report", report))
                Toast.makeText(this@CrashActivity, R.string.crash_copied, Toast.LENGTH_SHORT).show()
            }
        }
        val close = Button(this).apply {
            setText(R.string.crash_close)
            setOnClickListener {
                CrashReport.clear(this@CrashActivity)
                finish()
            }
        }
        val buttons = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END
            addView(copy)
            addView(close)
        }
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad * 3, pad, pad * 3)
            addView(title)
            addView(body)
            addView(buttons)
            addView(trace)
        }
        setContentView(ScrollView(this).apply { addView(column) })
    }
}
