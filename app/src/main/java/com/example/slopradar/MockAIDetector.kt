package com.example.slopradar

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Rect
import android.util.Log

/**
 * A mock object-detector that uses lightweight frame-difference analysis (motion detection)
 * to locate active videos on screen. It filters out screen scrolling and ignores static screens.
 */
class MockAIDetector(private val context: Context) : AIDetector {
    
    // Cache for frame difference calculation
    private var lastTinyBitmap: Bitmap? = null

    override fun analyzeFrame(bitmap: Bitmap): List<Detection> {
        val width = bitmap.width
        val height = bitmap.height
        
        // Simulating the 200ms ML model inference latency
        try {
            Thread.sleep(200)
        } catch (e: InterruptedException) {
            Log.e(TAG, "Mock inference interrupted", e)
            Thread.currentThread().interrupt()
            return emptyList()
        }

        // Downscale to a tiny bitmap to calculate frame diff (motion detection)
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
            
            // Loop through the downscaled pixels to locate changes
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
                    
                    // L1 distance between pixel colors
                    val diff = Math.abs(r1 - r2) + Math.abs(g1 - g2) + Math.abs(b1 - b2)
                    if (diff > 35) { // Threshold for individual pixel motion
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
            
            // Heuristic to filter out scrolling:
            // 1. If more than 35% of the screen pixels are changing, it's likely a global screen scroll.
            // 2. If the moving bounding box spans more than 80% of the screen height, it's a vertical scroll.
            val isScrolling = motionPercent > 0.35f || (maxY - minY) > (tinyHeight * 0.80f)
            
            if (isScrolling) {
                Log.d(TAG, "Screen motion detected, but filtered as SCROLLING (Percent: $motionPercent, Height: ${maxY - minY}). Ignoring.")
            } else if (movingPixelsCount > 80 && minX < maxX && minY < maxY) {
                // Scale coordinates from tiny space back to captured bitmap space
                val scaleX = width.toFloat() / tinyWidth
                val scaleY = height.toFloat() / tinyHeight
                
                val left = (minX * scaleX).toInt().coerceAtLeast(0)
                val top = (minY * scaleY).toInt().coerceAtLeast(0)
                val right = (maxX * scaleX).toInt().coerceAtMost(width)
                val bottom = (maxY * scaleY).toInt().coerceAtMost(height)
                
                Log.d(TAG, "Active Video detected! Bounds: L=$left, T=$top, R=$right, B=$bottom")
                
                motionDetection = Detection(
                    boundingBox = Rect(left, top, right, bottom),
                    confidence = 0.95f, // Mock high confidence for active video
                    label = "AI Video Content"
                )
            }
        }
        
        // Recycle the cached tiny bitmap to prevent accumulation in memory
        prevTiny?.recycle()
        lastTinyBitmap = currentTiny

        val detections = mutableListOf<Detection>()
        if (motionDetection != null) {
            detections.add(motionDetection)
        }
        
        return detections
    }

    companion object {
        private const val TAG = "MockAIDetector"
    }
}
