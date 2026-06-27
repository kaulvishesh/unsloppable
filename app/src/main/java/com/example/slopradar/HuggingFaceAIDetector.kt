package com.example.slopradar

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Rect
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.util.concurrent.TimeUnit

class HuggingFaceAIDetector(private val context: Context) : AIDetector {

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .build()

    override suspend fun analyzeFrame(bitmap: Bitmap): List<Detection> = withContext(Dispatchers.IO) {
        val width = bitmap.width
        val height = bitmap.height

        val prefs = context.getSharedPreferences(Constants.PREFS_NAME, Context.MODE_PRIVATE)
        val modelId = prefs.getString(Constants.PREF_MODEL_ID, Constants.DEFAULT_MODEL_ID) ?: Constants.DEFAULT_MODEL_ID
        // Fix: Use correct router URL with /hf-inference path prefix
        val endpointUrl = "https://router.huggingface.co/hf-inference/models/$modelId"

        var token = prefs.getString(Constants.PREF_HF_TOKEN, "") ?: ""
        if (token.isEmpty()) token = BuildConfig.HF_API_TOKEN

        if (token.isEmpty()) {
            Log.w(TAG, "Hugging Face API Token is empty! Detection will likely fail.")
        } else {
            val masked = if (token.length > 8) "${token.take(4)}...${token.takeLast(4)}" else "****"
            Log.d(TAG, "Using Token: $masked (len=${token.length})")
        }

        val outputStream = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, 75, outputStream)
        val jpegBytes = outputStream.toByteArray()

        val requestBuilder = Request.Builder()
            .url(endpointUrl)
            .post(jpegBytes.toRequestBody("image/jpeg".toMediaType()))

        if (token.isNotEmpty()) {
            requestBuilder.addHeader("Authorization", "Bearer $token")
        }

        var attempts = 0
        val maxAttempts = 6 // Initial attempt + 5 retries
        val delayMs = 4000L

        while (attempts < maxAttempts) {
            try {
                client.newCall(requestBuilder.build()).execute().use { response ->
                    if (response.isSuccessful) {
                        val responseString = response.body?.string() ?: ""
                        val trimmed = responseString.trim()
                        var aiScore = 0.0f

                        if (trimmed.startsWith("{")) {
                            val jsonObj = JSONObject(trimmed)
                            if (jsonObj.has("error")) {
                                val errorMsg = jsonObj.getString("error")
                                Log.w(TAG, "Hugging Face API returned error: $errorMsg")
                                if (errorMsg.contains("loading", ignoreCase = true)) {
                                    attempts++
                                    Log.i(TAG, "Model is loading. Retrying attempt $attempts of $maxAttempts after ${delayMs}ms...")
                                    kotlinx.coroutines.delay(delayMs)
                                    return@use
                                }
                                return@withContext emptyList()
                            }
                        } else if (trimmed.startsWith("[")) {
                            val jsonArray = JSONArray(trimmed)
                            for (i in 0 until jsonArray.length()) {
                                val obj = jsonArray.getJSONObject(i)
                                val label = obj.getString("label").lowercase()
                                val score = obj.getDouble("score").toFloat()
                                
                                Log.d(TAG, "API Result: label=$label, score=$score")

                                if (label == "artificial" || label == "fake" || label.contains("synthetic") || label.contains("ai")) {
                                    aiScore = score
                                }
                            }
                        }

                        return@withContext listOf(
                            Detection(
                                boundingBox = Rect(0, 0, width, height),
                                confidence = aiScore,
                                label = Constants.DETECTION_LABEL
                            )
                        )
                    } else {
                        Log.e(TAG, "Inference API returned error code ${response.code}")
                        if (response.code == 429 || response.code == 503 || response.code == 504) {
                            attempts++
                            Log.i(TAG, "Server error (${response.code}). Retrying attempt $attempts of $maxAttempts after ${delayMs}ms...")
                            kotlinx.coroutines.delay(delayMs)
                            return@use
                        }
                        return@withContext emptyList()
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to run Hugging Face cloud inference.", e)
                attempts++
                if (attempts < maxAttempts) {
                    Log.i(TAG, "Network exception. Retrying attempt $attempts of $maxAttempts after ${delayMs}ms...")
                    kotlinx.coroutines.delay(delayMs)
                }
            }
        }
        
        return@withContext emptyList()
    }

    companion object {
        private const val TAG = "HuggingFaceAIDetector"
    }
}