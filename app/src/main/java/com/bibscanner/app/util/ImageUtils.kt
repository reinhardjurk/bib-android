package com.bibscanner.app.util

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Matrix
import android.graphics.Rect
import java.io.File
import java.io.FileOutputStream
import java.util.Locale

object ImageUtils {

    /** Format seconds as h:mm:ss.mmm, matching the Python timestamp style. */
    fun formatHms(totalSeconds: Double): String {
        val clamped = if (totalSeconds < 0) 0.0 else totalSeconds
        val ms = (clamped * 1000).toLong()
        val h = ms / 3_600_000
        val m = (ms % 3_600_000) / 60_000
        val s = (ms % 60_000) / 1000
        val millis = ms % 1000
        return String.format(Locale.US, "%d:%02d:%02d.%03d", h, m, s, millis)
    }

    fun rotateBitmap(src: Bitmap, degrees: Int): Bitmap {
        if (degrees == 0) return src
        val m = Matrix().apply { postRotate(degrees.toFloat()) }
        return Bitmap.createBitmap(src, 0, 0, src.width, src.height, m, true)
    }

    /** Crop with bounds clamping; returns null if the rect has no overlap. */
    fun cropSafely(src: Bitmap, rect: Rect): Bitmap? {
        val left = rect.left.coerceIn(0, src.width)
        val top = rect.top.coerceIn(0, src.height)
        val right = rect.right.coerceIn(0, src.width)
        val bottom = rect.bottom.coerceIn(0, src.height)
        val w = right - left
        val h = bottom - top
        if (w <= 0 || h <= 0) return null
        return Bitmap.createBitmap(src, left, top, w, h)
    }

    /** Folder where close-up images are written (analogous to runner_data/). */
    fun runnerDataDir(context: Context): File =
        File(context.getExternalFilesDir(null), "runner_data").apply { mkdirs() }

    /** Where the .mp4 backup recordings go. */
    fun backupDir(context: Context): File =
        File(context.getExternalFilesDir(null), "backups").apply { mkdirs() }

    fun saveCrop(context: Context, bib: String, bitmap: Bitmap): String? {
        return try {
            val file = File(runnerDataDir(context), "bib_closeup_$bib.jpg")
            FileOutputStream(file).use { out ->
                bitmap.compress(Bitmap.CompressFormat.JPEG, 90, out)
            }
            file.absolutePath
        } catch (e: Exception) {
            null
        }
    }
}
