package com.droidrun.portal

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Color
import android.graphics.PixelFormat
import com.droidrun.portal.Point
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
        // const val ACTION_TOGGLE_FLOATING_VOICE_BUTTON = "com.droidrun.portal.TOGGLE_FLOATING_VOICE_BUTTON" // Removed
        const val ACTION_GET_ELEMENTS = "com.droidrun.portal.GET_ELEMENTS"
        const val ACTION_ELEMENTS_RESPONSE = "com.droidrun.portal.ELEMENTS_RESPONSE"
        const val ACTION_TOGGLE_OVERLAY = "com.droidrun.portal.TOGGLE_OVERLAY" // Used by MainActivity
        const val ACTION_RETRIGGER_ELEMENTS = "com.droidrun.portal.RETRIGGER_ELEMENTS"
        const val ACTION_GET_ALL_ELEMENTS = "com.droidrun.portal.GET_ALL_ELEMENTS"
        const val ACTION_GET_INTERACTIVE_ELEMENTS = "com.droidrun.portal.GET_INTERACTIVE_ELEMENTS"
        const val ACTION_FORCE_HIDE_OVERLAY = "com.droidrun.portal.FORCE_HIDE_OVERLAY"
        const val ACTION_INSERT_ACTION = "com.droidrun.portal.INSERT_ACTION"
        const val ACTION_REMOVE_ACTION = "com.droidrun.portal.REMOVE_ACTION"
        const val ACTION_REORDER_ACTION = "com.droidrun.portal.REORDER_ACTION"
        const val ACTION_PAUSE_TASK = "com.droidrun.portal.PAUSE_TASK"
        const val ACTION_RESUME_TASK = "com.droidrun.portal.RESUME_TASK"
        const val ACTION_FLOATING_BUTTON_TAPPED = "com.droidrun.portal.FLOATING_BUTTON_TAPPED"
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

    private enum class TaskState {
        IDLE,
        RUNNING,
        PAUSED,
        ERROR
    }

    private var taskState = TaskState.IDLE

    // Removed:
    // private var floatingVoiceButton: View? = null
    // private lateinit var windowManagerService: WindowManager // Removed as it was only for FAB
    // private var isFloatingButtonActuallyShown: Boolean = false

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
            // windowManagerService = getSystemService(WINDOW_SERVICE) as WindowManager // Removed
            geminiProcessor = GeminiCommandProcessor(this)
            overlayManager = OverlayManager(this)
            overlayManager.setOnFloatingButtonTapListener {
                val intent = Intent(ACTION_FLOATING_BUTTON_TAPPED)
                intent.setPackage(packageName)
                sendBroadcast(intent)
            }

            geminiActionCallback = object : GeminiCommandProcessor.CommandCallback {
                override fun onActionsReady(actions: List<GeminiCommandProcessor.UIAction>, forCommand: String, uiContextUsed: String) {
                    handleGeminiActions(actions, forCommand, uiContextUsed)
                }
                override fun onError(error: String) {
                    DebugLog.add(TAG, "Gemini onError callback triggered. Original cmd: '${currentOriginalCommand ?: "N/A"}'. Error: $error. Resetting all relevant states.")

                    taskState = TaskState.ERROR

                    if (isProcessingMultiStep) {
                        isProcessingMultiStep = false
                        DebugLog.add(TAG, "StateChange: isProcessingMultiStep set to false (Gemini onError).")
                    }
                    if (currentOriginalCommand != null) {
                        currentOriginalCommand = null
                        DebugLog.add(TAG, "StateChange: currentOriginalCommand nulled (Gemini onError).")
                    }
                    val oldQueueSize = pendingActionsQueue.size
                    if (oldQueueSize > 0) {
                        pendingActionsQueue.clear()
                        DebugLog.add(TAG, "StateChange: pendingActionsQueue cleared (Gemini onError). Old size: $oldQueueSize")
                    }
                    if (currentRepromptAttempts != 0) { // Only log if it was not already 0
                        currentRepromptAttempts = 0
                        DebugLog.add(TAG, "StateChange: currentRepromptAttempts reset to 0 (Gemini onError).")
                    }
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
                        // Removed case for ACTION_TOGGLE_FLOATING_VOICE_BUTTON
                        // ACTION_TOGGLE_FLOATING_VOICE_BUTTON -> {
                        //     val show = intent.getBooleanExtra("show_button", false)
                        //     if (show) showFloatingVoiceButton() else hideFloatingVoiceButton()
                        // }
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
                        ACTION_INSERT_ACTION -> {
                            val actionJson = intent.getStringExtra("action")
                            val index = intent.getIntExtra("index", -1)
                            if (actionJson != null) {
                                val json = JSONObject(actionJson)
                                val pointsList = mutableListOf<Point>()
                                val pointsJson = json.optJSONArray("points")
                                if (pointsJson != null) {
                                    for (i in 0 until pointsJson.length()) {
                                        val pointJson = pointsJson.getJSONObject(i)
                                        pointsList.add(Point(pointJson.getInt("x"), pointJson.getInt("y")))
                                    }
                                }
                                val action = GeminiCommandProcessor.UIAction(
                                    type = json.getString("type"),
                                    elementIndex = json.optInt("elementIndex", -1),
                                    text = json.optString("text", ""),
                                    x = json.optInt("x", -1),
                                    y = json.optInt("y", -1),
                                    direction = json.optString("direction", ""),
                                    taps = json.optInt("taps", 1),
                                    points = pointsList,
                                    edge = json.optString("edge", "")
                                )
                                if (index >= 0 && index <= pendingActionsQueue.size) {
                                    pendingActionsQueue.add(index, action)
                                } else {
                                    pendingActionsQueue.add(action)
                                }
                            }
                        }
                        ACTION_REMOVE_ACTION -> {
                            val index = intent.getIntExtra("index", -1)
                            if (index >= 0 && index < pendingActionsQueue.size) {
                                pendingActionsQueue.removeAt(index)
                            }
                        }
                        ACTION_REORDER_ACTION -> {
                            val fromIndex = intent.getIntExtra("fromIndex", -1)
                            val toIndex = intent.getIntExtra("toIndex", -1)
                            if (fromIndex >= 0 && fromIndex < pendingActionsQueue.size &&
                                toIndex >= 0 && toIndex < pendingActionsQueue.size) {
                                val action = pendingActionsQueue.removeAt(fromIndex)
                                pendingActionsQueue.add(toIndex, action)
                            }
                        }
                        ACTION_PAUSE_TASK -> {
                            taskState = TaskState.PAUSED
                        }
                        ACTION_RESUME_TASK -> {
                            taskState = TaskState.RUNNING
                            executeNextValidActionFromQueue()
                        }
                        ACTION_FLOATING_BUTTON_TAPPED -> {
                            // For now, just log the event.
                            DebugLog.add(TAG, "Floating button tapped.")
                        }
                    }
                }
            }
            
            val filter = IntentFilter().apply {
                addAction("com.droidrun.portal.PROCESS_NL_COMMAND")
                addAction("com.droidrun.portal.PROCESS_VOICE_COMMAND")
                // addAction(ACTION_TOGGLE_FLOATING_VOICE_BUTTON) // Removed
                addAction(MainActivity.ACTION_UPDATE_OVERLAY_OFFSET)
                addAction(MainActivity.ACTION_UPDATE_OVERLAY_OFFSET_X) // Added X-offset action
                addAction(ACTION_TOGGLE_OVERLAY)
                addAction(ACTION_GET_ELEMENTS)
                addAction(ACTION_GET_ALL_ELEMENTS)
                addAction(ACTION_RETRIGGER_ELEMENTS)
                addAction(ACTION_INSERT_ACTION)
                addAction(ACTION_REMOVE_ACTION)
                addAction(ACTION_REORDER_ACTION)
                addAction(ACTION_PAUSE_TASK)
                addAction(ACTION_RESUME_TASK)
                addAction(ACTION_FLOATING_BUTTON_TAPPED)
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
            // hideFloatingVoiceButton() // Removed
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
        DebugLog.add(TAG, "processNaturalLanguageCommand: Received new command: '$command'")

        taskState = TaskState.RUNNING

        if (isProcessingMultiStep) {
            DebugLog.add(TAG, "StateReset: New command received while current command '${currentOriginalCommand ?: "N/A"}' is in progress. Resetting state for new command.")
        } else {
            DebugLog.add(TAG, "StateReset: Starting new command processing. Multi-step state was clear.")
        }

        // Reliably reset all relevant states
        currentOriginalCommand = command
        DebugLog.add(TAG, "StateChange: currentOriginalCommand set to '$command'")

        if (!isProcessingMultiStep) {
            isProcessingMultiStep = true
            DebugLog.add(TAG, "StateChange: isProcessingMultiStep set to true")
        } else {
            DebugLog.add(TAG, "StateInfo: isProcessingMultiStep remains true for new command.")
        }

        val oldQueueSize = pendingActionsQueue.size
        if (oldQueueSize > 0) {
            pendingActionsQueue.clear()
            DebugLog.add(TAG, "StateChange: pendingActionsQueue cleared. Old size was $oldQueueSize")
        } else {
            DebugLog.add(TAG, "StateInfo: pendingActionsQueue was already empty.")
        }

        currentRepromptAttempts = 0
        DebugLog.add(TAG, "StateChange: currentRepromptAttempts reset to 0")

        DebugLog.add(TAG, "processNaturalLanguageCommand: All states prepared for new command '$command'")

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
        DebugLog.add(TAG, "handleGeminiActions: Received ${actions.size} actions for command '$forCommand'. UI context hash: ${uiContextUsed.hashCode()}.")

        val oldQueueSize = pendingActionsQueue.size
        if (oldQueueSize > 0) {
            pendingActionsQueue.clear()
            DebugLog.add(TAG, "StateChange: pendingActionsQueue cleared at start of handleGeminiActions. Old size: $oldQueueSize")
        }

        if (!isProcessingMultiStep || forCommand != currentOriginalCommand) {
            DebugLog.add(TAG, "handleGeminiActions: Ignoring stale actions. isProcessingMultiStep: $isProcessingMultiStep (expected true), currentOriginalCommand: '${currentOriginalCommand ?: "null"}', actions were for: '$forCommand'.")
            return
        }

        val finishAction = actions.firstOrNull { it.type.equals("finish", ignoreCase = true) }
        if (finishAction != null) {
            DebugLog.add(TAG, "handleGeminiActions: Received 'finish' action. Multi-step command '${currentOriginalCommand ?: "N/A"}' completed. Clearing states.")
            if (isProcessingMultiStep) {
                isProcessingMultiStep = false
                DebugLog.add(TAG, "StateChange: isProcessingMultiStep set to false (finish action).")
            }
            if (currentOriginalCommand != null) {
                currentOriginalCommand = null
                DebugLog.add(TAG, "StateChange: currentOriginalCommand nulled (finish action).")
            }
            // pendingActionsQueue is already cleared at the beginning of this function.
            // If not, it should be:
            if (pendingActionsQueue.isNotEmpty()) {
                 pendingActionsQueue.clear()
                 DebugLog.add(TAG, "StateChange: pendingActionsQueue explicitly cleared (finish action).")
            }
            currentRepromptAttempts = 0
            DebugLog.add(TAG, "StateChange: currentRepromptAttempts reset to 0 (finish action).")
            return
        }

        if (actions.isEmpty()) {
            DebugLog.add(TAG, "handleGeminiActions: Gemini returned no actions for '$forCommand'. Current re-prompt attempts: $currentRepromptAttempts.")
            if (currentRepromptAttempts < MAX_REPROMPT_ATTEMPTS) {
                currentRepromptAttempts++
                DebugLog.add(TAG, "StateChange: currentRepromptAttempts incremented to $currentRepromptAttempts.")
                DebugLog.add(TAG, "handleGeminiActions: No actions from Gemini, attempting re-prompt (CONTINUATION_VALIDATE). Last UI Hash: ${lastKnownUiContext?.hashCode()}")
                mainHandler.post { processActiveWindow() }
                mainHandler.postDelayed({
                    val newUiContext = getCurrentElementsJson()
                    lastKnownUiContext = newUiContext
                    DebugLog.add(TAG, "  Calling makeGeminiRequest (CONTINUATION_VALIDATE, empty actions case). OriginalCmd: '${currentOriginalCommand ?: "N/A"}'. Attempt: $currentRepromptAttempts. UI Hash: ${newUiContext.hashCode()}")
                    geminiProcessor.makeGeminiRequest(
                        GeminiCommandProcessor.PromptType.CONTINUATION_VALIDATE,
                        currentOriginalCommand!!, newUiContext, null, null, geminiActionCallback
                    )
                }, 200)
            } else {
                DebugLog.add(TAG, "handleGeminiActions: Max re-prompt attempts reached for empty actions. Ending multi-step command '${currentOriginalCommand ?: "N/A"}'. Clearing states.")
                if (isProcessingMultiStep) {
                    isProcessingMultiStep = false
                    DebugLog.add(TAG, "StateChange: isProcessingMultiStep set to false (max re-prompts for empty actions).")
                }
                if (currentOriginalCommand != null) {
                    currentOriginalCommand = null
                    DebugLog.add(TAG, "StateChange: currentOriginalCommand nulled (max re-prompts for empty actions).")
                }
                // pendingActionsQueue is already empty or cleared at function start.
                currentRepromptAttempts = 0 // Reset for next command
                DebugLog.add(TAG, "StateChange: currentRepromptAttempts reset to 0 (max re-prompts for empty actions).")
            }
            return
        }

        pendingActionsQueue.addAll(actions)
        DebugLog.add(TAG, "StateChange: Added ${actions.size} actions to queue for '$currentOriginalCommand'. New total: ${pendingActionsQueue.size}")
        executeNextValidActionFromQueue()
    }

    private fun executeNextValidActionFromQueue() {
        if (taskState != TaskState.RUNNING) {
            DebugLog.add(TAG, "executeNextValidActionFromQueue: Task not running. State: $taskState")
            return
        }
        DebugLog.add(TAG, "executeNextValidActionFromQueue. isProcessingMultiStep: $isProcessingMultiStep, Queue size: ${pendingActionsQueue.size}, Command: '${currentOriginalCommand ?: "N/A"}'")
        if (!isProcessingMultiStep) {
            DebugLog.add(TAG, "executeNextValidActionFromQueue: Not processing multi-step. Clearing queue and command.")
            val oldQueueSize = pendingActionsQueue.size
            if (oldQueueSize > 0) {
                pendingActionsQueue.clear()
                DebugLog.add(TAG, "StateChange: pendingActionsQueue cleared (not processing multi-step). Old size: $oldQueueSize")
            }
            if (currentOriginalCommand != null) {
                currentOriginalCommand = null
                DebugLog.add(TAG, "StateChange: currentOriginalCommand nulled (not processing multi-step).")
            }
            // currentRepromptAttempts should ideally be 0 here or reset when isProcessingMultiStep becomes false.
            // If isProcessingMultiStep became false, currentRepromptAttempts should have been reset
            // when isProcessingMultiStep was set to false.
            // Adding a log here if it's not 0, as it indicates a potential state mismatch.
            if (currentRepromptAttempts != 0) {
                DebugLog.add(TAG, "StateWarn: currentRepromptAttempts is $currentRepromptAttempts when !isProcessingMultiStep. Should ideally be 0.")
                // Optionally reset it here again if strict safety is needed, though it implies a logic flaw elsewhere.
                // currentRepromptAttempts = 0
                // DebugLog.add(TAG, "StateChange: currentRepromptAttempts force reset to 0 (not processing multi-step cleanup).")
            }
            return
        }
        if (pendingActionsQueue.isEmpty()) {
            DebugLog.add(TAG, "executeNextValidActionFromQueue: Action queue empty for '${currentOriginalCommand ?: "N/A"}'. Current re-prompt attempts: $currentRepromptAttempts (Max: $MAX_REPROMPT_ATTEMPTS).")
            if (currentRepromptAttempts < MAX_REPROMPT_ATTEMPTS) {
                currentRepromptAttempts++
                DebugLog.add(TAG, "StateChange: currentRepromptAttempts incremented to $currentRepromptAttempts.")
                mainHandler.post { processActiveWindow() }
                mainHandler.postDelayed({
                    val newUiContext = getCurrentElementsJson()
                    lastKnownUiContext = newUiContext
                    DebugLog.add(TAG, "  Calling makeGeminiRequest (CONTINUATION_VALIDATE, empty queue). OriginalCmd: '${currentOriginalCommand ?: "N/A"}'. Attempt: $currentRepromptAttempts. UI Hash: ${newUiContext.hashCode()}")
                    geminiProcessor.makeGeminiRequest(
                        GeminiCommandProcessor.PromptType.CONTINUATION_VALIDATE,
                        currentOriginalCommand!!, newUiContext, null, null, geminiActionCallback
                    )
                }, 200)
            } else {
                DebugLog.add(TAG, "executeNextValidActionFromQueue: Max re-prompt attempts and queue empty for '${currentOriginalCommand ?: "N/A"}'. Ending command. Clearing states.")
                if (isProcessingMultiStep) {
                    isProcessingMultiStep = false
                    DebugLog.add(TAG, "StateChange: isProcessingMultiStep set to false (max re-prompts for empty queue).")
                }
                if (currentOriginalCommand != null) {
                    currentOriginalCommand = null
                    DebugLog.add(TAG, "StateChange: currentOriginalCommand nulled (max re-prompts for empty queue).")
                }
                currentRepromptAttempts = 0 // Reset for next command.
                DebugLog.add(TAG, "StateChange: currentRepromptAttempts reset to 0 (max re-prompts for empty queue).")
            }
            return
        }
        val actionToExecute = pendingActionsQueue.removeFirst()
        DebugLog.add(TAG, "StateChange: Removed action from queue. Old size: ${pendingActionsQueue.size + 1}, New size: ${pendingActionsQueue.size}")
        DebugLog.add(TAG, "Executing action: ${actionToExecute.toString()}. For command: '$currentOriginalCommand'. Remaining in queue: ${pendingActionsQueue.size}")

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

        mainHandler.postDelayed({
            processActiveWindow()
        }, delayMillis + 250)
    }
    
    private fun processVoiceCommand(command: String) {
        DebugLog.add(TAG, "Processing voice command: '$command'")
        processNaturalLanguageCommand(command)
    }

    private fun executeAction(action: GeminiCommandProcessor.UIAction) {
        DebugLog.add(TAG, "executeAction: Type=${action.type}, Index=${action.elementIndex}, Text='${action.text}', XY=(${action.x},${action.y}), Dir='${action.direction}'")
        // General try-catch for the when statement itself, though individual handlers will have their own.
        try {
            when (action.type.lowercase()) {
                "click" -> handleActionClick(action)
                "type" -> handleActionType(action)
                "scroll" -> handleActionScroll(action)
                "swipe" -> handleActionSwipe(action)
                "home" -> handleActionHome()
                "back" -> handleActionBack()
                "recent" -> handleActionRecents()
                "pinch_in" -> handleActionPinchIn()
                "pinch_out" -> handleActionPinchOut()
                "long_click" -> handleActionLongClick(action)
                "multi_tap" -> handleActionMultiTap(action)
                "touch_gesture" -> handleActionTouchBasedGesture(action)
                "edge_gesture" -> handleActionEdgeGesture(action)
                "finish" -> DebugLog.add(TAG, "executeAction: Received 'finish' type, which should be handled by handleGeminiActions, not executeAction.")
                else -> DebugLog.add(TAG, "executeAction: Unknown action type: ${action.type}")
            }
        } catch (e: Exception) {
            // This catch block is a fallback. Ideally, specific handlers catch their own errors.
            DebugLog.add(TAG, "executeAction: Unexpected exception in top-level action dispatcher for action ${action.type}: ${e.message}")
            Log.e(TAG, "executeAction: Unexpected exception for action $action", e)
        }
    }

    private fun handleActionClick(action: GeminiCommandProcessor.UIAction) {
        try {
            if (action.elementIndex >= 0) {
                clickElementByIndex(action.elementIndex)
            } else if (action.x >= 0 && action.y >= 0) {
                var adjustedX = action.x
                if (::overlayManager.isInitialized) {
                    val offsetX = overlayManager.getPositionOffsetX()
                    adjustedX -= offsetX // Adjust for current X offset
                    DebugLog.add(TAG, "handleActionClick: Adjusting click X-coordinate: original=${action.x}, offset=$offsetX, new=$adjustedX")
                } else {
                    DebugLog.add(TAG, "handleActionClick: OverlayManager not init for X-offset, using original X: ${action.x}")
                }
                clickAtCoordinates(adjustedX, action.y)
            } else {
                DebugLog.add(TAG, "handleActionClick: Click action invalid - no elementIndex or valid coordinates provided. Action: $action")
            }
        } catch (e: Exception) {
            DebugLog.add(TAG, "handleActionClick: Exception for action $action: ${e.message}")
            Log.e(TAG, "handleActionClick: Exception for action $action", e)
        }
    }

    private fun clickElementByIndex(index: Int) {
        val elements = getInteractiveElements()
        if (index < 0 || index >= elements.size) {
            DebugLog.add(TAG, "clickElementByIndex: Failed to click - Element index $index out of bounds (size: ${elements.size}).")
            return
        }
        val nodeToClick = elements[index]

        if (!nodeToClick.isVisibleToUser) {
            DebugLog.add(TAG, "clickElementByIndex: Warning - Element at index $index (ID: ${nodeToClick.viewIdResourceName ?: "N/A"}, Text: '${nodeToClick.text}') is no longer visible.")
            // Decide if to proceed or return. For now, proceeding as per subtask note.
        }
        if (!nodeToClick.isEnabled) {
            DebugLog.add(TAG, "clickElementByIndex: Warning - Element at index $index (ID: ${nodeToClick.viewIdResourceName ?: "N/A"}, Text: '${nodeToClick.text}') is disabled.")
            // Decide if to proceed or return.
        }
        if (!nodeToClick.isClickable) { // Redundant check if getInteractiveElements is perfect, but good for safety.
            DebugLog.add(TAG, "clickElementByIndex: Warning - Element at index $index (ID: ${nodeToClick.viewIdResourceName ?: "N/A"}, Text: '${nodeToClick.text}') is not marked clickable by its properties (isClickable=${nodeToClick.isClickable}).")
        }

        DebugLog.add(TAG, "clickElementByIndex: Attempting to click element at index $index: ${nodeToClick.className} '${nodeToClick.text}', ID: ${nodeToClick.viewIdResourceName ?: "N/A"}")
        nodeToClick.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        DebugLog.add(TAG, "clickElementByIndex: Click action performed for element at index $index.")
    }
    
    private fun clickAtCoordinates(x: Int, y: Int) {
        DebugLog.add(TAG, "clickAtCoordinates: Attempting click at ($x, $y). Screen bounds: $screenBounds")
        try {
            val path = Path().apply { moveTo(x.toFloat(), y.toFloat()) }
            val gesture = GestureDescription.Builder().addStroke(GestureDescription.StrokeDescription(path, 0, 100)).build()
            val dispatched = dispatchGesture(gesture, null, null)
            DebugLog.add(TAG, "clickAtCoordinates: dispatchGesture result: $dispatched at ($x, $y)")
        } catch (e: Exception) {
            DebugLog.add(TAG, "clickAtCoordinates: Exception dispatching click gesture at ($x, $y): ${e.message}")
            Log.e(TAG, "clickAtCoordinates: Exception dispatching click", e)
        }
    }
    
    private fun handleActionType(action: GeminiCommandProcessor.UIAction) {
        try {
            if (action.elementIndex >= 0) {
                typeInElement(action.elementIndex, action.text)
            } else {
                DebugLog.add(TAG, "handleActionType: Type action invalid - no elementIndex provided. Action: $action")
            }
        } catch (e: Exception) {
            DebugLog.add(TAG, "handleActionType: Exception for action $action: ${e.message}")
            Log.e(TAG, "handleActionType: Exception for action $action", e)
        }
    }

    private fun typeInElement(index: Int, text: String) {
        val elements = getInteractiveElements()
        if (index < 0 || index >= elements.size) {
            DebugLog.add(TAG, "typeInElement: Failed to type - Element index $index out of bounds (size: ${elements.size}).")
            return
        }
        val nodeToTypeIn = elements[index]

        if (!nodeToTypeIn.isVisibleToUser) {
            DebugLog.add(TAG, "typeInElement: Warning - Element at index $index (ID: ${nodeToTypeIn.viewIdResourceName ?: "N/A"}, Text: '${nodeToTypeIn.text}') is no longer visible. Cannot type.")
            return // Do not proceed if not visible
        }
        if (!nodeToTypeIn.isEnabled) {
            DebugLog.add(TAG, "typeInElement: Warning - Element at index $index (ID: ${nodeToTypeIn.viewIdResourceName ?: "N/A"}, Text: '${nodeToTypeIn.text}') is disabled. Cannot type.")
            return // Do not proceed if disabled
        }
        if (!nodeToTypeIn.isEditable) {
            DebugLog.add(TAG, "typeInElement: Error - Element at index $index (ID: ${nodeToTypeIn.viewIdResourceName ?: "N/A"}, Text: '${nodeToTypeIn.text}') is not editable. Skipping type action.")
            return // Crucial: Do not proceed if not editable
        }

        DebugLog.add(TAG, "typeInElement: Attempting to type '$text' in element at index $index: ID: ${nodeToTypeIn.viewIdResourceName ?: "N/A"}, Class: ${nodeToTypeIn.className}")

        // Focus the element first
        if (nodeToTypeIn.isFocusable) {
            if (!nodeToTypeIn.isFocused) {
                 DebugLog.add(TAG, "typeInElement: Element not focused. Attempting to set focus.")
                 nodeToTypeIn.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
                 // Re-fetch the node or check focus state again if necessary, though performAction is often synchronous enough
            }
        } else {
             DebugLog.add(TAG, "typeInElement: Warning - Element is not focusable, typing might fail or go to wrong field.")
        }

        val arguments = Bundle().apply { putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text) }
        val success = nodeToTypeIn.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, arguments)
        if (success) {
            DebugLog.add(TAG, "typeInElement: Typed '$text' in element at index $index successfully.")
        } else {
            DebugLog.add(TAG, "typeInElement: Failed to type '$text' in element at index $index. ACTION_SET_TEXT returned false.")
        }
    }
    
    private fun handleActionScroll(action: GeminiCommandProcessor.UIAction) {
        try {
            if (action.direction.isBlank()) {
                DebugLog.add(TAG, "handleActionScroll: Scroll action invalid - no direction provided. Action: $action")
                return
            }
            performScroll(action.direction)
        } catch (e: Exception) {
            DebugLog.add(TAG, "handleActionScroll: Exception for action $action: ${e.message}")
            Log.e(TAG, "handleActionScroll: Exception for action $action", e)
        }
    }

    private fun performScroll(direction: String) {
        // Called by handleActionScroll, error handling there.
        DebugLog.add(TAG, "performScroll: Attempting scroll in direction: $direction")
        // Using ACTION_SCROLL_BACKWARD for 'left' and ACTION_SCROLL_FORWARD for 'right'
        // as general scroll actions. Specific left/right actions might have compatibility issues
        // or not be universally supported by all views for horizontal scrolling via these constants.
        // ACTION_SCROLL_FORWARD/BACKWARD are more commonly implemented for vertical or list scrolling,
        // but can sometimes work for horizontal if the view is focused and designed for it.
        val actionCode = when (direction.lowercase()) {
            "up" -> AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
            "down" -> AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
            "left" -> AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD // Changed from ACTION_SCROLL_LEFT
            "right" -> AccessibilityNodeInfo.ACTION_SCROLL_FORWARD // Changed from ACTION_SCROLL_RIGHT
            else -> {
                DebugLog.add(TAG, "performScroll: Unknown scroll direction: $direction. Not performing action.")
                return
            }
        }

        val currentRoot = rootInActiveWindow
        if (currentRoot == null) {
            DebugLog.add(TAG, "performScroll: Cannot scroll, rootInActiveWindow is null.")
            return
        }

        val scrollableNode = findFirstScrollableNode(currentRoot)
        if (scrollableNode != null) {
            DebugLog.add(TAG, "performScroll: Found scrollable node ${scrollableNode.className}, attempting to scroll $direction.")
            scrollableNode.performAction(actionCode)
            DebugLog.add(TAG, "performScroll: Performed scroll $direction on specific node: ${scrollableNode.className}.")
        } else {
            DebugLog.add(TAG, "performScroll: No specific scrollable node found, attempting to scroll $direction on root window.")
            currentRoot.performAction(actionCode) // May not always work if root itself is not scrollable in that direction
            DebugLog.add(TAG, "performScroll: Performed scroll $direction on root window.")
        }
    }

    private fun findFirstScrollableNode(rootNode: AccessibilityNodeInfo?): AccessibilityNodeInfo? { // Already robust for null rootNode
        if (rootNode == null) {
            DebugLog.add(TAG, "findFirstScrollableNode: rootNode is null, cannot find scrollable node.")
            return null
        }
        val queue = LinkedList<AccessibilityNodeInfo>().apply { add(rootNode) }
        while (queue.isNotEmpty()) {
            val node = queue.removeFirst()
            if (node.isScrollable) {
                DebugLog.add(TAG, "findFirstScrollableNode: Found scrollable node: ${node.className}")
                return node
            }
            for (i in 0 until node.childCount) {
                node.getChild(i)?.let { queue.addLast(it) }
            }
        }
        DebugLog.add(TAG, "findFirstScrollableNode: No scrollable node found under the provided root.")
        return null
    }

    private fun handleActionSwipe(action: GeminiCommandProcessor.UIAction) {
        try {
            if (action.direction.isBlank()) {
                DebugLog.add(TAG, "handleActionSwipe: Swipe action invalid - no direction provided. Action: $action")
                return
            }
            performSwipe(action.direction)
        } catch (e: Exception) {
            DebugLog.add(TAG, "handleActionSwipe: Exception for action $action: ${e.message}")
            Log.e(TAG, "handleActionSwipe: Exception for action $action", e)
        }
    }
    
    private fun performSwipe(direction: String) {
        DebugLog.add(TAG, "performSwipe: Attempting swipe $direction")
        try {
            val displayMetrics = resources.displayMetrics
            val width = displayMetrics.widthPixels
            val height = displayMetrics.heightPixels
            val path = Path()
            val midX = width / 2f
            val midY = height / 2f
            val swipeLengthHorizontal = width / 3f // Example swipe length
            val swipeLengthVertical = height / 3f // Example swipe length

            when (direction.lowercase()) {
                "left" -> { path.moveTo(midX + swipeLengthHorizontal / 2, midY); path.lineTo(midX - swipeLengthHorizontal / 2, midY) }
                "right" -> { path.moveTo(midX - swipeLengthHorizontal / 2, midY); path.lineTo(midX + swipeLengthHorizontal / 2, midY) }
                "up" -> { path.moveTo(midX, midY + swipeLengthVertical / 2); path.lineTo(midX, midY - swipeLengthVertical / 2) }
                "down" -> { path.moveTo(midX, midY - swipeLengthVertical / 2); path.lineTo(midX, midY + swipeLengthVertical / 2) }
                else -> {
                    DebugLog.add(TAG, "performSwipe: Unknown swipe direction: $direction. Not performing action.")
                    return
                }
            }
            val gesture = GestureDescription.Builder().addStroke(GestureDescription.StrokeDescription(path, 0, 200)).build() // 200ms duration
            val dispatched = dispatchGesture(gesture, null, null)
            DebugLog.add(TAG, "performSwipe: dispatchGesture result for $direction swipe: $dispatched")
        } catch (e: Exception) {
            DebugLog.add(TAG, "performSwipe: Exception dispatching $direction swipe: ${e.message}")
            Log.e(TAG, "performSwipe: Exception for $direction swipe", e)
        }
    }

    private fun handleActionHome() {
        DebugLog.add(TAG, "handleActionHome: Attempting to perform GLOBAL_ACTION_HOME")
        try {
            val success = performGlobalAction(GLOBAL_ACTION_HOME)
            DebugLog.add(TAG, "handleActionHome: GLOBAL_ACTION_HOME success: $success")
        } catch (e: Exception) {
            DebugLog.add(TAG, "handleActionHome: Exception performing GLOBAL_ACTION_HOME: ${e.message}")
            Log.e(TAG, "handleActionHome: Exception", e)
        }
    }

    private fun handleActionBack() {
        DebugLog.add(TAG, "handleActionBack: Attempting to perform GLOBAL_ACTION_BACK")
        try {
            val success = performGlobalAction(GLOBAL_ACTION_BACK)
            DebugLog.add(TAG, "handleActionBack: GLOBAL_ACTION_BACK success: $success")
        } catch (e: Exception) {
            DebugLog.add(TAG, "handleActionBack: Exception performing GLOBAL_ACTION_BACK: ${e.message}")
            Log.e(TAG, "handleActionBack: Exception", e)
        }
    }

    private fun handleActionRecents() {
        DebugLog.add(TAG, "handleActionRecents: Attempting to perform GLOBAL_ACTION_RECENTS")
        try {
            val success = performGlobalAction(GLOBAL_ACTION_RECENTS)
            DebugLog.add(TAG, "handleActionRecents: GLOBAL_ACTION_RECENTS success: $success")
        } catch (e: Exception) {
            DebugLog.add(TAG, "handleActionRecents: Exception performing GLOBAL_ACTION_RECENTS: ${e.message}")
            Log.e(TAG, "handleActionRecents: Exception", e)
        }
    }

    private fun handleActionLongClick(action: GeminiCommandProcessor.UIAction) {
        try {
            if (action.elementIndex >= 0) {
                longClickElementByIndex(action.elementIndex)
            } else if (action.x >= 0 && action.y >= 0) {
                longClickAtCoordinates(action.x, action.y)
            } else {
                DebugLog.add(TAG, "handleActionLongClick: Long click action invalid - no elementIndex or valid coordinates provided. Action: $action")
            }
        } catch (e: Exception) {
            DebugLog.add(TAG, "handleActionLongClick: Exception for action $action: ${e.message}")
            Log.e(TAG, "handleActionLongClick: Exception for action $action", e)
        }
    }

    private fun longClickElementByIndex(index: Int) {
        val elements = getInteractiveElements()
        if (index < 0 || index >= elements.size) {
            DebugLog.add(TAG, "longClickElementByIndex: Failed to long click - Element index $index out of bounds (size: ${elements.size}).")
            return
        }
        val nodeToLongClick = elements[index]
        DebugLog.add(TAG, "longClickElementByIndex: Attempting to long click element at index $index: ${nodeToLongClick.className} '${nodeToLongClick.text}'")
        nodeToLongClick.performAction(AccessibilityNodeInfo.ACTION_LONG_CLICK)
        DebugLog.add(TAG, "longClickElementByIndex: Long click action performed for element at index $index.")
    }

    private fun longClickAtCoordinates(x: Int, y: Int) {
        DebugLog.add(TAG, "longClickAtCoordinates: Attempting long click at ($x, $y)")
        val path = Path().apply { moveTo(x.toFloat(), y.toFloat()) }
        val gesture = GestureDescription.Builder().addStroke(GestureDescription.StrokeDescription(path, 0, 500)).build()
        dispatchGesture(gesture, null, null)
    }

    private fun handleActionMultiTap(action: GeminiCommandProcessor.UIAction) {
        try {
            if (action.elementIndex >= 0) {
                multiTapElementByIndex(action.elementIndex, action.taps)
            } else if (action.x >= 0 && action.y >= 0) {
                multiTapAtCoordinates(action.x, action.y, action.taps)
            } else {
                DebugLog.add(TAG, "handleActionMultiTap: Multi-tap action invalid - no elementIndex or valid coordinates provided. Action: $action")
            }
        } catch (e: Exception) {
            DebugLog.add(TAG, "handleActionMultiTap: Exception for action $action: ${e.message}")
            Log.e(TAG, "handleActionMultiTap: Exception for action $action", e)
        }
    }

    private fun multiTapElementByIndex(index: Int, taps: Int) {
        val elements = getInteractiveElements()
        if (index < 0 || index >= elements.size) {
            DebugLog.add(TAG, "multiTapElementByIndex: Failed to multi-tap - Element index $index out of bounds (size: ${elements.size}).")
            return
        }
        val nodeToMultiTap = elements[index]
        val rect = Rect()
        nodeToMultiTap.getBoundsInScreen(rect)
        multiTapAtCoordinates(rect.centerX(), rect.centerY(), taps)
    }

    private fun multiTapAtCoordinates(x: Int, y: Int, taps: Int) {
        DebugLog.add(TAG, "multiTapAtCoordinates: Attempting $taps taps at ($x, $y)")
        val gestureBuilder = GestureDescription.Builder()
        val path = Path().apply { moveTo(x.toFloat(), y.toFloat()) }
        for (i in 0 until taps) {
            gestureBuilder.addStroke(GestureDescription.StrokeDescription(path, i * 150L, 100))
        }
        dispatchGesture(gestureBuilder.build(), null, null)
    }

    private fun handleActionTouchBasedGesture(action: GeminiCommandProcessor.UIAction) {
        try {
            if (action.points.isNotEmpty()) {
                val path = Path()
                path.moveTo(action.points[0].x.toFloat(), action.points[0].y.toFloat())
                for (i in 1 until action.points.size) {
                    path.lineTo(action.points[i].x.toFloat(), action.points[i].y.toFloat())
                }
                val gesture = GestureDescription.Builder().addStroke(GestureDescription.StrokeDescription(path, 0, 500)).build()
                dispatchGesture(gesture, null, null)
            } else {
                DebugLog.add(TAG, "handleActionTouchBasedGesture: Touch-based gesture invalid - no points provided. Action: $action")
            }
        } catch (e: Exception) {
            DebugLog.add(TAG, "handleActionTouchBasedGesture: Exception for action $action: ${e.message}")
            Log.e(TAG, "handleActionTouchBasedGesture: Exception for action $action", e)
        }
    }

    private fun handleActionEdgeGesture(action: GeminiCommandProcessor.UIAction) {
        try {
            if (action.edge.isNotBlank()) {
                val displayMetrics = resources.displayMetrics
                val width = displayMetrics.widthPixels
                val height = displayMetrics.heightPixels
                val path = Path()
                when (action.edge.lowercase()) {
                    "left" -> {
                        path.moveTo(0f, height / 2f)
                        path.lineTo(width / 4f, height / 2f)
                    }
                    "right" -> {
                        path.moveTo(width.toFloat(), height / 2f)
                        path.lineTo(width * 3 / 4f, height / 2f)
                    }
                    "top" -> {
                        path.moveTo(width / 2f, 0f)
                        path.lineTo(width / 2f, height / 4f)
                    }
                    "bottom" -> {
                        path.moveTo(width / 2f, height.toFloat())
                        path.lineTo(width / 2f, height * 3 / 4f)
                    }
                    else -> {
                        DebugLog.add(TAG, "handleActionEdgeGesture: Edge gesture invalid - unknown edge. Action: $action")
                        return
                    }
                }
                val gesture = GestureDescription.Builder().addStroke(GestureDescription.StrokeDescription(path, 0, 200)).build()
                dispatchGesture(gesture, null, null)
            } else {
                DebugLog.add(TAG, "handleActionEdgeGesture: Edge gesture invalid - no edge provided. Action: $action")
            }
        } catch (e: Exception) {
            DebugLog.add(TAG, "handleActionEdgeGesture: Exception for action $action: ${e.message}")
            Log.e(TAG, "handleActionEdgeGesture: Exception for action $action", e)
        }
    }

    private fun getCurrentElementsJson(): String = getElementsAsJson(false)
    
    private fun getInteractiveElements(): List<AccessibilityNodeInfo> {
        val elements = mutableListOf<AccessibilityNodeInfo>()
        val root = rootInActiveWindow
        if (root == null) {
            DebugLog.add(TAG, "getInteractiveElements: rootInActiveWindow is null. Returning empty list.")
            return elements
        }
        val queue: LinkedList<AccessibilityNodeInfo> = LinkedList()
        root.let { queue.add(it) }
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
        try {
            val eventPackage = event.packageName?.toString() ?: ""
            if (eventPackage.isNotEmpty() && eventPackage != currentPackageName) {
                DebugLog.add(TAG, "onAccessibilityEvent: App context changed from '$currentPackageName' to '$eventPackage'.")
                currentPackageName = eventPackage
            }

            when (event.eventType) {
                AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED,
                AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED,
                AccessibilityEvent.TYPE_VIEW_SCROLLED -> {
                    handleRelevantAccessibilityEvent(event)
                }
                // Optionally handle other event types or log them:
                // else -> DebugLog.add(TAG, "onAccessibilityEvent: Received unhandled event type: ${AccessibilityEvent.eventTypeToString(event.eventType)}")
            }
        } catch (e: Exception) {
            DebugLog.add(TAG, "onAccessibilityEvent: Unexpected exception during event processing: ${e.message}")
            Log.e(TAG, "onAccessibilityEvent: Exception", e)
        }
    }

    private fun handleRelevantAccessibilityEvent(event: AccessibilityEvent) {
        // This function encapsulates the logic previously directly in onAccessibilityEvent's when block.
        // No try-catch here as the caller (onAccessibilityEvent) has one.
        DebugLog.add(TAG, "handleRelevantAccessibilityEvent: Processing event type ${AccessibilityEvent.eventTypeToString(event.eventType)} for package ${event.packageName}")
        if (isInitialized) {
            mainHandler.removeCallbacks(processActiveWindowRunnable)
            // Determine delay based on whether a multi-step operation is in progress.
            // Shorter delay if not, to make UI feel more responsive for single commands.
            // Longer delay if multi-step, to allow UI to settle after an action.
            val delay = if (isProcessingMultiStep) 500L else 150L
            mainHandler.postDelayed(processActiveWindowRunnable, delay)
            DebugLog.add(TAG, "handleRelevantAccessibilityEvent: Scheduled processActiveWindow with delay: $delay ms (isProcessingMultiStep: $isProcessingMultiStep)")
        } else {
            DebugLog.add(TAG, "handleRelevantAccessibilityEvent: Skipped processing as service not initialized.")
        }
    }

    override fun onInterrupt() { DebugLog.add(TAG, "Service interrupted.") }

    private fun processActiveWindow() {
        if (isProcessingAccessibilityEvent.getAndSet(true)) {
            DebugLog.add(TAG, "processActiveWindow: Already processing, skipping due to atomic lock.")
            return
        }
        DebugLog.add(TAG, "processActiveWindow: Triggered.")
        try {
            val rootNode = rootInActiveWindow
            if (rootNode != null) {
                extractAndStoreVisibleElements(rootNode)
            } else {
                DebugLog.add(TAG, "processActiveWindow: rootInActiveWindow is null. No elements to extract.")
                // Consider clearing visibleElements if root is null, depending on desired behavior
                synchronized(visibleElements) {
                    if (visibleElements.isNotEmpty()) {
                        DebugLog.add(TAG, "processActiveWindow: Clearing previously visible elements as root is now null.")
                        visibleElements.clear()
                        if (isOverlayVisuallyEnabledState) {
                             pendingVisualizationUpdate = true
                        }
                    }
                }
            }
        } catch (e: Exception) {
            DebugLog.add(TAG, "processActiveWindow: Unhandled exception: ${e.message}")
            Log.e(TAG, "processActiveWindow: Unhandled exception", e)
        } finally {
            isProcessingAccessibilityEvent.set(false)
            DebugLog.add(TAG, "processActiveWindow: Processing finished, lock released.")
        }
    }

    private fun extractAndStoreVisibleElements(rootNode: AccessibilityNodeInfo) {
        DebugLog.add(TAG, "extractAndStoreVisibleElements: Starting extraction from root: ${rootNode.className}")
        try {
            val newElements = mutableListOf<ElementNode>()
            recursivelyExtractElements(rootNode, null, newElements, 0) // Renamed recursive helper

            synchronized(visibleElements) {
                visibleElements.clear()
                visibleElements.addAll(newElements)
                DebugLog.add(TAG, "extractAndStoreVisibleElements: ${newElements.size} elements extracted and stored. visibleElements updated.")
            }

            if (isOverlayVisuallyEnabledState) {
                pendingVisualizationUpdate = true
                // updateVisualizationIfNeeded() // Consider if immediate update is needed or periodic is fine
                DebugLog.add(TAG, "extractAndStoreVisibleElements: Overlay update marked as pending.")
            }
        } catch (e: Exception) {
            DebugLog.add(TAG, "extractAndStoreVisibleElements: Exception during element extraction: ${e.message}")
            Log.e(TAG, "extractAndStoreVisibleElements: Exception", e)
            // Optionally clear visibleElements here if extraction fails critically
            // synchronized(visibleElements) { visibleElements.clear() }
        }
    }

    // Renamed from extractElements to recursivelyExtractElements to avoid confusion with the new orchestrator
    private fun recursivelyExtractElements(node: AccessibilityNodeInfo?, parentElement: ElementNode?, elements: MutableList<ElementNode>, depth: Int) {
        if (node == null) {
            // DebugLog.add(TAG, "recursivelyExtractElements: Encountered null node at depth $depth, skipping.")
            return
        }
        try {
            val rect = Rect()
            node.getBoundsInScreen(rect)

            // Basic visibility and size checks
            if (!node.isVisibleToUser || rect.width() < MIN_ELEMENT_SIZE || rect.height() < MIN_ELEMENT_SIZE) {
                // DebugLog.add(TAG, "recursivelyExtractElements: Node not processed (invisible or too small): ${node.className}")
                // Still recurse for children even if parent is not added
                for (i in 0 until node.childCount) {
                     node.getChild(i)?.let { recursivelyExtractElements(it, parentElement, elements, depth + 1) }
                }
                return // Return after checking children of non-visible/small parent
            }

            val classNameStr = node.className?.toString() ?: "UnknownClass"
            // Prioritize node.text for the 'text' field.
            val mainText = node.text?.toString() ?: ""
            val contentDescText = node.contentDescription?.toString()
            val resourceId = node.viewIdResourceName?.toString()
            val isPassword = node.isPassword
            val hint = if (node.isEditable) node.hintText?.toString() else null

            val element = ElementNode(
                nodeInfo = node, // Keep original node for actions
                rect = rect,
                text = mainText, // Primary text from node.text
                className = classNameStr,
                windowLayer = depth, // Could be useful for overlay drawing order
                creationTime = System.currentTimeMillis(),
                id = ElementNode.createId(rect, classNameStr, mainText), // ID based on mainText
                contentDescription = contentDescText,
                resourceIdName = resourceId,
                hintText = hint,
                isPasswordInput = isPassword,
                parent = parentElement,
                children = mutableListOf()
            )
            elements.add(element)
            parentElement?.addChild(element)
            // DebugLog.add(TAG, "recursivelyExtractElements: Added element ${classNameStr} with text '${mainText}' at depth $depth")

            for (i in 0 until node.childCount) {
                node.getChild(i)?.let { childNode ->
                    // Sanity check for child visibility before recursing, though isVisibleToUser on child is more reliable
                    // if(childNode.isVisibleToUser) // This check is done at the beginning of the recursive call already
                    recursivelyExtractElements(childNode, element, elements, depth + 1)
                }
            }
        } catch (e: Exception) {
            // Log error for this specific node and continue with siblings/children if possible
            DebugLog.add(TAG, "recursivelyExtractElements: Error extracting details for node ${node.className}: ${e.message}")
            // Consider if we should Log.e for stack trace depending on severity / frequency
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
                put("index", index) // This is the list index for Gemini, not related to ElementNode.overlayIndex
                put("text", element.text) // Already using element.text which is node.text
                put("class", element.className)
                put("contentDescription", element.contentDescription ?: JSONObject.NULL)
                put("resourceId", element.resourceIdName ?: JSONObject.NULL)
                put("hintText", element.hintText ?: JSONObject.NULL)
                put("isPassword", element.isPasswordInput)
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
        val intent = Intent(ACTION_ELEMENTS_RESPONSE).apply {
            putExtra(EXTRA_ELEMENTS_DATA, getElementsAsJson(false))
            setPackage(packageName) // Make broadcast explicit
        }
        sendBroadcast(intent)
        DebugLog.add(TAG, "Broadcasted element data (interactive only) explicitly to package $packageName.")
    }

    private fun broadcastAllElementsData() {
        val intent = Intent(ACTION_ELEMENTS_RESPONSE).apply {
            putExtra(EXTRA_ALL_ELEMENTS_DATA, getElementsAsJson(true))
            setPackage(packageName) // Make broadcast explicit
        }
        sendBroadcast(intent)
        DebugLog.add(TAG, "Broadcasted all elements data explicitly to package $packageName.")
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
    
    // Removed showFloatingVoiceButton()
    // Removed hideFloatingVoiceButton()
}
