package com.droidrun.portal

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Point
import android.graphics.Path
import android.graphics.Rect
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.droidrun.portal.DebugLog
import com.droidrun.portal.GeminiCommandProcessor
import java.util.LinkedList
import java.util.concurrent.atomic.AtomicBoolean
import org.json.JSONArray
import org.json.JSONObject
// import com.droidrun.portal.UiUtils.findFirstScrollableNode // Assuming findFirstScrollableNode is made private in this file

class DroidrunPortalService : AccessibilityService() {

    companion object {
        private const val TAG = "DroidRunPortalSvc"
        const val ACTION_TOGGLE_FLOATING_VOICE_BUTTON = "com.droidrun.portal.TOGGLE_FLOATING_VOICE_BUTTON"
        const val ACTION_GET_ELEMENTS = "com.droidrun.portal.GET_ELEMENTS"
        const val ACTION_ELEMENTS_RESPONSE = "com.droidrun.portal.ELEMENTS_RESPONSE"
        const val ACTION_TOGGLE_OVERLAY = "com.droidrun.portal.TOGGLE_OVERLAY" // Used by MainActivity
        const val ACTION_RETRIGGER_ELEMENTS = "com.droidrun.portal.RETRIGGER_ELEMENTS"
        const val ACTION_GET_ALL_ELEMENTS = "com.droidrun.portal.GET_ALL_ELEMENTS"
        const val ACTION_GET_INTERACTIVE_ELEMENTS = "com.droidrun.portal.GET_INTERACTIVE_ELEMENTS"
        const val ACTION_FORCE_HIDE_OVERLAY = "com.droidrun.portal.FORCE_HIDE_OVERLAY"
        // ACTION_UPDATE_OVERLAY_OFFSET is defined in MainActivity
        // EXTRA_OVERLAY_OFFSET is defined in MainActivity
        const val EXTRA_ELEMENTS_DATA = "elements_data"
        const val EXTRA_ALL_ELEMENTS_DATA = "all_elements_data"
        const val EXTRA_OVERLAY_VISIBLE = "overlay_visible" // Used by MainActivity for broadcast, and service for SharedPreferences key

        private const val REFRESH_INTERVAL_MS = 250L
        private const val MIN_ELEMENT_SIZE = 5
        private const val MIN_DISPLAY_WEIGHT = 0.05f // Used in updateVisualizationIfNeeded
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
    private var isOverlayVisuallyEnabledState: Boolean = true

    private lateinit var geminiActionCallback: GeminiCommandProcessor.CommandCallback
    private var currentOriginalCommand: String? = null
    private var isProcessingMultiStep: Boolean = false
    private var lastKnownUiContext: String? = null
    private val pendingActionsQueue: LinkedList<GeminiCommandProcessor.UIAction> = LinkedList()
    private val MAX_REPROMPT_ATTEMPTS = 5
    private var currentRepromptAttempts = 0

    private var floatingVoiceButton: View? = null
    private lateinit var windowManagerService: WindowManager
    private var isFloatingButtonActuallyShown: Boolean = false

    private val processActiveWindowRunnable = Runnable { processActiveWindow() }
    private var pendingVisualizationUpdate: Boolean = false
    private val updateOverlayVisualizationRunnable: Runnable = object : Runnable {
        override fun run() {
            if (isInitialized) {
                if (pendingVisualizationUpdate && isOverlayVisuallyEnabledState) {
                    updateVisualizationIfNeeded()
                }
            }
            mainHandler.postDelayed(this, REFRESH_INTERVAL_MS)
        }
    }

    override fun onCreate() {
        super.onCreate()
        DebugLog.add(TAG, "onCreate: Service initializing...")
        try {
            windowManagerService = getSystemService(WINDOW_SERVICE) as WindowManager
            geminiProcessor = GeminiCommandProcessor(this)
            overlayManager = OverlayManager(this)

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
                    val action = intent.action
                    DebugLog.add(TAG, "commandReceiver onReceive: $action")
                    when (action) {
                        "com.droidrun.portal.PROCESS_NL_COMMAND" -> {
                            val command = intent.getStringExtra("command")
                            if (command != null) {
                                DebugLog.add(TAG, "Service received PROCESS_NL_COMMAND: '$command'")
                                processNaturalLanguageCommand(command)
                            }
                        }
                        "com.droidrun.portal.PROCESS_VOICE_COMMAND" -> {
                            val command = intent.getStringExtra("command")
                            if (command != null) {
                                processVoiceCommand(command)
                            }
                        }
                        ACTION_TOGGLE_FLOATING_VOICE_BUTTON -> {
                            val show = intent.getBooleanExtra("show_button", false)
                            if (show) showFloatingVoiceButton() else hideFloatingVoiceButton()
                        }
                        MainActivity.ACTION_UPDATE_OVERLAY_OFFSET -> {
                            val offsetValue = intent.getIntExtra(MainActivity.EXTRA_OVERLAY_OFFSET, MainActivity.DEFAULT_OFFSET)
                            DebugLog.add(TAG, "Received ACTION_UPDATE_OVERLAY_OFFSET, new offset: $offsetValue")
                            if (::overlayManager.isInitialized) {
                                overlayManager.setPositionOffsetY(offsetValue)
                            } else {
                                DebugLog.add(TAG, "OverlayManager not initialized, cannot set offset.")
                            }
                        }
                        MainActivity.ACTION_UPDATE_OVERLAY_OFFSET_X -> {
                            val offsetXValue = intent.getIntExtra(MainActivity.EXTRA_OVERLAY_OFFSET_X, MainActivity.DEFAULT_OFFSET_X)
                            DebugLog.add(TAG, "Received ACTION_UPDATE_OVERLAY_OFFSET_X, new X-offset: $offsetXValue")
                            if (::overlayManager.isInitialized) {
                                overlayManager.setPositionOffsetX(offsetXValue)
                            } else {
                                DebugLog.add(TAG, "OverlayManager not initialized, cannot set X-offset for overlay.")
                            }
                        }
                        ACTION_TOGGLE_OVERLAY -> {
                            isOverlayVisuallyEnabledState = intent.getBooleanExtra(EXTRA_OVERLAY_VISIBLE, true)
                            DebugLog.add(TAG, "Overlay visibility toggled: $isOverlayVisuallyEnabledState. Applying.")
                            if (::overlayManager.isInitialized) {
                                if (isOverlayVisuallyEnabledState) {
                                    overlayManager.showOverlay()
                                    pendingVisualizationUpdate = true // Mark for update
                                    updateVisualizationIfNeeded() // Update immediately
                                } else {
                                    overlayManager.hideOverlay()
                                }
                            }
                        }
                        ACTION_GET_ELEMENTS -> broadcastElementData()
                        ACTION_GET_ALL_ELEMENTS -> broadcastAllElementsData()
                        ACTION_RETRIGGER_ELEMENTS -> retriggerElements()
                    }
                }
            }
            
            val filter = IntentFilter().apply {
                addAction("com.droidrun.portal.PROCESS_NL_COMMAND")
                addAction("com.droidrun.portal.PROCESS_VOICE_COMMAND")
                addAction(ACTION_TOGGLE_FLOATING_VOICE_BUTTON)
                addAction(MainActivity.ACTION_UPDATE_OVERLAY_OFFSET)
                addAction(MainActivity.ACTION_UPDATE_OVERLAY_OFFSET_X) // Added X-offset action
                addAction(ACTION_TOGGLE_OVERLAY)
                addAction(ACTION_GET_ELEMENTS)
                addAction(ACTION_GET_ALL_ELEMENTS)
                addAction(ACTION_RETRIGGER_ELEMENTS)
            }
            val receiverFlags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) Context.RECEIVER_EXPORTED else 0
            registerReceiver(commandReceiver, filter, null, mainHandler, receiverFlags)
            
            isInitialized = true
            
            val localWindowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
            @Suppress("DEPRECATION") // For defaultDisplay
            val display = localWindowManager.defaultDisplay
            val size = Point()
            @Suppress("DEPRECATION") // For getSize
            display.getSize(size)
            screenBounds.set(0, 0, size.x, size.y)

            val prefs = getSharedPreferences(MainActivity.PREFS_NAME, Context.MODE_PRIVATE)
            isOverlayVisuallyEnabledState = prefs.getBoolean(MainActivity.KEY_OVERLAY_VISIBLE, true) // Use MainActivity's key
            val currentYOffset = prefs.getInt(MainActivity.KEY_OVERLAY_OFFSET, MainActivity.DEFAULT_OFFSET)
            overlayManager.setPositionOffsetY(currentYOffset)
            val currentOffsetX = prefs.getInt(MainActivity.KEY_OVERLAY_OFFSET_X, MainActivity.DEFAULT_OFFSET_X)
            overlayManager.setPositionOffsetX(currentOffsetX)
            DebugLog.add(TAG, "Initial X-Offset set for OverlayManager: $currentOffsetX")

            if (isOverlayVisuallyEnabledState) {
                overlayManager.showOverlay()
                mainHandler.postDelayed(processActiveWindowRunnable, 500)
            } else {
                overlayManager.hideOverlay()
            }

            mainHandler.postDelayed(updateOverlayVisualizationRunnable, REFRESH_INTERVAL_MS)
            DebugLog.add(TAG, "onCreate: Service initialized. Overlay visible: $isOverlayVisuallyEnabledState, Y-Offset: $currentYOffset, X-Offset: $currentOffsetX")

        } catch (e: Exception) {
            DebugLog.add(TAG, "Error during service onCreate: ${e.message}")
            Log.e(TAG, "Error initializing service", e)
        }
    }

    override fun onDestroy() {
        DebugLog.add(TAG, "onDestroy: Service shutting down.")
        try {
            if (::commandReceiver.isInitialized) {
                unregisterReceiver(commandReceiver)
            }
            hideFloatingVoiceButton()
            mainHandler.removeCallbacks(updateOverlayVisualizationRunnable)
            mainHandler.removeCallbacks(processActiveWindowRunnable)
            if (::overlayManager.isInitialized) {
                overlayManager.hideOverlay()
            }
        } catch (e: Exception) {
            DebugLog.add(TAG, "Error in onDestroy: ${e.message}")
        }
        super.onDestroy()
    }

    private fun processNaturalLanguageCommand(command: String) {
        DebugLog.add(TAG, "processNaturalLanguageCommand: New command: '$command'")
        if (isProcessingMultiStep) {
            DebugLog.add(TAG, "Warning: New command received while already processing '${currentOriginalCommand}'. Overwriting.")
        }
        currentOriginalCommand = command
        isProcessingMultiStep = true
        pendingActionsQueue.clear()
        currentRepromptAttempts = 0

        mainHandler.post { processActiveWindow() }
        mainHandler.postDelayed({
            val currentElementsJson = getCurrentElementsJson()
            lastKnownUiContext = currentElementsJson
            DebugLog.add(TAG, "Initiating Gemini request (INITIAL) for: '$command'. UI Hash: ${currentElementsJson.hashCode()}")
            geminiProcessor.makeGeminiRequest(
                GeminiCommandProcessor.PromptType.INITIAL,
                command, currentElementsJson, null, null, geminiActionCallback
            )
        }, 250)
    }

    private fun handleGeminiActions(actions: List<GeminiCommandProcessor.UIAction>, forCommand: String, uiContextUsed: String) {
        DebugLog.add(TAG, "handleGeminiActions received ${actions.size} actions for command '$forCommand'. UI context hash: ${uiContextUsed.hashCode()}. Clearing old pending queue.")
        pendingActionsQueue.clear()

        if (!isProcessingMultiStep || forCommand != currentOriginalCommand) {
            DebugLog.add(TAG, "Ignoring stale actions. Current command is '${currentOriginalCommand}'. Actions were for '${forCommand}'.")
            return
        }

        val finishAction = actions.firstOrNull { it.type.equals("finish", ignoreCase = true) }
        if (finishAction != null) {
            DebugLog.add(TAG, "Received 'finish' action. Multi-step command '$currentOriginalCommand' completed.")
            isProcessingMultiStep = false; currentOriginalCommand = null; pendingActionsQueue.clear()
            return
        }

        if (actions.isEmpty()) {
            DebugLog.add(TAG, "Gemini returned no actions for '$forCommand'.")
            if (currentRepromptAttempts < MAX_REPROMPT_ATTEMPTS) {
                currentRepromptAttempts++
                DebugLog.add(TAG, "No actions from Gemini, attempting general re-prompt (CONTINUATION_VALIDATE, attempt $currentRepromptAttempts). Last UI Hash: ${lastKnownUiContext?.hashCode()}")
                mainHandler.post { processActiveWindow() }
                mainHandler.postDelayed({
                    val newUiContext = getCurrentElementsJson()
                    lastKnownUiContext = newUiContext
                    DebugLog.add(TAG, "  Calling makeGeminiRequest with PromptType.CONTINUATION_VALIDATE (lastAction=null, nextPlannedAction=null) for empty actions case.")
                    geminiProcessor.makeGeminiRequest(
                        GeminiCommandProcessor.PromptType.CONTINUATION_VALIDATE,
                        currentOriginalCommand!!, newUiContext, null, null, geminiActionCallback
                    )
                }, 200)
            } else {
                DebugLog.add(TAG, "Max re-prompt attempts reached. Ending multi-step command '$currentOriginalCommand'.")
                isProcessingMultiStep = false; currentOriginalCommand = null
            }
            return
        }

        pendingActionsQueue.addAll(actions)
        DebugLog.add(TAG, "Added ${actions.size} actions to queue for '$currentOriginalCommand'. Total: ${pendingActionsQueue.size}")
        executeNextValidActionFromQueue()
    }

    private fun executeNextValidActionFromQueue() {
        DebugLog.add(TAG, "executeNextValidActionFromQueue. isProcessing: $isProcessingMultiStep, Queue size: ${pendingActionsQueue.size}")
        if (!isProcessingMultiStep) {
            DebugLog.add(TAG, "Not processing multi-step. Clearing queue."); pendingActionsQueue.clear(); currentOriginalCommand = null; return
        }
        if (pendingActionsQueue.isEmpty()) {
            DebugLog.add(TAG, "Action queue empty for '$currentOriginalCommand'. Attempting re-prompt (CONTINUATION_VALIDATE 'what now?').")
            if (currentRepromptAttempts < MAX_REPROMPT_ATTEMPTS) {
                currentRepromptAttempts++
                mainHandler.post { processActiveWindow() }
                mainHandler.postDelayed({
                    val newUiContext = getCurrentElementsJson()
                    lastKnownUiContext = newUiContext
                    DebugLog.add(TAG, "  Calling makeGeminiRequest (CONTINUATION_VALIDATE, empty queue). Attempt: $currentRepromptAttempts. UI Hash: ${newUiContext.hashCode()}")
                    geminiProcessor.makeGeminiRequest(
                        GeminiCommandProcessor.PromptType.CONTINUATION_VALIDATE,
                        currentOriginalCommand!!, newUiContext, null, null, geminiActionCallback
                    )
                }, 200)
            } else {
                DebugLog.add(TAG, "Max re-prompt attempts and queue empty for '$currentOriginalCommand'. Ending command.")
                isProcessingMultiStep = false; currentOriginalCommand = null
            }
            return
        }
        val actionToExecute = pendingActionsQueue.removeFirst()
        DebugLog.add(TAG, "Executing action: ${actionToExecute.toString()}. For command: '$currentOriginalCommand'. Remaining in queue for this context: ${pendingActionsQueue.size}")

        executeAction(actionToExecute) // This is synchronous

        val delayMillis = if (actionToExecute.type.equals("click", ignoreCase = true)) {
            DebugLog.add(TAG, "Post-action: 'click' detected, using longer delay (3s) for potential app load.")
            3000L
        } else {
            1000L
        }

        // After action, re-evaluate by asking Gemini for next steps with new context
        mainHandler.postDelayed({
            if (isProcessingMultiStep) {
                DebugLog.add(TAG, "Post-action delay ($delayMillis ms) complete for '$currentOriginalCommand'. Requesting next step from Gemini (CONTINUATION_VALIDATE).")
                mainHandler.post { processActiveWindow() }
                mainHandler.postDelayed({
                    val newUiContext = getCurrentElementsJson()
                    lastKnownUiContext = newUiContext
                    DebugLog.add(TAG, "  Calling makeGeminiRequest (CONTINUATION_VALIDATE, after action). LastAction: ${actionToExecute.type}, NextPlanned: ${pendingActionsQueue.firstOrNull()?.type ?: "null"}. UI Hash: ${newUiContext.hashCode()}")
                    geminiProcessor.makeGeminiRequest(
                        GeminiCommandProcessor.PromptType.CONTINUATION_VALIDATE,
                        currentOriginalCommand!!, newUiContext, actionToExecute,
                        pendingActionsQueue.firstOrNull(),
                        geminiActionCallback
                    )
                }, 200) // This internal 200ms delay is for screen capture after processActiveWindow
            } else {
                 DebugLog.add(TAG, "Post-action delay ($delayMillis ms): No longer processing multi-step for '$currentOriginalCommand'. Not continuing.")
            }
        }, delayMillis) // Use the new conditional delayMillis here
    }
    
    private fun processVoiceCommand(command: String) {
        DebugLog.add(TAG, "Processing voice command: '$command'")
        processNaturalLanguageCommand(command)
    }

    private fun executeAction(action: GeminiCommandProcessor.UIAction) {
        DebugLog.add(TAG, "executeAction: Type=${action.type}, Index=${action.elementIndex}, Text='${action.text}', XY=(${action.x},${action.y}), Dir='${action.direction}'")
        try {
            when (action.type.lowercase()) {
                "click" -> if (action.elementIndex >= 0) clickElementByIndex(action.elementIndex)
                           else if (action.x >= 0 && action.y >= 0) {
                               var adjustedX = action.x
                               if (::overlayManager.isInitialized) {
                                   val offsetX = overlayManager.getPositionOffsetX()
                                   adjustedX -= offsetX // Adjust for current X offset
                                   DebugLog.add(TAG, "Adjusting click X-coordinate: original=${action.x}, offset=$offsetX, new=$adjustedX")
                               } else {
                                   DebugLog.add(TAG, "OverlayManager not init for X-offset, using original X: ${action.x}")
                               }
                               clickAtCoordinates(adjustedX, action.y)
                           } else DebugLog.add(TAG, "Click action invalid: no index or coordinates.")
                "type" -> if (action.elementIndex >= 0) typeInElement(action.elementIndex, action.text) else DebugLog.add(TAG, "Type action invalid: no elementIndex.")
                "scroll" -> performScroll(action.direction)
                "swipe" -> performSwipe(action.direction)
                "home" -> { performGlobalAction(GLOBAL_ACTION_HOME); DebugLog.add(TAG, "Performed global action: HOME.") }
                "back" -> { performGlobalAction(GLOBAL_ACTION_BACK); DebugLog.add(TAG, "Performed global action: BACK.") }
                "recent" -> { performGlobalAction(GLOBAL_ACTION_RECENTS); DebugLog.add(TAG, "Performed global action: RECENTS.") }
                "finish" -> DebugLog.add(TAG, "Received 'finish' type in executeAction - handled by handleGeminiActions.")
                else -> DebugLog.add(TAG, "Unknown action type: ${action.type}")
            }
        } catch (e: Exception) {
            DebugLog.add(TAG, "Exception during executeAction (${action.type}): ${e.message}")
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
        DebugLog.add(TAG, "Attempting click at coordinates ($x, $y)")
        val path = Path().apply { moveTo(x.toFloat(), y.toFloat()) }
        val gesture = GestureDescription.Builder().addStroke(GestureDescription.StrokeDescription(path, 0, 100)).build()
        dispatchGesture(gesture, null, null)
    }
    
    private fun typeInElement(index: Int, text: String) {
        val elements = getInteractiveElements()
        if (index >= 0 && index < elements.size) {
            val node = elements[index]
            node.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
            val arguments = Bundle().apply { putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text) }
            node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, arguments)
            DebugLog.add(TAG, "Typed '$text' in element at index $index successfully.")
        } else {
            DebugLog.add(TAG, "Failed to type: Element index $index out of bounds (size: ${elements.size}).")
        }
    }
    
    private fun performScroll(direction: String) {
        val actionCode = when (direction.lowercase()) {
            "up" -> AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
            "down" -> AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
            "left" -> { DebugLog.add(TAG, "Horizontal scroll (left) requested, defaulting to SCROLL_BACKWARD (up)."); AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD }
            "right" -> { DebugLog.add(TAG, "Horizontal scroll (right) requested, defaulting to SCROLL_FORWARD (down)."); AccessibilityNodeInfo.ACTION_SCROLL_FORWARD }
            else -> { DebugLog.add(TAG, "Unknown scroll direction: $direction"); return }
        }
        val scrollableNode = findFirstScrollableNode(rootInActiveWindow)
        if (scrollableNode != null) {
            scrollableNode.performAction(actionCode); DebugLog.add(TAG, "Performed scroll $direction on specific node.")
        } else {
            rootInActiveWindow?.performAction(actionCode); DebugLog.add(TAG, "Performed scroll $direction on root window.")
        }
    }

    private fun findFirstScrollableNode(rootNode: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
        if (rootNode == null) return null
        val queue = LinkedList<AccessibilityNodeInfo>().apply { add(rootNode) }
        while (queue.isNotEmpty()) {
            val node = queue.removeFirst()
            if (node.isScrollable) return node
            for (i in 0 until node.childCount) { node.getChild(i)?.let { queue.addLast(it) } }
        }
        return null
    }
    
    private fun performSwipe(direction: String) {
        DebugLog.add(TAG, "Attempting swipe $direction")
        val displayMetrics = resources.displayMetrics
        val width = displayMetrics.widthPixels; val height = displayMetrics.heightPixels
        val path = Path()
        val midX = width / 2f; val midY = height / 2f
        val swipeLength = width / 3f
        when (direction.lowercase()) {
            "left" -> { path.moveTo(midX + swipeLength / 2, midY); path.lineTo(midX - swipeLength / 2, midY) }
            "right" -> { path.moveTo(midX - swipeLength / 2, midY); path.lineTo(midX + swipeLength / 2, midY) }
            "up" -> { path.moveTo(midX, midY + swipeLength / 2); path.lineTo(midX, midY - swipeLength / 2) }
            "down" -> { path.moveTo(midX, midY - swipeLength / 2); path.lineTo(midX, midY + swipeLength / 2) }
            else -> { DebugLog.add(TAG, "Unknown swipe direction: $direction"); return }
        }
        dispatchGesture(GestureDescription.Builder().addStroke(GestureDescription.StrokeDescription(path, 0, 200)).build(), null, null)
        DebugLog.add(TAG, "Dispatched swipe $direction gesture.")
    }

    private fun getCurrentElementsJson(): String = getElementsAsJson(false)
    
    private fun getInteractiveElements(): List<AccessibilityNodeInfo> {
        val elements = mutableListOf<AccessibilityNodeInfo>()
        val root = rootInActiveWindow ?: return elements
        val queue: LinkedList<AccessibilityNodeInfo> = LinkedList()
        root.let { queue.add(it) } // Use add, not addLast for initial
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
        }
        when (event.eventType) {
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED,
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED,
            AccessibilityEvent.TYPE_VIEW_SCROLLED -> {
                if (isInitialized) {
                    mainHandler.removeCallbacks(processActiveWindowRunnable)
                    val delay = if (isProcessingMultiStep) 500L else 150L
                    mainHandler.postDelayed(processActiveWindowRunnable, delay)
                    DebugLog.add(TAG, "Scheduled processActiveWindow with delay: $delay ms (isProcessingMultiStep: $isProcessingMultiStep)")
                }
            }
        }
    }

    override fun onInterrupt() { DebugLog.add(TAG, "Service interrupted.") }

    private fun processActiveWindow() {
        if (isProcessingAccessibilityEvent.getAndSet(true)) {
            DebugLog.add(TAG, "processActiveWindow: Already processing, skipping.")
            return
        }
        DebugLog.add(TAG, "processActiveWindow triggered.")
        try {
            val rootNode = rootInActiveWindow
            if (rootNode != null) {
                val newElements = mutableListOf<ElementNode>()
                extractElements(rootNode, newElements, 0)
                synchronized(visibleElements) {
                    visibleElements.clear()
                    visibleElements.addAll(newElements)
                }
                if (isOverlayVisuallyEnabledState) {
                    pendingVisualizationUpdate = true
                    // No direct call to updateVisualizationIfNeeded, let periodic updater handle
                }
                 DebugLog.add(TAG, "processActiveWindow: ${newElements.size} elements extracted.")
            } else {
                DebugLog.add(TAG, "processActiveWindow: rootInActiveWindow is null.")
            }
        } catch (e: Exception) {
            DebugLog.add(TAG, "Error processing active window: ${e.message}")
        } finally {
            isProcessingAccessibilityEvent.set(false)
        }
    }

    private fun extractElements(node: AccessibilityNodeInfo?, elements: MutableList<ElementNode>, depth: Int) {
        if (node == null) return
        try {
            val rect = Rect()
            node.getBoundsInScreen(rect)
            if (rect.width() >= MIN_ELEMENT_SIZE && rect.height() >= MIN_ELEMENT_SIZE && node.isVisibleToUser) {
                val classNameStr = node.className?.toString() ?: ""
                val textStr = node.text?.toString() ?: node.contentDescription?.toString() ?: ""
                elements.add(ElementNode(
                    nodeInfo = node,
                    rect = rect,
                    text = textStr,
                    className = classNameStr,
                    windowLayer = depth,
                    creationTime = System.currentTimeMillis(),
                    id = ElementNode.createId(rect, classNameStr, textStr)
                ))
            }
            for (i in 0 until node.childCount) {
                node.getChild(i)?.let { if (it.isVisibleToUser) extractElements(it, elements, depth + 1) }
            }
        } catch (e: Exception) {
            DebugLog.add(TAG, "Error extracting element: ${node.className} - ${e.message}")
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

    private fun updateVisualizationIfNeeded() {
        if (!isInitialized || !::overlayManager.isInitialized) {
            DebugLog.add(TAG, "updateVisualizationIfNeeded: OverlayManager not available or service not initialized.")
            return
        }
        if (!isOverlayVisuallyEnabledState) {
            DebugLog.add(TAG, "updateVisualizationIfNeeded: Overlay not visually enabled, ensuring it's hidden.")
            overlayManager.hideOverlay()
            pendingVisualizationUpdate = false
            return
        }

        overlayManager.showOverlay()
        overlayManager.clearElements()

        val elementsToDraw = synchronized(visibleElements) { visibleElements.toList() }

        if (elementsToDraw.isEmpty()) {
             DebugLog.add(TAG, "updateVisualizationIfNeeded: No visible elements to draw, overlay cleared.")
        } else {
            DebugLog.add(TAG, "updateVisualizationIfNeeded: Updating overlay with ${elementsToDraw.size} elements.")
        }

        for (element in elementsToDraw) {
            val weight = element.calculateWeight()
            if (weight > MIN_DISPLAY_WEIGHT) {
                 val heatmapColor = calculateHeatmapColor(weight)
                 overlayManager.addElement(
                     rect = element.rect,
                     type = element.className,
                     text = element.text,
                     depth = element.windowLayer,
                     color = heatmapColor
                 )
            }
        }
        overlayManager.refreshOverlay()
        pendingVisualizationUpdate = false
    }

    private fun calculateHeatmapColor(weight: Float): Int {
        val red = (255 * weight).toInt().coerceIn(0, 255)
        val blue = (255 * (1 - weight)).toInt().coerceIn(0, 255)
        return Color.rgb(red, 0, blue)
    }
    
    private fun showFloatingVoiceButton() {
        if (!Settings.canDrawOverlays(this)) {
            DebugLog.add(TAG, "Cannot show floating button: SYSTEM_ALERT_WINDOW permission not granted.")
            return
        }
        if (floatingVoiceButton != null) {
            DebugLog.add(TAG, "Floating button already shown.")
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
            DebugLog.add(TAG, "Floating voice button onClick: Listener triggered.")
            try {
                val intent = Intent(this, VoiceCommandActivity::class.java)
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                DebugLog.add(TAG, "Floating voice button onClick: Intent created for VoiceCommandActivity. Starting activity...")
                startActivity(intent)
                DebugLog.add(TAG, "Floating voice button onClick: startActivity(intent) called successfully.")
            } catch (e: Exception) {
                DebugLog.add(TAG, "Floating voice button onClick: EXCEPTION while trying to start VoiceCommandActivity: ${e.message}")
                Log.e(TAG, "Error starting VoiceCommandActivity from FAB", e) // Standard log for stack trace
            }
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
}
