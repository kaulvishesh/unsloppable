package com.example.slopradar

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Rect
import android.util.Log
import org.json.JSONArray
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * A cloud-connected implementation of AIDetector that uses the Hugging Face Inference API.
 * It sends cropped image/video frames to a SOTA vision transformer model for AI classification.
 */
class HuggingFaceAIDetector(private val context: Context) : AIDetector {

    // Public Hugging Face Serverless model ID for AI Image Detection
    private val modelId = "umm-maybe/AI-image-detector"
    private val endpointUrl = "https://api-inference.huggingface.co/models/$modelId"

    override fun analyzeFrame(bitmap: Bitmap): List<Detection> {
        val width = bitmap.width
        val height = bitmap.height

        // 1. Fetch HF Token (check preferences first, fallback to BuildConfig)
        val prefs = context.getSharedPreferences("slop_radar_prefs", Context.MODE_PRIVATE)
        var token = prefs.getString("hf_api_token", "") ?: ""
        
        if (token.isEmpty()) {
            token = BuildConfig.HF_API_TOKEN
        }
        
        if (token.isEmpty()) {
            Log.w(TAG, "Hugging Face API token is missing. Inference request may fail or be rate-limited.")
        }

        // 2. Compress the bitmap into JPEG bytes to send over the network
        val outputStream = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, 75, outputStream) // 75% quality is a good balance for ML vs data size
        val jpegBytes = outputStream.toByteArray()
        Log.d(TAG, "Compressed frame size for upload: ${jpegBytes.size / 1024} KB")

        // 3. Make HTTP request to Hugging Face Serverless API
        var connection: HttpURLConnection? = null
        try {
            val url = URL(endpointUrl)
            connection = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                doOutput = true
                connectTimeout = 5000
                readTimeout = 5000
                setRequestProperty("Content-Type", "image/jpeg")
                if (token.isNotEmpty()) {
                    setRequestProperty("Authorization", "Bearer $token")
                }
            }

            // Write binary JPEG payload
            connection.outputStream.use { os ->
                os.write(jpegBytes)
                os.flush()
            }

            // Check response status
            val responseCode = connection.responseCode
            if (responseCode == HttpURLConnection.HTTP_OK) {
                val responseString = connection.inputStream.bufferedReader().use { it.readText() }
                Log.d(TAG, "Inference API response: $responseString")

                // 4. Parse Hugging Face JSON output
                // Example response: [{"label": "artificial", "score": 0.98}, {"label": "human", "score": 0.02}]
                val jsonArray = JSONArray(responseString)
                var aiScore = 0.0f

                for (i in 0 until jsonArray.length()) {
                    val obj = jsonArray.getJSONObject(i)
                    val label = obj.getString("label").lowercase()
                    val score = obj.getDouble("score").toFloat()

                    // Check labels commonly used by AI detection models ("artificial", "fake", "synthetic", "generator")
                    if (label == "artificial" || label == "fake" || label.contains("synthetic") || label.contains("ai")) {
                        aiScore = score
                    }
                }

                Log.d(TAG, "Classified AI Confidence Score: $aiScore")
                
                // Return detection bounding box covering the entire analyzed cropped container
                return listOf(
                    Detection(
                        boundingBox = Rect(0, 0, width, height),
                        confidence = aiScore,
                        label = "AI Content Detected"
                    )
                )
            } else {
                val errString = connection.errorStream?.bufferedReader()?.use { it.readText() } ?: ""
                Log.e(TAG, "Inference API returned error code $responseCode: $errString")
            }

        } catch (e: Exception) {
            Log.e(TAG, "Failed to run Hugging Face cloud inference", e)
        } finally {
            connection?.disconnect()
        }

        return emptyList()
    }

    companion object {
        private const val TAG = "HuggingFaceAIDetector"
    }
}
