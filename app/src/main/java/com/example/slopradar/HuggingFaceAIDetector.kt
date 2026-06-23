package com.example.slopradar

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Rect
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * A cloud-connected implementation of AIDetector that uses the Hugging Face Inference API.
 * It sends cropped image/video frames to a SOTA vision transformer model for AI classification.
 */
class HuggingFaceAIDetector(private val context: Context) : AIDetector {

    private val defaultModelId = "umm-maybe/AI-image-detector"

    override fun analyzeFrame(bitmap: Bitmap): List<Detection> {
        val width = bitmap.width
        val height = bitmap.height

        // 1. Fetch settings dynamically (model ID and auth token)
        val prefs = context.getSharedPreferences("slop_radar_prefs", Context.MODE_PRIVATE)
        val modelId = prefs.getString("selected_model_id", defaultModelId) ?: defaultModelId
        val endpointUrl = "https://api-inference.huggingface.co/models/$modelId"

        var token = prefs.getString("hf_api_token", "") ?: ""
        if (token.isEmpty()) {
            token = BuildConfig.HF_API_TOKEN
        }
        
        if (token.isEmpty()) {
            Log.w(TAG, "Hugging Face API token is missing. Inference request may fail or be rate-limited.")
        }

        // 2. Compress the bitmap into JPEG bytes to send over the network
        val outputStream = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, 75, outputStream)
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

                val trimmed = responseString.trim()
                var aiScore = 0.0f

                // 4. Robust JSON Parsing (Handles loading JSONObject vs result JSONArray)
                if (trimmed.startsWith("{")) {
                    val jsonObj = JSONObject(trimmed)
                    if (jsonObj.has("error")) {
                        Log.w(TAG, "Hugging Face API returned a loading warning or error: ${jsonObj.getString("error")}")
                        return emptyList()
                    }
                } else if (trimmed.startsWith("[")) {
                    val jsonArray = JSONArray(trimmed)
                    for (i in 0 until jsonArray.length()) {
                        val obj = jsonArray.getJSONObject(i)
                        val label = obj.getString("label").lowercase()
                        val score = obj.getDouble("score").toFloat()

                        if (label == "artificial" || label == "fake" || label.contains("synthetic") || label.contains("ai")) {
                            aiScore = score
                        }
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
            // Append e.message to the main log string to make it visible under filters
            Log.e(TAG, "Failed to run Hugging Face cloud inference. Error: ${e.message}", e)
        } finally {
            connection?.disconnect()
        }

        return emptyList()
    }

    companion object {
        private const val TAG = "HuggingFaceAIDetector"
    }
}
