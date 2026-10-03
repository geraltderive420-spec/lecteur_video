package com.lecteur.core.common.scan

/** What the database already knows about a file. */
data class KnownFile(
    val id: Long,
    val uri: String,
    val fingerprint: String,
    val size: Long,
    val lastModified: Long,
    val isAvailable: Boolean,
    /** False for a row left without movie/episode (an interrupted scan): it is processed again. */
    val isLinked: Boolean = true
)

/** A video file seen during the walk of a folder. */
data class FoundFile(
    val uri: String,
    val name: String,
    val size: Long,
    val lastModified: Long,
    /** Path from the scanned root, file name included, '/' separated: the folder hints used by the filename parser. */
    val relativePath: String
) {
    val parentName: String? get() = relativePath.split('/').dropLast(1).lastOrNull()
    val grandparentName: String? get() = relativePath.split('/').dropLast(2).lastOrNull()
}

data class PlannedFile(val found: FoundFile, val known: KnownFile?)

data class ScanPlan(
    /** Same location, size and date as last time: left alone (only flipped back to available if needed). */
    val unchanged: List<PlannedFile>,
    /** New or modified files, which need a fingerprint and an inspection. */
    val toProcess: List<PlannedFile>,
    /** Known files that were not found: either moved (see [MoveResolver]) or gone. */
    val missing: List<KnownFile>
)

object ScanPlanner {

    /** @param force re-inspect every file, changed or not (the "rescan" button). */
    fun plan(known: List<KnownFile>, found: List<FoundFile>, force: Boolean = false): ScanPlan {
        val knownByUri = known.associateBy { it.uri }
        val foundUris = HashSet<String>(found.size * 2)

        val unchanged = ArrayList<PlannedFile>()
        val toProcess = ArrayList<PlannedFile>()

        for (file in found) {
            foundUris += file.uri
            val previous = knownByUri[file.uri]
            when {
                previous == null || force || !previous.isLinked || isModified(previous, file) -> toProcess += PlannedFile(file, previous)
                else -> unchanged += PlannedFile(file, previous)
            }
        }

        return ScanPlan(unchanged, toProcess, known.filter { it.uri !in foundUris })
    }

    /** Providers that cannot report a modification date return 0: size alone then decides. */
    private fun isModified(known: KnownFile, found: FoundFile): Boolean =
        known.size != found.size || (known.lastModified != 0L && found.lastModified != 0L && known.lastModified != found.lastModified)
}

/**
 * Recognises a moved or renamed file among the files that disappeared: same content fingerprint.
 * Each missing file can be claimed once, so two identical copies cannot both steal the same history.
 */
class MoveResolver(missing: List<KnownFile>) {
    private val byFingerprint: MutableMap<String, ArrayDeque<KnownFile>> =
        missing.groupByTo(HashMap()) { it.fingerprint }.mapValuesTo(HashMap()) { ArrayDeque(it.value) }

    @Synchronized
    fun claim(fingerprint: String): KnownFile? {
        val queue = byFingerprint[fingerprint] ?: return null
        return queue.removeFirstOrNull().also { if (queue.isEmpty()) byFingerprint.remove(fingerprint) }
    }

    /** Files nobody claimed: really gone (or on an unplugged drive). */
    @Synchronized
    fun unclaimed(): List<KnownFile> = byFingerprint.values.flatten()
}
