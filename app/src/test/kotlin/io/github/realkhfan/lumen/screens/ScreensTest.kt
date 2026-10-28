package io.github.realkhfan.lumen.screens

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.graphics.Rect
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import androidx.test.core.app.ApplicationProvider
import com.github.takahirom.roborazzi.captureRoboImage
import io.github.realkhfan.lumen.FolderActivity
import io.github.realkhfan.lumen.LumenApp
import io.github.realkhfan.lumen.MainActivity
import io.github.realkhfan.lumen.viewer.ViewerActivity
import org.junit.Assert.assertNotNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.time.Duration

/**
 * Screenshots of Lumen's key screens with a fake photo library, saved to src/test/screenshots by
 * `./gradlew recordRoborazziDebug`. The fake photos are dated relative to today, so dates in the
 * pictures change from day to day: look at them, don't compare them pixel by pixel.
 * A plain `testDebugUnitTest` still runs every screen, so a crash fails the build.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w384dp-h854dp-xxhdpi")
class ScreensTest {
    private val app get() = ApplicationProvider.getApplicationContext<LumenApp>()

    @Before
    fun setUp() {
        Robolectric.setupContentProvider(FakeMediaProvider::class.java, "media")
        shadowOf(app).grantPermissions(Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.READ_MEDIA_VIDEO)
    }

    @Test
    fun photos() = shoot(main(), "photos")

    @Test
    @Config(qualifiers = "+night")
    fun photosDark() = shoot(main(), "photos_dark")

    @Test
    fun photosLargeText() {
        RuntimeEnvironment.setFontScale(2f)
        shoot(main(), "photos_large_text")
    }

    @Test
    fun photosScrolled() {
        val a = main()
        find(a) { it is android.widget.ListView }!!.let { (it as android.widget.ListView).setSelectionFromTop(4, 0) }
        shoot(a, "photos_scrolled")
    }

    @Test
    fun selecting() {
        val a = main()
        val firstPhoto = find(a) { it.contentDescription?.startsWith("Photo,") == true }
        assertNotNull("no thumbnail on screen", firstPhoto)
        firstPhoto!!.performLongClick()
        shoot(a, "selecting")
    }

    @Test
    fun folderFilter() {
        val a = main()
        find(a) { it is ViewGroup && it.hasOnClickListeners() && hasText(it, "All folders") }!!.performClick()
        shoot(a, "folder_filter")
    }

    @Test
    fun menu() {
        val a = main()
        find(a) { it.contentDescription == "Open menu" }!!.performClick()
        shoot(a, "menu")
    }

    @Test
    fun folders() {
        val a = main()
        find(a) { it.contentDescription == "Folders" }!!.performClick()
        shoot(a, "folders")
    }

    @Test
    fun bin() {
        val a = main()
        find(a) { it.contentDescription == "Bin" }!!.performClick()
        shoot(a, "bin")
    }

    @Test
    fun folder() {
        val intent = Intent(app, FolderActivity::class.java)
            .putExtra(FolderActivity.EXTRA_BUCKET, 2L)
            .putExtra(FolderActivity.EXTRA_NAME, "Screenshots")
        shoot(start(Robolectric.buildActivity(FolderActivity::class.java, intent)), "folder")
    }

    @Test
    fun emptyFolder() {
        // A folder whose photos all went to the bin stays open with a message (its Undo stays usable).
        val intent = Intent(app, FolderActivity::class.java)
            .putExtra(FolderActivity.EXTRA_BUCKET, 99L)
            .putExtra(FolderActivity.EXTRA_NAME, "Downloads")
        val a = start(Robolectric.buildActivity(FolderActivity::class.java, intent))
        assertNotNull("empty message missing", find(a) { it is android.widget.TextView && it.text == "This folder is empty" })
        shoot(a, "folder_empty")
    }

    @Test
    fun viewer() {
        val intent = Intent(app, ViewerActivity::class.java)
            .putExtra(ViewerActivity.EXTRA_SOURCE, ViewerActivity.SOURCE_LIBRARY)
            .putExtra(ViewerActivity.EXTRA_START_ID, 1L)
            .putExtra(ViewerActivity.EXTRA_FROM, Rect(0, 300, 280, 580))
        shoot(start(Robolectric.buildActivity(ViewerActivity::class.java, intent)), "viewer")
    }

    @Test
    fun noAccess() {
        shadowOf(app).denyPermissions(Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.READ_MEDIA_VIDEO)
        shoot(start(Robolectric.buildActivity(MainActivity::class.java)), "no_access")
    }

    // ------------------------------------------------------------------ helpers

    private fun main(): Activity = start(Robolectric.buildActivity(MainActivity::class.java))

    private fun <T : Activity> start(controller: ActivityController<T>): T {
        val activity = controller.setup().get()
        settle()
        return activity
    }

    /** Waits until Lumen's loader threads (media list, thumbnails, pictures) are idle and animations end. */
    private fun settle() {
        var quiet = 0
        repeat(400) {
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(100))
            Thread.sleep(25)
            val busy = Thread.getAllStackTraces().keys.any { it.name.startsWith("lumen-") && it.state == Thread.State.RUNNABLE }
            quiet = if (busy) 0 else quiet + 1
            if (quiet >= 8) return
        }
    }

    private fun shoot(activity: Activity, name: String) {
        settle()
        activity.window.decorView.captureRoboImage("src/test/screenshots/$name.png")
    }

    private fun find(activity: Activity, match: (View) -> Boolean): View? {
        fun walk(v: View): View? {
            if (v.isShown && match(v)) return v
            if (v is ViewGroup) for (i in 0 until v.childCount) walk(v.getChildAt(i))?.let { return it }
            return null
        }
        return walk(activity.window.decorView)
    }

    private fun hasText(v: View, text: String): Boolean = when (v) {
        is android.widget.TextView -> v.text?.toString() == text
        is ViewGroup -> (0 until v.childCount).any { hasText(v.getChildAt(it), text) }
        else -> false
    }
}
