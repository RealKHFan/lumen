package io.github.realkhfan.lumen

import android.app.Activity
import android.app.PendingIntent
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ComponentName
import android.content.Intent
import android.content.IntentSender
import android.net.Uri
import android.provider.MediaStore
import android.provider.Settings
import android.widget.Toast
import io.github.realkhfan.lumen.viewer.ViewerActivity

/** Hand-offs to other apps and to Android's own confirmation screens. */
object MediaActions {
    const val REQ_TRASH = 101
    const val REQ_RESTORE = 102
    const val REQ_DELETE = 103
    const val REQ_UNDO = 104

    fun share(activity: Activity, uris: List<Uri>, mimes: List<String>) {
        if (uris.isEmpty()) return
        val intent = if (uris.size == 1) {
            Intent(Intent.ACTION_SEND).putExtra(Intent.EXTRA_STREAM, uris[0])
        } else {
            Intent(Intent.ACTION_SEND_MULTIPLE).putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(uris))
        }
        intent.type = commonMime(mimes)
        intent.clipData = ClipData.newRawUri(null, uris[0]).also { clip ->
            for (i in 1 until uris.size) clip.addItem(ClipData.Item(uris[i]))
        }
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        start(activity, Intent.createChooser(intent, null))
    }

    // One exact type when all match, the family wildcard for mixed images, any type for photos plus videos.
    fun commonMime(mimes: List<String>): String {
        val distinct = mimes.distinct()
        if (distinct.size == 1) return distinct[0]
        val families = distinct.map { it.substringBefore('/') }.distinct()
        return if (families.size == 1) families[0] + "/*" else "*/*"
    }

    fun edit(activity: Activity, uri: Uri, mime: String) {
        val intent = Intent(Intent.ACTION_EDIT).setDataAndType(uri, mime)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        if (activity.packageManager.queryIntentActivities(intent, 0).isEmpty()) {
            Toast.makeText(activity, R.string.no_editor, Toast.LENGTH_SHORT).show()
            return
        }
        start(activity, Intent.createChooser(intent, activity.getString(R.string.action_edit)))
    }

    fun useAs(activity: Activity, uri: Uri, mime: String) {
        val intent = Intent(Intent.ACTION_ATTACH_DATA).setDataAndType(uri, mime)
            .putExtra("mimeType", mime)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        start(activity, Intent.createChooser(intent, activity.getString(R.string.action_use_as)))
    }

    fun openWith(activity: Activity, uri: Uri, mime: String) {
        val intent = Intent(Intent.ACTION_VIEW).setDataAndType(uri, mime)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        val chooser = Intent.createChooser(intent, activity.getString(R.string.action_open_with))
            .putExtra(Intent.EXTRA_EXCLUDE_COMPONENTS, arrayOf(ComponentName(activity, ViewerActivity::class.java)))
        start(activity, chooser)
    }

    /** Shows Android's "Allow Lumen to move this to the bin?" style screen. */
    fun startRequest(activity: Activity, request: PendingIntent?, code: Int): Boolean {
        if (request == null) return false
        return try {
            activity.startIntentSenderForResult(request.intentSender, code, null, 0, 0, 0)
            true
        } catch (e: IntentSender.SendIntentException) {
            false
        }
    }

    /** True when Lumen may move things to and from the bin without asking every time. */
    fun canSkipPrompts(activity: Activity): Boolean = MediaStore.canManageMedia(activity)

    fun openMediaManagementSettings(activity: Activity) {
        val intent = Intent(Settings.ACTION_REQUEST_MANAGE_MEDIA, Uri.fromParts("package", activity.packageName, null))
        try {
            activity.startActivity(intent)
        } catch (e: ActivityNotFoundException) {
            openAppSettings(activity)
        }
    }

    fun openAppSettings(activity: Activity) {
        start(activity, Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", activity.packageName, null)))
    }

    fun start(activity: Activity, intent: Intent) {
        try {
            activity.startActivity(intent)
        } catch (e: ActivityNotFoundException) {
            Toast.makeText(activity, R.string.no_app, Toast.LENGTH_SHORT).show()
        }
    }
}
