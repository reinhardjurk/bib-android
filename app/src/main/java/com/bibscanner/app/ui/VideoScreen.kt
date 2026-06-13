package com.bibscanner.app.ui

import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.provider.OpenableColumns
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.bibscanner.app.data.AppSettings
import com.bibscanner.app.detect.VideoProcessor
import com.bibscanner.app.net.CallbackClient
import com.bibscanner.app.util.ImageUtils
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.min

@Composable
fun VideoScreen(
    vm: ScannerViewModel,
    settings: AppSettings,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val callbackClient = remember { CallbackClient() }

    val processing by vm.videoProcessing.collectAsStateWithLifecycle()
    val progress by vm.videoProgress.collectAsStateWithLifecycle()
    val status by vm.videoStatus.collectAsStateWithLifecycle()
    val results by vm.results.collectAsStateWithLifecycle()
    val preview by vm.videoPreview.collectAsStateWithLifecycle()
    val offset by vm.calibrationOffset.collectAsStateWithLifecycle()
    val calibrationLabel by vm.calibrationLabel.collectAsStateWithLifecycle()

    val labelPaint = remember {
        android.graphics.Paint().apply {
            color = android.graphics.Color.GREEN
            textSize = 36f
            isFakeBoldText = true
            isAntiAlias = true
        }
    }

    val uri = vm.pendingVideoUri
    val fileName = remember(uri) {
        if (uri == null) null else runCatching {
            context.contentResolver.query(
                uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null
            )?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
        }.getOrNull()
    }

    // ROI selection happens first; processing waits until it's confirmed.
    var roiConfirmed by remember(uri) { mutableStateOf(false) }
    var firstFrame by remember(uri) { mutableStateOf<Bitmap?>(null) }

    LaunchedEffect(uri) {
        if (uri == null) return@LaunchedEffect
        firstFrame = withContext(Dispatchers.IO) {
            val r = MediaMetadataRetriever()
            try {
                r.setDataSource(context, uri)
                r.getFrameAtTime(0L, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
            } catch (e: Exception) {
                null
            } finally {
                try { r.release() } catch (_: Exception) {}
            }
        }
    }

    DisposableEffect(Unit) { onDispose { vm.videoPreview.value = null } }

    // Start processing once a ROI is confirmed. Leaving cancels cooperatively.
    LaunchedEffect(uri, roiConfirmed) {
        if (uri == null || !roiConfirmed) return@LaunchedEffect
        vm.videoProcessing.value = true
        vm.videoProgress.value = 0f
        vm.videoStatus.value = "Starting…"
        vm.videoPreview.value = null
        val processor = VideoProcessor(
            context = context,
            settings = settings,
            callbackClient = callbackClient,
            roi = vm.videoRoi,
            calibrationOffset = { vm.calibrationOffset.value },
            onResult = { vm.addResult(it) },
            onProgress = { done, total ->
                vm.videoProgress.value = if (total > 0) done.toFloat() / total else 0f
                vm.videoStatus.value =
                    "${ImageUtils.formatHms(done / 1000.0)} / ${ImageUtils.formatHms(total / 1000.0)}"
            },
            onPreview = { thumb, frame -> vm.videoPreview.value = VideoPreview(thumb, frame) },
        )
        try {
            processor.process(uri)
            vm.videoStatus.value = "Done"
        } catch (e: CancellationException) {
            vm.videoStatus.value = "Cancelled"
            throw e
        } catch (e: Exception) {
            vm.videoStatus.value = "Error: ${e.message}"
        } finally {
            vm.videoProcessing.value = false
            vm.videoPreview.value = null
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text("Process recorded video", style = MaterialTheme.typography.headlineSmall)

        when {
            uri == null -> Text("No video selected.")

            !roiConfirmed -> {
                // --- ROI selection step ---
                Text(fileName ?: "Selected video", style = MaterialTheme.typography.bodyMedium)
                val ff = firstFrame
                if (ff == null) {
                    Text("Loading first frame…")
                } else {
                    RoiSelector(firstFrame = ff) { rect ->
                        vm.videoRoi = rect
                        roiConfirmed = true
                    }
                }
                OutlinedButton(onClick = onBack, modifier = Modifier.fillMaxWidth()) {
                    Text("Back")
                }
            }

            else -> {
                // --- Processing step ---
                Text(fileName ?: "Selected video", style = MaterialTheme.typography.bodyMedium)

                val p = preview
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .aspectRatio(16f / 9f),
                    contentAlignment = Alignment.Center
                ) {
                    if (p != null) {
                        Image(
                            bitmap = p.bitmap.asImageBitmap(),
                            contentDescription = "Current frame",
                            contentScale = ContentScale.Fit,
                            modifier = Modifier.fillMaxSize()
                        )
                        val frame = p.frame
                        if (frame.imageWidth > 0 && frame.imageHeight > 0) {
                            Canvas(modifier = Modifier.fillMaxSize()) {
                                val scale = min(
                                    size.width / frame.imageWidth,
                                    size.height / frame.imageHeight
                                )
                                val dx = (size.width - frame.imageWidth * scale) / 2f
                                val dy = (size.height - frame.imageHeight * scale) / 2f
                                val stroke = 2.dp.toPx()
                                for (b in frame.personBoxes) {
                                    drawRect(
                                        color = Color.Yellow,
                                        topLeft = Offset(b.left * scale + dx, b.top * scale + dy),
                                        size = Size((b.right - b.left) * scale, (b.bottom - b.top) * scale),
                                        style = Stroke(width = stroke)
                                    )
                                }
                                for (b in frame.boxes) {
                                    val l = b.left * scale + dx
                                    val r = b.right * scale + dx
                                    val t = b.top * scale + dy
                                    val bottom = b.bottom * scale + dy
                                    drawRect(
                                        color = Color.Green,
                                        topLeft = Offset(l, t),
                                        size = Size(r - l, bottom - t),
                                        style = Stroke(width = stroke)
                                    )
                                    drawIntoCanvas { canvas ->
                                        canvas.nativeCanvas.drawText(
                                            b.label, l, (t - 6f).coerceAtLeast(34f), labelPaint
                                        )
                                    }
                                }
                            }
                        }
                    } else {
                        Text(if (processing) "Decoding…" else "—")
                    }
                }

                LinearProgressIndicator(
                    progress = { progress },
                    modifier = Modifier.fillMaxWidth()
                )
                Text(status, style = MaterialTheme.typography.bodyMedium)
                Text(
                    if (processing) "Scanning…" else "Finished",
                    style = MaterialTheme.typography.bodySmall
                )

                HorizontalDivider()

                Text("Confirmed bibs: ${results.size}", style = MaterialTheme.typography.titleMedium)
                results.takeLast(6).reversed().forEach { r ->
                    Text(
                        "• ${if (r.isNoNumber) "nonumber" else r.bib}   ${ImageUtils.correctedHms(r.elapsedSeconds, offset)}",
                        style = MaterialTheme.typography.bodyMedium
                    )
                }

                // Editable during analysis too, so you can anchor and re-issue
                // corrected bibs without waiting for the run to finish.
                if (results.isNotEmpty()) {
                    HorizontalDivider()
                    CalibrationSection(
                        results = results,
                        calibrationLabel = calibrationLabel,
                        onApply = { bib, secs -> vm.calibrate(bib, secs) },
                        onClear = { vm.clearCalibration() },
                        onResend = {
                            val off = vm.calibrationOffset.value
                            results.forEach { callbackClient.fire(settings, it.bib, it.elapsedSeconds + off) }
                        },
                    )
                }

                OutlinedButton(onClick = onBack, modifier = Modifier.fillMaxWidth()) {
                    Text(if (processing) "Cancel" else "Back")
                }
            }
        }
    }
}
