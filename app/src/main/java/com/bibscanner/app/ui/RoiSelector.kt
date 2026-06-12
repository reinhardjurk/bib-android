package com.bibscanner.app.ui

import android.graphics.Bitmap
import android.graphics.RectF
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import kotlin.math.max
import kotlin.math.min

/**
 * Lets the user drag a rectangle over the video's first frame to limit detection
 * to that region (e.g. the finish line), which speeds up processing because ML
 * Kit only sees the crop. Returns the box as fractions (0..1) of the frame, or
 * the full frame.
 */
@Composable
fun RoiSelector(
    firstFrame: Bitmap,
    onConfirm: (RectF) -> Unit,
) {
    var boxSize by remember { mutableStateOf(IntSize.Zero) }
    var startPt by remember { mutableStateOf<Offset?>(null) }
    var endPt by remember { mutableStateOf<Offset?>(null) }

    val aspect = firstFrame.width.toFloat() / firstFrame.height.toFloat()

    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(
            "Drag a box over the area to scan (e.g. the finish line). Smaller area = faster.",
            style = MaterialTheme.typography.bodyMedium
        )
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(aspect)
                .onSizeChanged { boxSize = it }
                .pointerInput(Unit) {
                    detectDragGestures(
                        onDragStart = { startPt = it; endPt = it },
                        onDrag = { change, _ ->
                            change.consume()
                            endPt = change.position
                        },
                    )
                }
        ) {
            Image(
                bitmap = firstFrame.asImageBitmap(),
                contentDescription = "First frame",
                // Box aspect == image aspect, so FillBounds shows it undistorted
                // and drag coordinates map straight to frame fractions.
                contentScale = ContentScale.FillBounds,
                modifier = Modifier.fillMaxSize()
            )
            Canvas(modifier = Modifier.fillMaxSize()) {
                val s = startPt
                val e = endPt
                if (s != null && e != null) {
                    val l = min(s.x, e.x)
                    val t = min(s.y, e.y)
                    val r = max(s.x, e.x)
                    val b = max(s.y, e.y)
                    drawRect(
                        color = Color(0x3300FF00),
                        topLeft = Offset(l, t),
                        size = Size(r - l, b - t),
                    )
                    drawRect(
                        color = Color.Green,
                        topLeft = Offset(l, t),
                        size = Size(r - l, b - t),
                        style = Stroke(width = 3.dp.toPx())
                    )
                }
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Button(
                onClick = { onConfirm(currentFraction(startPt, endPt, boxSize)) },
                modifier = Modifier.weight(1f)
            ) { Text("Scan this area") }
            OutlinedButton(
                onClick = { onConfirm(RectF(0f, 0f, 1f, 1f)) },
                modifier = Modifier.weight(1f)
            ) { Text("Full frame") }
        }
    }
}

private fun currentFraction(start: Offset?, end: Offset?, boxSize: IntSize): RectF {
    if (start == null || end == null || boxSize.width == 0 || boxSize.height == 0) {
        return RectF(0f, 0f, 1f, 1f)
    }
    val l = (min(start.x, end.x) / boxSize.width).coerceIn(0f, 1f)
    val t = (min(start.y, end.y) / boxSize.height).coerceIn(0f, 1f)
    val r = (max(start.x, end.x) / boxSize.width).coerceIn(0f, 1f)
    val b = (max(start.y, end.y) / boxSize.height).coerceIn(0f, 1f)
    // Too-small accidental drags fall back to the full frame.
    return if (r - l < 0.02f || b - t < 0.02f) RectF(0f, 0f, 1f, 1f) else RectF(l, t, r, b)
}
