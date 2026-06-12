package com.bibscanner.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.bibscanner.app.data.BibResult
import com.bibscanner.app.util.ImageUtils

/**
 * Anchor-based time calibration: pick one bib whose true absolute time you know,
 * enter that time, and every result is shifted by the difference. Shown after a
 * run finishes (or is interrupted). Non-destructive — clearing reverts to the
 * raw detected times.
 */
@Composable
fun CalibrationSection(
    results: List<BibResult>,
    calibrationLabel: String?,
    onApply: (bib: String, absoluteSeconds: Double) -> Unit,
    onClear: () -> Unit,
    onResend: (() -> Unit)? = null,
) {
    var bib by remember { mutableStateOf("") }
    var timeText by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }

    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text("Calibrate times", style = MaterialTheme.typography.titleMedium)
        Text(
            "Anchor on one known bib + its real time; all other times shift to match.",
            style = MaterialTheme.typography.bodySmall
        )

        if (calibrationLabel != null) {
            Text("Active: $calibrationLabel", style = MaterialTheme.typography.bodyMedium)
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            OutlinedTextField(
                value = bib,
                onValueChange = { bib = it; error = null },
                label = { Text("Reference bib") },
                singleLine = true,
                modifier = Modifier.weight(1f)
            )
            OutlinedTextField(
                value = timeText,
                onValueChange = { timeText = it; error = null },
                label = { Text("True time") },
                placeholder = { Text("h:mm:ss.mmm") },
                singleLine = true,
                modifier = Modifier.weight(1f)
            )
        }

        if (error != null) {
            Text(error!!, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Button(
                onClick = {
                    val b = bib.trim()
                    val secs = ImageUtils.parseTimeToSeconds(timeText)
                    when {
                        b.isEmpty() -> error = "Enter a bib number."
                        results.none { it.bib == b } -> error = "Bib $b is not in the results."
                        secs == null -> error = "Couldn't read the time. Try h:mm:ss or seconds."
                        else -> {
                            error = null
                            onApply(b, secs)
                        }
                    }
                },
                modifier = Modifier.weight(1f)
            ) { Text("Apply") }
            OutlinedButton(onClick = onClear, modifier = Modifier.weight(1f)) { Text("Clear") }
        }

        if (onResend != null && calibrationLabel != null) {
            OutlinedButton(onClick = onResend, modifier = Modifier.fillMaxWidth()) {
                Text("Re-send corrected times to webhook")
            }
        }
    }
}
