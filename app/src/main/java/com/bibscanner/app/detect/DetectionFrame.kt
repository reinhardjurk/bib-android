package com.bibscanner.app.detect

/** One detected number box, in upright-image pixel coordinates. */
data class DetBox(
    val label: String,
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
)

/**
 * The latest analyzer result, published for the live preview overlay. The boxes
 * are in the coordinate space of the upright image whose size is
 * [imageWidth] x [imageHeight]; the overlay scales them onto the preview.
 */
data class DetectionFrame(
    val imageWidth: Int,
    val imageHeight: Int,
    val isFrontCamera: Boolean,
    val boxes: List<DetBox>,
    /** Person/object boxes (drawn in a second colour); empty if person detection is off. */
    val personBoxes: List<DetBox> = emptyList(),
)
