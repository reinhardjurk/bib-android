package com.bibscanner.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.bibscanner.app.data.AppSettings

@Composable
fun SettingsScreen(
    current: AppSettings,
    onSave: (AppSettings) -> Unit,
    onBack: () -> Unit,
) {
    // Local editable copies (strings for numeric fields so typing is smooth).
    var callbackUrl by remember { mutableStateOf(current.callbackUrl) }
    var httpMethod by remember { mutableStateOf(current.httpMethod) }
    var fireCallback by remember { mutableStateOf(current.fireCallback) }
    var timeOffset by remember { mutableStateOf(current.timeOffsetSeconds.toString()) }
    var minConsecutive by remember { mutableStateOf(current.minConsecutiveDetections.toString()) }
    var patience by remember { mutableStateOf(current.patienceSeconds.toString()) }
    var minDigits by remember { mutableStateOf(current.minBibDigits.toString()) }
    var maxDigits by remember { mutableStateOf(current.maxBibDigits.toString()) }
    var recordBackup by remember { mutableStateOf(current.recordBackup) }
    var useFrontCamera by remember { mutableStateOf(current.useFrontCamera) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text("Settings", style = MaterialTheme.typography.headlineSmall)

        SectionTitle("Webhook")
        OutlinedTextField(
            value = callbackUrl,
            onValueChange = { callbackUrl = it },
            label = { Text("Callback URL") },
            supportingText = { Text("Placeholders: {bib} {time} {time_hms} {raw_seconds}") },
            modifier = Modifier.fillMaxWidth()
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Method:", modifier = Modifier.padding(end = 8.dp))
            FilterChip(
                selected = httpMethod.equals("GET", true),
                onClick = { httpMethod = "GET" },
                label = { Text("GET") },
                modifier = Modifier.padding(end = 8.dp)
            )
            FilterChip(
                selected = httpMethod.equals("POST", true),
                onClick = { httpMethod = "POST" },
                label = { Text("POST") }
            )
        }
        SwitchRow("Fire callback on each bib", fireCallback) { fireCallback = it }

        HorizontalDivider()
        SectionTitle("Timing & recognition")
        NumberField("Time offset (seconds, added to elapsed)", timeOffset) { timeOffset = it }
        NumberField("Min consecutive detections", minConsecutive) { minConsecutive = it }
        NumberField("Patience (seconds before confirming)", patience) { patience = it }
        NumberField("Min bib digits", minDigits) { minDigits = it }
        NumberField("Max bib digits", maxDigits) { maxDigits = it }

        HorizontalDivider()
        SectionTitle("Capture")
        SwitchRow("Record .mp4 backup", recordBackup) { recordBackup = it }
        SwitchRow("Use front camera", useFrontCamera) { useFrontCamera = it }

        HorizontalDivider()
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Button(
                onClick = {
                    onSave(
                        AppSettings(
                            callbackUrl = callbackUrl,
                            httpMethod = if (httpMethod.equals("POST", true)) "POST" else "GET",
                            fireCallback = fireCallback,
                            timeOffsetSeconds = timeOffset.toDoubleOrNull() ?: 0.0,
                            minConsecutiveDetections = minConsecutive.toIntOrNull()?.coerceAtLeast(1) ?: 2,
                            patienceSeconds = patience.toDoubleOrNull()?.coerceAtLeast(0.1) ?: 2.0,
                            minBibDigits = minDigits.toIntOrNull()?.coerceAtLeast(1) ?: 1,
                            maxBibDigits = maxDigits.toIntOrNull()?.coerceAtLeast(1) ?: 5,
                            recordBackup = recordBackup,
                            useFrontCamera = useFrontCamera,
                        )
                    )
                    onBack()
                },
                modifier = Modifier.weight(1f)
            ) { Text("Save") }
            Button(onClick = onBack, modifier = Modifier.weight(1f)) { Text("Cancel") }
        }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(text, style = MaterialTheme.typography.titleMedium)
}

@Composable
private fun SwitchRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(label)
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
private fun NumberField(label: String, value: String, onChange: (String) -> Unit) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        modifier = Modifier.fillMaxWidth()
    )
}
