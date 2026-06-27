package com.example.slopradar

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class HybridPipelineDetector(context: Context) : AIDetector {
    private val motionTracker = MockAIDetector(context)
    private val cloudClassifier = HuggingFaceAIDetector(context)

    override suspend fun analyzeFrame(bitmap: Bitmap): List<Detection> = withContext(Dispatchers.Default) {
        // Step A: Run frame through fast on-device motion tracking
        val motionDetections = motionTracker.analyzeFrame(bitmap)
        Log.d("HybridPipeline", "Motion detections found: ${motionDetections.size}")
        if (motionDetections.isEmpty()) return@withContext emptyList()

        val finalDetections = mutableListOf<Detection>()

        // Step B: If localized motion is found, crop the original bitmap to that specific Rect
        for (motion in motionDetections) {
            val box = motion.boundingBox
            
            val croppedBitmap = if (motion.label == "STATIC_TRIGGER") {
                Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height)
            } else {
                // Ensure crop bounds safely fit inside the bitmap dimensions
                val safeLeft = box.left.coerceAtLeast(0)
                val safeTop = box.top.coerceAtLeast(0)
                val safeWidth = box.width().coerceAtMost(bitmap.width - safeLeft)
                val safeHeight = box.height().coerceAtMost(bitmap.height - safeTop)

                if (safeWidth <= 0 || safeHeight <= 0) continue

                Bitmap.createBitmap(bitmap, safeLeft, safeTop, safeWidth, safeHeight)
            }

            // Step C: Upload ONLY the small, cropped segment to Hugging Face
            val cloudResult = cloudClassifier.analyzeFrame(croppedBitmap)
            
            // Reclaim memory for crop immediately
            croppedBitmap.recycle()

            // Step D: Map classification back to original screen coordinates 
            for (cloudDet in cloudResult) {
                Log.d("HybridPipeline", "Cloud classification result: confidence=${cloudDet.confidence}")
                finalDetections.add(
                    Detection(
                        boundingBox = box, // Original screen box returned by motion tracker (full screen or cropped rect)
                        confidence = cloudDet.confidence,
                        label = cloudDet.label
                    )
                )
            }
        }
        return@withContext finalDetections
    }
}