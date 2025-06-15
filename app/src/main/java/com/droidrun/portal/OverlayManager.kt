package com.droidrun.portal

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
// import android.util.Log // Replaced with DebugLog
import com.droidrun.portal.DebugLog // Added DebugLog
import android.view.Gravity
import android.view.WindowManager
import android.widget.FrameLayout
import android.content.Intent
import android.view.View
import java.util.concurrent.atomic.AtomicBoolean

class OverlayManager(private val context: Context) {
    private val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private var overlayView: OverlayView? = null
    private val handler = Handler(Looper.getMainLooper())
    private val elementRects = mutableListOf<ElementInfo>()
    private var isOverlayVisible = false
    private var positionCorrectionOffset = 0 // Default correction offset
    private var elementIndexCounter = 0 // Counter to assign indexes to elements
    private val isOverlayReady = AtomicBoolean(false)
    private var onReadyCallback: (() -> Unit)? = null
    
    private var positionOffsetY = -128 // Default offset value
    private var positionOffsetX = 0 // Default X offset to 0

    companion object {
        private const val TAG = "TOPVIEW_OVERLAY"
        private const val OVERLAP_THRESHOLD = 0.5f // Lower overlap threshold for matching
        
        // Define a color scheme with 8 visually distinct colors
        private val COLOR_SCHEME = arrayOf(
            Color.rgb(0, 122, 255),    // Blue
            Color.rgb(255, 45, 85),    // Red
            Color.rgb(52, 199, 89),    // Green
            Color.rgb(255, 149, 0),    // Orange
            Color.rgb(175, 82, 222),   // Purple
            Color.rgb(255, 204, 0),    // Yellow
            Color.rgb(90, 200, 250),   // Light Blue
            Color.rgb(88, 86, 214)     // Indigo
        )
    }

    data class ElementInfo(
        val rect: Rect, 
        val type: String, 
        val text: String,
        val depth: Int = 0, // Added depth field to track hierarchy level
        val color: Int = Color.GREEN, // Add color field with default value
        val index: Int = 0 // Index number for identifying the element
    )
    
    // Add method to adjust the vertical offset
    fun setPositionOffsetY(offsetY: Int) {
        DebugLog.add(TAG, "Setting positionOffsetY to: $offsetY (old value was ${this.positionOffsetY})")
        this.positionOffsetY = offsetY
        refreshOverlay() // This will redraw with the new Y offset applied in correctRectPosition
    }
    
    // Add getter for the current offset value
    fun getPositionOffsetY(): Int {
        return positionOffsetY
    }

    fun setPositionOffsetX(offsetX: Int) {
        DebugLog.add(TAG, "Setting positionOffsetX to: $offsetX (old value was ${this.positionOffsetX})")
        this.positionOffsetX = offsetX
        refreshOverlay() // This will redraw with the new X offset applied in correctRectPosition
    }

    fun getPositionOffsetX(): Int {
        return positionOffsetX
    }

    fun setOnReadyCallback(callback: () -> Unit) {
        DebugLog.add(TAG, "setOnReadyCallback: Callback being set. Overlay ready state: ${isOverlayReady.get()}")
        onReadyCallback = callback
        if (isOverlayReady.get()) {
            DebugLog.add(TAG, "setOnReadyCallback: Overlay already ready, invoking callback immediately.")
            handler.post(callback)
        }
    }

    fun showOverlay() {
        DebugLog.add(TAG, "showOverlay: Called. Current overlayView is ${if (overlayView == null) "null" else "not null"}.")
        if (overlayView != null) {
            DebugLog.add(TAG, "showOverlay: OverlayView already exists. Checking attachment state.")
            try {
                if (overlayView?.parent == null) {
                    DebugLog.add(TAG, "showOverlay: OverlayView exists but not attached to window. Recreating.")
                    try { windowManager.removeView(overlayView) } catch (e: Exception) { DebugLog.add(TAG, "showOverlay: Ignored error while removing defunct overlay: ${e.message}") }
                    overlayView = null
                    createAndAddOverlay()
                } else {
                    DebugLog.add(TAG, "showOverlay: OverlayView already exists and is attached. Ensuring visibility and readiness.")
                    overlayView?.visibility = View.VISIBLE
                    if (!isOverlayReady.getAndSet(true)) {
                         onReadyCallback?.let {
                            DebugLog.add(TAG, "showOverlay: Overlay was not marked ready. Invoking onReadyCallback.")
                            handler.post(it)
                        }
                    } else {
                        DebugLog.add(TAG, "showOverlay: Overlay already marked ready.")
                    }
                }
            } catch (e: Exception) {
                DebugLog.add(TAG, "showOverlay: Error checking existing overlay state: ${e.message}. Recreating.", e)
                overlayView = null
                createAndAddOverlay()
            }
            return
        }
        // overlayView is null, proceed to create
        createAndAddOverlay()
    }

    private fun createAndAddOverlay() {
        DebugLog.add(TAG, "createAndAddOverlay: Attempting to create and add new OverlayView.")
        try {
            overlayView = OverlayView(context).apply {
                setLayerType(View.LAYER_TYPE_HARDWARE, null) // Hardware acceleration
            }
            val params = WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                        WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                        WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                        WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
                PixelFormat.TRANSLUCENT
            )
            params.gravity = Gravity.TOP or Gravity.START
            
            handler.post {
                try {
                    windowManager.addView(overlayView, params)
                    isOverlayVisible = true
                    
                    // Set ready state and notify callback after a short delay to ensure view is laid out
                    handler.postDelayed({
                        if (overlayView?.parent != null) {
                            isOverlayReady.set(true)
                            DebugLog.add(TAG, "createAndAddOverlay: Delayed check: Overlay ready and callback invoked (if set).")
                            onReadyCallback?.let { it() }
                        } else {
                            DebugLog.add(TAG, "createAndAddOverlay: Delayed check: Overlay not properly attached after delay. Attempting recovery.", e = Exception("Overlay not attached"))
                            hideOverlay()
                            showOverlay()
                        }
                    }, 500)
                } catch (e: Exception) {
                    DebugLog.add(TAG, "createAndAddOverlay: Error adding OverlayView to WindowManager: ${e.message}", e)
                    overlayView = null
                    isOverlayVisible = false
                    isOverlayReady.set(false)
                }
            }
        } catch (e: Exception) {
            DebugLog.add(TAG, "createAndAddOverlay: Error creating OverlayView instance: ${e.message}", e)
            overlayView = null
            isOverlayVisible = false
            isOverlayReady.set(false)
        }
    }

    fun hideOverlay() {
        handler.post {
            try {
                overlayView?.let {
                    DebugLog.add(TAG, "hideOverlay: Removing OverlayView from WindowManager.")
                    windowManager.removeView(it)
                    overlayView = null
                    DebugLog.add(TAG, "hideOverlay: OverlayView removed and nulled successfully.")
                }
                isOverlayVisible = false
                isOverlayReady.set(false)
                DebugLog.add(TAG, "hideOverlay: Overlay state set to not visible and not ready.")
            } catch (e: Exception) {
                DebugLog.add(TAG, "hideOverlay: Error removing OverlayView: ${e.message}", e)
            }
        }
    }

    fun clearElements() {
        DebugLog.add(TAG, "clearElements: Clearing ${elementRects.size} elements. Resetting index counter.")
        elementRects.clear()
        elementIndexCounter = 0
        refreshOverlay()
    }

    fun addElement(rect: Rect, type: String, text: String, depth: Int = 0, color: Int = Color.GREEN) {
        val correctedRect = correctRectPosition(rect)
        val index = elementIndexCounter++
        val colorFromScheme = COLOR_SCHEME[index % COLOR_SCHEME.size]
        val newElement = ElementInfo(correctedRect, type, text, depth, colorFromScheme, index)
        elementRects.add(newElement)
        DebugLog.add(TAG, "addElement: Added new element (Index: $index, Type: $type, Text: '$text', Rect: $correctedRect, Depth: $depth, Color: $colorFromScheme). Total elements: ${elementRects.size}")
    }
    
    private fun correctRectPosition(rect: Rect): Rect {
        val correctedRect = Rect(rect)
        // Example of conditional logging if needed, but often direct logging is fine for DebugLog
        // if (positionOffsetX != 0 || positionOffsetY != 0) {
        //    DebugLog.add(TAG, "correctRectPosition: Original: $rect, OffsetX: $positionOffsetX, OffsetY: $positionOffsetY")
        // }
        correctedRect.offset(positionOffsetX, positionOffsetY)
        // if (rect != correctedRect) {
        //    DebugLog.add(TAG, "correctRectPosition: Corrected: $correctedRect")
        // }
        return correctedRect
    }

    fun refreshOverlay() {
        DebugLog.add(TAG, "refreshOverlay: Posting invalidate to handler. OverlayView is ${if (overlayView == null) "null" else "not null"}.")
        handler.post {
            if (overlayView == null) {
                DebugLog.add(TAG, "refreshOverlay: OverlayView is null. Attempting to show/recreate overlay first.")
                showOverlay() // Attempt to recreate if null
            }
            overlayView?.invalidate()
        }
    }

    fun updateElement(rect: Rect, text: String, color: Int = Color.GREEN) {
        DebugLog.add(TAG, "updateElement: Attempting to update element. Input Rect: $rect, Text: '$text'")
        val correctedRect = correctRectPosition(rect)
        
        val existingElement = elementRects.find { element ->
            if (element.text == text) {
                val overlapRect = Rect(element.rect)
                if (overlapRect.intersect(correctedRect)) {
                    val overlapArea = overlapRect.width() * overlapRect.height()
                    val elementArea = element.rect.width() * element.rect.height()
                    val inputArea = correctedRect.width() * correctedRect.height()
                    val minArea = minOf(elementArea, inputArea).toFloat()
                    minArea > 0 && (overlapArea / minArea) > OVERLAP_THRESHOLD
                } else false
            } else false
        }
        
        if (existingElement != null) {
            DebugLog.add(TAG, "updateElement: Found existing element (Index: ${existingElement.index}). Updating its properties.")
            val listIndex = elementRects.indexOf(existingElement)
            if (listIndex >= 0) {
                elementRects[listIndex] = ElementInfo(
                    rect = correctedRect,
                    type = existingElement.type, // Retain original type
                    text = text,
                    depth = existingElement.depth, // Retain original depth
                    color = existingElement.color, // Retain original color for consistency
                    index = existingElement.index  // IMPORTANT: Retain original index
                )
                DebugLog.add(TAG, "updateElement: Element at internal list index $listIndex updated.")
            }
        } else {
            DebugLog.add(TAG, "updateElement: No existing element found matching criteria. Adding as new element.")
            val newIndex = elementIndexCounter++
            val newColorFromScheme = COLOR_SCHEME[newIndex % COLOR_SCHEME.size]
            elementRects.add(ElementInfo(
                rect = correctedRect,
                type = "UpdatedElement", // Or a default type
                text = text,
                depth = 0, // Default depth
                color = newColorFromScheme,
                index = newIndex
            ))
            DebugLog.add(TAG, "updateElement: Added new element (Index: $newIndex) due to no match.")
        }
    }
    
    fun getElementCount(): Int {
        val count = elementRects.size
        DebugLog.add(TAG, "getElementCount: Returning $count elements.")
        return count
    }

    fun getElementIndex(rect: Rect, text: String): Int {
        val correctedRect = correctRectPosition(rect)
        DebugLog.add(TAG, "getElementIndex: Searching for element. CorrectedRect: $correctedRect, Text: '$text'")
        
        val exactMatch = elementRects.find { it.rect == correctedRect && it.text == text }
        if (exactMatch != null) {
            DebugLog.add(TAG, "getElementIndex: Found exact match. Index: ${exactMatch.index}")
            return exactMatch.index
        } else {
            DebugLog.add(TAG, "getElementIndex: No exact match found. Attempting looser matching.")
        }
        
        val similarElement = elementRects.find { element ->
            val rectOverlaps = Rect.intersects(element.rect, correctedRect)
            val textMatches = element.text.trim() == text.trim()
            
            if (rectOverlaps && textMatches) { // Ensure text matches before calculating overlap area for performance
                val overlapRect = Rect(element.rect)
                overlapRect.intersect(correctedRect) // Modifies overlapRect to be the intersection
                val overlapArea = overlapRect.width() * overlapRect.height()
                val elementArea = element.rect.width() * element.rect.height()
                val inputArea = correctedRect.width() * correctedRect.height()
                
                if (elementArea == 0 || inputArea == 0) return@find false // Avoid division by zero for zero-area rects
                
                val minArea = minOf(elementArea, inputArea).toFloat()
                (overlapArea / minArea) > OVERLAP_THRESHOLD
            } else {
                false
            }
        }
        
        if (similarElement != null) {
            DebugLog.add(TAG, "getElementIndex: Found similar element. Index: ${similarElement.index}")
            return similarElement.index
        }
        DebugLog.add(TAG, "getElementIndex: No similar element found. Returning -1.")
        return -1
    }

    inner class OverlayView(context: Context) : FrameLayout(context) {
        private val boxPaint = Paint().apply {
            style = Paint.Style.STROKE
            strokeWidth = 2f  // Thinner border as requested
            isAntiAlias = true
            // Enable hardware acceleration features
            flags = Paint.ANTI_ALIAS_FLAG or Paint.DITHER_FLAG
        }
        
        private val textPaint = Paint().apply {
            color = Color.WHITE
            textSize = 32f  // Increased text size for better visibility
            isAntiAlias = true
            // Enable hardware acceleration features
            flags = Paint.ANTI_ALIAS_FLAG or Paint.DITHER_FLAG
        }
        
        private val textBackgroundPaint = Paint().apply {
            // Color will be set dynamically to match the border color
            style = Paint.Style.FILL
            // Enable hardware acceleration features
            flags = Paint.ANTI_ALIAS_FLAG or Paint.DITHER_FLAG
        }

        init {
            setWillNotDraw(false)
            setBackgroundColor(Color.TRANSPARENT)
            // Enable hardware acceleration
            setLayerType(View.LAYER_TYPE_HARDWARE, null)
        }

        override fun onDraw(canvas: Canvas) {
            try {
                if (canvas == null) {
                    DebugLog.add(TAG, "OverlayView.onDraw: Canvas is null. Cannot draw.")
                    // Log.e(TAG, "OverlayView.onDraw: Canvas is null") // Replaced by DebugLog with exception
                    return
                }

                if (!isOverlayVisible) {
                    DebugLog.add(TAG, "OverlayView.onDraw: Overlay not visible (isOverlayVisible=false), skipping draw.")
                    return
                } else if (overlayView == null || overlayView?.parent == null) { // Check if view is still valid
                    DebugLog.add(TAG, "OverlayView.onDraw: OverlayView is null or not attached to window. Skipping draw.")
                    return
                }

                super.onDraw(canvas)
                // val startTime = System.currentTimeMillis() // For performance timing

                if (elementRects.isEmpty()) {
                    // DebugLog.add(TAG, "OverlayView.onDraw: No elements to draw.") // Can be noisy
                    if (isDebugging()) drawDebugRect(canvas)
                    return
                }
                
                val elementsToDraw = ArrayList(elementRects) // Local copy for thread safety
                val sortedElements = elementsToDraw.sortedBy { it.depth } // Draw deeper elements first
                
                for (elementInfo in sortedElements) {
                    drawElement(canvas, elementInfo)
                }

                // val drawTime = System.currentTimeMillis() - startTime
                // DebugLog.add(TAG, "OverlayView.onDraw: Draw completed in $drawTime ms for ${sortedElements.size} elements.") // Can be noisy
            } catch (e: Exception) {
                DebugLog.add(TAG, "OverlayView.onDraw: Error during drawing: ${e.message}", e)
            }
        }

        private fun drawElement(canvas: Canvas, elementInfo: ElementInfo) {
            try {
                if (elementInfo.rect.width() <= 0 || elementInfo.rect.height() <= 0 || elementInfo.rect.left < 0 || elementInfo.rect.top < 0) {
                    DebugLog.add(TAG, "OverlayView.drawElement: Invalid rectangle dimensions or position for element ${elementInfo.index}: ${elementInfo.rect}. Skipping draw for this element.")
                    return
                }

                val elementColor = elementInfo.color
                
                // Ensure color has full alpha for visibility
                val colorWithAlpha = Color.argb(
                    255,
                    Color.red(elementColor),
                    Color.green(elementColor),
                    Color.blue(elementColor)
                )
                
                boxPaint.color = colorWithAlpha
                
                // Set the background color to match the border color with some transparency
                textBackgroundPaint.color = Color.argb(
                    200, // Semi-transparent
                    Color.red(elementColor),
                    Color.green(elementColor),
                    Color.blue(elementColor)
                )
                
                // Draw the rectangle with the specified color
                canvas.drawRect(elementInfo.rect, boxPaint)
                
                // Draw the index number in the top-right corner
                val displayText = "${elementInfo.index}"
                val textWidth = textPaint.measureText(displayText)
                val textHeight = 36f  // Larger text height to match increased text size
                
                // Position for top-right corner with small padding
                val textX = elementInfo.rect.right - textWidth - 4f  // 4px padding from right edge
                val textY = elementInfo.rect.top + textHeight  // Position text at top with some padding
                
                // Calculate background rectangle for the text
                val backgroundPadding = 4f
                val backgroundRect = Rect(
                    (textX - backgroundPadding).toInt(),
                    (textY - textHeight).toInt(),
                    (textX + textWidth + backgroundPadding).toInt(),
                    (textY + backgroundPadding).toInt()
                )
                
                // Draw background and text
                canvas.drawRect(backgroundRect, textBackgroundPaint)
                canvas.drawText(
                    displayText,
                    textX,
                    textY - backgroundPadding,
                    textPaint
                )
            } catch (e: Exception) {
                DebugLog.add(TAG, "OverlayView.drawElement: Error drawing element ${elementInfo.index} (Text: '${elementInfo.text}'): ${e.message}", e)
            }
        }

        private fun drawDebugRect(canvas: Canvas) {
            try {
                val screenWidth = width
                val screenHeight = height
                val testRect = Rect(screenWidth / 4, screenHeight / 4, (screenWidth * 3) / 4, (screenHeight * 3) / 4)
                boxPaint.color = Color.GREEN
                canvas.drawRect(testRect, boxPaint)
                DebugLog.add(TAG, "OverlayView.drawDebugRect: Drew test rectangle at $testRect")
            } catch (e: Exception) {
                DebugLog.add(TAG, "OverlayView.drawDebugRect: Error: ${e.message}", e)
            }
        }
        private fun isDebugging(): Boolean {
            return false // Set to true to show test rectangle
        }
    }
} 