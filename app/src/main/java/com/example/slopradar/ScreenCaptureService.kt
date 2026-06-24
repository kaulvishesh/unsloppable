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
import android.graphics.Canvas
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
import kotlinx.coroutines.*
import java.util.concurrent.atomic.AtomicBoolean

class ScreenCaptureService : Service() {
    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null

    private var backgroundThread: HandlerThread? = null
    private var backgroundHandler: Handler? = null
    
    private val serviceScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private val isProcessing = AtomicBoolean(false)

    // Pre-allocated Buffers to prevent memory churn
    private var reusablePaddedBitmap: Bitmap? = null
    private var reusableCleanBitmap: Bitmap? = null
    private var canvasClean: Canvas? = null

    private lateinit var aiDetector: AIDetector
    private lateinit var floatingWindowManager: FloatingWindowManager

    private var lastProcessedTimeMs = 0L

    private val mediaProjectionCallback = object : MediaProjection.Callback() {
        override fun onStop() {
            stopSelf()
        }
    }

    private val imageAvailableListener = ImageReader.OnImageAvailableListener { reader ->
        val currentTime = System.currentTimeMillis()
        if (currentTime - lastProcessedTimeMs < 1000L) {
            val img = try { reader.acquireLatestImage() } catch (e: Exception) { null }
            img?.close()
            return@OnImageAvailableListener
        }

        val image = try { reader.acquireLatestImage() } catch (e: Exception) { null } ?: return@OnImageAvailableListener
        
        Log.d(TAG, "Frame captured at $currentTime")

        // Skip frames safely if AI is still running (prevents memory collisions & coroutine pileups)
        if (!isProcessing.compareAndSet(false, true)) {
            image.close()
            return@OnImageAvailableListener
        }
        
        lastProcessedTimeMs = currentTime

        // Run synchronously to safely extract buffer before closing image
        val bitmap = convertImageToBitmap(image)
        image.close() 

        if (bitmap == null) {
            isProcessing.set(false)
            return@OnImageAvailableListener
        }

        serviceScope.launch {
            try {
                val detections = aiDetector.analyzeFrame(bitmap)

                val prefs = getSharedPreferences(Constants.PREFS_NAME, Context.MODE_PRIVATE)
                val threshold = prefs.getFloat(Constants.PREF_SENSITIVITY, Constants.DEFAULT_SENSITIVITY)

                val aiDetections = detections.filter { it.confidence > threshold }

                withContext(Dispatchers.Main) {
                    if (aiDetections.isNotEmpty()) {
                        val windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
                        val metrics = DisplayMetrics()
                        @Suppress("DEPRECATION")
                        windowManager.defaultDisplay.getRealMetrics(metrics)

                        val screenWidth = metrics.widthPixels
                        val screenHeight = metrics.heightPixels

                        val scaleX = screenWidth.toFloat() / bitmap.width
                        val scaleY = screenHeight.toFloat() / bitmap.height

                        val mappedRects = aiDetections.map { det ->
                            val box = det.boundingBox
                            Rect(
                                (box.left * scaleX).toInt(),
                                (box.top * scaleY).toInt(),
                                (box.right * scaleX).toInt(),
                                (box.bottom * scaleY).toInt()
                            )
                        }
                        floatingWindowManager.showHighlights(mappedRects)
                    } else {
                        floatingWindowManager.clearHighlights()
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error in frame processing loop", e)
            } finally {
                isProcessing.set(false)
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "onCreate: Service created")
        
        // Utilize the newly built Architectural Pipeline (Fix 1)
        aiDetector = HybridPipelineDetector(this)
        
        floatingWindowManager = FloatingWindowManager(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action
        Log.d(TAG, "onStartCommand: action=$action")
        if (action == ACTION_START) {
            val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, Activity.RESULT_CANCELED)
            val data = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                intent.getParcelableExtra(EXTRA_RESULT_DATA, Intent::class.java)
            } else {
                @Suppress("DEPRECATION")
                intent.getParcelableExtra(EXTRA_RESULT_DATA)
            }

            Log.d(TAG, "onStartCommand: resultCode=$resultCode, data=$data")
            if (data != null && resultCode == Activity.RESULT_OK) {
                startCaptureService(resultCode, data)
            } else {
                Log.e(TAG, "onStartCommand: Invalid data or resultCode")
                stopSelf()
            }
        } else if (action == ACTION_STOP) {
            stopSelf()
        }
        return START_NOT_STICKY
    }

    private fun startCaptureService(resultCode: Int, data: Intent) {
        Log.d(TAG, "startCaptureService: isRunning=$isRunning")
        if (isRunning) return
        isRunning = true

        createNotificationChannel()
        val notification = buildNotification()

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
            Log.d(TAG, "startForeground successful")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start foreground service", e)
            isRunning = false
            return
        }

        backgroundThread = HandlerThread("SlopRadarML").apply { start() }
        backgroundHandler = Handler(backgroundThread!!.looper)

        val mediaProjectionManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        mediaProjection = mediaProjectionManager.getMediaProjection(resultCode, data)
        mediaProjection?.registerCallback(mediaProjectionCallback, backgroundHandler)

        Log.d(TAG, "MediaProjection obtained: $mediaProjection")
        setupVirtualDisplay()
    }

    private fun setupVirtualDisplay() {
        val windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val metrics = DisplayMetrics()
        @Suppress("DEPRECATION")
        windowManager.defaultDisplay.getRealMetrics(metrics)

        val captureWidth = (metrics.widthPixels * 0.5f).toInt()
        val captureHeight = (metrics.heightPixels * 0.5f).toInt()
        
        Log.d(TAG, "setupVirtualDisplay: ${captureWidth}x${captureHeight}, density=${metrics.densityDpi}")

        imageReader = ImageReader.newInstance(captureWidth, captureHeight, PixelFormat.RGBA_8888, 2)
        imageReader?.setOnImageAvailableListener(imageAvailableListener, backgroundHandler)

        virtualDisplay = mediaProjection?.createVirtualDisplay(
            "SlopRadarDisplay", captureWidth, captureHeight, metrics.densityDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, imageReader?.surface, null, backgroundHandler
        )
        Log.d(TAG, "VirtualDisplay created: $virtualDisplay")
    }

    // Zero-Allocation Conversion using persistent Canvas
    private fun convertImageToBitmap(image: Image): Bitmap? {
        val planes = image.planes
        val buffer = planes[0].buffer ?: return null
        buffer.rewind()

        val width = image.width
        val height = image.height
        val pixelStride = planes[0].pixelStride
        val rowStride = planes[0].rowStride
        val rowPadding = rowStride - pixelStride * width
        val bufferWidth = width + rowPadding / pixelStride

        if (reusablePaddedBitmap == null || reusablePaddedBitmap!!.width != bufferWidth || reusablePaddedBitmap!!.height != height) {
            reusablePaddedBitmap?.recycle()
            reusablePaddedBitmap = Bitmap.createBitmap(bufferWidth, height, Bitmap.Config.ARGB_8888)
        }
        reusablePaddedBitmap!!.copyPixelsFromBuffer(buffer)

        return if (rowPadding > 0) {
            if (reusableCleanBitmap == null || reusableCleanBitmap!!.width != width || reusableCleanBitmap!!.height != height) {
                reusableCleanBitmap?.recycle()
                reusableCleanBitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                canvasClean = Canvas(reusableCleanBitmap!!)
            }
            // Draw padded image exactly at 0,0 - implicitly cropping extra right-side buffer
            canvasClean!!.drawBitmap(reusablePaddedBitmap!!, 0f, 0f, null)
            reusableCleanBitmap
        } else {
            reusablePaddedBitmap
        }
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(CHANNEL_ID, "Screen Analysis Service", NotificationManager.IMPORTANCE_LOW)
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.createNotificationChannel(channel)
    }

    private fun buildNotification(): Notification {
        val pendingIntent = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("SlopRadar Shield Active")
            .setContentText("Scanning screen in background...")
            .setSmallIcon(android.R.drawable.ic_menu_compass)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()
    }

    override fun onDestroy() {
        floatingWindowManager.clearHighlights()

        serviceScope.cancel()
        reusablePaddedBitmap?.recycle()
        reusablePaddedBitmap = null
        reusableCleanBitmap?.recycle()
        reusableCleanBitmap = null

        virtualDisplay?.release()
        imageReader?.setOnImageAvailableListener(null, null)
        imageReader?.close()
        mediaProjection?.unregisterCallback(mediaProjectionCallback)
        mediaProjection?.stop()
        backgroundThread?.quitSafely()
        
        isRunning = false
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

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