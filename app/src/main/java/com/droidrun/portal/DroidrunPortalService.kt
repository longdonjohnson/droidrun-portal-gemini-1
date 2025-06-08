package com.droidrun.portal

import com.droidrun.portal.DebugLog // Added
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
import java.util.LinkedList // Added
import kotlin.math.abs

class DroidrunPortalService : AccessibilityService() {
    
    companion object {
        private const val TAG = "DROIDRUN_PORTAL"
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
    
    // Variables for multi-step command processing
    private var currentOriginalCommand: String? = null
    private var isProcessingMultiStep: Boolean = false
    private var lastKnownUiContext: String? = null
    private val pendingActionsQueue: java.util.LinkedList<GeminiCommandProcessor.UIAction> = java.util.LinkedList()
    private val MAX_REPROMPT_ATTEMPTS = 5
    private var currentRepromptAttempts = 0

    // Track currently displayed elements (after filtering)
    private val displayedElements = mutableListOf<Pair<ElementNode, Float>>()
    
    private var lastDrawTime = 0L
    private var pendingVisualizationUpdate = false

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "Service onCreate")
        try {
            // Initialize Gemini processor
            geminiProcessor = GeminiCommandProcessor(this)
            
            // Register broadcast receiver for commands
            commandReceiver = object : BroadcastReceiver() {
                override fun onReceive(context: Context, intent: Intent) {
                    when (intent.action) {
                        "com.droidrun.portal.PROCESS_NL_COMMAND" -> {
                            val command = intent.getStringExtra("command")
                            DebugLog.add(TAG, "Service received PROCESS_NL_COMMAND: '$command'")
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
            }
            registerReceiver(commandReceiver, filter, RECEIVER_EXPORTED)
            
            overlayManager = OverlayManager(this)
            isInitialized = true
            
            val windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
            val display = windowManager.defaultDisplay
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

    // Gemini Integration Methods
    private fun processNaturalLanguageCommand(command: String) {
        DebugLog.add(TAG, "New LPN command received: '$command'")
        if (isProcessingMultiStep) {
            DebugLog.add(TAG, "Warning: New command received while already processing a multi-step command ('${currentOriginalCommand}'). Overwriting.")
            // Optionally, you might want to queue commands or disallow new ones until current finishes.
            // For now, a new command interrupts and replaces the old one.
        }

        currentOriginalCommand = command
        isProcessingMultiStep = true
        pendingActionsQueue.clear() // Clear any actions from a previous command
        currentRepromptAttempts = 0 // Reset re-prompt counter

        val currentElementsJson = getCurrentElementsJson()
        lastKnownUiContext = currentElementsJson // Store context used for this command cycle

        DebugLog.add(TAG, "Initiating Gemini processing for: '$command'")
        geminiProcessor.processCommand(
            command,
            currentElementsJson,
            object : GeminiCommandProcessor.CommandCallback {
                override fun onActionsReady(actions: List<GeminiCommandProcessor.UIAction>, forCommand: String, uiContextUsed: String) {
                    handleGeminiActions(actions, forCommand, uiContextUsed)
                }

                override fun onError(error: String) {
                    DebugLog.add(TAG, "Gemini processing error for '$command': $error")
                    isProcessingMultiStep = false
                    currentOriginalCommand = null
                }
            }
        )
    }

    private fun handleGeminiActions(actions: List<GeminiCommandProcessor.UIAction>, forCommand: String, uiContextUsed: String) {
        DebugLog.add(TAG, "Gemini actions ready for command '$forCommand' (UI context hash: ${uiContextUsed.hashCode()}): ${actions.size} actions.")
        if (!isProcessingMultiStep || forCommand != currentOriginalCommand) {
            DebugLog.add(TAG, "Ignoring stale actions. Current command is '${currentOriginalCommand}'. Actions were for '${forCommand}'.")
            return
        }

        if (actions.isEmpty()) {
            DebugLog.add(TAG, "Gemini returned no actions for '$forCommand'. Considering this step complete or stuck.")
            // Check if we should stop or re-prompt
            if (currentRepromptAttempts >= MAX_REPROMPT_ATTEMPTS) {
                DebugLog.add(TAG, "Max re-prompt attempts reached. Ending multi-step command.")
                isProcessingMultiStep = false
                currentOriginalCommand = null
            } else {
                DebugLog.add(TAG, "No actions, attempting re-prompt (attempt ${currentRepromptAttempts + 1}).")
                currentRepromptAttempts++ // Increment before calling continue
                mainHandler.postDelayed({ continueMultiStepCommand() }, 1000) // Delay before re-prompting
            }
            return
        }

        // Check for "finish" action
        val finishAction = actions.firstOrNull { it.type.equals("finish", ignoreCase = true) }
        if (finishAction != null) {
            DebugLog.add(TAG, "Received 'finish' action. Multi-step command completed.")
            isProcessingMultiStep = false
            currentOriginalCommand = null
            pendingActionsQueue.clear()
            return
        }

        pendingActionsQueue.addAll(actions)
        DebugLog.add(TAG, "Added ${actions.size} actions to queue. Total queue size: ${pendingActionsQueue.size}")

        // If not already processing (e.g. first set of actions), start processing queue.
        // Or if it was processing, this might be a new set of actions from a re-prompt.
        executeNextPendingAction()
    }

    private fun executeNextPendingAction() {
        if (!isProcessingMultiStep) {
            DebugLog.add(TAG, "executeNextPendingAction called but not processing multi-step.")
            pendingActionsQueue.clear()
            return
        }

        if (pendingActionsQueue.isEmpty()) {
            DebugLog.add(TAG, "Action queue is empty. Need to re-prompt for '$currentOriginalCommand'.")
            // This state should ideally be caught by handleGeminiActions or lead to continueMultiStepCommand for re-prompt
            if (currentRepromptAttempts < MAX_REPROMPT_ATTEMPTS) {
                 DebugLog.add(TAG, "Queue empty, attempting re-prompt (attempt ${currentRepromptAttempts + 1}).")
                 currentRepromptAttempts++
                 mainHandler.postDelayed({ continueMultiStepCommand() }, 1000)
            } else {
                DebugLog.add(TAG, "Max re-prompt attempts reached and queue empty. Ending multi-step command.")
                isProcessingMultiStep = false
                currentOriginalCommand = null
            }
            return
        }

        val nextAction = pendingActionsQueue.removeFirst()
        DebugLog.add(TAG, "Executing next action from queue: ${nextAction.type}. Remaining in queue: ${pendingActionsQueue.size}")
        
        // Clear the rest of pendingActionsQueue to force re-evaluation after this single action
        // This ensures we always get fresh context from Gemini after every discrete step.
        pendingActionsQueue.clear()
        DebugLog.add(TAG, "Cleared pending actions queue to force re-evaluation after this action.")

        executeAction(nextAction)

        // After action execution, schedule continuation (which will re-prompt due to empty queue)
        mainHandler.postDelayed({
            if (isProcessingMultiStep) {
                // Since queue is now empty, this will trigger re-prompt logic in continueMultiStepCommand
                // or the start of executeNextPendingAction.
                // For clarity, directly call continueMultiStepCommand for re-prompt.
                if (currentRepromptAttempts < MAX_REPROMPT_ATTEMPTS) {
                    DebugLog.add(TAG, "Action executed. Re-prompting (attempt ${currentRepromptAttempts + 1}).")
                    currentRepromptAttempts++
                    continueMultiStepCommand() // Call directly, no need for another postDelayed here if continueMultiStepCommand handles its own async call to Gemini
                } else {
                    DebugLog.add(TAG, "Max re-prompt attempts reached after action. Ending multi-step command.")
                    isProcessingMultiStep = false
                    currentOriginalCommand = null
                }
            }
        }, 1000) // 1 second delay for UI to settle
    }

    private fun continueMultiStepCommand() {
        DebugLog.add(TAG, "continueMultiStepCommand called. Current original command: '$currentOriginalCommand', isProcessing: $isProcessingMultiStep, Reprompt attempt: $currentRepromptAttempts / $MAX_REPROMPT_ATTEMPTS")

        if (!isProcessingMultiStep) {
            DebugLog.add(TAG, "Not in multi-step processing mode. Aborting continueMultiStepCommand.")
            currentOriginalCommand = null // Ensure it's cleared
            pendingActionsQueue.clear()
            return
        }

        if (currentOriginalCommand == null) {
            DebugLog.add(TAG, "Error: continueMultiStepCommand called with no original command. Aborting.")
            isProcessingMultiStep = false
            pendingActionsQueue.clear()
            return
        }

        // Refresh UI context
        // Note: processActiveWindow() updates visibleElements, getCurrentElementsJson() uses it.
        // This needs to be on main thread if processActiveWindow() has UI interactions or specific thread requirements.
        // For now, assuming it can be called here. If issues, may need to wrap in mainHandler.post.
        processActiveWindow() // Make sure this captures the latest screen
        val newUiContext = getCurrentElementsJson()

        DebugLog.add(TAG, "New UI context captured (hash: ${newUiContext.hashCode()}). Comparing with last (hash: ${lastKnownUiContext?.hashCode()}).")

        // Basic check to prevent loops if UI isn't changing and Gemini isn't finishing.
        // More sophisticated checks could be added (e.g., if newUiContext is identical to lastKnownUiContext for X retries).
        if (newUiContext == lastKnownUiContext && pendingActionsQueue.isEmpty()) {
            // If UI is same and queue was emptied (meaning previous Gemini response didn't yield useful actions or finished its batch)
            // This check is tricky; if an action *did* happen, UI *should* change. If it didn't, we might be stuck.
            // The MAX_REPROMPT_ATTEMPTS should primarily handle infinite loops.
            // This specific check might be too aggressive if Gemini legitimately returns no actions for a state.
            // For now, primary reliance is on MAX_REPROMPT_ATTEMPTS managed in executeNextPendingAction/handleGeminiActions.
            DebugLog.add(TAG, "UI context appears unchanged and queue is empty. Relying on MAX_REPROMPT_ATTEMPTS to break loops.")
        }

        lastKnownUiContext = newUiContext // Update for the next cycle

        DebugLog.add(TAG, "Re-prompting Gemini for original command: '$currentOriginalCommand' with new UI context.")
        geminiProcessor.processCommand(
            currentOriginalCommand!!, // Known not null due to check above
            newUiContext,
            object : GeminiCommandProcessor.CommandCallback {
                override fun onActionsReady(actions: List<GeminiCommandProcessor.UIAction>, forCommand: String, uiContextUsed: String) {
                    handleGeminiActions(actions, forCommand, uiContextUsed)
                }

                override fun onError(error: String) {
                    DebugLog.add(TAG, "Gemini processing error during continuation for '$currentOriginalCommand': $error")
                    // Decide if we should stop or retry. For now, stop.
                    isProcessingMultiStep = false
                    currentOriginalCommand = null
                }
            }
        )
    }
    
    private fun processVoiceCommand(command: String) {
        Log.d(TAG, "Processing voice command: $command")
        processNaturalLanguageCommand(command)
    }
    
    // private fun executeActions(actions: List<GeminiCommandProcessor.UIAction>) { // Replaced by queue processing
    //     for (action in actions) {
    //         executeAction(action)
    //         Thread.sleep(500) // Small delay between actions
    //     }
    // }
    
    private fun executeAction(action: GeminiCommandProcessor.UIAction) {
        DebugLog.add(TAG, "Executing action: Type=${action.type}, Index=${action.elementIndex}, Text='${action.text}', XY=(${action.x},${action.y}), Dir='${action.direction}'")
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
                DebugLog.add(TAG, "Performed global action: HOME.")
            }
            "back" -> {
                performGlobalAction(GLOBAL_ACTION_BACK)
                DebugLog.add(TAG, "Performed global action: BACK.")
            }
            "recent" -> {
                performGlobalAction(GLOBAL_ACTION_RECENTS)
                DebugLog.add(TAG, "Performed global action: RECENTS.")
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
            DebugLog.add(TAG, "Clicked element at index $index successfully.")
        } else {
            DebugLog.add(TAG, "Failed to click: Element index $index out of bounds (size: ${elements.size}).")
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
            DebugLog.add(TAG, "Typed '$text' in element at index $index successfully.")
        } else {
            DebugLog.add(TAG, "Failed to type: Element index $index out of bounds (size: ${elements.size}).")
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
        DebugLog.add(TAG, "Performed scroll $direction.")
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
        DebugLog.add(TAG, "Performed swipe $direction.")
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
                val classNameStr = node.className?.toString() ?: ""
                val textStr = node.text?.toString() ?: ""
                val element = ElementNode(
                    nodeInfo = node,
                    rect = rect,
                    text = textStr,
                    className = classNameStr,
                    windowLayer = depth,
                    creationTime = System.currentTimeMillis(),
                    id = ElementNode.createId(rect, classNameStr, textStr)
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
                visibleElements.filter { it.isClickable() || it.nodeInfo.isCheckable || it.nodeInfo.isEditable || it.nodeInfo.isScrollable || it.nodeInfo.isFocusable }
            }
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

