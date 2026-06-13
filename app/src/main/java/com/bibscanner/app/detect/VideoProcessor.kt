package com.bibscanner.app.detect

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Rect
import android.graphics.RectF
import android.media.MediaMetadataRetriever
import android.net.Uri
import com.bibscanner.app.data.AppSettings
import com.bibscanner.app.data.BibResult
import com.bibscanner.app.net.CallbackClient
import com.bibscanner.app.util.ImageUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/**
 * Runs the bib pipeline over a recorded video file instead of the live camera.
 *
 * Frames are sampled every [AppSettings.videoSampleMs] milliseconds with
 * [MediaMetadataRetriever] (which returns them already upright), recognised with
 * ML Kit, and fed to the shared [BibRecognizer]. Confirmed bibs save a close-up,
 * push a [BibResult], and fire the same webhook as the live path — the elapsed
 * time is the position within the video.
 */
class VideoProcessor(
    private val context: Context,
    private val settings: AppSettings,
    private val callbackClient: CallbackClient,
    private val roi: RectF,
    private val calibrationOffset: () -> Double,
    private val onResult: (BibResult) -> Unit,
    private val onProgress: (processedMs: Long, totalMs: Long) -> Unit,
    private val onPreview: (thumbnail: Bitmap, frame: DetectionFrame) -> Unit,
) {
    /** Suspends until the whole video has been processed (or the job is cancelled). */
    suspend fun process(uri: Uri) = withContext(Dispatchers.Default) {
        var lastFrame: DetectionFrame? = null
        val recognizer = BibRecognizer(
            settings = settings,
            timeOriginMs = 0L,                 // timestamps are already video-relative
            isFrontCamera = false,
            onStatus = {},
            onDetections = { lastFrame = it },
        ) { confirmed ->
            val path = confirmed.crop?.let { saveConfirmedCrop(confirmed) }
            onResult(
                BibResult(
                    bib = confirmed.bib,
                    elapsedSeconds = confirmed.elapsedSeconds,
                    timeText = confirmed.timeText,
                    imagePath = path,
                    isNoNumber = confirmed.isNoNumber,
                )
            )
            // Apply the current calibration offset so mid-run corrections take effect.
            callbackClient.fire(settings, confirmed.bib, confirmed.elapsedSeconds + calibrationOffset())
        }

        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(context, uri)
            val totalMs = retriever
                .extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                ?.toLongOrNull() ?: 0L

            val stepMs = settings.videoSampleMs.toLong().coerceAtLeast(20L)
            var t = 0L
            while (t <= totalMs) {
                ensureActive()                        // cooperative cancellation
                val bmp = retriever.getFrameAtTime(
                    t * 1000L,                        // microseconds
                    MediaMetadataRetriever.OPTION_CLOSEST
                )
                if (bmp != null) {
                    val roiBmp = cropToRoi(bmp)        // analyse only the selected region
                    recognizer.processFrame(roiBmp, t)
                    onPreview(
                        thumbnail(roiBmp),
                        lastFrame ?: DetectionFrame(roiBmp.width, roiBmp.height, false, emptyList())
                    )
                }
                onProgress(t, totalMs)
                t += stepMs
            }
            recognizer.flush()
            onProgress(totalMs, totalMs)
        } finally {
            try {
                retriever.release()
            } catch (_: Exception) {
            }
            recognizer.close()
        }
    }

    /** Crop a frame to the ROI fractions; returns the frame unchanged for full-frame. */
    private fun cropToRoi(src: Bitmap): Bitmap {
        if (roi.left <= 0f && roi.top <= 0f && roi.right >= 1f && roi.bottom >= 1f) return src
        val rect = Rect(
            (roi.left * src.width).toInt(),
            (roi.top * src.height).toInt(),
            (roi.right * src.width).toInt(),
            (roi.bottom * src.height).toInt()
        )
        return ImageUtils.cropSafely(src, rect) ?: src
    }

    private fun saveConfirmedCrop(c: BibRecognizer.Confirmed): String? {
        val crop = c.crop ?: return null
        return if (c.isNoNumber) {
            ImageUtils.saveCropNamed(context, "nonumber_${(c.elapsedSeconds * 1000).toLong()}", crop)
        } else {
            ImageUtils.saveCrop(context, c.bib, crop)
        }
    }

    /** Downscale a frame for cheap live display (boxes are scaled by the UI). */
    private fun thumbnail(src: Bitmap, maxWidth: Int = 480): Bitmap {
        if (src.width <= maxWidth) return src
        val w = maxWidth
        val h = (src.height.toFloat() * w / src.width).toInt().coerceAtLeast(1)
        return Bitmap.createScaledBitmap(src, w, h, true)
    }
}
