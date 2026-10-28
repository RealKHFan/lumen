package io.github.realkhfan.lumen.compat

import android.Manifest
import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.IntentFilter
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Parcelable
import android.view.Window
import android.window.OnBackInvokedCallback
import android.window.OnBackInvokedDispatcher

/**
 * Every call that needs a newer Android than 12 (API 31) lives here, behind a version check.
 * Android lint (part of every release build) checks that the rest of the app works on Android 12.
 */
object Compat {
    /** Runtime permissions for reading photos and videos on this Android version. */
    fun mediaPermissions(): Array<String> = when {
        Build.VERSION.SDK_INT >= 34 -> arrayOf(
            Manifest.permission.READ_MEDIA_IMAGES,
            Manifest.permission.READ_MEDIA_VIDEO,
            Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED,
        )
        Build.VERSION.SDK_INT >= 33 -> arrayOf(Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.READ_MEDIA_VIDEO)
        else -> arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
    }

    private fun granted(context: Context, permission: String) =
        context.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED

    /** Full access to every photo and video. */
    fun hasFullMediaAccess(context: Context): Boolean = when {
        Build.VERSION.SDK_INT >= 33 -> granted(context, Manifest.permission.READ_MEDIA_IMAGES) ||
            granted(context, Manifest.permission.READ_MEDIA_VIDEO)
        else -> granted(context, Manifest.permission.READ_EXTERNAL_STORAGE)
    }

    /** Any access, including Android 14's "selected photos only". */
    fun hasMediaAccess(context: Context): Boolean =
        hasFullMediaAccess(context) ||
            (Build.VERSION.SDK_INT >= 34 && granted(context, Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED))

    fun isPartialAccess(context: Context): Boolean = hasMediaAccess(context) && !hasFullMediaAccess(context)

    fun <T : Parcelable> parcelableExtra(intent: Intent, name: String, type: Class<T>): T? =
        if (Build.VERSION.SDK_INT >= 33) {
            intent.getParcelableExtra(name, type)
        } else {
            @Suppress("DEPRECATION")
            val value = intent.getParcelableExtra<Parcelable>(name)
            if (type.isInstance(value)) type.cast(value) else null
        }

    /** Turns off the system's open or close animation; Lumen animates the viewer itself. */
    fun skipTransition(activity: Activity, opening: Boolean) {
        if (Build.VERSION.SDK_INT >= 34) {
            activity.overrideActivityTransition(
                if (opening) Activity.OVERRIDE_TRANSITION_OPEN else Activity.OVERRIDE_TRANSITION_CLOSE, 0, 0,
            )
        } else {
            @Suppress("DEPRECATION")
            activity.overridePendingTransition(0, 0)
        }
    }

    /** Draw behind the status and navigation bars (always the case from Android 15 on). */
    fun edgeToEdge(window: Window) {
        @Suppress("DEPRECATION")
        window.setDecorFitsSystemWindows(false)
    }

    /** For system broadcasts such as "phone unlocked"; never reachable by other apps. */
    fun registerSystemReceiver(context: Context, receiver: BroadcastReceiver, filter: IntentFilter) {
        if (Build.VERSION.SDK_INT >= 33) context.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
        else context.registerReceiver(receiver, filter)
    }

    fun versionName(context: Context): String = try {
        val info = if (Build.VERSION.SDK_INT >= 33) {
            context.packageManager.getPackageInfo(context.packageName, PackageManager.PackageInfoFlags.of(0))
        } else {
            @Suppress("DEPRECATION")
            context.packageManager.getPackageInfo(context.packageName, 0)
        }
        info.versionName ?: "?"
    } catch (e: PackageManager.NameNotFoundException) {
        "?"
    }
}

/**
 * Back gesture handling for both Android 12 (onBackPressed) and 13+ (predictive back).
 * Only enable it while Lumen has something to close, so the system back animation still works.
 */
class BackHandler(private val activity: Activity, private val onBack: () -> Unit) {
    private var callback: Any? = null

    var enabled: Boolean = false
        set(value) {
            if (field == value) return
            field = value
            update33(value)
        }

    private fun update33(on: Boolean) {
        if (Build.VERSION.SDK_INT < 33) return
        val dispatcher = activity.onBackInvokedDispatcher
        if (on) {
            val cb = OnBackInvokedCallback { onBack() }
            callback = cb
            dispatcher.registerOnBackInvokedCallback(OnBackInvokedDispatcher.PRIORITY_DEFAULT, cb)
        } else {
            (callback as? OnBackInvokedCallback)?.let { dispatcher.unregisterOnBackInvokedCallback(it) }
            callback = null
        }
    }

    /** For Android 12: call from onBackPressed. Returns true when the press was handled. */
    fun handleLegacy(): Boolean {
        if (!enabled) return false
        onBack()
        return true
    }
}
