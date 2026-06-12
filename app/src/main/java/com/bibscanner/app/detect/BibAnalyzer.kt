package com.bibscanner.app.detect

import androidx.camera.core.ExperimentalGetImage
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import com.bibscanner.app.data.AppSettings
import com.bibscanner.app.util.ImageUtils

/**
 * Live-camera analyzer. Converts each CameraX frame to an upright bitmap and
 * hands it to the shared [BibRecognizer]. Runs on a single-thread background
 * executor, so the synchronous [BibRecognizer.processFrame] both works (ML Kit
 * tasks are awaited off the main thread) and provides back-pressure — the next
 * frame waits until this one is fully processed.
 */
class BibAnalyzer(
    settings: AppSettings,
    sessionStartMs: Long,
    isFrontCamera: Boolean,
    private val onStatus: (String) -> Unit,
    onDetections: (DetectionFrame) -> Unit,
    onConfirmed: (BibRecognizer.Confirmed) -> Unit,
) : ImageAnalysis.Analyzer {

    private val core = BibRecognizer(
        settings = settings,
        timeOriginMs = sessionStartMs,
        isFrontCamera = isFrontCamera,
        onStatus = onStatus,
        onDetections = onDetections,
        onConfirmed = onConfirmed,
    )

    @ExperimentalGetImage
    override fun analyze(imageProxy: ImageProxy) {
        val mediaImage = imageProxy.image
        if (mediaImage == null) {
            imageProxy.close()
            return
        }
        val rotation = imageProxy.imageInfo.rotationDegrees
        val bitmap = try {
            ImageUtils.rotateBitmap(imageProxy.toBitmap(), rotation)
        } catch (e: Exception) {
            null
        } finally {
            imageProxy.close()
        }
        if (bitmap == null) return

        try {
            core.processFrame(bitmap, System.currentTimeMillis())
        } catch (e: Exception) {
            onStatus("Recognition error: ${e.message}")
        }
    }

    /** Confirm anything still pending; call when the session is stopped. */
    fun flush() = core.flush()

    fun close() = core.close()
}
