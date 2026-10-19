package io.github.realkhfan.lumen.core

import io.github.realkhfan.lumen.core.TestData.item
import io.github.realkhfan.lumen.core.TestData.ms
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FoldersTest {
    private val items = listOf(
        item(10, ms(2026, 10, 19), bucket = 2, bucketName = "Screenshots", path = "Pictures/Screenshots/"),
        item(9, ms(2026, 10, 18), bucket = 1),
        item(8, ms(2026, 10, 17), bucket = 3, bucketName = "Signal", path = "Pictures/Signal/"),
        item(7, ms(2026, 10, 16), bucket = 1),
        item(6, ms(2026, 10, 15), bucket = 2, bucketName = "Screenshots", path = "Pictures/Screenshots/"),
    )

    @Test
    fun cameraFirstThenMostRecent() {
        val folders = Folders.from(items)
        assertEquals(listOf("Camera", "Screenshots", "Signal"), folders.map { it.name })
        assertEquals(listOf(2, 2, 1), folders.map { it.count })
        assertEquals(listOf(9L, 10L, 8L), folders.map { it.cover.id })
    }

    @Test
    fun nameFallsBackToPath() {
        val nameless = listOf(item(1, ms(2026, 1, 1), bucket = 5, bucketName = "", path = "Download/Trips/"))
        assertEquals("Trips", Folders.from(nameless).single().name)
    }

    @Test
    fun cameraBucket() {
        assertEquals(1L, Folders.cameraBucket(items))
        assertEquals(null, Folders.cameraBucket(items.filter { it.bucketId != 1L }))
    }

    @Test
    fun defaultFilterShowsEverything() {
        val filter = FolderFilter.EVERYTHING
        assertTrue(filter.showsEverything)
        assertEquals(items, filter.apply(items))
    }

    @Test
    fun filterKeepsOnlyChosenFolders() {
        val filter = FolderFilter.EVERYTHING.toggle(2).toggle(3)
        assertEquals(listOf(10L, 8L, 6L), filter.apply(items).map { it.id })
        assertFalse(filter.matches(items[1]))
        // Toggling every folder off again means everything.
        assertTrue(filter.toggle(2).toggle(3).showsEverything)
    }

    @Test
    fun filterSurvivesSaving() {
        val filter = FolderFilter(setOf(42L, 7L, -3L))
        assertEquals("-3,7,42", filter.encode())
        assertEquals(filter, FolderFilter.decode(filter.encode()))
        assertEquals(FolderFilter.EVERYTHING, FolderFilter.decode(null))
        assertEquals(FolderFilter.EVERYTHING, FolderFilter.decode(""))
        assertEquals(FolderFilter(setOf(1L)), FolderFilter.decode("1,junk,"))
    }

    @Test
    fun chipLabel() {
        val folders = Folders.from(items)
        val count = { n: Int -> "$n folders" }
        assertEquals("All folders", FolderFilter.EVERYTHING.label(folders, "All folders", count))
        assertEquals("Signal", FolderFilter(setOf(3L)).label(folders, "All folders", count))
        assertEquals("2 folders", FolderFilter(setOf(1L, 3L)).label(folders, "All folders", count))
        assertEquals("1 folders", FolderFilter(setOf(99L)).label(folders, "All folders", count))
    }
}
