package com.example.slopradar

import android.graphics.Bitmap
import android.graphics.Rect

/**
 * Data class representing a detected object (e.g., photo, video) on the screen.
 */
data class Detection(
    /**
     * Bounding box coordinates of the detected item relative to the input frame.
     */
    val boundingBox: Rect,
    
    /**
     * Confidence score between 0.0 and 1.0.
     */
    val confidence: Float,
    
    /**
     * Label of the detection (e.g., "Photo", "Video").
     */
    val label: String
)

/**
 * Interface for AI detection algorithms.
 * Allows easy drop-in of real object-detection models later.
 */
interface AIDetector {
    /**
     * Analyzes a screen capture frame (Bitmap).
     * @param bitmap The frame to analyze.
     * @return A list of detections found in the frame.
     */
    fun analyzeFrame(bitmap: Bitmap): List<Detection>
}
