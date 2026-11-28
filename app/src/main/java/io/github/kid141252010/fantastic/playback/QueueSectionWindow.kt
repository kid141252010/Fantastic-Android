package io.github.kid141252010.fantastic.playback

/** Contiguous upcoming queue items that belong to the user-inserted queue section. */
internal data class QueueSectionWindow(
    var startIndex: Int = -1,
    var endIndex: Int = -1,
) {
    fun contains(index: Int): Boolean = !isEmpty() && index in startIndex..endIndex

    fun isEmpty(): Boolean = startIndex == -1 && endIndex == -1

    fun onItemsAdded(insertionIndex: Int, count: Int) {
        if (count <= 0) return
        if (isEmpty()) {
            startIndex = insertionIndex
            endIndex = insertionIndex + count - 1
        } else {
            endIndex += count
        }
        resetIfInvalid()
    }

    fun onItemRemoved(index: Int) {
        if (contains(index)) {
            endIndex--
            resetIfInvalid()
        }
        if (index < startIndex) {
            startIndex--
            endIndex--
            resetIfInvalid()
        }
    }

    fun onItemMoved(fromIndex: Int, toIndex: Int) {
        val sourceInSection = contains(fromIndex)
        val targetInSection = contains(toIndex)
        when {
            sourceInSection && !targetInSection -> onItemRemoved(fromIndex)
            !sourceInSection && targetInSection -> onItemsAdded(toIndex, 1)
        }
    }

    fun onPlayingIndexChanged(previousIndex: Int, newIndex: Int): Boolean {
        val wasEmpty = isEmpty()
        if (wasEmpty && previousIndex != newIndex + 1) return false

        startIndex = newIndex + 1
        if (wasEmpty && previousIndex == startIndex) endIndex = previousIndex
        resetIfInvalid()
        return true
    }

    fun reset() {
        startIndex = -1
        endIndex = -1
    }

    private fun resetIfInvalid() {
        if ((startIndex == -1) != (endIndex == -1) || (startIndex != -1 && endIndex < startIndex)) reset()
    }
}
