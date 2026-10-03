package com.lecteur.core.player.queue

import kotlin.random.Random

enum class RepeatMode {
    OFF, ALL, ONE;

    fun next(): RepeatMode = entries[(ordinal + 1) % entries.size]
}

/** [mediaFileId] is set for files of the library: the player then opens them by row instead of re-reading them from disk. */
data class QueueItem(val uri: String, val title: String, val mediaFileId: Long? = null)

/**
 * The files being played one after the other: the rest of a series, the files of a folder.
 *
 * Plain state machine, no player in it: [next] and [previous] only move a cursor over the play order, the caller
 * opens whatever they return. The play order is the given order, or a random one that starts with the current item
 * when shuffling.
 */
class PlaybackQueue(
    items: List<QueueItem>,
    startIndex: Int = 0,
    shuffle: Boolean = false,
    private val random: Random = Random.Default
) {
    init {
        require(items.isNotEmpty()) { "A queue needs at least one item" }
        require(startIndex in items.indices) { "startIndex $startIndex outside ${items.indices}" }
    }

    private val original: List<QueueItem> = items

    /** Indices into [original] in play order. */
    private var order: List<Int> = original.indices.toList()
    private var cursor: Int = startIndex

    var repeat: RepeatMode = RepeatMode.OFF
        private set

    var isShuffled: Boolean = false
        private set

    init {
        if (shuffle) setShuffle(true)
    }

    /** What the queue is ("Saison 2", a folder name), for the queue sheet. Not used by the logic. */
    var label: String? = null

    val size: Int get() = original.size

    val current: QueueItem get() = original[order[cursor]]

    /** 1-based place in the play order, for "3 / 12". */
    val position: Int get() = cursor + 1

    /** The items in play order, current one included. */
    val playOrder: List<QueueItem> get() = order.map { original[it] }

    val currentIndexInPlayOrder: Int get() = cursor

    /**
     * What plays when the current item ends by itself: the same item with repeat-one, the next one, the first one again
     * with repeat-all, nothing at the end of the queue.
     */
    fun peekNextOnEnd(): QueueItem? = when {
        repeat == RepeatMode.ONE -> current
        cursor + 1 < order.size -> original[order[cursor + 1]]
        repeat == RepeatMode.ALL -> original[order[0]]
        else -> null
    }

    /** What the "next" button leads to (repeat-one does not apply to a deliberate skip). */
    fun peekNext(): QueueItem? = when {
        cursor + 1 < order.size -> original[order[cursor + 1]]
        repeat == RepeatMode.ALL && order.size > 1 -> original[order[0]]
        else -> null
    }

    fun hasNext(): Boolean = peekNext() != null

    fun hasPrevious(): Boolean = cursor > 0 || (repeat == RepeatMode.ALL && order.size > 1)

    /** The item ended by itself. Moves on according to [repeat] and returns what to play, null at the end. */
    fun advanceOnEnd(): QueueItem? {
        if (repeat == RepeatMode.ONE) return current
        return next()
    }

    /** Deliberate skip forward. */
    fun next(): QueueItem? {
        cursor = when {
            cursor + 1 < order.size -> cursor + 1
            repeat == RepeatMode.ALL && order.size > 1 -> 0
            else -> return null
        }
        return current
    }

    fun previous(): QueueItem? {
        cursor = when {
            cursor > 0 -> cursor - 1
            repeat == RepeatMode.ALL && order.size > 1 -> order.size - 1
            else -> return null
        }
        return current
    }

    /** Plays [indexInPlayOrder] next (the user tapped it in the queue sheet). */
    fun jumpTo(indexInPlayOrder: Int): QueueItem? {
        if (indexInPlayOrder !in order.indices) return null
        cursor = indexInPlayOrder
        return current
    }

    fun cycleRepeat() {
        repeat = repeat.next()
    }

    fun setRepeat(mode: RepeatMode) {
        repeat = mode
    }

    /** Shuffling keeps the current item playing and reorders what follows; unshuffling restores the original order around it. */
    fun setShuffle(on: Boolean) {
        if (on == isShuffled) return
        val currentOriginal = order[cursor]
        if (on) {
            order = listOf(currentOriginal) + original.indices.filter { it != currentOriginal }.shuffled(random)
            cursor = 0
        } else {
            order = original.indices.toList()
            cursor = currentOriginal
        }
        isShuffled = on
    }
}
