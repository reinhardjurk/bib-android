package com.bibscanner.app.data

/**
 * All user-configurable settings. Mirrors the CLI flags / constants of the
 * Python real-time script (realtime.py). Edited on the Settings screen and
 * persisted via DataStore (see [SettingsRepository]).
 */
data class AppSettings(
    // Webhook called on every confirmed bib. Placeholders:
    //   {bib} {time} {time_hms} {raw_seconds}
    val callbackUrl: String = "http://10.0.2.2:8000/bib?number={bib}&time={time}",
    val httpMethod: String = "GET",          // GET or POST
    val fireCallback: Boolean = true,

    // Race start offset in seconds, added to every elapsed time.
    val timeOffsetSeconds: Double = 0.0,

    // Recognition / consecutive-detection logic (mirrors realtime.py).
    val minConsecutiveDetections: Int = 2,
    val patienceSeconds: Double = 2.0,
    val minBibDigits: Int = 1,
    val maxBibDigits: Int = 5,

    // Capture.
    val recordBackup: Boolean = true,
    val useFrontCamera: Boolean = false,
)
