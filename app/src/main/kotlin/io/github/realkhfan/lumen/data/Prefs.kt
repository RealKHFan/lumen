package io.github.realkhfan.lumen.data

import android.content.Context
import io.github.realkhfan.lumen.core.FolderFilter
import io.github.realkhfan.lumen.core.GridZoom

/** Small settings that are remembered between launches. Stored only on the phone. */
class Prefs(context: Context) {
    private val sp = context.getSharedPreferences("lumen", Context.MODE_PRIVATE)

    var columns: Int
        get() = sp.getInt(KEY_COLUMNS, GridZoom.DEFAULT).coerceIn(GridZoom.MIN, GridZoom.MAX)
        set(value) = sp.edit().putInt(KEY_COLUMNS, value).apply()

    var filter: FolderFilter
        get() = FolderFilter.decode(sp.getString(KEY_FILTER, null))
        set(value) = sp.edit().putString(KEY_FILTER, value.encode()).apply()

    var theme: Int
        get() = sp.getInt(KEY_THEME, THEME_SYSTEM)
        set(value) = sp.edit().putInt(KEY_THEME, value).apply()

    var pureBlack: Boolean
        get() = sp.getBoolean(KEY_PURE_BLACK, false)
        set(value) = sp.edit().putBoolean(KEY_PURE_BLACK, value).apply()

    var hideBinHint: Boolean
        get() = sp.getBoolean(KEY_HIDE_BIN_HINT, false)
        set(value) = sp.edit().putBoolean(KEY_HIDE_BIN_HINT, value).apply()

    /** Set once the permission prompt was answered "no", so the next tap opens Settings. */
    var askedForAccess: Boolean
        get() = sp.getBoolean(KEY_ASKED, false)
        set(value) = sp.edit().putBoolean(KEY_ASKED, value).apply()

    companion object {
        const val THEME_SYSTEM = 0
        const val THEME_LIGHT = 1
        const val THEME_DARK = 2

        private const val KEY_COLUMNS = "columns"
        private const val KEY_FILTER = "folder_filter"
        private const val KEY_THEME = "theme"
        private const val KEY_PURE_BLACK = "pure_black"
        private const val KEY_HIDE_BIN_HINT = "hide_bin_hint"
        private const val KEY_ASKED = "asked_for_access"
    }
}
