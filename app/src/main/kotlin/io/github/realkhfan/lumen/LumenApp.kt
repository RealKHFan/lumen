package io.github.realkhfan.lumen

import android.app.Application
import android.app.UiModeManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Handler
import android.os.Looper
import android.os.Process
import io.github.realkhfan.lumen.compat.Compat
import io.github.realkhfan.lumen.core.SecureSession
import io.github.realkhfan.lumen.data.MediaRepo
import io.github.realkhfan.lumen.data.Prefs
import io.github.realkhfan.lumen.data.ThumbLoader
import io.github.realkhfan.lumen.ui.Fonts
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.ThreadFactory
import java.util.concurrent.atomic.AtomicInteger

class LumenApp : Application() {
    lateinit var prefs: Prefs
        private set
    lateinit var repo: MediaRepo
        private set
    lateinit var thumbs: ThumbLoader
        private set

    val main = Handler(Looper.getMainLooper())

    /** Full-size picture decoding and info lookups for the viewer. */
    val decoder: ExecutorService = Executors.newFixedThreadPool(2, backgroundThreads("decode"))

    /** What the camera showed through Lumen while the phone stayed locked. */
    val secureSession = SecureSession()

    /** Bumped when a colour setting changes, so open screens rebuild with the new colours. */
    var themeGeneration = 0

    override fun onCreate() {
        super.onCreate()
        CrashReport.install(this)
        prefs = Prefs(this)
        repo = MediaRepo(this, main)
        thumbs = ThumbLoader(this, main)
        Fonts.init(assets)
        // No applyTheme() here: Android remembers the choice, and re-applying it at start-up
        // could restart the screen the camera just opened.

        // Unlocking ends the locked camera session: nothing from it stays reachable later.
        Compat.registerSystemReceiver(this, object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) = secureSession.clear()
        }, IntentFilter(Intent.ACTION_USER_PRESENT))
    }

    /** Light, dark or follow the system. Called when the setting changes; Android keeps it. */
    fun applyTheme() {
        val mode = when (prefs.theme) {
            Prefs.THEME_LIGHT -> UiModeManager.MODE_NIGHT_NO
            Prefs.THEME_DARK -> UiModeManager.MODE_NIGHT_YES
            else -> UiModeManager.MODE_NIGHT_AUTO
        }
        getSystemService(UiModeManager::class.java)?.setApplicationNightMode(mode)
    }

    companion object {
        fun backgroundThreads(name: String): ThreadFactory {
            val n = AtomicInteger()
            return ThreadFactory { task ->
                Thread({
                    Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND)
                    task.run()
                }, "lumen-$name-${n.incrementAndGet()}")
            }
        }
    }
}

val Context.lumen: LumenApp get() = applicationContext as LumenApp
