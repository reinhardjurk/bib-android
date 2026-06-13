package com.bibscanner.app.detect

/**
 * Consecutive-detection logic, ported from the Python pipeline.
 *
 * The Python script tracks each runner by a YOLO track-id and, once that track
 * disappears, picks the number it saw most consistently. On Android we key by
 * the recognised number string instead: each candidate number accumulates
 * "sightings"; once a number has been seen at least [minConsecutive] times and
 * is then unseen for longer than [patienceMillis], it is confirmed exactly once
 * (and never re-emitted, mirroring `globally_completed_bibs`).
 *
 * Pure logic, no Android dependencies, so it can be unit-tested.
 */
class BibTracker(
    private val minConsecutive: Int,
    private val patienceMillis: Long,
    private val minDigits: Int,
    private val maxDigits: Int,
    private val confirmOnThreshold: Boolean,
    private val onConfirmed: (number: String, firstSeenMs: Long) -> Unit,
) {
    private class Track(var count: Int, val firstSeenMs: Long, var lastSeenMs: Long)

    private val active = HashMap<String, Track>()
    private val completed = HashSet<String>()

    /** Feed the numbers recognised in one frame at wall-clock time [nowMs]. */
    fun onFrame(nowMs: Long, numbers: List<String>) {
        for (raw in numbers) {
            if (raw.length < minDigits || raw.length > maxDigits) continue
            if (raw in completed) continue
            val t = active[raw]
            if (t == null) {
                active[raw] = Track(count = 1, firstSeenMs = nowMs, lastSeenMs = nowMs)
            } else {
                t.count++
                t.lastSeenMs = nowMs
            }
            // Confirm immediately once the threshold is reached, rather than
            // waiting for the number to leave the frame.
            if (confirmOnThreshold) {
                val tk = active[raw]!!
                if (tk.count >= minConsecutive) {
                    completed.add(raw)
                    onConfirmed(raw, tk.firstSeenMs)
                    active.remove(raw)
                }
            }
        }
        prune(nowMs)
    }

    /** Called periodically (and on stop) to finalise tracks that have left view. */
    fun prune(nowMs: Long) {
        val it = active.entries.iterator()
        while (it.hasNext()) {
            val (number, t) = it.next()
            if (nowMs - t.lastSeenMs > patienceMillis) {
                if (t.count >= minConsecutive) {
                    completed.add(number)
                    onConfirmed(number, t.firstSeenMs)
                }
                it.remove()
            }
        }
    }

    /** Force-confirm everything still pending (e.g. when the session stops). */
    fun flush() {
        for ((number, t) in active) {
            if (t.count >= minConsecutive && number !in completed) {
                completed.add(number)
                onConfirmed(number, t.firstSeenMs)
            }
        }
        active.clear()
    }

    fun activeCount(): Int = active.size
    fun completedCount(): Int = completed.size
}
