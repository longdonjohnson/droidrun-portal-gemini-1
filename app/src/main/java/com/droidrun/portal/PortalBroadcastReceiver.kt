package com.droidrun.portal

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Base64
// Removed android.util.Log, using DebugLog instead
import com.droidrun.portal.DebugLog // Added DebugLog import

class PortalBroadcastReceiver : BroadcastReceiver() {
    private val TAG = "PortalReceiver" // Consistent TAG
    
    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: "null_action"
        DebugLog.add(TAG, "onReceive: Received action: $action, from package: ${intent.component?.packageName ?: intent.getPackage() ?: "unknown"}")

        // Verify that the intent is from our own package for additional security
        // Checking intent.package is more reliable for broadcasts not specifying a component
        if (intent.getPackage() != null && intent.getPackage() != context.packageName) {
            DebugLog.add(TAG, "onReceive: Intent from unauthorized package: ${intent.getPackage()}. Ignoring.")
            return
        }
        
        when (action) {
            "com.droidrun.portal.DROIDRUN_INPUT_B64" -> {
                DebugLog.add(TAG, "onReceive: Handling DROIDRUN_INPUT_B64.")
                handleBase64Input(context, intent)
            }
            "com.droidrun.portal.NATURAL_LANGUAGE_COMMAND" -> {
                DebugLog.add(TAG, "onReceive: Handling NATURAL_LANGUAGE_COMMAND.")
                handleNaturalLanguageCommand(context, intent)
            }
            "com.droidrun.portal.VOICE_COMMAND" -> {
                DebugLog.add(TAG, "onReceive: Handling VOICE_COMMAND.")
                handleVoiceCommand(context, intent)
            }
            "com.droidrun.portal.EXECUTE_ACTION" -> {
                DebugLog.add(TAG, "onReceive: Handling EXECUTE_ACTION.")
                handleExecuteAction(context, intent)
            }
            else -> {
                DebugLog.add(TAG, "onReceive: Received unexpected action: $action. No handler.")
            }
        }
    }
    
    private fun handleBase64Input(context: Context, intent: Intent) {
        val message = intent.getStringExtra("msg")
        if (message == null) {
            DebugLog.add(TAG, "handleBase64Input: Received null message for DROIDRUN_INPUT_B64. Discarding.")
            return
        }
        DebugLog.add(TAG, "handleBase64Input: Received message (first 50 chars): ${message.take(50)}...")

        if (isValidBase64(message)) {
            val forwardIntent = Intent("com.droidrun.portal.INTERNAL_INPUT_B64").apply {
                putExtra("msg", message) // Message is already confirmed not null
                setPackage(context.packageName) // Ensure it targets our app
            }
            try {
                context.sendBroadcast(forwardIntent)
                DebugLog.add(TAG, "handleBase64Input: Forwarded valid Base64 message to INTERNAL_INPUT_B64 (Keyboard Service).")
            } catch (e: Exception) {
                DebugLog.add(TAG, "handleBase64Input: Error forwarding broadcast to INTERNAL_INPUT_B64: ${e.message}")
                // Log.e is fine for exceptions if DebugLog doesn't have specific exception logging
            }
        } else {
            // isValidBase64 already logs the error
            DebugLog.add(TAG, "handleBase64Input: Received DROIDRUN_INPUT_B64 with invalid Base64 message. Not forwarding.")
        }
    }
    
    private fun handleNaturalLanguageCommand(context: Context, intent: Intent) {
        val command = intent.getStringExtra("command")
        if (command == null) {
            DebugLog.add(TAG, "handleNaturalLanguageCommand: Received null command. Discarding.")
            return
        }
        DebugLog.add(TAG, "handleNaturalLanguageCommand: Received command: '$command'")

        val serviceIntent = Intent("com.droidrun.portal.PROCESS_NL_COMMAND").apply {
            putExtra("command", command)
            setPackage(context.packageName) // Target our app's service
        }
        try {
            context.sendBroadcast(serviceIntent)
            DebugLog.add(TAG, "handleNaturalLanguageCommand: Forwarded NL command to PROCESS_NL_COMMAND (DroidrunPortalService): '$command'")
        } catch (e: Exception) {
            DebugLog.add(TAG, "handleNaturalLanguageCommand: Error forwarding NL command: ${e.message}")
        }
    }
    
    private fun handleVoiceCommand(context: Context, intent: Intent) {
        val command = intent.getStringExtra("command")
        if (command == null) {
            DebugLog.add(TAG, "handleVoiceCommand: Received null voice command. Discarding.")
            return
        }
        DebugLog.add(TAG, "handleVoiceCommand: Received voice command: '$command'")

        val serviceIntent = Intent("com.droidrun.portal.PROCESS_VOICE_COMMAND").apply {
            putExtra("command", command)
            setPackage(context.packageName) // Target our app's service
        }
        try {
            context.sendBroadcast(serviceIntent)
            DebugLog.add(TAG, "handleVoiceCommand: Forwarded voice command to PROCESS_VOICE_COMMAND (DroidrunPortalService): '$command'")
        } catch (e: Exception) {
            DebugLog.add(TAG, "handleVoiceCommand: Error forwarding voice command: ${e.message}")
        }
    }
    
    private fun handleExecuteAction(context: Context, intent: Intent) {
        val actionType = intent.getStringExtra("actionType")
        val elementIndex = intent.getIntExtra("elementIndex", -1)
        val text = intent.getStringExtra("text") // Default is null if not present
        val x = intent.getIntExtra("x", -1)
        val y = intent.getIntExtra("y", -1)
        val direction = intent.getStringExtra("direction") // Default is null

        DebugLog.add(TAG, "handleExecuteAction: Received actionType: $actionType, elementIndex: $elementIndex, text: '$text', x: $x, y: $y, direction: '$direction'")
        
        // Basic validation: actionType should not be null
        if (actionType == null) {
            DebugLog.add(TAG, "handleExecuteAction: actionType is null. Cannot forward action. Discarding.")
            return
        }

        val serviceIntent = Intent("com.droidrun.portal.EXECUTE_UI_ACTION").apply {
            putExtra("actionType", actionType) // Not null checked above
            putExtra("elementIndex", elementIndex)
            if (text != null) putExtra("text", text)
            putExtra("x", x)
            putExtra("y", y)
            if (direction != null) putExtra("direction", direction)
            setPackage(context.packageName) // Target our app's service
        }
        try {
            context.sendBroadcast(serviceIntent)
            DebugLog.add(TAG, "handleExecuteAction: Forwarded action '$actionType' to EXECUTE_UI_ACTION (DroidrunPortalService).")
        } catch (e: Exception) {
             DebugLog.add(TAG, "handleExecuteAction: Error forwarding action '$actionType': ${e.message}")
        }
    }
    
    private fun isValidBase64(input: String): Boolean {
        return try {
            Base64.decode(input, Base64.DEFAULT)
            true
        } catch (e: IllegalArgumentException) {
            // Log specific error for invalid base64
            DebugLog.add(TAG, "isValidBase64: Input string is not valid Base64. Error: ${e.message}")
            false
        }
    }
}

