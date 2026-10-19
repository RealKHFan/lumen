package io.github.realkhfan.lumen.core

/** A folder on the phone that contains photos or videos (Android calls it a bucket). */
data class Folder(
    val bucketId: Long,
    val name: String,
    val relativePath: String,
    val count: Int,
    /** Newest item, used as the cover. */
    val cover: MediaItem,
)

object Folders {
    /**
     * Groups [items] (newest first) into folders. The camera folder comes first,
     * the rest are ordered by their newest photo, like a recent-activity list.
     */
    fun from(items: List<MediaItem>): List<Folder> {
        val covers = LinkedHashMap<Long, MediaItem>()
        val counts = HashMap<Long, Int>()
        for (item in items) {
            if (item.bucketId !in covers) covers[item.bucketId] = item
            counts[item.bucketId] = (counts[item.bucketId] ?: 0) + 1
        }
        val folders = covers.values.map { cover ->
            Folder(
                bucketId = cover.bucketId,
                name = cover.bucketName.ifBlank { cover.relativePath.trimEnd('/').substringAfterLast('/') },
                relativePath = cover.relativePath,
                count = counts[cover.bucketId] ?: 0,
                cover = cover,
            )
        }
        return folders.sortedWith(Comparator { a, b ->
            val camA = a.cover.isCameraFolder
            val camB = b.cover.isCameraFolder
            when {
                camA != camB -> if (camA) -1 else 1
                a.cover.timeMs != b.cover.timeMs -> b.cover.timeMs.compareTo(a.cover.timeMs)
                else -> a.name.compareTo(b.name, ignoreCase = true)
            }
        })
    }

    /** The folder the camera saves to, if there is one. */
    fun cameraBucket(items: List<MediaItem>): Long? = items.firstOrNull { it.isCameraFolder }?.bucketId
}

/**
 * Which folders the Photos tab shows. An empty set means everything, which is the default.
 */
data class FolderFilter(val bucketIds: Set<Long> = emptySet()) {
    val showsEverything: Boolean get() = bucketIds.isEmpty()

    fun matches(item: MediaItem): Boolean = showsEverything || item.bucketId in bucketIds

    fun apply(items: List<MediaItem>): List<MediaItem> =
        if (showsEverything) items else items.filter { it.bucketId in bucketIds }

    fun toggle(bucketId: Long): FolderFilter =
        FolderFilter(if (bucketId in bucketIds) bucketIds - bucketId else bucketIds + bucketId)

    /** Short text for the chip in the top bar. */
    fun label(folders: List<Folder>, everything: String, count: (Int) -> String): String {
        if (showsEverything) return everything
        if (bucketIds.size == 1) {
            val only = folders.firstOrNull { it.bucketId in bucketIds }
            return only?.name ?: count(1)
        }
        return count(bucketIds.size)
    }

    fun encode(): String = bucketIds.sorted().joinToString(",")

    companion object {
        val EVERYTHING = FolderFilter()

        fun decode(text: String?): FolderFilter {
            if (text.isNullOrBlank()) return EVERYTHING
            return FolderFilter(text.split(',').mapNotNull { it.trim().toLongOrNull() }.toSet())
        }
    }
}
