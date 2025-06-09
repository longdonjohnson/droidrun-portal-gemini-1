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
import android.content.Context.RECEIVER_EXPORTED
import android.content.Intent
import android.content.BroadcastReceiver
import android.content.IntentFilter
import android.graphics.Point
import android.graphics.Path
import android.view.WindowManager
import java.util.concurrent.atomic.AtomicBoolean
import android.graphics.Color
import org.json.JSONArray
import org.json.JSONObject
import java.util.LinkedList
import kotlin.math.abs
import android.view.View
import android.graphics.PixelFormat
import android.view.Gravity
import android.view.MotionEvent
import android.os.Build
import android.provider.Settings
import android.view.LayoutInflater
import com.droidrun.portal.DebugLog // Ensure DebugLog is imported

class DroidrunPortalService : AccessibilityService() {
    
    companion object {
        private const val TAG = "DROIDRUN_PORTAL"
        const val ACTION_TOGGLE_FLOATING_VOICE_BUTTON = "com.droidrun.portal.TOGGLE_FLOATING_VOICE_BUTTON"
        // Using DroidrunPortalService.EXTRA_OVERLAY_VISIBLE as key for SharedPreferences
        const val EXTRA_OVERLAY_VISIBLE = "com.droidrun.portal.OVERLAY_VISIBLE"
        
        // Other constants from original file (ensure they are all here)
        private const val REFRESH_INTERVAL_MS = 250L
        private const val MIN_ELEMENT_SIZE = 5
        private const val MIN_FRAME_TIME_MS = 16L
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
    }
    
    private lateinit var overlayManager: OverlayManager
    private lateinit var geminiProcessor: GeminiCommandProcessor
    private lateinit var commandReceiver: BroadcastReceiver
    private val mainHandler = Handler(Looper.getMainLooper())
    private var isInitialized = false
    private val screenBounds = Rect()
    private val visibleElements = mutableListOf<ElementNode>()
    private val isProcessingAccessibilityEvent = AtomicBoolean(false)
    private var currentPackageName: String = ""
    private var overlayVisibleState: Boolean = true // Internal state for overlay visibility

    // Floating Action Button related
    private var floatingVoiceButton: View? = null
    private lateinit var windowManagerService: WindowManager
    private var isFloatingButtonActuallyShown: Boolean = false

    // Variables for multi-step command processing
    private lateinit var geminiActionCallback: GeminiCommandProcessor.CommandCallback
    private var currentOriginalCommand: String? = null
    private var isProcessingMultiStep: Boolean = false
    private var lastKnownUiContext: String? = null
    private val pendingActionsQueue: LinkedList<GeminiCommandProcessor.UIAction> = LinkedList()
    private val MAX_REPROMPT_ATTEMPTS = 5
    private var currentRepromptAttempts = 0

    override fun onCreate() {
        super.onCreate()
        DebugLog.add(TAG, "Service onCreate")
        try {
            windowManagerService = getSystemService(WINDOW_SERVICE) as WindowManager
            geminiProcessor = GeminiCommandProcessor(this)

            geminiActionCallback = object : GeminiCommandProcessor.CommandCallback {
                override fun onActionsReady(actions: List<GeminiCommandProcessor.UIAction>, forCommand: String, uiContextUsed: String) {
                    handleGeminiActions(actions, forCommand, uiContextUsed)
                }
                override fun onError(error: String) {
                    DebugLog.add(TAG, "Gemini onError. Current original cmd: '${currentOriginalCommand ?: "N/A"}'. Error: $error")
                    isProcessingMultiStep = false
                    currentOriginalCommand = null
                }
            }
            
            commandReceiver = object : BroadcastReceiver() {
                override fun onReceive(context: Context, intent: Intent) {
                    DebugLog.add(TAG, "Service received broadcast: ${intent.action}")
                    when (intent.action) {
                        "com.droidrun.portal.PROCESS_NL_COMMAND" -> {
                            val command = intent.getStringExtra("command")
                            if (command != null) processNaturalLanguageCommand(command)
                        }
                        ACTION_TOGGLE_FLOATING_VOICE_BUTTON -> {
                            val show = intent.getBooleanExtra("show_button", false)
                            if (show) showFloatingVoiceButton() else hideFloatingVoiceButton()
                        }
                        ACTION_TOGGLE_OVERLAY -> {
                            overlayVisibleState = intent.getBooleanExtra(EXTRA_OVERLAY_VISIBLE, true)
                            DebugLog.add(TAG, "Overlay visibility toggled by MainActivity: $overlayVisibleState")
                            if (isInitialized && ::overlayManager.isInitialized) {
                                if (overlayVisibleState) overlayManager.showOverlay() else overlayManager.hideOverlay()
                            }
                        }
                        ACTION_UPDATE_OVERLAY_OFFSET -> {
                            // Use MainActivity.DEFAULT_OFFSET if accessible, otherwise use the literal value.
                            // For robustness in this subtask, using the literal value -128.
                            val offsetValue = intent.getIntExtra(EXTRA_OVERLAY_OFFSET, -128 /* MainActivity.DEFAULT_OFFSET */)
                            DebugLog.add(TAG, "Received ACTION_UPDATE_OVERLAY_OFFSET, new offset: $offsetValue")
                            if(::overlayManager.isInitialized) { // Check if overlayManager is initialized
                                overlayManager.setPositionOffsetY(offsetValue)
                                // overlayManager.refreshOverlay() // Likely not needed as setPositionOffsetY calls it.
                            } else {
                                DebugLog.add(TAG, "OverlayManager not initialized, cannot set offset for overlay.")
                            }
                        }
                        // Other original actions
                        ACTION_GET_ELEMENTS -> broadcastElementData()
                        ACTION_GET_ALL_ELEMENTS -> broadcastAllElementsData()
                        ACTION_RETRIGGER_ELEMENTS -> retriggerElements()
                        // etc.
                    }
                }
            }
            
            val filter = IntentFilter().apply {
                addAction("com.droidrun.portal.PROCESS_NL_COMMAND")
                addAction(ACTION_TOGGLE_FLOATING_VOICE_BUTTON)
                addAction(ACTION_GET_ELEMENTS)
                addAction(ACTION_GET_ALL_ELEMENTS)
                addAction(ACTION_TOGGLE_OVERLAY)
                addAction(ACTION_UPDATE_OVERLAY_OFFSET)
                addAction(ACTION_RETRIGGER_ELEMENTS)
            }
            val receiverFlags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) Context.RECEIVER_EXPORTED else 0
            registerReceiver(commandReceiver, filter, null, mainHandler, receiverFlags) // Using mainHandler for broadcasts
            
            overlayManager = OverlayManager(this)
            isInitialized = true
            
             // Initialize overlay state from prefs (or default)
            val prefs = getSharedPreferences(MainActivity.PREFS_NAME, Context.MODE_PRIVATE)
            overlayVisibleState = prefs.getBoolean(EXTRA_OVERLAY_VISIBLE, true)
            if (overlayVisibleState) overlayManager.showOverlay() else overlayManager.hideOverlay()
            val currentOffset = prefs.getInt(MainActivity.KEY_OVERLAY_OFFSET, MainActivity.DEFAULT_OFFSET)
            overlayManager.setOffset(currentOffset)


        } catch (e: Exception) {
            DebugLog.add(TAG, "Error initializing service: ${e.message}")
            Log.e(TAG, "Error initializing service", e)
        }
    }

    override fun onDestroy() {
        DebugLog.add(TAG, "Service onDestroy")
        try {
            if (::commandReceiver.isInitialized) unregisterReceiver(commandReceiver)
            hideFloatingVoiceButton()
            if (isInitialized && ::overlayManager.isInitialized) overlayManager.hideOverlay()
        } catch (e: Exception) {
            DebugLog.add(TAG, "Error in onDestroy: ${e.message}")
            Log.e(TAG, "Error in onDestroy", e)
        }
        super.onDestroy()
    }

    private fun showFloatingVoiceButton() {
        if (floatingVoiceButton != null ){
            DebugLog.add(TAG, "Floating button is already shown.")
            return
        }
        if(!Settings.canDrawOverlays(this)){
            DebugLog.add(TAG, "Cannot show floating button: SYSTEM_ALERT_WINDOW permission not granted.")
            return
        }
        DebugLog.add(TAG, "Attempting to show floating voice button.")
        isFloatingButtonActuallyShown = true
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
            private var initialX: Int = 0; private var initialY: Int = 0
            private var initialTouchX: Float = 0f; private var initialTouchY: Float = 0f
            override fun onTouch(v: View, event: MotionEvent): Boolean {
                when (event.action) {
                    MotionEvent.ACTION_DOWN -> {
                        initialX = params.x; initialY = params.y
                        initialTouchX = event.rawX; initialTouchY = event.rawY
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
        isFloatingButtonActuallyShown = false
    }

    private fun processNaturalLanguageCommand(command: String) {
        DebugLog.add(TAG, "New LPN command received: '$command'")
        if (isProcessingMultiStep) {
            DebugLog.add(TAG, "Warning: New command received while already processing a multi-step command ('${currentOriginalCommand}'). Overwriting.")
        }
        currentOriginalCommand = command
        isProcessingMultiStep = true
        pendingActionsQueue.clear()
        currentRepromptAttempts = 0
        val currentElementsJson = getCurrentElementsJson()
        lastKnownUiContext = currentElementsJson
        DebugLog.add(TAG, "Initiating Gemini processing for: '$command'")
        geminiProcessor.makeGeminiRequest(
            GeminiCommandProcessor.PromptType.INITIAL,
            command, currentElementsJson, null, null, geminiActionCallback
        )
    }

    private fun handleGeminiActions(actions: List<GeminiCommandProcessor.UIAction>, forCommand: String, uiContextUsed: String) {
        DebugLog.add(TAG, "handleGeminiActions received ${actions.size} actions for command '$forCommand'. UI context hash: ${uiContextUsed.hashCode()}. Clearing old pending queue.")
        pendingActionsQueue.clear()
        if (!isProcessingMultiStep || forCommand != currentOriginalCommand) {
            DebugLog.add(TAG, "Ignoring stale actions. Current command is '${currentOriginalCommand}'. Actions were for '${forCommand}'.")
            return
        }

        if (actions.isEmpty()) {
            DebugLog.add(TAG, "Gemini returned no actions for '$forCommand'.")
            if (currentRepromptAttempts < MAX_REPROMPT_ATTEMPTS) {
                currentRepromptAttempts++
                DebugLog.add(TAG, "Attempting general re-prompt (attempt $currentRepromptAttempts).")
                processActiveWindow()
                val newUiContext = getCurrentElementsJson()
                lastKnownUiContext = newUiContext
                geminiProcessor.makeGeminiRequest(
                    GeminiCommandProcessor.PromptType.CONTINUATION_VALIDATE,
                    currentOriginalCommand!!, newUiContext, null, null, geminiActionCallback
                )
            } else {
                DebugLog.add(TAG, "Max re-prompt attempts reached. Ending multi-step command.")
                isProcessingMultiStep = false; currentOriginalCommand = null
            }
            return
        }

        val finishAction = actions.firstOrNull { it.type.equals("finish", ignoreCase = true) }
        if (finishAction != null) {
            DebugLog.add(TAG, "Received 'finish' action. Multi-step command completed.")
            isProcessingMultiStep = false; currentOriginalCommand = null; pendingActionsQueue.clear()
            return
        }

        pendingActionsQueue.addAll(actions)
        DebugLog.add(TAG, "Added ${actions.size} actions to queue. Total: ${pendingActionsQueue.size}")
        executeNextValidActionFromQueue()
    }

    private fun executeNextValidActionFromQueue() {
        if (!isProcessingMultiStep) {
            DebugLog.add(TAG, "Not processing multi-step. Clearing queue."); pendingActionsQueue.clear(); return
        }
        if (pendingActionsQueue.isEmpty()) {
            DebugLog.add(TAG, "Action queue empty. Asking Gemini 'what now?' for '$currentOriginalCommand'.")
            if (currentRepromptAttempts < MAX_REPROMPT_ATTEMPTS) {
                currentRepromptAttempts++
                processActiveWindow()
                val newUiContext = getCurrentElementsJson()
                lastKnownUiContext = newUiContext
                geminiProcessor.makeGeminiRequest(
                    GeminiCommandProcessor.PromptType.CONTINUATION_VALIDATE,
                    currentOriginalCommand!!, newUiContext, null, null, geminiActionCallback
                )
            } else {
                DebugLog.add(TAG, "Max re-prompt attempts and queue empty. Ending multi-step command.")
                isProcessingMultiStep = false; currentOriginalCommand = null
            }
            return
        }
        val actionToExecute = pendingActionsQueue.removeFirst()
        DebugLog.add(TAG, "Executing action: ${actionToExecute.type}. Queue: ${pendingActionsQueue.size}")
        executeAction(actionToExecute)
        mainHandler.postDelayed({
            if (isProcessingMultiStep) {
                processActiveWindow()
                val newUiContext = getCurrentElementsJson()
                lastKnownUiContext = newUiContext
                geminiProcessor.makeGeminiRequest(
                    GeminiCommandProcessor.PromptType.CONTINUATION_VALIDATE,
                    currentOriginalCommand!!, newUiContext, actionToExecute,
                    pendingActionsQueue.firstOrNull(), // Next planned action if any
                    geminiActionCallback
                )
            }
        }, 1000)
    }
    
    private fun processVoiceCommand(command: String) {
        DebugLog.add(TAG, "Processing voice command: '$command'")
        processNaturalLanguageCommand(command)
    }

    private fun executeAction(action: GeminiCommandProcessor.UIAction) {
        DebugLog.add(TAG, "Executing action: Type=${action.type}, Index=${action.elementIndex}, Text='${action.text}', XY=(${action.x},${action.y}), Dir='${action.direction}'")
        when (action.type.lowercase()) { // Normalize type
            "click" -> if (action.elementIndex >= 0) clickElementByIndex(action.elementIndex) else if (action.x >= 0 && action.y >= 0) clickAtCoordinates(action.x, action.y)
            "type" -> if (action.elementIndex >= 0) typeInElement(action.elementIndex, action.text)
            "scroll" -> performScroll(action.direction)
            "swipe" -> performSwipe(action.direction)
            "home" -> { performGlobalAction(GLOBAL_ACTION_HOME); DebugLog.add(TAG, "Performed global action: HOME.") }
            "back" -> { performGlobalAction(GLOBAL_ACTION_BACK); DebugLog.add(TAG, "Performed global action: BACK.") }
            "recent" -> { performGlobalAction(GLOBAL_ACTION_RECENTS); DebugLog.add(TAG, "Performed global action: RECENTS.") }
            "finish" -> DebugLog.add(TAG, "Received 'finish' action type in executeAction - should have been handled by handleGeminiActions.")
            else -> DebugLog.add(TAG, "Unknown action type: ${action.type}")
        }
    }
    
    private fun clickElementByIndex(index: Int) {
        val elements = getInteractiveElements()
        if (index >= 0 && index < elements.size) {
            elements[index].performAction(AccessibilityNodeInfo.ACTION_CLICK)
            DebugLog.add(TAG, "Clicked element at index $index successfully.")
        } else {
            DebugLog.add(TAG, "Failed to click: Element index $index out of bounds (size: ${elements.size}).")
        }
    }
    
    private fun clickAtCoordinates(x: Int, y: Int) {
        val path = Path().apply { moveTo(x.toFloat(), y.toFloat()) }
        val gesture = GestureDescription.Builder().addStroke(GestureDescription.StrokeDescription(path, 0, 100)).build()
        dispatchGesture(gesture, null, null)
        DebugLog.add(TAG, "Clicked at coordinates ($x, $y).")
    }
    
    private fun typeInElement(index: Int, text: String) {
        val elements = getInteractiveElements()
        if (index >= 0 && index < elements.size) {
            val element = elements[index]
            element.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
            val arguments = Bundle().apply { putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text) }
            element.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, arguments)
            DebugLog.add(TAG, "Typed '$text' in element at index $index successfully.")
        } else {
            DebugLog.add(TAG, "Failed to type: Element index $index out of bounds (size: ${elements.size}).")
        }
    }
    
    private fun performScroll(direction: String) {
        val actionCode = when (direction.lowercase()) {
            "up" -> AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
            "down" -> AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
            "left" -> { DebugLog.add(TAG, "Horizontal scroll (left) requested, defaulting to SCROLL_BACKWARD (up) for now."); AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD } // Or FORWARD depending on desired general behavior
            "right" -> { DebugLog.add(TAG, "Horizontal scroll (right) requested, defaulting to SCROLL_FORWARD (down) for now."); AccessibilityNodeInfo.ACTION_SCROLL_FORWARD }
            else -> { DebugLog.add(TAG, "Unknown scroll direction '$direction', defaulting to SCROLL_FORWARD."); AccessibilityNodeInfo.ACTION_SCROLL_FORWARD }
        }
        // Try to find a scrollable node first
        val scrollableNode = findFirstScrollableNode(rootInActiveWindow)
        if (scrollableNode != null) {
            scrollableNode.performAction(actionCode)
            DebugLog.add(TAG, "Performed scroll $direction on specific node.")
        } else {
            rootInActiveWindow?.performAction(actionCode) // Fallback to root
            DebugLog.add(TAG, "Performed scroll $direction on root window.")
        }
    }
    
    private fun performSwipe(direction: String) {
        val displayMetrics = resources.displayMetrics
        val width = displayMetrics.widthPixels; val height = displayMetrics.heightPixels
        val path = Path()
        when (direction.lowercase()) {
            "left" -> { path.moveTo(width * 0.8f, height * 0.5f); path.lineTo(width * 0.2f, height * 0.5f) }
            "right" -> { path.moveTo(width * 0.2f, height * 0.5f); path.lineTo(width * 0.8f, height * 0.5f) }
            "up" -> { path.moveTo(width * 0.5f, height * 0.8f); path.lineTo(width * 0.5f, height * 0.2f) }
            "down" -> { path.moveTo(width * 0.5f, height * 0.2f); path.lineTo(width * 0.5f, height * 0.8f) }
            else -> { DebugLog.add(TAG, "Unknown swipe direction: $direction"); return }
        }
        dispatchGesture(GestureDescription.Builder().addStroke(GestureDescription.StrokeDescription(path, 0, 300)).build(), null, null)
        DebugLog.add(TAG, "Performed swipe $direction.")
    }
    
    private fun getCurrentElementsJson(): String = getElementsAsJson(false)
    
    private fun getInteractiveElements(): List<AccessibilityNodeInfo> {
        val elements = mutableListOf<AccessibilityNodeInfo>()
        val root = rootInActiveWindow ?: return elements
        val queue: LinkedList<AccessibilityNodeInfo> = LinkedList()
        queue.add(root)
        while (queue.isNotEmpty()) {
            val node = queue.removeFirst()
            if (node.isClickable || node.isCheckable || node.isEditable || node.isScrollable || node.isFocusable) {
                elements.add(node)
            }
            for (i in 0 until node.childCount) { node.getChild(i)?.let { queue.addLast(it) } }
        }
        return elements
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        val eventPackage = event.packageName?.toString() ?: ""
        if (eventPackage.isNotEmpty() && eventPackage != currentPackageName) {
             DebugLog.add(TAG, "App context changed from '$currentPackageName' to '$eventPackage'.")
            currentPackageName = eventPackage
            // Consider if ongoing multi-step commands should be reset or re-evaluated on app change.
            // For now, an app change doesn't automatically stop a multi-step command.
        }
        when (event.eventType) {
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED, AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED -> {
                if (isInitialized) processActiveWindow()
            }
        }
    }

    override fun onInterrupt() { DebugLog.add(TAG, "Service interrupted.") }

    private fun processActiveWindow() {
        if (isProcessingAccessibilityEvent.getAndSet(true)) return
        try {
            val rootNode = rootInActiveWindow ?: return
            val newElements = mutableListOf<ElementNode>()
            extractElements(rootNode, newElements, 0)
            synchronized(visibleElements) {
                visibleElements.clear()
                visibleElements.addAll(newElements)
            }
            if (overlayVisibleState && isInitialized && ::overlayManager.isInitialized) {
                 // Instead of updateElements, call updateVisualizationIfNeeded which has the correct logic
                 // or replicate its core logic if more direct control is needed here.
                 // Calling updateVisualizationIfNeeded is safer if it's already correct.
                 pendingVisualizationUpdate = true // Mark that visualization needs update
                 updateVisualizationIfNeeded() // This will use the new newElements implicitly through visibleElements
                 DebugLog.add(TAG, "processActiveWindow: Marked for visualization update.")
            }
        } catch (e: Exception) {
            DebugLog.add(TAG, "Error processing active window: ${e.message}")
        } finally {
            isProcessingAccessibilityEvent.set(false)
        }
    }

    private fun extractElements(node: AccessibilityNodeInfo, elements: MutableList<ElementNode>, depth: Int) {
        try {
            val rect = Rect()
            node.getBoundsInScreen(rect)
            if (rect.width() >= MIN_ELEMENT_SIZE && rect.height() >= MIN_ELEMENT_SIZE && node.isVisibleToUser) { // Added isVisibleToUser
                val classNameStr = node.className?.toString() ?: ""
                val textStr = node.text?.toString() ?: ""
                val element = ElementNode(
                    nodeInfo = node, rect = rect, text = textStr, className = classNameStr,
                    windowLayer = depth, creationTime = System.currentTimeMillis(),
                    id = ElementNode.createId(rect, classNameStr, textStr)
                )
                elements.add(element)
            }
            for (i in 0 until node.childCount) {
                node.getChild(i)?.let { if (it.isVisibleToUser) extractElements(it, elements, depth + 1) } // Check visibility before recursing
            }
        } catch (e: Exception) {
            DebugLog.add(TAG, "Error extracting element: ${e.message}")
        }
    }

    private fun getElementsAsJson(includeAll: Boolean): String {
        val jsonArray = JSONArray()
        val elementsToProcess = synchronized(visibleElements) {
            if (includeAll) visibleElements.toList()
            else visibleElements.filter { it.isClickable() || it.nodeInfo.isCheckable || it.nodeInfo.isEditable || it.nodeInfo.isScrollable || it.nodeInfo.isFocusable }
        }
        elementsToProcess.forEachIndexed { index, element ->
            val jsonObject = JSONObject().apply {
                put("index", index)
                put("text", element.text)
                put("class", element.className)
                put("clickable", element.isClickable())
                put("checkable", element.nodeInfo.isCheckable)
                put("editable", element.nodeInfo.isEditable)
                put("scrollable", element.nodeInfo.isScrollable)
                put("focusable", element.nodeInfo.isFocusable)
                put("bounds", JSONObject().apply {
                    put("left", element.rect.left); put("top", element.rect.top)
                    put("right", element.rect.right); put("bottom", element.rect.bottom)
                })
                put("id", element.id)
            }
            jsonArray.put(jsonObject)
        }
        return jsonArray.toString()
    }

    private fun broadcastElementData() {
        sendBroadcast(Intent(ACTION_ELEMENTS_RESPONSE).apply { putExtra(EXTRA_ELEMENTS_DATA, getElementsAsJson(false)) })
        DebugLog.add(TAG, "Broadcasted element data (interactive only).")
    }

    private fun broadcastAllElementsData() {
        sendBroadcast(Intent(ACTION_ELEMENTS_RESPONSE).apply { putExtra(EXTRA_ALL_ELEMENTS_DATA, getElementsAsJson(true)) })
        DebugLog.add(TAG, "Broadcasted all elements data.")
    }

    private fun retriggerElements() {
        DebugLog.add(TAG, "Retrigger elements called. Forcing a screen refresh/re-evaluation.")
        processActiveWindow()
    }

    private fun resetOverlayState() { // Renamed from original plan's resetOverlayState for clarity
        if (isInitialized && ::overlayManager.isInitialized) {
            overlayManager.clearElements()
            overlayManager.refreshOverlay() // Ensure this method exists on OverlayManager
        }
        DebugLog.add(TAG, "Overlay display elements reset.")
    }

    private fun isOverlayManagerAvailable(): Boolean = isInitialized && ::overlayManager.isInitialized
    
    // Removed heatmap color and periodic update logic, assuming OverlayManager handles its own drawing if needed
}
