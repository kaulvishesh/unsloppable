package com.example.slopradar

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.TextView
import androidx.core.graphics.toColorInt

/**
 * Controller to show and hide high-quality, animated warning highlights on the screen.
 * Dynamically reconciles (diffs) active windows to prevent flickering and transparency issues.
 */
class FloatingWindowManager(private val context: Context) {

    private val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val mainHandler = Handler(Looper.getMainLooper())
    
    // Track active overlay views
    private val activeViews = mutableListOf<View>()
    
    private val hideRunnable = Runnable { clearHighlights() }

    /**
     * Highlights a list of detected AI content coordinates on the screen.
     * Thread-safe.
     */
    fun showHighlights(rects: List<Rect>) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            showHighlightsInternal(rects)
        } else {
            mainHandler.post { showHighlightsInternal(rects) }
        }
    }

    /**
     * Clears all active highlight overlays and stops their animators.
     * Thread-safe.
     */
    fun clearHighlights() {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            clearHighlightsInternal()
        } else {
            mainHandler.post { clearHighlightsInternal() }
        }
    }

    /**
     * Internal implementation of showHighlights. MUST run on the main thread.
     * Compares new rectangles with active views to reuse overlays in-place.
     */
    private fun showHighlightsInternal(rects: List<Rect>) {
        // Cancel the auto-hide timer since we have received a fresh update
        mainHandler.removeCallbacks(hideRunnable)
        
        val newCount = rects.size
        val oldCount = activeViews.size

        Log.d(TAG, "showHighlights - Old Count: $oldCount, New Count: $newCount")

        // 1. Remove excess views if we have fewer detected items in this frame
        if (oldCount > newCount) {
            Log.d(TAG, "Removing ${oldCount - newCount} excess overlay windows")
            for (i in (oldCount - 1) downTo newCount) {
                val view = activeViews.removeAt(i)
                removeHighlightView(view)
            }
        }

        // 2. Reconcile and update/add remaining views
        for (i in 0 until newCount) {
            val rect = rects[i]
            val width = rect.width()
            val height = rect.height()
            if (width <= 0 || height <= 0) continue

            if (i < activeViews.size) {
                // UPDATE: Reuse the existing overlay window to avoid re-triggering fade animations
                val view = activeViews[i]
                val params = view.layoutParams as WindowManager.LayoutParams
                
                params.x = rect.left
                params.y = rect.top
                params.width = width
                params.height = height
                
                try {
                    windowManager.updateViewLayout(view, params)
                } catch (e: Exception) {
                    Log.e(TAG, "Error updating layout for view index $i", e)
                }
            } else {
                // ADD: Create a new overlay window since we have more detections
                val highlightView = createHighlightView()
                
                val params = WindowManager.LayoutParams(
                    width,
                    height,
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or 
                            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or 
                            WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
                    PixelFormat.TRANSLUCENT
                ).apply {
                    gravity = Gravity.TOP or Gravity.START
                    x = rect.left
                    y = rect.top
                    // Enter/Exit transition is only triggered when first added or finally removed
                    windowAnimations = android.R.style.Animation_Toast
                    
                    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
                        flags = flags or WindowManager.LayoutParams.FLAG_BLUR_BEHIND
                        blurBehindRadius = 45
                    }
                }
                
                try {
                    windowManager.addView(highlightView, params)
                    activeViews.add(highlightView)
                    startPulsingAnimation(highlightView)
                } catch (e: Exception) {
                    Log.e(TAG, "Error adding overlay window to screen", e)
                }
            }
        }

        // Schedule auto-hide timer for 3 seconds
        mainHandler.postDelayed(hideRunnable, 3000)
    }

    /**
     * Internal implementation of clearHighlights. MUST run on the main thread.
     */
    private fun clearHighlightsInternal() {
        Log.d(TAG, "clearHighlightsInternal - Clearing all ${activeViews.size} views")
        for (view in activeViews) {
            removeHighlightView(view)
        }
        activeViews.clear()
    }

    /**
     * Safely stops animations associated with the view and removes it from WindowManager.
     */
    private fun removeHighlightView(view: View) {
        // Cancel the animator stored in the view's tag to prevent memory leaks
        (view.tag as? ValueAnimator)?.cancel()
        
        try {
            windowManager.removeView(view)
        } catch (e: Exception) {
            Log.e(TAG, "Error removing highlight view from window", e)
        }
    }

    /**
     * Animates the border alpha with a smooth pulsing effect.
     * Stores the animator reference in the view's tag for clean lifecycle tracking.
     */
    private fun startPulsingAnimation(view: View) {
        val animator = ValueAnimator.ofFloat(0.35f, 1.0f).apply {
            duration = 900
            repeatMode = ValueAnimator.REVERSE
            repeatCount = ValueAnimator.INFINITE
            addUpdateListener { anim ->
                view.alpha = anim.animatedValue as Float
            }
        }
        animator.start()
        view.tag = animator
    }

    /**
     * Creates a transparent view with a glowing red border and a warning pill badge.
     */
    private fun createHighlightView(): View {
        val root = FrameLayout(context).apply {
            isClickable = false
            isFocusable = false

            // Glowing border
            val borderDrawable = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                setColor(Color.TRANSPARENT)
                setStroke(6, Constants.BORDER_COLOR_HEX.toColorInt())
                cornerRadius = 24f
            }
            background = borderDrawable
        }

        // Warning Badge Pill
        val warningBadge = TextView(context).apply {
            text = Constants.WARNING_BADGE_TEXT
            setTextColor(Color.WHITE)
            textSize = 10f
            typeface = Typeface.create("sans-serif-black", Typeface.BOLD)
            setPadding(16, 8, 16, 8)

            val badgeBg = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                setColor(Constants.BORDER_COLOR_HEX.toColorInt())
                cornerRadius = 16f
            }
            background = badgeBg
        }

        val badgeParams = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT,
            FrameLayout.LayoutParams.WRAP_CONTENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            leftMargin = 16
            topMargin = 16
        }

        root.addView(warningBadge, badgeParams)
        return root
    }

    companion object {
        private const val TAG = "FloatingWindowManager"
    }


}
