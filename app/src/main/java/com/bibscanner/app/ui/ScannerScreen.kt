package com.bibscanner.app.ui

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.lifecycle.awaitInstance
import androidx.camera.video.FallbackStrategy
import androidx.camera.video.FileOutputOptions
import androidx.camera.video.Quality
import androidx.camera.video.QualitySelector
import androidx.camera.video.Recorder
import androidx.camera.video.VideoCapture
import androidx.camera.video.VideoRecordEvent
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import kotlin.math.min
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.bibscanner.app.data.AppSettings
import com.bibscanner.app.data.BibResult
import com.bibscanner.app.detect.BibAnalyzer
import com.bibscanner.app.net.CallbackClient
import com.bibscanner.app.util.ImageUtils
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

@Composable
fun ScannerScreen(
    vm: ScannerViewModel,
    settings: AppSettings,
    onOpenSettings: () -> Unit,
    onOpenResults: () -> Unit,
    onOpenVideo: () -> Unit,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    // Pick a recorded video to run the same pipeline over.
    val videoPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri ->
        if (uri != null) {
            vm.pendingVideoUri = uri
            onOpenVideo()
        }
    }

    var hasCamPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
                PackageManager.PERMISSION_GRANTED
        )
    }
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted -> hasCamPermission = granted }

    LaunchedEffect(Unit) {
        if (!hasCamPermission) permissionLauncher.launch(Manifest.permission.CAMERA)
    }

    val previewView = remember {
        PreviewView(context).apply {
            // FIT_CENTER letterboxes the full frame, so the overlay can map
            // analysis-image coordinates with one uniform scale + centering.
            scaleType = PreviewView.ScaleType.FIT_CENTER
        }
    }
    val callbackClient = remember { CallbackClient() }
    val analysisExecutor = remember { Executors.newSingleThreadExecutor() }
    var cameraProvider by remember { mutableStateOf<ProcessCameraProvider?>(null) }

    // Leaving this screen (e.g. to Settings) must release the camera and stop
    // recording before the executor is shut down, or bound analysis would keep
    // submitting frames to a dead executor.
    DisposableEffect(Unit) {
        onDispose {
            vm.currentRecording?.let {
                it.stop()
                vm.currentRecording = null
            }
            cameraProvider?.unbindAll()
            analysisExecutor.shutdown()
        }
    }

    val isRunning by vm.isRunning.collectAsStateWithLifecycle()
    val status by vm.status.collectAsStateWithLifecycle()
    val results by vm.results.collectAsStateWithLifecycle()
    val overlay by vm.overlay.collectAsStateWithLifecycle()
    val offset by vm.calibrationOffset.collectAsStateWithLifecycle()

    val labelPaint = remember {
        android.graphics.Paint().apply {
            color = android.graphics.Color.GREEN
            textSize = 44f
            isFakeBoldText = true
            isAntiAlias = true
        }
    }

    // Keep a reference to the analyzer so we can flush pending tracks on stop.
    var activeAnalyzer by remember { mutableStateOf<BibAnalyzer?>(null) }

    // (Re)bind whenever the run state, permission, or settings change. Because
    // AppSettings is a data class, this only re-runs on a real change.
    LaunchedEffect(isRunning, hasCamPermission, settings) {
        val provider = ProcessCameraProvider.awaitInstance(context)
        cameraProvider = provider

        // Stop any prior recording so the file is finalised.
        vm.currentRecording?.let {
            it.stop()
            vm.currentRecording = null
        }
        // Unbind first so no new frames are analysed, then flush pending tracks.
        provider.unbindAll()
        activeAnalyzer?.flush()
        activeAnalyzer = null
        vm.overlay.value = null

        if (!isRunning || !hasCamPermission) return@LaunchedEffect

        vm.sessionStartMs = System.currentTimeMillis()

        val analyzer = BibAnalyzer(
            settings = settings,
            sessionStartMs = vm.sessionStartMs,
            isFrontCamera = settings.useFrontCamera,
            onStatus = { vm.setStatus(it) },
            onDetections = { vm.overlay.value = it },
        ) { confirmed ->
            // Runs on the analysis thread. Persist + notify.
            val path = confirmed.crop?.let { crop ->
                if (confirmed.isNoNumber) {
                    ImageUtils.saveCropNamed(context, "nonumber_${(confirmed.elapsedSeconds * 1000).toLong()}", crop)
                } else {
                    ImageUtils.saveCrop(context, confirmed.bib, crop)
                }
            }
            vm.addResult(
                BibResult(
                    bib = confirmed.bib,
                    elapsedSeconds = confirmed.elapsedSeconds,
                    timeText = confirmed.timeText,
                    imagePath = path,
                    isNoNumber = confirmed.isNoNumber,
                )
            )
            callbackClient.fire(settings, confirmed.bib, confirmed.elapsedSeconds)
        }
        activeAnalyzer = analyzer

        val analysis = ImageAnalysis.Builder()
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .build()
            .also { it.setAnalyzer(analysisExecutor, analyzer) }

        val preview = Preview.Builder().build().also {
            it.surfaceProvider = previewView.surfaceProvider
        }

        val selector = if (settings.useFrontCamera) {
            CameraSelector.DEFAULT_FRONT_CAMERA
        } else {
            CameraSelector.DEFAULT_BACK_CAMERA
        }

        try {
            if (settings.recordBackup) {
                val recorder = Recorder.Builder()
                    .setQualitySelector(
                        QualitySelector.from(
                            Quality.HD,
                            FallbackStrategy.lowerQualityOrHigherThan(Quality.SD)
                        )
                    )
                    .build()
                val videoCapture = VideoCapture.withOutput(recorder)

                provider.bindToLifecycle(lifecycleOwner, selector, preview, analysis, videoCapture)

                val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
                val file = File(ImageUtils.backupDir(context), "backup_$stamp.mp4")
                vm.currentRecording = videoCapture.output
                    .prepareRecording(context, FileOutputOptions.Builder(file).build())
                    .start(ContextCompat.getMainExecutor(context)) { event ->
                        if (event is VideoRecordEvent.Finalize && event.hasError()) {
                            vm.setStatus("Backup error: ${event.error}")
                        }
                    }
                vm.setStatus("Recording → ${file.name}")
            } else {
                provider.bindToLifecycle(lifecycleOwner, selector, preview, analysis)
                vm.setStatus("Scanning (no backup)")
            }
        } catch (_: Exception) {
            // Some devices can't run preview+analysis+video together; fall back.
            try {
                provider.unbindAll()
                provider.bindToLifecycle(lifecycleOwner, selector, preview, analysis)
                vm.setStatus("Recording unsupported here — scanning only")
            } catch (e2: Exception) {
                vm.setStatus("Camera error: ${e2.message}")
            }
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
        ) {
            if (hasCamPermission) {
                AndroidView(factory = { previewView }, modifier = Modifier.fillMaxSize())

                // Live bounding-box overlay, aligned to the FIT_CENTER preview.
                val frame = overlay
                if (frame != null && frame.imageWidth > 0 && frame.imageHeight > 0) {
                    Canvas(modifier = Modifier.fillMaxSize()) {
                        val scale = min(
                            size.width / frame.imageWidth,
                            size.height / frame.imageHeight
                        )
                        val dx = (size.width - frame.imageWidth * scale) / 2f
                        val dy = (size.height - frame.imageHeight * scale) / 2f
                        val stroke = 3.dp.toPx()
                        // People (yellow) drawn under the number boxes.
                        for (b in frame.personBoxes) {
                            var l = b.left * scale + dx
                            var r = b.right * scale + dx
                            val t = b.top * scale + dy
                            val bottom = b.bottom * scale + dy
                            if (frame.isFrontCamera) {
                                val ml = size.width - r; val mr = size.width - l
                                l = ml; r = mr
                            }
                            drawRect(
                                color = Color.Yellow,
                                topLeft = Offset(l, t),
                                size = Size(r - l, bottom - t),
                                style = Stroke(width = 2.dp.toPx())
                            )
                        }
                        for (b in frame.boxes) {
                            var l = b.left * scale + dx
                            var r = b.right * scale + dx
                            val t = b.top * scale + dy
                            val bottom = b.bottom * scale + dy
                            if (frame.isFrontCamera) {
                                // Front preview is mirrored; mirror boxes too.
                                val ml = size.width - r
                                val mr = size.width - l
                                l = ml; r = mr
                            }
                            drawRect(
                                color = Color.Green,
                                topLeft = Offset(l, t),
                                size = Size(r - l, bottom - t),
                                style = Stroke(width = stroke)
                            )
                            drawIntoCanvas { canvas ->
                                canvas.nativeCanvas.drawText(
                                    b.label, l, (t - 8f).coerceAtLeast(40f), labelPaint
                                )
                            }
                        }
                    }
                }
            } else {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("Camera permission required")
                }
            }
            Surface(
                color = Color.Black.copy(alpha = 0.45f),
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(8.dp)
            ) {
                Text(
                    text = "  $status  ",
                    color = Color.White,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(4.dp)
                )
            }
        }

        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = "Confirmed bibs: ${results.size}",
                style = MaterialTheme.typography.titleMedium
            )
            results.takeLast(3).reversed().forEach { r ->
                Text(
                    "• ${r.bib}   ${ImageUtils.correctedHms(r.elapsedSeconds, offset)}",
                    style = MaterialTheme.typography.bodyMedium
                )
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Button(
                    onClick = { vm.toggleRunning() },
                    enabled = hasCamPermission,
                    modifier = Modifier.weight(1f)
                ) {
                    Text(if (isRunning) "Stop" else "Start")
                }
                OutlinedButton(onClick = onOpenResults, modifier = Modifier.weight(1f)) {
                    Text("Results")
                }
                OutlinedButton(onClick = onOpenSettings, modifier = Modifier.weight(1f)) {
                    Text("Settings")
                }
            }

            OutlinedButton(
                onClick = { videoPicker.launch("video/*") },
                enabled = !isRunning,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp)
            ) {
                Text("Process recorded video…")
            }
        }
    }
}
