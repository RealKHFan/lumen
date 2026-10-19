package io.github.realkhfan.lumen.core

import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

enum class Grouping { DAY, MONTH, NONE }

/** One row of the photo grid: either a date header or a row of thumbnails. */
sealed class GridRow {
    /** Text for the fast-scroll bubble while this row is on screen. */
    abstract val section: String

    data class Header(
        val label: String,
        override val section: String,
        /** Index of the first item under this header, and how many items it covers. */
        val first: Int,
        val count: Int,
    ) : GridRow()

    data class Cells(val first: Int, val count: Int, override val section: String) : GridRow()
}

/**
 * Turns timestamps into the words shown in the app. Patterns come from the Android
 * layer so they follow the phone's language; tests pass fixed ones.
 */
class DateLabels(
    private val today: String,
    private val yesterday: String,
    locale: Locale,
    val zone: ZoneId,
    dayPattern: String,
    dayYearPattern: String,
    monthPattern: String,
    monthYearPattern: String,
    sectionPattern: String,
) {
    private val day = DateTimeFormatter.ofPattern(dayPattern, locale)
    private val dayYear = DateTimeFormatter.ofPattern(dayYearPattern, locale)
    private val month = DateTimeFormatter.ofPattern(monthPattern, locale)
    private val monthYear = DateTimeFormatter.ofPattern(monthYearPattern, locale)
    private val section = DateTimeFormatter.ofPattern(sectionPattern, locale)

    fun date(timeMs: Long): LocalDate = Instant.ofEpochMilli(timeMs).atZone(zone).toLocalDate()

    fun dayLabel(date: LocalDate, now: LocalDate): String = when (date) {
        now -> today
        now.minusDays(1) -> yesterday
        else -> (if (date.year == now.year) day else dayYear).format(date)
    }

    fun monthLabel(date: LocalDate, now: LocalDate): String =
        (if (date.year == now.year) month else monthYear).format(date)

    fun sectionLabel(date: LocalDate): String = section.format(date)
}

object Timeline {
    /**
     * Builds grid rows for [items], which must be sorted newest first.
     * Each group (a day or a month) starts with a header, then rows of up to [columns] cells.
     */
    fun rows(
        items: List<MediaItem>,
        columns: Int,
        grouping: Grouping,
        labels: DateLabels,
        now: LocalDate,
    ): List<GridRow> {
        require(columns > 0) { "columns must be positive" }
        val rows = ArrayList<GridRow>(items.size / columns + 16)
        if (items.isEmpty()) return rows

        var start = 0
        while (start < items.size) {
            val firstDate = labels.date(items[start].timeMs)
            var end = start + 1
            if (grouping == Grouping.NONE) {
                end = items.size
            } else {
                val key: Any = if (grouping == Grouping.DAY) firstDate else YearMonth.from(firstDate)
                while (end < items.size) {
                    val d = labels.date(items[end].timeMs)
                    val k: Any = if (grouping == Grouping.DAY) d else YearMonth.from(d)
                    if (k != key) break
                    end++
                }
            }
            val count = end - start
            val section = labels.sectionLabel(firstDate)
            when (grouping) {
                Grouping.DAY -> rows.add(GridRow.Header(labels.dayLabel(firstDate, now), section, start, count))
                Grouping.MONTH -> rows.add(GridRow.Header(labels.monthLabel(firstDate, now), section, start, count))
                Grouping.NONE -> Unit
            }
            var cell = start
            while (cell < end) {
                val n = minOf(columns, end - cell)
                val rowSection = if (grouping == Grouping.NONE) labels.sectionLabel(labels.date(items[cell].timeMs)) else section
                rows.add(GridRow.Cells(cell, n, rowSection))
                cell += n
            }
            start = end
        }
        return rows
    }

    /** Index of the row that shows item [itemIndex], or -1. Rows must come from [rows]. */
    fun rowOf(rows: List<GridRow>, itemIndex: Int): Int {
        // Last row whose first item is at or before itemIndex. A header shares its first index
        // with the cell row right after it, so this always lands on a cell row.
        var lo = 0
        var hi = rows.lastIndex
        var found = -1
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            val first = when (val r = rows[mid]) {
                is GridRow.Cells -> r.first
                is GridRow.Header -> r.first
            }
            if (first <= itemIndex) {
                found = mid
                lo = mid + 1
            } else {
                hi = mid - 1
            }
        }
        if (found < 0) return -1
        val row = rows[found]
        return if (row is GridRow.Cells && itemIndex < row.first + row.count) found else -1
    }
}

/** Pinch-to-zoom steps for the grid. */
object GridZoom {
    const val MIN = 2
    const val MAX = 6
    const val DEFAULT = 4

    /** Small thumbnails are grouped by month, bigger ones by day. */
    fun grouping(columns: Int): Grouping = if (columns >= 5) Grouping.MONTH else Grouping.DAY

    /** Spreading two fingers (scale above 1) makes thumbnails bigger, so fewer columns. */
    fun next(columns: Int, scale: Float): Int = when {
        scale > 1.25f -> (columns - 1).coerceAtLeast(MIN)
        scale < 0.8f -> (columns + 1).coerceAtMost(MAX)
        else -> columns
    }
}
