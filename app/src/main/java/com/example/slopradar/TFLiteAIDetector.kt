package com.example.slopradar

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Rect
import android.util.Log
import org.tensorflow.lite.Interpreter
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel

/**
 * A production-ready TensorFlow Lite implementation of the AIDetector.
 * It loads a local model 'slop_detector.tflite' from the assets directory
 * and runs object detection on the captured screen frames.
 */
class TFLiteAIDetector(context: Context) : AIDetector {
    private var interpreter: Interpreter? = null
    
    // Configurable model input dimensions (typically 300x300 for SSD MobileNet or 320x320 for YOLO)
    private val inputSize = 300 
    
    // Class index for AI-generated slop as defined during your model training (e.g., 1)
    private val aiSlopClassId = 1 

    init {
        try {
            // Loads 'slop_detector.tflite' from assets
            interpreter = Interpreter(loadModelFile(context, "slop_detector.tflite"))
            Log.d(TAG, "TFLite interpreter loaded successfully.")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize TFLite interpreter. Make sure 'slop_detector.tflite' exists in assets.", e)
        }
    }

    override fun analyzeFrame(bitmap: Bitmap): List<Detection> {
        val tflite = interpreter ?: return emptyList()

        val width = bitmap.width
        val height = bitmap.height

        // 1. Preprocess: Resize bitmap to model's expected input dimensions
        val resizedBitmap = Bitmap.createScaledBitmap(bitmap, inputSize, inputSize, true)
        
        // 2. Preprocess: Convert resized bitmap to float ByteBuffer
        // 4 bytes (Float) * inputSize * inputSize * 3 channels (RGB)
        val byteBuffer = ByteBuffer.allocateDirect(4 * inputSize * inputSize * 3).apply {
            order(ByteOrder.nativeOrder())
        }
        
        val intValues = IntArray(inputSize * inputSize)
        resizedBitmap.getPixels(intValues, 0, resizedBitmap.width, 0, 0, resizedBitmap.width, resizedBitmap.height)
        
        byteBuffer.rewind()
        for (pixelValue in intValues) {
            // Extract RGB values and normalize to [0.0, 1.0] (or custom ranges matching training)
            val r = ((pixelValue shr 16) and 0xFF) / 255.0f
            val g = ((pixelValue shr 8) and 0xFF) / 255.0f
            val b = (pixelValue and 0xFF) / 255.0f
            
            byteBuffer.putFloat(r)
            byteBuffer.putFloat(g)
            byteBuffer.putFloat(b)
        }

        // 3. Prepare Outputs (Based on standard COCO SSD MobileNet TFLite structure)
        // Output 0: Locations [1, 10, 4] -> 10 bounding boxes with [top, left, bottom, right] values
        val outputBoxes = Array(1) { Array(10) { FloatArray(4) } }
        // Output 1: Classes [1, 10] -> 10 class IDs
        val outputClasses = Array(1) { FloatArray(10) }
        // Output 2: Scores [1, 10] -> 10 confidence scores
        val outputScores = Array(1) { FloatArray(10) }
        // Output 3: NumDetections [1] -> Float containing count of detections
        val numDetections = FloatArray(1)

        val outputs = mapOf(
            0 to outputBoxes,
            1 to outputClasses,
            2 to outputScores,
            3 to numDetections
        )

        // 4. Run inference
        try {
            tflite.runForMultipleInputsOutputs(arrayOf(byteBuffer), outputs)
        } catch (e: Exception) {
            Log.e(TAG, "Error executing TFLite model inference", e)
            resizedBitmap.recycle()
            return emptyList()
        }

        // Clean up preprocessed bitmap immediately to free RAM
        resizedBitmap.recycle()

        // 5. Parse output predictions
        val detections = mutableListOf<Detection>()
        val count = numDetections[0].toInt().coerceAtMost(10)
        
        for (i in 0 until count) {
            val score = outputScores[0][i]
            val classId = outputClasses[0][i].toInt()

            // Filter out low confidence detections or non-slop content
            if (score > 0.50f && classId == aiSlopClassId) {
                // TFLite bounding boxes are returned normalized in [0.0, 1.0] as [top, left, bottom, right]
                val topNormalized = outputBoxes[0][i][0]
                val leftNormalized = outputBoxes[0][i][1]
                val bottomNormalized = outputBoxes[0][i][2]
                val rightNormalized = outputBoxes[0][i][3]

                // Map normalized coordinates back to the input captured frame scale
                val left = (leftNormalized * width).toInt().coerceAtLeast(0)
                val top = (topNormalized * height).toInt().coerceAtLeast(0)
                val right = (rightNormalized * width).toInt().coerceAtMost(width)
                val bottom = (bottomNormalized * height).toInt().coerceAtMost(height)

                detections.add(
                    Detection(
                        boundingBox = Rect(left, top, right, bottom),
                        confidence = score,
                        label = "AI Content Detected"
                    )
                )
            }
        }

        return detections
    }

    private fun loadModelFile(context: Context, modelName: String): ByteBuffer {
        val fileDescriptor = context.assets.openFd(modelName)
        val inputStream = FileInputStream(fileDescriptor.fileDescriptor)
        val fileChannel = inputStream.channel
        val startOffset = fileDescriptor.startOffset
        val declaredLength = fileDescriptor.declaredLength
        return fileChannel.map(FileChannel.MapMode.READ_ONLY, startOffset, declaredLength)
    }

    companion object {
        private const val TAG = "TFLiteAIDetector"
    }
}
