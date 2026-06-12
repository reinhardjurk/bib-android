package com.bibscanner.app.data

/** A single confirmed runner. */
data class BibResult(
    val bib: String,
    val elapsedSeconds: Double,
    val timeText: String,        // hh:mm:ss.mmm
    val imagePath: String?,      // saved close-up jpg, or null if unavailable
)
