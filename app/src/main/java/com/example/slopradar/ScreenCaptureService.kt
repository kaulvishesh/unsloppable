package com.example.slopradar

import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.graphics.Rect
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.util.DisplayMetrics
import android.util.Log
import android.view.WindowManager
import androidx.core.app.NotificationCompat

/**
 * Foreground Service that captures the screen and analyzes frames with AIDetector.
 */
class ScreenCaptureService : Service() {
    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null

    private var backgroundThread: HandlerThread? = null
    private var backgroundHandler: Handler? = null

    private lateinit var aiDetector: AIDetector
    private lateinit var floatingWindowManager: FloatingWindowManager

    private var lastProcessedTimeMs = 0L

    private val mediaProjectionCallback = object : MediaProjection.Callback() {
        override fun onStop() {
            Log.d(TAG, "MediaProjection stopped by system. Stopping service.")
            stopSelf()
        }
    }

    private val imageAvailableListener = ImageReader.OnImageAvailableListener { reader ->
        val currentTime = System.currentTimeMillis()
        
        // Throttling to 1 Frame Per Second (1000 ms)
        if (currentTime - lastProcessedTimeMs < 1000L) {
            val img = try { reader.acquireLatestImage() } catch (e: Exception) { null }
            img?.close()
            return@OnImageAvailableListener
        }

        val image = try {
            reader.acquireLatestImage()
        } catch (e: Exception) {
            Log.e(TAG, "Error acquiring latest image from reader", e)
            null
        } ?: return@OnImageAvailableListener

        lastProcessedTimeMs = currentTime

        try {
            val startTime = System.currentTimeMillis()
            
            // Convert Image to Bitmap
            val bitmap = convertImageToBitmap(image)
            if (bitmap != null) {
                // Log memory stats
                val runtime = Runtime.getRuntime()
                val usedMemory = (runtime.totalMemory() - runtime.freeMemory()) / (1024 * 1024)
                val maxMemory = runtime.maxMemory() / (1024 * 1024)
                Log.d(TAG, "Current RAM usage: ${usedMemory}MB / ${maxMemory}MB")

                // Analyze frame
                val inferenceStart = System.currentTimeMillis()
                val detections = aiDetector.analyzeFrame(bitmap)
                val inferenceDuration = System.currentTimeMillis() - inferenceStart
                val totalDuration = System.currentTimeMillis() - startTime
                
                Log.d(TAG, "Frame conversion & analysis took ${totalDuration}ms. ML Inference: ${inferenceDuration}ms. Detections count: ${detections.size}")

                // Load the sensitivity threshold dynamically from SharedPreferences (default: 0.85)
                val prefs = getSharedPreferences("slop_radar_prefs", Context.MODE_PRIVATE)
                val threshold = prefs.getFloat("sensitivity_threshold", 0.85f)

                // Filter for high-confidence AI detections (> threshold)
                val aiDetections = detections.filter { it.confidence > threshold }

                if (aiDetections.isNotEmpty()) {
                    // Map coordinate rectangles from 50% scale back to physical screen size
                    val windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
                    val metrics = DisplayMetrics()
                    @Suppress("DEPRECATION")
                    windowManager.defaultDisplay.getRealMetrics(metrics)

                    val screenWidth = metrics.widthPixels
                    val screenHeight = metrics.heightPixels

                    val scaleX = screenWidth.toFloat() / image.width
                    val scaleY = screenHeight.toFloat() / image.height

                    val mappedRects = aiDetections.map { det ->
                        val box = det.boundingBox
                        Rect(
                            (box.left * scaleX).toInt(),
                            (box.top * scaleY).toInt(),
                            (box.right * scaleX).toInt(),
                            (box.bottom * scaleY).toInt()
                        )
                    }

                    Log.d(TAG, "AI Content Detected! Mapped ${mappedRects.size} regions on screen. Triggering warning overlays.")
                    floatingWindowManager.showHighlights(mappedRects)
                } else {
                    // Screen is clean or AI content scrolled away, clear highlights immediately
                    floatingWindowManager.clearHighlights()
                }

                // Immediately recycle bitmap to prevent memory accumulation
                bitmap.recycle()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error in frame processing loop", e)
        } finally {
            // Must close the image to release buffer back to the reader queue
            image.close()
        }
    }

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "Service onCreate()")
        
        // --- CHOOSE YOUR DETECTOR ENGINE HERE ---
        
        // Option 1: Hugging Face Serverless Cloud API (Real SOTA ViT Model)
        aiDetector = HuggingFaceAIDetector(this)
        
        // Option 2: Local TensorFlow Lite Model (Requires slop_detector.tflite in assets)
        // aiDetector = TFLiteAIDetector(this)
        
        // Option 3: Local Mock Detector (Motion tracking coordinates simulator)
        // aiDetector = MockAIDetector(this)
        
        floatingWindowManager = FloatingWindowManager(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action
        Log.d(TAG, "Service received command with action: $action")
        
        if (action == ACTION_START) {
            val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, Activity.RESULT_CANCELED)
            val data = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                intent.getParcelableExtra(EXTRA_RESULT_DATA, Intent::class.java)
            } else {
                @Suppress("DEPRECATION")
                intent.getParcelableExtra(EXTRA_RESULT_DATA)
            }

            if (data != null && resultCode == Activity.RESULT_OK) {
                startCaptureService(resultCode, data)
            } else {
                Log.e(TAG, "Cannot start capture. Invalid resultCode or data intent.")
                stopSelf()
            }
        } else if (action == ACTION_STOP) {
            stopSelf()
        }

        return START_NOT_STICKY
    }

    private fun startCaptureService(resultCode: Int, data: Intent) {
        if (isRunning) {
            Log.d(TAG, "Service already running. Ignoring start command.")
            return
        }
        isRunning = true

        Log.d(TAG, "Starting screen capture foreground service...")
        createNotificationChannel()
        val notification = buildNotification()

        // Requirements for Media Projection Foreground Service starting from Android 10/14
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }

        // Run ML analysis and processing on a dedicated background HandlerThread
        backgroundThread = HandlerThread("SlopRadarML").apply { start() }
        backgroundHandler = Handler(backgroundThread!!.looper)

        val mediaProjectionManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        mediaProjection = mediaProjectionManager.getMediaProjection(resultCode, data)
        mediaProjection?.registerCallback(mediaProjectionCallback, backgroundHandler)

        setupVirtualDisplay()
    }

    private fun setupVirtualDisplay() {
        val windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val metrics = DisplayMetrics()
        @Suppress("DEPRECATION")
        windowManager.defaultDisplay.getRealMetrics(metrics)

        val screenWidth = metrics.widthPixels
        val screenHeight = metrics.heightPixels
        val screenDensity = metrics.densityDpi

        // Performance Optimization: Capture at 50% scale to reduce memory footprint and latency
        val scaleFactor = 0.5f
        val captureWidth = (screenWidth * scaleFactor).toInt()
        val captureHeight = (screenHeight * scaleFactor).toInt()

        Log.d(TAG, "Virtual Display Setup. Physical: ${screenWidth}x${screenHeight}, Capture Resolution: ${captureWidth}x${captureHeight}")

        // Initialize ImageReader using PixelFormat.RGBA_8888 which aligns with Bitmap
        imageReader = ImageReader.newInstance(
            captureWidth,
            captureHeight,
            PixelFormat.RGBA_8888,
            2 // Double buffering
        )

        imageReader?.setOnImageAvailableListener(imageAvailableListener, backgroundHandler)

        val flags = DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR
        virtualDisplay = mediaProjection?.createVirtualDisplay(
            "SlopRadarDisplay",
            captureWidth,
            captureHeight,
            screenDensity,
            flags,
            imageReader?.surface,
            null,
            backgroundHandler
        )
    }

    private fun convertImageToBitmap(image: Image): Bitmap? {
        val planes = image.planes
        val buffer = planes[0].buffer ?: return null
        
        // Rewind buffer to read from start
        buffer.rewind()

        val width = image.width
        val height = image.height
        val pixelStride = planes[0].pixelStride
        val rowStride = planes[0].rowStride
        val rowPadding = rowStride - pixelStride * width

        // Create temporary bitmap that includes row padding
        val bitmap = Bitmap.createBitmap(
            width + rowPadding / pixelStride,
            height,
            Bitmap.Config.ARGB_8888
        )
        bitmap.copyPixelsFromBuffer(buffer)

        // If there was padding, crop it to the clean width of the captured screen
        return if (rowPadding > 0) {
            val cropped = Bitmap.createBitmap(bitmap, 0, 0, width, height)
            if (cropped != bitmap) {
                bitmap.recycle() // Recycle temporary bitmap to prevent memory leak
            }
            cropped
        } else {
            bitmap
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Screen Analysis Service",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Notifies that SlopRadar is scanning screen in real-time."
            }
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(): Notification {
        val intent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("SlopRadar Shield Active")
            .setContentText("Scanning screen in background...")
            .setSmallIcon(android.R.drawable.ic_menu_compass) // Default system icon
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()
    }

    override fun onDestroy() {
        Log.d(TAG, "onDestroy() - Stopping capture and cleaning up allocations")
        
        floatingWindowManager.clearHighlights()

        virtualDisplay?.release()
        virtualDisplay = null

        imageReader?.setOnImageAvailableListener(null, null)
        imageReader?.close()
        imageReader = null

        mediaProjection?.unregisterCallback(mediaProjectionCallback)
        mediaProjection?.stop()
        mediaProjection = null

        backgroundThread?.quitSafely()
        backgroundThread = null
        backgroundHandler = null

        isRunning = false
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? {
        return null
    }

    companion object {
        private const val TAG = "ScreenCaptureService"
        private const val NOTIFICATION_ID = 888
        private const val CHANNEL_ID = "slop_radar_channel"

        const val ACTION_START = "com.example.slopradar.action.START"
        const val ACTION_STOP = "com.example.slopradar.action.STOP"

        const val EXTRA_RESULT_CODE = "com.example.slopradar.extra.RESULT_CODE"
        const val EXTRA_RESULT_DATA = "com.example.slopradar.extra.RESULT_DATA"

        @Volatile
        var isRunning = false
    }
}
