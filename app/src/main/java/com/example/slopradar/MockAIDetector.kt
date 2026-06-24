package com.example.slopradar

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Rect
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

class MockAIDetector(private val context: Context) : AIDetector {
    
    private var lastTinyBitmap: Bitmap? = null
    private var hasAnalyzedStaticState = false

    override suspend fun analyzeFrame(bitmap: Bitmap): List<Detection> = withContext(Dispatchers.Default) {
        val width = bitmap.width
        val height = bitmap.height
        Log.d(TAG, "analyzeFrame: ${width}x${height}")
        
        delay(200) // Replaces Thread.sleep(200)

        val tinyWidth = 36
        val tinyHeight = 64
        val currentTiny = Bitmap.createScaledBitmap(bitmap, tinyWidth, tinyHeight, false)
        
        val prevTiny = lastTinyBitmap
        var motionDetection: Detection? = null
        
        if (prevTiny != null && prevTiny.width == tinyWidth && prevTiny.height == tinyHeight) {
            val pixelsCurrent = IntArray(tinyWidth * tinyHeight)
            val pixelsPrev = IntArray(tinyWidth * tinyHeight)
            
            currentTiny.getPixels(pixelsCurrent, 0, tinyWidth, 0, 0, tinyWidth, tinyHeight)
            prevTiny.getPixels(pixelsPrev, 0, tinyWidth, 0, 0, tinyWidth, tinyHeight)
            
            var minX = tinyWidth
            var maxX = -1
            var minY = tinyHeight
            var maxY = -1
            var movingPixelsCount = 0
            
            for (y in 0 until tinyHeight) {
                for (x in 0 until tinyWidth) {
                    val idx = y * tinyWidth + x
                    val c1 = pixelsCurrent[idx]
                    val c2 = pixelsPrev[idx]
                    
                    val r1 = (c1 shr 16) and 0xFF
                    val g1 = (c1 shr 8) and 0xFF
                    val b1 = c1 and 0xFF
                    
                    val r2 = (c2 shr 16) and 0xFF
                    val g2 = (c2 shr 8) and 0xFF
                    val b2 = c2 and 0xFF
                    
                    val diff = Math.abs(r1 - r2) + Math.abs(g1 - g2) + Math.abs(b1 - b2)
                    if (diff > 35) {
                        movingPixelsCount++
                        if (x < minX) minX = x
                        if (x > maxX) maxX = x
                        if (y < minY) minY = y
                        if (y > maxY) maxY = y
                    }
                }
            }
            
            val totalPixels = tinyWidth * tinyHeight
            val motionPercent = movingPixelsCount.toFloat() / totalPixels
            
            // Lenient filtering for testing: Only filter if motion is very widespread (global scroll)
            val isScrolling = motionPercent > 0.70f || (maxY - minY) > (tinyHeight * 0.94f)
            
            Log.d(TAG, "Analysis: movingPixelsCount=$movingPixelsCount, motionPercent=${String.format("%.2f", motionPercent)}, isScrolling=$isScrolling, hasAnalyzedStaticState=$hasAnalyzedStaticState, bounds=[$minX, $minY, $maxX, $maxY]")

            if (isScrolling) {
                Log.d(TAG, "Screen motion detected, but filtered as SCROLLING. Ignoring.")
                hasAnalyzedStaticState = false
            } else if (movingPixelsCount > 40 && minX < maxX && minY < maxY) {
                hasAnalyzedStaticState = false
                val scaleX = width.toFloat() / tinyWidth
                val scaleY = height.toFloat() / tinyHeight
                
                val left = (minX * scaleX).toInt().coerceAtLeast(0)
                val top = (minY * scaleY).toInt().coerceAtLeast(0)
                val right = (maxX * scaleX).toInt().coerceAtMost(width)
                val bottom = (maxY * scaleY).toInt().coerceAtMost(height)
                
                motionDetection = Detection(
                    boundingBox = Rect(left, top, right, bottom),
                    confidence = 0.95f,
                    label = Constants.DETECTION_LABEL
                )
            } else if (movingPixelsCount < 15) {
                if (!hasAnalyzedStaticState) {
                    Log.d(TAG, "Screen is static. Triggering full-screen analysis.")
                    motionDetection = Detection(
                        boundingBox = Rect(0, 0, width, height),
                        confidence = 1.0f,
                        label = "STATIC_TRIGGER"
                    )
                    hasAnalyzedStaticState = true
                }
            } else if (movingPixelsCount > 40) {
                hasAnalyzedStaticState = false
            }
        } else {
            // First frame, trigger static analysis to handle initial screen state
            Log.d(TAG, "First frame. Triggering initial screen analysis.")
            motionDetection = Detection(
                boundingBox = Rect(0, 0, width, height),
                confidence = 1.0f,
                label = "STATIC_TRIGGER"
            )
            hasAnalyzedStaticState = true
        }
        
        prevTiny?.recycle()
        lastTinyBitmap = currentTiny

        val detections = mutableListOf<Detection>()
        if (motionDetection != null) {
            detections.add(motionDetection)
        }
        
        return@withContext detections
    }

    companion object {
        private const val TAG = "MockAIDetector"
    }
}