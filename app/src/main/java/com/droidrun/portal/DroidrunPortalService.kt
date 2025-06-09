package com.droidrun.portal

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.os.Bundle
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.content.Context
import android.content.Context.RECEIVER_NOT_EXPORTED
import android.content.Context.RECEIVER_EXPORTED
import android.graphics.Point
import android.graphics.Path
import android.view.WindowManager
import android.view.accessibility.AccessibilityWindowInfo
import java.util.concurrent.atomic.AtomicBoolean
import android.graphics.Color
import android.content.BroadcastReceiver
import android.content.Intent
import android.content.IntentFilter
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.abs
import android.view.View // Added
//WindowManager is already imported
import android.graphics.PixelFormat // Added
import android.view.Gravity // Added
import android.view.MotionEvent // Added
import android.os.Build // Added
import android.provider.Settings // Added
import android.view.LayoutInflater // Added


class DroidrunPortalService : AccessibilityService() {
    
    companion object {
        private const val TAG = "DROIDRUN_PORTAL"
        const val ACTION_TOGGLE_FLOATING_VOICE_BUTTON = "com.droidrun.portal.TOGGLE_FLOATING_VOICE_BUTTON" // Added
        private const val REFRESH_INTERVAL_MS = 250L // Single refresh interval for all updates
        private const val MIN_ELEMENT_SIZE = 5 // Minimum size for an element to be considered
        private const val MIN_FRAME_TIME_MS = 16L // Minimum time between frames (roughly 60 FPS)
        
        // Time-based fade settings
        private const val FADE_DURATION_MS = 300000L // Time to fade from weight 1.0 to 0.0 (300 seconds = 5 minutes)
        private const val VISUALIZATION_REFRESH_MS = 250L // How often to refresh visualization (250ms = 4 times per second)
        private const val MIN_DISPLAY_WEIGHT = 0.05f // Minimum weight to display elements
        private const val SAME_TIME_THRESHOLD_MS = 500L // Elements appearing within this time window are considered "same time"
        
        // Color for heatmap (we'll use a gradient from RED to BLUE based on weight)
        private val NEW_ELEMENT_COLOR = Color.RED         // Newest elements
        private val OLD_ELEMENT_COLOR = Color.BLUE        // Oldest elements
        
        // Intent actions for ADB communication
        const val ACTION_GET_ELEMENTS = "com.droidrun.portal.GET_ELEMENTS"
        const val ACTION_ELEMENTS_RESPONSE = "com.droidrun.portal.ELEMENTS_RESPONSE"
        const val ACTION_TOGGLE_OVERLAY = "com.droidrun.portal.TOGGLE_OVERLAY"
        const val ACTION_RETRIGGER_ELEMENTS = "com.droidrun.portal.RETRIGGER_ELEMENTS"
        const val ACTION_GET_ALL_ELEMENTS = "com.droidrun.portal.GET_ALL_ELEMENTS"
        const val ACTION_GET_INTERACTIVE_ELEMENTS = "com.droidrun.portal.GET_INTERACTIVE_ELEMENTS"
        const val ACTION_FORCE_HIDE_OVERLAY = "com.droidrun.portal.FORCE_HIDE_OVERLAY"
        const val ACTION_UPDATE_OVERLAY_OFFSET = "com.droidrun.portal.UPDATE_OVERLAY_OFFSET"
        const val EXTRA_OVERLAY_OFFSET = "overlay_offset"
        const val EXTRA_ELEMENTS_DATA = "elements_data"
        const val EXTRA_ALL_ELEMENTS_DATA = "all_elements_data"
        const val EXTRA_OVERLAY_VISIBLE = "overlay_visible"
    }
    
    private lateinit var overlayManager: OverlayManager
    private lateinit var geminiProcessor: GeminiCommandProcessor
    private lateinit var commandReceiver: BroadcastReceiver
    private val mainHandler = Handler(Looper.getMainLooper())
    private var isInitialized = false
    private val screenBounds = Rect()
    private val visibleElements = mutableListOf<ElementNode>()
    private val previousElements = mutableListOf<ElementNode>() // Track previous elements
    private val isProcessing = AtomicBoolean(false)
    private var currentPackageName: String = "" // Track current app package
    private var overlayVisible = true // Track if overlay is visible
    
    // Track currently displayed elements (after filtering)
    private val displayedElements = mutableListOf<Pair<ElementNode, Float>>()
    
    private var lastDrawTime = 0L
    private var pendingVisualizationUpdate = false

    // Floating Action Button related
    private var floatingVoiceButton: View? = null
    private lateinit var windowManagerService: WindowManager // Using plan name
    private var isFloatingButtonActuallyShown: Boolean = false // Using plan name

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "Service onCreate")
        try {
            windowManagerService = getSystemService(WINDOW_SERVICE) as WindowManager // Initialization
            // Initialize Gemini processor
            geminiProcessor = GeminiCommandProcessor(this)
            
            // Register broadcast receiver for commands
            commandReceiver = object : BroadcastReceiver() {
                override fun onReceive(context: Context, intent: Intent) {
                    when (intent.action) {
                        "com.droidrun.portal.PROCESS_NL_COMMAND" -> {
                            val command = intent.getStringExtra("command")
                            if (command != null) {
                                processNaturalLanguageCommand(command)
                            }
                        }
                        "com.droidrun.portal.PROCESS_VOICE_COMMAND" -> {
                            val command = intent.getStringExtra("command")
                            if (command != null) {
                                processVoiceCommand(command)
                            }
                        }
                        "com.droidrun.portal.EXECUTE_UI_ACTION" -> {
                            executeUIAction(intent)
                        }
                        // Original ADB commands
                        ACTION_GET_ELEMENTS -> {
                            Log.e("DROIDRUN_RECEIVER", "Received GET_ELEMENTS command")
                            broadcastElementData()
                        }
                        ACTION_GET_INTERACTIVE_ELEMENTS -> {
                            Log.e("DROIDRUN_RECEIVER", "Received GET_INTERACTIVE_ELEMENTS command")
                            broadcastElementData()
                        }
                        ACTION_GET_ALL_ELEMENTS -> {
                            Log.e("DROIDRUN_RECEIVER", "Received GET_ALL_ELEMENTS command")
                            broadcastAllElementsData()
                        }
                        ACTION_TOGGLE_OVERLAY -> {
                            if (!isOverlayManagerAvailable()) {
                                Log.e("DROIDRUN_RECEIVER", "Cannot toggle overlay: OverlayManager not initialized")
                                return
                            }
                            
                            val shouldShow = intent.getBooleanExtra(EXTRA_OVERLAY_VISIBLE, !overlayVisible)
                            Log.e("DROIDRUN_RECEIVER", "Received TOGGLE_OVERLAY command: $shouldShow")
                            if (shouldShow) {
                                overlayManager.showOverlay()
                                overlayVisible = true
                            } else {
                                overlayManager.hideOverlay()
                                overlayVisible = false
                            }
                            val responseIntent = Intent(ACTION_ELEMENTS_RESPONSE).apply {
                                putExtra(EXTRA_OVERLAY_VISIBLE, overlayVisible)
                            }
                            sendBroadcast(responseIntent)
                        }
                        ACTION_RETRIGGER_ELEMENTS -> {
                            Log.e("DROIDRUN_RECEIVER", "Received RETRIGGER_ELEMENTS command")
                            retriggerElements()
                        }
                        ACTION_FORCE_HIDE_OVERLAY -> {
                            Log.e("DROIDRUN_RECEIVER", "Received FORCE_HIDE_OVERLAY command")
                            if (isOverlayManagerAvailable()) {
                                overlayManager.hideOverlay()
                                overlayVisible = false
                                overlayManager.clearElements()
                                overlayManager.refreshOverlay()
                            }
                        }
                        ACTION_TOGGLE_FLOATING_VOICE_BUTTON -> {
                            val show = intent.getBooleanExtra("show_button", false)
                            DebugLog.add(TAG, "Received toggle floating button broadcast: show=$show")
                            if (show) showFloatingVoiceButton() else hideFloatingVoiceButton()
                        }
                    }
                }
            }
            
            val filter = IntentFilter().apply {
                addAction("com.droidrun.portal.PROCESS_NL_COMMAND")
                addAction("com.droidrun.portal.PROCESS_VOICE_COMMAND")
                addAction("com.droidrun.portal.EXECUTE_UI_ACTION")
                addAction(ACTION_GET_ELEMENTS)
                addAction(ACTION_GET_INTERACTIVE_ELEMENTS)
                addAction(ACTION_GET_ALL_ELEMENTS)
                addAction(ACTION_TOGGLE_OVERLAY)
                addAction(ACTION_RETRIGGER_ELEMENTS)
                addAction(ACTION_FORCE_HIDE_OVERLAY)
                addAction(ACTION_UPDATE_OVERLAY_OFFSET)
                addAction(ACTION_TOGGLE_FLOATING_VOICE_BUTTON)
            }
            registerReceiver(commandReceiver, filter, RECEIVER_EXPORTED)
            
            overlayManager = OverlayManager(this)
            isInitialized = true
            
            val localWindowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager // Using local var for display
            val display = localWindowManager.defaultDisplay
            val size = Point()
            display.getSize(size)
            screenBounds.set(0, 0, size.x, size.y)
            
            startPeriodicUpdates()
            startVisualizationUpdates()
        } catch (e: Exception) {
            Log.e(TAG, "Error initializing service: ${e.message}", e)
        }
    }

    override fun onDestroy() {
        Log.d(TAG, "Service onDestroy")
        try {
            if (::commandReceiver.isInitialized) {
                unregisterReceiver(commandReceiver)
            }
            hideFloatingVoiceButton()
            stopPeriodicUpdates()
            mainHandler.removeCallbacks(visualizationRunnable)
            resetOverlayState()
            
            if (isInitialized) {
                overlayManager.hideOverlay()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error in onDestroy: ${e.message}", e)
        }
        super.onDestroy()
    }

    private fun showFloatingVoiceButton() {
        if (floatingVoiceButton != null || !Settings.canDrawOverlays(this)) {
            if (!Settings.canDrawOverlays(this)) {
                DebugLog.add(TAG, "Cannot show floating button: SYSTEM_ALERT_WINDOW permission not granted.")
                // TODO: Consider sending a broadcast to MainActivity to request user to grant permission.
            } else {
                DebugLog.add(TAG, "Floating button already shown or permission issue.")
            }
            return
        }
        DebugLog.add(TAG, "Attempting to show floating voice button.")
        isFloatingButtonActuallyShown = true // State update
        val inflater = LayoutInflater.from(this)
        floatingVoiceButton = inflater.inflate(R.layout.floating_voice_button_layout, null)

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY else WindowManager.LayoutParams.TYPE_PHONE,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        )
        params.gravity = Gravity.TOP or Gravity.START
        params.x = 100
        params.y = 300

        floatingVoiceButton?.setOnClickListener {
            val intent = Intent(this, VoiceCommandActivity::class.java)
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            startActivity(intent)
            DebugLog.add(TAG, "Floating voice button clicked, launching VoiceCommandActivity.")
        }

        floatingVoiceButton?.setOnTouchListener(object : View.OnTouchListener {
            private var initialX: Int = 0
            private var initialY: Int = 0
            private var initialTouchX: Float = 0f
            private var initialTouchY: Float = 0f

            override fun onTouch(v: View, event: MotionEvent): Boolean {
                when (event.action) {
                    MotionEvent.ACTION_DOWN -> {
                        initialX = params.x
                        initialY = params.y
                        initialTouchX = event.rawX
                        initialTouchY = event.rawY
                        return true
                    }
                    MotionEvent.ACTION_MOVE -> {
                        params.x = initialX + (event.rawX - initialTouchX).toInt()
                        params.y = initialY + (event.rawY - initialTouchY).toInt()
                        if (floatingVoiceButton != null) windowManagerService.updateViewLayout(floatingVoiceButton, params)
                        return true
                    }
                }
                return false
            }
        })
        try {
             windowManagerService.addView(floatingVoiceButton, params)
             DebugLog.add(TAG, "Floating voice button added to window.")
        } catch (e: Exception) {
             DebugLog.add(TAG, "Error adding floating voice button: ${e.message}")
             isFloatingButtonActuallyShown = false; floatingVoiceButton = null;
        }
    }

    private fun hideFloatingVoiceButton() {
        if (floatingVoiceButton != null) {
            DebugLog.add(TAG, "Attempting to hide floating voice button.")
            try {
                windowManagerService.removeView(floatingVoiceButton)
                DebugLog.add(TAG, "Floating voice button removed from window.")
            } catch (e: Exception) {
                 DebugLog.add(TAG, "Error removing floating voice button: ${e.message}")
            }
            floatingVoiceButton = null
        }
        isFloatingButtonActuallyShown = false // State update
    }

    // Gemini Integration Methods
    private fun processNaturalLanguageCommand(command: String) {
        Log.d(TAG, "Processing natural language command: $command")
        val currentElements = getCurrentElementsJson()
        
        geminiProcessor.processCommand(command, currentElements, object : GeminiCommandProcessor.CommandCallback {
            override fun onCommandProcessed(actions: List<GeminiCommandProcessor.UIAction>) {
                Log.d(TAG, "Received ${actions.size} actions from Gemini")
                executeActions(actions)
            }

            override fun onError(error: String) {
                Log.e(TAG, "Gemini processing error: $error")
            }
        })
    }
    
    private fun processVoiceCommand(command: String) {
        Log.d(TAG, "Processing voice command: $command")
        processNaturalLanguageCommand(command)
    }
    
    private fun executeActions(actions: List<GeminiCommandProcessor.UIAction>) {
        for (action in actions) {
            executeAction(action)
            Thread.sleep(500) // Small delay between actions
        }
    }
    
    private fun executeAction(action: GeminiCommandProcessor.UIAction) {
        when (action.type) {
            "click" -> {
                if (action.elementIndex >= 0) {
                    clickElementByIndex(action.elementIndex)
                } else if (action.x >= 0 && action.y >= 0) {
                    clickAtCoordinates(action.x, action.y)
                }
            }
            "type" -> {
                if (action.elementIndex >= 0) {
                    typeInElement(action.elementIndex, action.text)
                }
            }
            "scroll" -> {
                performScroll(action.direction)
            }
            "swipe" -> {
                performSwipe(action.direction)
            }
            "home" -> {
                performGlobalAction(GLOBAL_ACTION_HOME)
            }
            "back" -> {
                performGlobalAction(GLOBAL_ACTION_BACK)
            }
            "recent" -> {
                performGlobalAction(GLOBAL_ACTION_RECENTS)
            }
        }
    }
    
    private fun executeUIAction(intent: Intent) {
        val actionType = intent.getStringExtra("actionType") ?: return
        val elementIndex = intent.getIntExtra("elementIndex", -1)
        val text = intent.getStringExtra("text") ?: ""
        val x = intent.getIntExtra("x", -1)
        val y = intent.getIntExtra("y", -1)
        val direction = intent.getStringExtra("direction") ?: ""
        
        val action = GeminiCommandProcessor.UIAction(actionType, elementIndex, text, x, y, direction)
        executeAction(action)
    }
    
    private fun clickElementByIndex(index: Int) {
        val elements = getInteractiveElements()
        if (index < elements.size) {
            val element = elements[index]
            element.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            Log.d(TAG, "Clicked element at index $index")
        }
    }
    
    private fun clickAtCoordinates(x: Int, y: Int) {
        val path = Path()
        path.moveTo(x.toFloat(), y.toFloat())
        
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, 100))
            .build()
            
        dispatchGesture(gesture, null, null)
        Log.d(TAG, "Clicked at coordinates ($x, $y)")
    }
    
    private fun typeInElement(index: Int, text: String) {
        val elements = getInteractiveElements()
        if (index < elements.size) {
            val element = elements[index]
            element.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
            val arguments = Bundle().apply {
                putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
            }
            element.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, arguments)
            Log.d(TAG, "Typed '$text' in element at index $index")
        }
    }
    
    private fun performScroll(direction: String) {
        val action = when (direction.lowercase()) {
            "up" -> AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
            "down" -> AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
            else -> AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
        }
        
        rootInActiveWindow?.performAction(action)
        Log.d(TAG, "Performed scroll $direction")
    }
    
    private fun performSwipe(direction: String) {
        val displayMetrics = resources.displayMetrics
        val width = displayMetrics.widthPixels
        val height = displayMetrics.heightPixels
        
        val path = Path()
        when (direction.lowercase()) {
            "left" -> {
                path.moveTo(width * 0.8f, height * 0.5f)
                path.lineTo(width * 0.2f, height * 0.5f)
            }
            "right" -> {
                path.moveTo(width * 0.2f, height * 0.5f)
                path.lineTo(width * 0.8f, height * 0.5f)
            }
            "up" -> {
                path.moveTo(width * 0.5f, height * 0.8f)
                path.lineTo(width * 0.5f, height * 0.2f)
            }
            "down" -> {
                path.moveTo(width * 0.5f, height * 0.2f)
                path.lineTo(width * 0.5f, height * 0.8f)
            }
        }
        
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, 300))
            .build()
            
        dispatchGesture(gesture, null, null)
        Log.d(TAG, "Performed swipe $direction")
    }
    
    private fun getCurrentElementsJson(): String {
        return getElementsAsJson(false) // false for interactive elements only
    }
    
    private fun getInteractiveElements(): List<AccessibilityNodeInfo> {
        val elements = mutableListOf<AccessibilityNodeInfo>()
        val rootNode = rootInActiveWindow ?: return elements
        
        fun traverseNode(node: AccessibilityNodeInfo) {
            if (node.isClickable || node.isCheckable || node.isEditable || node.isScrollable || node.isFocusable) {
                elements.add(node)
            }
            for (i in 0 until node.childCount) {
                node.getChild(i)?.let { traverseNode(it) }
            }
        }
        
        traverseNode(rootNode)
        return elements
    }

    // Original DroidRun Portal methods (simplified for space)
    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        val eventPackage = event.packageName?.toString() ?: ""
        
        if (eventPackage.isNotEmpty() && eventPackage != currentPackageName && currentPackageName.isNotEmpty()) {
            resetOverlayState()
        }
        
        if (eventPackage.isNotEmpty()) {
            currentPackageName = eventPackage
        }
        
        when (event.eventType) {
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED,
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED -> {
                if (isInitialized) {
                    processActiveWindow()
                }
            }
        }
    }

    override fun onInterrupt() {
        Log.d(TAG, "Service interrupted")
    }

    private fun processActiveWindow() {
        if (isProcessing.get()) return
        
        isProcessing.set(true)
        try {
            val rootNode = rootInActiveWindow
            if (rootNode != null) {
                val newElements = mutableListOf<ElementNode>()
                extractElements(rootNode, newElements, 0)
                
                synchronized(visibleElements) {
                    visibleElements.clear()
                    visibleElements.addAll(newElements)
                }
                
                pendingVisualizationUpdate = true
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error processing active window: ${e.message}", e)
        } finally {
            isProcessing.set(false)
        }
    }

    private fun extractElements(node: AccessibilityNodeInfo, elements: MutableList<ElementNode>, depth: Int) {
        try {
            val rect = Rect()
            node.getBoundsInScreen(rect)
            
            if (rect.width() >= MIN_ELEMENT_SIZE && rect.height() >= MIN_ELEMENT_SIZE) {
                val element = ElementNode(
                    rect = rect,
                    text = node.text?.toString() ?: "",
                    className = node.className?.toString() ?: "",
                    isClickable = node.isClickable,
                    isCheckable = node.isCheckable,
                    isEditable = node.isEditable,
                    isScrollable = node.isScrollable,
                    isFocusable = node.isFocusable,
                    windowLayer = depth
                )
                elements.add(element)
            }
            
            for (i in 0 until node.childCount) {
                node.getChild(i)?.let { child ->
                    extractElements(child, elements, depth + 1)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error extracting element: ${e.message}")
        }
    }

    private fun getElementsAsJson(includeAll: Boolean): String {
        val jsonArray = JSONArray()
        val elementsToProcess = synchronized(visibleElements) {
            if (includeAll) {
                visibleElements.toList()
            } else {
                visibleElements.filter { it.isClickable || it.isCheckable || it.isEditable || it.isScrollable || it.isFocusable }
            }
        }
        
        elementsToProcess.forEachIndexed { index, element ->
            val jsonObject = JSONObject().apply {
                put("index", index)
                put("text", element.text)
                put("class", element.className)
                put("clickable", element.isClickable)
                put("checkable", element.isCheckable)
                put("editable", element.isEditable)
                put("scrollable", element.isScrollable)
                put("focusable", element.isFocusable)
                put("bounds", JSONObject().apply {
                    put("left", element.rect.left)
                    put("top", element.rect.top)
                    put("right", element.rect.right)
                    put("bottom", element.rect.bottom)
                })
            }
            jsonArray.put(jsonObject)
        }
        
        return jsonArray.toString()
    }

    private fun broadcastElementData() {
        val elementsJson = getElementsAsJson(false)
        val responseIntent = Intent(ACTION_ELEMENTS_RESPONSE).apply {
            putExtra(EXTRA_ELEMENTS_DATA, elementsJson)
        }
        sendBroadcast(responseIntent)
        Log.e("DROIDRUN_RECEIVER", "Broadcasted element data")
    }

    private fun broadcastAllElementsData() {
        val allElementsJson = getElementsAsJson(true)
        val responseIntent = Intent(ACTION_ELEMENTS_RESPONSE).apply {
            putExtra(EXTRA_ALL_ELEMENTS_DATA, allElementsJson)
        }
        sendBroadcast(responseIntent)
        Log.e("DROIDRUN_RECEIVER", "Broadcasted all elements data")
    }

    private fun retriggerElements() {
        synchronized(visibleElements) {
            val currentTime = System.currentTimeMillis()
            visibleElements.forEach { it.creationTime = currentTime }
        }
        pendingVisualizationUpdate = true
    }

    private fun resetOverlayState() {
        synchronized(visibleElements) {
            visibleElements.clear()
        }
        synchronized(displayedElements) {
            displayedElements.clear()
        }
        if (isOverlayManagerAvailable()) {
            overlayManager.clearElements()
            overlayManager.refreshOverlay()
        }
    }

    private fun isOverlayManagerAvailable(): Boolean {
        return isInitialized && ::overlayManager.isInitialized
    }

    private fun calculateHeatmapColor(weight: Float): Int {
        val red = (255 * weight).toInt().coerceIn(0, 255)
        val blue = (255 * (1 - weight)).toInt().coerceIn(0, 255)
        return Color.rgb(red, 0, blue)
    }

    // Periodic update methods
    private val updateRunnable = object : Runnable {
        override fun run() {
            if (isInitialized) {
                val currentTime = System.currentTimeMillis()
                val timeSinceLastDraw = currentTime - lastDrawTime
                
                if (timeSinceLastDraw >= MIN_FRAME_TIME_MS) {
                    processActiveWindow()
                    updateVisualizationIfNeeded()
                    lastDrawTime = currentTime
                }
            }
            mainHandler.postDelayed(this, REFRESH_INTERVAL_MS)
        }
    }
    
    private val visualizationRunnable = object : Runnable {
        override fun run() {
            if (isInitialized) {
                updateVisualizationIfNeeded()
            }
            mainHandler.postDelayed(this, REFRESH_INTERVAL_MS)
        }
    }

    private fun startPeriodicUpdates() {
        lastDrawTime = System.currentTimeMillis()
        mainHandler.postDelayed(updateRunnable, REFRESH_INTERVAL_MS)
    }
    
    private fun stopPeriodicUpdates() {
        mainHandler.removeCallbacks(updateRunnable)
    }
    
    private fun startVisualizationUpdates() {
        mainHandler.postDelayed(visualizationRunnable, VISUALIZATION_REFRESH_MS)
    }

    private fun updateVisualizationIfNeeded() {
        if (!isOverlayManagerAvailable() || visibleElements.isEmpty()) {
            pendingVisualizationUpdate = false
            return
        }
        
        try {
            val elementsToProcess = visibleElements.map { element -> 
                Pair(element, element.calculateWeight())
            }
            
            val weightSortedElements = elementsToProcess
                .filter { (_, weight) -> weight > MIN_DISPLAY_WEIGHT }
                .sortedByDescending { (_, weight) -> weight }
            
            if (!overlayVisible) {
                pendingVisualizationUpdate = false
                return
            }
            
            if (overlayVisible) {
                overlayManager.clearElements()
                
                for ((element, weight) in weightSortedElements) {
                    val heatmapColor = calculateHeatmapColor(weight)
                    
                    overlayManager.addElement(
                        rect = element.rect,
                        type = "${element.className}", 
                        text = element.text,
                        depth = element.windowLayer,
                        color = heatmapColor
                    )
                }
                
                overlayManager.refreshOverlay()
            }
            
            pendingVisualizationUpdate = false
        } catch (e: Exception) {
            Log.e(TAG, "Error updating visualization: ${e.message}", e)
            pendingVisualizationUpdate = true
        }
    }
}

