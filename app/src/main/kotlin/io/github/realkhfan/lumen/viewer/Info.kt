package io.github.realkhfan.lumen.viewer

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.ExifInterface
import android.net.Uri
import android.provider.MediaStore
import android.text.format.DateFormat
import android.view.Gravity
import android.view.View
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import io.github.realkhfan.lumen.MediaActions
import io.github.realkhfan.lumen.R
import io.github.realkhfan.lumen.core.Format
import io.github.realkhfan.lumen.data.MediaRepo
import io.github.realkhfan.lumen.ui.Fonts
import io.github.realkhfan.lumen.ui.Palette
import io.github.realkhfan.lumen.ui.Ui
import io.github.realkhfan.lumen.ui.dp
import io.github.realkhfan.lumen.ui.dpf
import java.io.IOException
import java.util.Date
import java.util.Locale

/** The "Details" panel: when, which file, which camera settings, and where (if allowed). */
object Info {
    class Details(
        val camera: String?,
        val aperture: Double,
        val exposure: Double,
        val iso: Int,
        val focalLength: Double,
        val latLong: DoubleArray?,
        /** True when Lumen may not read locations yet, so "Show location" is offered. */
        val locationHidden: Boolean,
    )

    /** Reads the photo's camera details. Background thread only. */
    fun read(context: Context, entry: Entry): Details {
        val media = MediaRepo.isMediaUri(entry.uri)
        val canLocate = context.checkSelfPermission(Manifest.permission.ACCESS_MEDIA_LOCATION) ==
            PackageManager.PERMISSION_GRANTED
        if (entry.isVideo) return Details(null, 0.0, 0.0, 0, 0.0, null, false)
        val exif = (if (media && canLocate) open(context, requireOriginal(entry.uri)) else null)
            ?: open(context, entry.uri)
        if (exif == null) return Details(null, 0.0, 0.0, 0, 0.0, null, false)
        val make = exif.getAttribute(ExifInterface.TAG_MAKE)?.trim().orEmpty()
        val model = exif.getAttribute(ExifInterface.TAG_MODEL)?.trim().orEmpty()
        val camera = when {
            model.isEmpty() -> make.ifEmpty { null }
            make.isEmpty() || model.startsWith(make, ignoreCase = true) -> model
            else -> "$make $model"
        }
        return Details(
            camera = camera,
            aperture = exif.getAttributeDouble(ExifInterface.TAG_F_NUMBER, 0.0),
            exposure = exif.getAttributeDouble(ExifInterface.TAG_EXPOSURE_TIME, 0.0),
            iso = exif.getAttributeInt(ExifInterface.TAG_ISO_SPEED_RATINGS, 0),
            focalLength = exif.getAttributeDouble(ExifInterface.TAG_FOCAL_LENGTH, 0.0),
            latLong = FloatArray(2).let { if (exif.getLatLong(it)) doubleArrayOf(it[0].toDouble(), it[1].toDouble()) else null },
            locationHidden = media && !canLocate,
        )
    }

    private fun requireOriginal(uri: Uri): Uri = try {
        MediaStore.setRequireOriginal(uri)
    } catch (e: RuntimeException) {
        uri
    }

    private fun open(context: Context, uri: Uri): ExifInterface? = try {
        context.contentResolver.openFileDescriptor(uri, "r")?.use { ExifInterface(it.fileDescriptor) }
    } catch (e: IOException) {
        null
    } catch (e: RuntimeException) {
        null
    }

    fun build(activity: Activity, palette: Palette, entry: Entry, details: Details?, onShowLocation: () -> Unit): View {
        val locale = Locale.getDefault()
        val root = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(activity.dp(24), 0, activity.dp(24), activity.dp(16))
        }
        val item = entry.item

        if (item != null) {
            val date = Date(item.timeMs)
            val day = DateFormat.format(DateFormat.getBestDateTimePattern(locale, "EEEEdMMMMy"), date).toString()
            val time = DateFormat.getTimeFormat(activity).format(date)
            root.addView(Ui.text(activity, 24f, palette.onSurface, Fonts.display, day))
            root.addView(Ui.text(activity, 15f, palette.onSurfaceVariant, Fonts.body, time),
                LinearLayout.LayoutParams(-2, -2).apply { topMargin = activity.dp(2); bottomMargin = activity.dp(12) })
        } else {
            root.addView(Ui.text(activity, 24f, palette.onSurface, Fonts.display, activity.getString(R.string.info_title)).apply {
                setPadding(0, 0, 0, activity.dp(12))
            })
        }

        // File
        val fileChips = ArrayList<String>()
        if (item != null) {
            Format.resolution(item.shownWidth, item.shownHeight)?.let { fileChips.add(it) }
            if (!item.isVideo) Format.megapixels(item.shownWidth, item.shownHeight, locale)?.let { fileChips.add(it) }
            if (item.isVideo && item.durationMs > 0) fileChips.add(Format.duration(item.durationMs))
            if (item.sizeBytes > 0) fileChips.add(Format.size(item.sizeBytes, locale))
        }
        val fileName = item?.displayName?.ifBlank { null } ?: entry.uri.lastPathSegment ?: ""
        root.addView(section(activity, palette, if (entry.isVideo) R.drawable.ic_play_circle_fill1 else R.drawable.ic_photo,
            fileName, item?.relativePath?.trimEnd('/')?.ifBlank { null }, fileChips))

        // Camera
        if (details != null && (details.camera != null || details.aperture > 0 || details.exposure > 0)) {
            val chips = listOfNotNull(
                Format.aperture(details.aperture, locale),
                Format.exposure(details.exposure, locale),
                if (details.iso > 0) "ISO ${details.iso}" else null,
                Format.focalLength(details.focalLength, locale),
            )
            root.addView(section(activity, palette, R.drawable.ic_tune,
                details.camera ?: activity.getString(R.string.info_camera), null, chips))
        }

        // Location
        if (details != null && !entry.isVideo) {
            val ll = details.latLong
            val row = when {
                ll != null && ll.size == 2 -> {
                    val text = String.format(Locale.ROOT, "%.5f, %.5f", ll[0], ll[1])
                    section(activity, palette, R.drawable.ic_location_on, activity.getString(R.string.info_location), text, emptyList(),
                        Ui.textButton(activity, activity.getString(R.string.info_open_map), palette.primary) {
                            val geo = Uri.parse("geo:${ll[0]},${ll[1]}?q=${ll[0]},${ll[1]}")
                            MediaActions.start(activity, Intent(Intent.ACTION_VIEW, geo))
                        })
                }
                details.locationHidden -> section(activity, palette, R.drawable.ic_location_on,
                    activity.getString(R.string.info_location), null, emptyList(),
                    Ui.textButton(activity, activity.getString(R.string.info_show_location), palette.primary) { onShowLocation() })
                else -> section(activity, palette, R.drawable.ic_location_on, activity.getString(R.string.info_location),
                    activity.getString(R.string.info_no_location), emptyList())
            }
            root.addView(row)
        }
        return root
    }

    private fun section(
        context: Context, palette: Palette, icon: Int, title: String, subtitle: String?, chips: List<String>,
        extra: View? = null,
    ): LinearLayout {
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, context.dp(14), 0, context.dp(4))
        }
        row.addView(Ui.icon(context, icon, palette.onSurfaceVariant), LinearLayout.LayoutParams(context.dp(24), context.dp(24)).apply {
            marginEnd = context.dp(16)
        })
        val column = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        column.addView(Ui.text(context, 16f, palette.onSurface, Fonts.body, title).apply { maxLines = 2 })
        if (subtitle != null) {
            column.addView(Ui.text(context, 14f, palette.onSurfaceVariant, Fonts.body, subtitle).apply { setTextIsSelectable(true) },
                LinearLayout.LayoutParams(-1, -2).apply { topMargin = context.dp(2) })
        }
        if (chips.isNotEmpty()) {
            val chipRow = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
            for (c in chips) {
                chipRow.addView(Ui.text(context, 13f, palette.onSurface, Fonts.medium, c).apply {
                    background = Ui.rounded(palette.surfaceContainerHigh, context.dpf(10f))
                    setPadding(context.dp(10), context.dp(6), context.dp(10), context.dp(6))
                }, LinearLayout.LayoutParams(-2, -2).apply { marginEnd = context.dp(8) })
            }
            column.addView(HorizontalScrollView(context).apply {
                isHorizontalScrollBarEnabled = false
                addView(chipRow)
            }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = context.dp(10) })
        }
        if (extra != null) {
            column.addView(extra, LinearLayout.LayoutParams(-2, -2).apply {
                gravity = Gravity.START
                marginStart = -context.dp(14)
            })
        }
        row.addView(column, LinearLayout.LayoutParams(0, -2, 1f))
        return row
    }
}
