package com.example.slopradar

import android.graphics.Bitmap
import android.graphics.Rect

data class Detection(
    val boundingBox: Rect,
    val confidence: Float,
    val label: String
)

interface AIDetector {
    suspend fun analyzeFrame(bitmap: Bitmap): List<Detection>
}