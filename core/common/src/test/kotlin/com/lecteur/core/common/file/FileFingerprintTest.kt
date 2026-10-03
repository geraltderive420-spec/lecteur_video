package com.lecteur.core.common.file

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class FileFingerprintTest {

    private fun reader(content: ByteArray, calls: MutableList<Pair<Long, Int>>? = null): (Long, Int) -> ByteArray = { offset, length ->
        calls?.add(offset to length)
        val from = offset.toInt()
        content.copyOfRange(from, minOf(content.size, from + length))
    }

    private fun content(size: Int, seed: Int = 1) = ByteArray(size) { ((it * 31 + seed) % 251).toByte() }

    @Test
    fun sameContentGivesSameFingerprint() {
        val data = content(500_000)
        assertThat(FileFingerprint.compute(data.size.toLong(), reader(data)))
            .isEqualTo(FileFingerprint.compute(data.size.toLong(), reader(data.copyOf())))
    }

    @Test
    fun differentHeadOrTailChangesTheFingerprint() {
        val data = content(500_000)
        val base = FileFingerprint.compute(data.size.toLong(), reader(data))

        val headChanged = data.copyOf().also { it[10] = (it[10] + 1).toByte() }
        val tailChanged = data.copyOf().also { it[data.size - 10] = (it[data.size - 10] + 1).toByte() }
        assertThat(FileFingerprint.compute(data.size.toLong(), reader(headChanged))).isNotEqualTo(base)
        assertThat(FileFingerprint.compute(data.size.toLong(), reader(tailChanged))).isNotEqualTo(base)
    }

    @Test
    fun sizeIsPartOfTheFingerprint() {
        val a = ByteArray(100)
        val b = ByteArray(101)
        assertThat(FileFingerprint.compute(100, reader(a))).isNotEqualTo(FileFingerprint.compute(101, reader(b)))
    }

    @Test
    fun middleOfALargeFileIsNotRead() {
        val data = content(1_000_000)
        val base = FileFingerprint.compute(data.size.toLong(), reader(data))
        val middleChanged = data.copyOf().also { it[500_000] = (it[500_000] + 1).toByte() }
        assertThat(FileFingerprint.compute(data.size.toLong(), reader(middleChanged))).isEqualTo(base)
    }

    @Test
    fun readsHeadAndTailWithoutOverlap() {
        val calls = mutableListOf<Pair<Long, Int>>()
        val data = content(100_000)
        FileFingerprint.compute(data.size.toLong(), reader(data, calls))
        assertThat(calls).containsExactly(0L to 65_536, 65_536L to (100_000 - 65_536)).inOrder()
    }

    @Test
    fun smallAndEmptyFilesWork() {
        val small = content(1_000)
        val calls = mutableListOf<Pair<Long, Int>>()
        assertThat(FileFingerprint.compute(1_000, reader(small, calls))).startsWith("v1-")
        assertThat(calls).containsExactly(0L to 1_000)
        assertThat(FileFingerprint.compute(0) { _, _ -> error("must not read") }).startsWith("v1-")
    }

    @Test
    fun locationFallbackIsStablePerLocation() {
        assertThat(FileFingerprint.fromLocation("content://a", 10)).isEqualTo(FileFingerprint.fromLocation("content://a", 10))
        assertThat(FileFingerprint.fromLocation("content://a", 10)).isNotEqualTo(FileFingerprint.fromLocation("content://b", 10))
    }
}
