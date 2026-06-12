package com.bibscanner.app.detect

/** One person observation in a frame: a tracking id and whether a bib number
 *  was read inside that person's box this frame. */
data class PersonObs(val trackId: Int, val hasNumber: Boolean)

/**
 * Tracks detected people by their object-detection tracking id. A person who is
 * seen for at least [minFrames] frames and then leaves view *without ever*
 * carrying a readable number is reported once via [onNoNumber] — this is the
 * "person but no number" case. People who did carry a number are handled by the
 * number-keyed [BibTracker] instead, so they are not double-counted here.
 */
class PersonTracker(
    private val minFrames: Int,
    private val patienceMillis: Long,
    private val onNoNumber: (trackId: Int, firstSeenMs: Long) -> Unit,
) {
    private class Track(val firstSeenMs: Long, var lastSeenMs: Long, var frames: Int, var hadNumber: Boolean)

    private val active = HashMap<Int, Track>()
    private val reported = HashSet<Int>()

    fun onFrame(nowMs: Long, observations: List<PersonObs>) {
        for (o in observations) {
            val t = active[o.trackId]
            if (t == null) {
                active[o.trackId] = Track(nowMs, nowMs, 1, o.hasNumber)
            } else {
                t.lastSeenMs = nowMs
                t.frames++
                if (o.hasNumber) t.hadNumber = true
            }
        }
        prune(nowMs)
    }

    fun prune(nowMs: Long) {
        val it = active.entries.iterator()
        while (it.hasNext()) {
            val (id, t) = it.next()
            if (nowMs - t.lastSeenMs > patienceMillis) {
                if (!t.hadNumber && t.frames >= minFrames && id !in reported) {
                    reported.add(id)
                    onNoNumber(id, t.firstSeenMs)
                }
                it.remove()
            }
        }
    }

    fun flush() {
        for ((id, t) in active) {
            if (!t.hadNumber && t.frames >= minFrames && id !in reported) {
                reported.add(id)
                onNoNumber(id, t.firstSeenMs)
            }
        }
        active.clear()
    }
}
