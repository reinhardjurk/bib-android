package com.bibscanner.app.ui

import androidx.camera.video.Recording
import androidx.lifecycle.ViewModel
import com.bibscanner.app.data.BibResult
import com.bibscanner.app.detect.DetectionFrame
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update

/** Holds scanning state and the running list of confirmed results. */
class ScannerViewModel : ViewModel() {

    val isRunning = MutableStateFlow(false)
    val status = MutableStateFlow("Idle")
    val results = MutableStateFlow<List<BibResult>>(emptyList())

    /** Latest detected boxes for the live preview overlay (null = none). */
    val overlay = MutableStateFlow<DetectionFrame?>(null)

    /** Wall-clock origin for elapsed-time calculations (set on start). */
    var sessionStartMs: Long = 0L

    /** Active backup recording, kept so it can be stopped/finalised. */
    var currentRecording: Recording? = null

    fun setStatus(s: String) {
        status.value = s
    }

    fun addResult(result: BibResult) {
        // De-dupe defensively; the tracker already prevents repeats.
        results.update { list ->
            if (list.any { it.bib == result.bib }) list else list + result
        }
    }

    fun toggleRunning(): Boolean {
        val next = !isRunning.value
        isRunning.value = next
        if (next) {
            status.value = "Starting…"
        } else {
            status.value = "Stopped"
        }
        return next
    }

    fun clearResults() {
        results.value = emptyList()
    }
}
