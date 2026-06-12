package com.bibscanner.app.ui

import android.graphics.Bitmap
import android.graphics.RectF
import android.net.Uri
import androidx.camera.video.Recording
import androidx.lifecycle.ViewModel
import com.bibscanner.app.data.BibResult
import com.bibscanner.app.detect.DetectionFrame
import com.bibscanner.app.util.ImageUtils
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update

/** A frame thumbnail plus its detected boxes, shown live while a video runs. */
data class VideoPreview(val bitmap: Bitmap, val frame: DetectionFrame)

/** Holds scanning state and the running list of confirmed results. */
class ScannerViewModel : ViewModel() {

    val isRunning = MutableStateFlow(false)
    val status = MutableStateFlow("Idle")
    val results = MutableStateFlow<List<BibResult>>(emptyList())

    /** Latest detected boxes for the live preview overlay (null = none). */
    val overlay = MutableStateFlow<DetectionFrame?>(null)

    // --- Recorded-video processing ---
    /** Uri picked on the scanner screen, consumed by the video screen. */
    var pendingVideoUri: Uri? = null

    /** Region of interest for video processing, as fractions (0..1) of the frame.
     *  Full frame by default; set on the video screen's ROI selector. */
    var videoRoi: RectF = RectF(0f, 0f, 1f, 1f)
    val videoProcessing = MutableStateFlow(false)
    val videoProgress = MutableStateFlow(0f)     // 0f..1f
    val videoStatus = MutableStateFlow("")
    /** Current frame + boxes while processing a video (null = none). */
    val videoPreview = MutableStateFlow<VideoPreview?>(null)

    /** Wall-clock origin for elapsed-time calculations (set on start). */
    var sessionStartMs: Long = 0L

    /** Active backup recording, kept so it can be stopped/finalised. */
    var currentRecording: Recording? = null

    fun setStatus(s: String) {
        status.value = s
    }

    fun addResult(result: BibResult) {
        results.update { list ->
            // "nonumber" entries are distinct people, so never de-duped.
            // Numbered bibs de-dupe defensively (the tracker already prevents repeats).
            if (!result.isNoNumber && list.any { it.bib == result.bib }) list else list + result
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

    // --- One-anchor time calibration ---
    // Each result keeps its raw detected time; this single offset (computed from
    // one bib's known absolute time) is added everywhere times are shown/exported.
    val calibrationOffset = MutableStateFlow(0.0)
    val calibrationLabel = MutableStateFlow<String?>(null)

    /**
     * Anchor on [bib]: set the global offset so this bib reads [absoluteSeconds].
     * Always relative to the raw detected time, so re-applying never compounds.
     * Returns false if the bib isn't among the results.
     */
    fun calibrate(bib: String, absoluteSeconds: Double): Boolean {
        val r = results.value.firstOrNull { it.bib == bib } ?: return false
        val offset = absoluteSeconds - r.elapsedSeconds
        calibrationOffset.value = offset
        val sign = if (offset >= 0) "+" else "-"
        calibrationLabel.value =
            "Bib $bib = ${ImageUtils.formatHms(absoluteSeconds)}  (offset $sign${ImageUtils.formatHms(kotlin.math.abs(offset))})"
        return true
    }

    fun clearCalibration() {
        calibrationOffset.value = 0.0
        calibrationLabel.value = null
    }
}
