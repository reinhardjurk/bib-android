package com.bibscanner.app.detect

import android.graphics.Bitmap
import android.graphics.Rect
import androidx.camera.core.ExperimentalGetImage
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import com.bibscanner.app.data.AppSettings
import com.bibscanner.app.util.ImageUtils
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions

/**
 * CameraX analyzer that runs ML Kit text recognition on each frame, extracts
 * digit groups (the bib numbers), keeps a close-up crop of each candidate, and
 * feeds the numbers into [BibTracker]. Mirrors the YOLO+EasyOCR loop of the
 * Python script, with ML Kit replacing both models.
 */
class BibAnalyzer(
    private val settings: AppSettings,
    private val sessionStartMs: Long,
    private val isFrontCamera: Boolean,
    private val onStatus: (String) -> Unit,
    private val onDetections: (DetectionFrame) -> Unit,
    private val onConfirmed: (Confirmed) -> Unit,
) : ImageAnalysis.Analyzer {

    data class Confirmed(
        val bib: String,
        val elapsedSeconds: Double,
        val timeText: String,
        val crop: Bitmap?,
    )

    private val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    private val digitRegex = Regex("\\d+")

    // Latest close-up crop per still-pending number, popped when confirmed.
    private val pendingCrops = HashMap<String, Bitmap>()

    private val tracker = BibTracker(
        minConsecutive = settings.minConsecutiveDetections,
        patienceMillis = (settings.patienceSeconds * 1000).toLong(),
        minDigits = settings.minBibDigits,
        maxDigits = settings.maxBibDigits,
    ) { number, firstSeenMs ->
        val elapsed = (firstSeenMs - sessionStartMs) / 1000.0 + settings.timeOffsetSeconds
        val crop = pendingCrops.remove(number)
        onConfirmed(
            Confirmed(
                bib = number,
                elapsedSeconds = elapsed,
                timeText = ImageUtils.formatHms(elapsed),
                crop = crop,
            )
        )
    }

    @ExperimentalGetImage
    override fun analyze(imageProxy: ImageProxy) {
        val mediaImage = imageProxy.image
        if (mediaImage == null) {
            imageProxy.close()
            return
        }
        val rotation = imageProxy.imageInfo.rotationDegrees
        val input = InputImage.fromMediaImage(mediaImage, rotation)
        recognizer.process(input)
            .addOnSuccessListener { text -> handleText(text, imageProxy, rotation) }
            .addOnFailureListener { onStatus("OCR error: ${it.message}") }
            .addOnCompleteListener { imageProxy.close() }
    }

    private fun handleText(text: Text, imageProxy: ImageProxy, rotation: Int) {
        val now = System.currentTimeMillis()

        // Collect every digit group together with its bounding box.
        val candidates = ArrayList<Pair<String, Rect>>()
        for (block in text.textBlocks) {
            for (line in block.lines) {
                val box = line.boundingBox ?: continue
                for (match in digitRegex.findAll(line.text)) {
                    candidates.add(match.value to box)
                }
            }
        }

        if (candidates.isNotEmpty()) {
            // Convert the frame once and keep a close-up crop per new candidate.
            val upright = try {
                ImageUtils.rotateBitmap(imageProxy.toBitmap(), rotation)
            } catch (e: Exception) {
                null
            }
            if (upright != null) {
                for ((num, box) in candidates) {
                    if (num.length < settings.minBibDigits || num.length > settings.maxBibDigits) continue
                    // Expand the box a little for context (cheap stand-in for the
                    // Python 3x-context crop).
                    val pad = (box.height() * 0.5f).toInt()
                    val padded = Rect(
                        box.left - pad, box.top - pad,
                        box.right + pad, box.bottom + pad
                    )
                    ImageUtils.cropSafely(upright, padded)?.let { pendingCrops[num] = it }
                }
            }
        }

        // Publish boxes for the live overlay. ML Kit coordinates are in the
        // upright image; its size depends on the rotation we applied.
        val uw: Int
        val uh: Int
        if (rotation == 90 || rotation == 270) {
            uw = imageProxy.height; uh = imageProxy.width
        } else {
            uw = imageProxy.width; uh = imageProxy.height
        }
        onDetections(
            DetectionFrame(
                imageWidth = uw,
                imageHeight = uh,
                isFrontCamera = isFrontCamera,
                boxes = candidates.map { (num, box) ->
                    DetBox(num, box.left.toFloat(), box.top.toFloat(), box.right.toFloat(), box.bottom.toFloat())
                },
            )
        )

        tracker.onFrame(now, candidates.map { it.first })
        onStatus("Active ${tracker.activeCount()} · Confirmed ${tracker.completedCount()}")
    }

    /** Confirm anything still pending; call when the session is stopped. */
    fun flush() = tracker.flush()
}
