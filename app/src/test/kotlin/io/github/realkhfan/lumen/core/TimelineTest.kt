package io.github.realkhfan.lumen.core

import io.github.realkhfan.lumen.core.TestData.item
import io.github.realkhfan.lumen.core.TestData.ms
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class TimelineTest {
    private val now = LocalDate.of(2026, 10, 19)
    private val labels = TestData.labels()

    private fun sample(): List<MediaItem> = listOf(
        item(1, ms(2026, 10, 19, 18, 32)),
        item(2, ms(2026, 10, 19, 9, 0)),
        item(3, ms(2026, 10, 18, 20, 0)),
        item(4, ms(2026, 10, 17, 10, 0)),
        item(5, ms(2026, 10, 17, 9, 0)),
        item(6, ms(2026, 10, 17, 8, 0)),
        item(7, ms(2026, 10, 17, 7, 0)),
        item(8, ms(2026, 10, 17, 6, 0)),
        item(9, ms(2025, 12, 24, 18, 0)),
    ).sortedWith(MediaOrder.NEWEST_FIRST)

    @Test
    fun groupsByDayWithFriendlyLabels() {
        val rows = Timeline.rows(sample(), 4, Grouping.DAY, labels, now)
        val headers = rows.filterIsInstance<GridRow.Header>()
        assertEquals(listOf("Today", "Yesterday", "Sat 17 Oct", "Wed 24 Dec 2025"), headers.map { it.label })
        assertEquals(listOf(2, 1, 5, 1), headers.map { it.count })
        assertEquals(listOf(0, 2, 3, 8), headers.map { it.first })
    }

    @Test
    fun splitsLongDaysIntoRowsOfColumnWidth() {
        val rows = Timeline.rows(sample(), 4, Grouping.DAY, labels, now)
        // Sat 17 Oct has 5 items: one row of 4, one row of 1.
        val satHeader = rows.indexOfFirst { it is GridRow.Header && it.label == "Sat 17 Oct" }
        val r1 = rows[satHeader + 1] as GridRow.Cells
        val r2 = rows[satHeader + 2] as GridRow.Cells
        assertEquals(3 to 4, r1.first to r1.count)
        assertEquals(7 to 1, r2.first to r2.count)
        // Every item appears in exactly one cell row.
        val covered = rows.filterIsInstance<GridRow.Cells>().flatMap { (it.first until it.first + it.count).toList() }
        assertEquals((0 until 9).toList(), covered)
    }

    @Test
    fun groupsByMonthWhenZoomedOut() {
        val rows = Timeline.rows(sample(), 6, Grouping.MONTH, labels, now)
        val headers = rows.filterIsInstance<GridRow.Header>()
        assertEquals(listOf("October", "December 2025"), headers.map { it.label })
        assertEquals(listOf(8, 1), headers.map { it.count })
    }

    @Test
    fun noGroupingForBin() {
        val rows = Timeline.rows(sample(), 3, Grouping.NONE, labels, now)
        assertTrue(rows.none { it is GridRow.Header })
        assertEquals(3, rows.size)
        assertEquals("Oct 2026", rows.last().section)
    }

    @Test
    fun sectionLabelForFastScroll() {
        val rows = Timeline.rows(sample(), 4, Grouping.DAY, labels, now)
        assertEquals("Oct 2026", rows.first().section)
        assertEquals("Dec 2025", rows.last().section)
    }

    @Test
    fun rowOfFindsTheCellRowForEveryItem() {
        val items = sample()
        for (columns in GridZoom.MIN..GridZoom.MAX) {
            val rows = Timeline.rows(items, columns, GridZoom.grouping(columns), labels, now)
            for (i in items.indices) {
                val r = Timeline.rowOf(rows, i)
                val row = rows[r] as GridRow.Cells
                assertTrue("item $i at $columns columns", i >= row.first && i < row.first + row.count)
            }
            assertEquals(-1, Timeline.rowOf(rows, items.size))
        }
    }

    @Test
    fun emptyListGivesNoRows() {
        assertTrue(Timeline.rows(emptyList(), 4, Grouping.DAY, labels, now).isEmpty())
        assertEquals(-1, Timeline.rowOf(emptyList(), 0))
    }

    @Test
    fun newestFirstOrderIsStableForEqualTimes() {
        val t = ms(2026, 1, 1)
        val sorted = listOf(item(1, t), item(3, t), item(2, t)).sortedWith(MediaOrder.NEWEST_FIRST)
        assertEquals(listOf(3L, 2L, 1L), sorted.map { it.id })
    }

    @Test
    fun timeFallsBackWhenDateTakenMissing() {
        val noTaken = item(1, 0).copy(dateModifiedSec = 1_700_000_000, dateAddedSec = 1_600_000_000)
        assertEquals(1_700_000_000_000, noTaken.timeMs)
        val onlyAdded = noTaken.copy(dateModifiedSec = 0)
        assertEquals(1_600_000_000_000, onlyAdded.timeMs)
    }

    @Test
    fun pinchSteps() {
        assertEquals(3, GridZoom.next(4, 1.4f))
        assertEquals(5, GridZoom.next(4, 0.7f))
        assertEquals(4, GridZoom.next(4, 1.1f))
        assertEquals(GridZoom.MIN, GridZoom.next(GridZoom.MIN, 2f))
        assertEquals(GridZoom.MAX, GridZoom.next(GridZoom.MAX, 0.3f))
        assertEquals(Grouping.DAY, GridZoom.grouping(4))
        assertEquals(Grouping.MONTH, GridZoom.grouping(5))
    }
}
