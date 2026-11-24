package io.keyflow.remotekeyboard.keyboard

/** Bounded per-connection queue. A focus transition erases pending input before publishing the next token. */
internal class InputMailbox {
    private val pending = ArrayDeque<List<KeyboardInput>>()
    private var focus: FocusToken? = null
    private var revision = 0L
    private var scheduled = false
    private var closed = false

    enum class Result { STALE, QUEUED, SCHEDULE, OVERFLOW }

    @Synchronized
    fun setFocus(next: FocusToken?) {
        if (focus == next) return
        focus = next
        revision++
        pending.clear()
    }

    @Synchronized
    fun offer(target: FocusToken, batch: List<KeyboardInput>): Result {
        if (closed || focus != target || batch.isEmpty() || batch.size > 10) return Result.STALE
        if (pending.size >= 8) {
            close()
            return Result.OVERFLOW
        }
        pending.addLast(batch.toList())
        return schedule()
    }

    @Synchronized
    fun release(target: FocusToken): Result {
        if (closed || focus != target) return Result.STALE
        pending.clear()
        revision++
        pending.addLast(listOf(KeyboardInput.Release))
        return schedule()
    }

    @Synchronized
    fun next(): Triple<FocusToken, List<KeyboardInput>, Long>? {
        val current = focus
        if (!closed && current != null && pending.isNotEmpty()) return Triple(current, pending.removeFirst(), revision)
        scheduled = false
        return null
    }

    @Synchronized
    fun accepts(target: FocusToken, stamp: Long): Boolean = !closed && focus == target && revision == stamp

    @Synchronized
    fun close() {
        closed = true
        focus = null
        pending.clear()
    }

    private fun schedule(): Result {
        if (scheduled) return Result.QUEUED
        scheduled = true
        return Result.SCHEDULE
    }
}
