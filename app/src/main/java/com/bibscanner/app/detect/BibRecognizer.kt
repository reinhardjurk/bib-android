package com.bibscanner.app.detect

import android.graphics.Bitmap
import android.graphics.Rect
import com.bibscanner.app.data.AppSettings
import com.bibscanner.app.util.ImageUtils
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.objects.ObjectDetection
import com.google.mlkit.vision.objects.ObjectDetector
import com.google.mlkit.vision.objects.defaults.ObjectDetectorOptions
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.TextRecognizer
import com.google.mlkit.vision.text.latin.TextRecognizerOptions

/**
 * The recognition core shared by the live camera ([BibAnalyzer]) and the
 * recorded-video path ([VideoProcessor]).
 *
 * For each frame it runs ML Kit text recognition (the bib numbers) and, when
 * [AppSettings.reportNoNumber] is on, ML Kit object detection with tracking (the
 * people). Numbers feed the number-keyed [BibTracker]; people feed the
 * track-id-keyed [PersonTracker], with a number "belonging" to a person when its
 * box sits inside that person's box. A person who is tracked long enough but
 * never carries a number is emitted as a [NO_NUMBER_LABEL] result.
 *
 * [processFrame] is synchronous (it blocks on the ML Kit tasks) and must be
 * called off the main thread — both callers already run it on a background
 * thread, which also provides natural back-pressure.
 */
class BibRecognizer(
    private val settings: AppSettings,
    private val timeOriginMs: Long,
    private val isFrontCamera: Boolean,
    private val onStatus: (String) -> Unit,
    private val onDetections: (DetectionFrame) -> Unit,
    private val onConfirmed: (Confirmed) -> Unit,
) {
    data class Confirmed(
        val bib: String,
        val elapsedSeconds: Double,
        val timeText: String,
        val crop: Bitmap?,
        val isNoNumber: Boolean = false,
    )

    val recognizer: TextRecognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)

    private val objectDetector: ObjectDetector? = if (settings.reportNoNumber) {
        ObjectDetection.getClient(
            ObjectDetectorOptions.Builder()
                .setDetectorMode(ObjectDetectorOptions.STREAM_MODE)   // enables tracking ids
                .enableMultipleObjects()
                .build()
        )
    } else null

    private val digitRegex = Regex("\\d+")
    private val pendingNumberCrops = HashMap<String, Bitmap>()
    private val pendingPersonCrops = HashMap<Int, Bitmap>()

    private val numberTracker = BibTracker(
        minConsecutive = settings.minConsecutiveDetections,
        patienceMillis = (settings.patienceSeconds * 1000).toLong(),
        minDigits = settings.minBibDigits,
        maxDigits = settings.maxBibDigits,
    ) { number, firstSeenMs ->
        val elapsed = (firstSeenMs - timeOriginMs) / 1000.0 + settings.timeOffsetSeconds
        val crop = pendingNumberCrops.remove(number)
        onConfirmed(Confirmed(number, elapsed, ImageUtils.formatHms(elapsed), crop, isNoNumber = false))
    }

    private val personTracker: PersonTracker? = if (settings.reportNoNumber) {
        PersonTracker(
            minFrames = settings.minConsecutiveDetections,
            patienceMillis = (settings.patienceSeconds * 1000).toLong(),
        ) { trackId, firstSeenMs ->
            val elapsed = (firstSeenMs - timeOriginMs) / 1000.0 + settings.timeOffsetSeconds
            val crop = pendingPersonCrops.remove(trackId)
            onConfirmed(Confirmed(NO_NUMBER_LABEL, elapsed, ImageUtils.formatHms(elapsed), crop, isNoNumber = true))
        }
    } else null

    /**
     * Process one upright frame [bitmap] captured at [nowMs]. Blocking.
     */
    fun processFrame(bitmap: Bitmap, nowMs: Long) {
        val input = InputImage.fromBitmap(bitmap, 0)

        val text = Tasks.await(recognizer.process(input))
        val objects = objectDetector?.let { Tasks.await(it.process(input)) } ?: emptyList()

        // --- Numbers ---
        val numbers = ArrayList<Pair<String, Rect>>()
        for (block in text.textBlocks) {
            for (line in block.lines) {
                val box = line.boundingBox ?: continue
                for (m in digitRegex.findAll(line.text)) {
                    val n = m.value
                    if (n.length in settings.minBibDigits..settings.maxBibDigits) {
                        numbers.add(n to box)
                        if (n !in pendingNumberCrops) {
                            val pad = (box.height() * 0.5f).toInt()
                            ImageUtils.cropSafely(
                                bitmap,
                                Rect(box.left - pad, box.top - pad, box.right + pad, box.bottom + pad)
                            )?.let { pendingNumberCrops[n] = it }
                        }
                    }
                }
            }
        }
        numberTracker.onFrame(nowMs, numbers.map { it.first })

        // --- People (optional) ---
        val personBoxes = ArrayList<DetBox>()
        if (personTracker != null) {
            val observations = ArrayList<PersonObs>()
            for (obj in objects) {
                val id = obj.trackingId ?: continue
                val pbox = obj.boundingBox
                ImageUtils.cropSafely(bitmap, pbox)?.let { pendingPersonCrops[id] = it }
                val hasNumber = numbers.any { (_, nbox) ->
                    pbox.contains(nbox.centerX(), nbox.centerY())
                }
                observations.add(PersonObs(id, hasNumber))
                personBoxes.add(DetBox("", pbox.left.toFloat(), pbox.top.toFloat(), pbox.right.toFloat(), pbox.bottom.toFloat()))
            }
            personTracker.onFrame(nowMs, observations)
        }

        // --- Overlay ---
        onDetections(
            DetectionFrame(
                imageWidth = bitmap.width,
                imageHeight = bitmap.height,
                isFrontCamera = isFrontCamera,
                boxes = numbers.map { (n, box) ->
                    DetBox(n, box.left.toFloat(), box.top.toFloat(), box.right.toFloat(), box.bottom.toFloat())
                },
                personBoxes = personBoxes,
            )
        )

        onStatus("Active ${numberTracker.activeCount()} · Confirmed ${numberTracker.completedCount()}")
    }

    /** Confirm anything still pending (call when the source ends / is stopped). */
    fun flush() {
        numberTracker.flush()
        personTracker?.flush()
    }

    fun close() {
        recognizer.close()
        objectDetector?.close()
    }

    companion object {
        const val NO_NUMBER_LABEL = "nonumber"
    }
}
