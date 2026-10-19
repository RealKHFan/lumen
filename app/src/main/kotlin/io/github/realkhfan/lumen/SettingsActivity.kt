package io.github.realkhfan.lumen

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.res.ColorStateList
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import io.github.realkhfan.lumen.compat.Compat
import io.github.realkhfan.lumen.data.Prefs
import io.github.realkhfan.lumen.ui.Fonts
import io.github.realkhfan.lumen.ui.Palette
import io.github.realkhfan.lumen.ui.Ui
import io.github.realkhfan.lumen.ui.dp
import io.github.realkhfan.lumen.ui.onSystemInsets
import io.github.realkhfan.lumen.ui.withAlpha

class SettingsActivity : Activity() {
    private lateinit var palette: Palette
    private lateinit var mediaManagementSummary: TextView
    private val themeChecks = ArrayList<Pair<Int, ImageView>>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        palette = Palette(this)
        Ui.setupWindow(this, palette.isDark)
        val prefs = lumen.prefs

        val content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(4), 0, dp(4), 0)
            addView(Ui.iconButton(this@SettingsActivity, R.drawable.ic_arrow_back, palette.onSurface,
                getString(R.string.action_back)) { finish() })
            addView(Ui.text(this@SettingsActivity, 26f, palette.onSurface, Fonts.display, getString(R.string.settings)).apply {
                setPadding(dp(8), 0, 0, 0)
            })
        }
        content.addView(header, LinearLayout.LayoutParams(-1, dp(64)))

        section(content, R.string.settings_appearance)
        for ((mode, label) in listOf(
            Prefs.THEME_SYSTEM to R.string.settings_theme_system,
            Prefs.THEME_LIGHT to R.string.settings_theme_light,
            Prefs.THEME_DARK to R.string.settings_theme_dark,
        )) {
            val check = ImageView(this)
            themeChecks.add(mode to check)
            content.addView(row(getString(label), null, check) {
                prefs.theme = mode
                refreshThemeChecks()
                lumen.applyTheme()
            })
        }
        refreshThemeChecks()
        val blackSwitch = Switch(this).apply {
            isChecked = prefs.pureBlack
            isClickable = false
            thumbTintList = null
        }
        content.addView(row(getString(R.string.settings_pure_black), getString(R.string.settings_pure_black_summary), blackSwitch) {
            prefs.pureBlack = !prefs.pureBlack
            blackSwitch.isChecked = prefs.pureBlack
            lumen.themeGeneration++
            recreate()
        })

        section(content, R.string.settings_bin)
        val mm = row(getString(R.string.settings_media_management), "", null) {
            MediaActions.openMediaManagementSettings(this)
        }
        mediaManagementSummary = mm.findViewWithTag("summary")
        content.addView(mm)
        content.addView(row(getString(R.string.settings_access), getString(R.string.settings_access_summary), null) {
            MediaActions.openAppSettings(this)
        })

        section(content, R.string.settings_about)
        content.addView(row(getString(R.string.settings_camera), getString(R.string.settings_camera_summary), null, null))
        content.addView(row(getString(R.string.settings_privacy), getString(R.string.settings_privacy_summary), null, null))
        content.addView(row(getString(R.string.settings_source), getString(R.string.settings_source_summary), null) {
            MediaActions.start(this, Intent(Intent.ACTION_VIEW, Uri.parse(SOURCE_URL)))
        })
        content.addView(row(getString(R.string.settings_licenses), getString(R.string.settings_licenses_summary), null) {
            showLicenses()
        })
        content.addView(row(getString(R.string.settings_version, Compat.versionName(this)), null, null, null))

        val scroll = ScrollView(this).apply {
            clipToPadding = false
            addView(content)
        }
        val root = FrameLayout(this).apply {
            setBackgroundColor(palette.surface)
            addView(scroll, FrameLayout.LayoutParams(-1, -1))
        }
        root.onSystemInsets { ins -> scroll.setPadding(ins.left, ins.top, ins.right, ins.bottom + dp(24)) }
        setContentView(root)
    }

    override fun onResume() {
        super.onResume()
        mediaManagementSummary.text = getString(
            if (MediaActions.canSkipPrompts(this)) R.string.settings_media_management_on else R.string.settings_media_management_off,
        )
    }

    private fun refreshThemeChecks() {
        val current = lumen.prefs.theme
        for ((mode, view) in themeChecks) {
            val on = mode == current
            view.setImageResource(if (on) R.drawable.ic_check_circle_fill1 else R.drawable.ic_radio_button_unchecked)
            view.imageTintList = ColorStateList.valueOf(if (on) palette.primary else palette.outline)
        }
    }

    private fun section(parent: LinearLayout, title: Int) {
        parent.addView(Ui.text(this, 14f, palette.primary, Fonts.medium, getString(title)).apply {
            setPadding(dp(24), dp(24), dp(24), dp(8))
        })
    }

    private fun row(title: String, summary: String?, trailing: View?, onClick: (() -> Unit)?): LinearLayout =
        LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = dp(64)
            setPadding(dp(24), dp(12), dp(20), dp(12))
            if (onClick != null) {
                background = Ui.pressable(null, withAlpha(palette.onSurface, 0.1f), 0f)
                setOnClickListener { onClick() }
            }
            val texts = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                addView(Ui.text(context, 17f, palette.onSurface, Fonts.body, title))
                if (summary != null) {
                    addView(Ui.text(context, 14f, palette.onSurfaceVariant, Fonts.body, summary).apply {
                        tag = "summary"
                        setLineSpacing(0f, 1.2f)
                    }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(2) })
                }
            }
            addView(texts, LinearLayout.LayoutParams(0, -2, 1f))
            if (trailing != null) {
                addView(trailing, LinearLayout.LayoutParams(-2, -2).apply { marginStart = dp(16) })
            }
        }

    private fun showLicenses() {
        val text = try {
            assets.open("licenses.txt").bufferedReader().use { it.readText() }
        } catch (e: java.io.IOException) {
            getString(R.string.settings_licenses_summary)
        }
        val body = Ui.text(this, 13f, palette.onSurfaceVariant, Fonts.body, text).apply {
            setPadding(dp(24), dp(8), dp(24), dp(8))
            setTextIsSelectable(true)
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.settings_licenses)
            .setView(ScrollView(this).apply { addView(body) })
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }

    companion object {
        const val SOURCE_URL = "https://github.com/RealKHFan/lumen"
    }
}
