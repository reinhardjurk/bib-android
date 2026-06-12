package com.bibscanner.app.data

/** A single confirmed runner. */
data class BibResult(
    val bib: String,             // the number, or "nonumber" for a person without a readable bib
    val elapsedSeconds: Double,
    val timeText: String,        // hh:mm:ss.mmm
    val imagePath: String?,      // saved close-up jpg, or null if unavailable
    val isNoNumber: Boolean = false,
)
