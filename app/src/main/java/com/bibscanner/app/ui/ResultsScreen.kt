package com.bibscanner.app.ui

import android.graphics.BitmapFactory
import android.widget.Toast
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.bibscanner.app.data.AppSettings
import com.bibscanner.app.data.BibResult
import com.bibscanner.app.net.CallbackClient
import com.bibscanner.app.util.HtmlExporter
import com.bibscanner.app.util.ImageUtils
import java.io.File

@Composable
fun ResultsScreen(
    vm: ScannerViewModel,
    settings: AppSettings,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val callbackClient = remember { CallbackClient() }

    val results by vm.results.collectAsStateWithLifecycle()
    val offset by vm.calibrationOffset.collectAsStateWithLifecycle()
    val calibrationLabel by vm.calibrationLabel.collectAsStateWithLifecycle()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
    ) {
        Text(
            "Results (${results.size})",
            style = MaterialTheme.typography.headlineSmall
        )

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Button(
                onClick = {
                    val path = HtmlExporter.export(context, results, offset)
                    Toast.makeText(context, "Saved: $path", Toast.LENGTH_LONG).show()
                },
                modifier = Modifier.weight(1f)
            ) { Text("Export HTML") }
            OutlinedButton(
                onClick = { vm.clearResults() },
                modifier = Modifier.weight(1f)
            ) { Text("Clear") }
            OutlinedButton(onClick = onBack, modifier = Modifier.weight(1f)) { Text("Back") }
        }

        if (results.isNotEmpty()) {
            CalibrationSection(
                results = results,
                calibrationLabel = calibrationLabel,
                onApply = { bib, secs ->
                    if (!vm.calibrate(bib, secs)) {
                        Toast.makeText(context, "Bib $bib not found", Toast.LENGTH_SHORT).show()
                    }
                },
                onClear = { vm.clearCalibration() },
                onResend = {
                    val off = vm.calibrationOffset.value
                    results.forEach { callbackClient.fire(settings, it.bib, it.elapsedSeconds + off) }
                    Toast.makeText(context, "Re-sent ${results.size} corrected", Toast.LENGTH_SHORT).show()
                },
            )
            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
        }

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            items(results) { r -> ResultRow(r, offset) }
        }
    }
}

@Composable
private fun ResultRow(r: BibResult, offset: Double) {
    val thumb = remember(r.imagePath) {
        r.imagePath?.let { path ->
            val f = File(path)
            if (f.exists()) BitmapFactory.decodeFile(path) else null
        }
    }
    Card(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            if (thumb != null) {
                Image(
                    bitmap = thumb.asImageBitmap(),
                    contentDescription = "Bib ${r.bib}",
                    modifier = Modifier.size(72.dp)
                )
            }
            Column {
                Text(
                    if (r.isNoNumber) "No number" else "Bib ${r.bib}",
                    style = MaterialTheme.typography.titleMedium
                )
                Text(
                    ImageUtils.correctedHms(r.elapsedSeconds, offset),
                    style = MaterialTheme.typography.bodyMedium
                )
            }
        }
    }
}
