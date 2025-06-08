package com.droidrun.portal

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Base64
import android.util.Log

class PortalBroadcastReceiver : BroadcastReceiver() {
    private val TAG = "PortalBroadcastReceiver"
    
    override fun onReceive(context: Context, intent: Intent) {
        // Verify that the intent is from our own package for additional security
        if (intent.component?.packageName != null && intent.component?.packageName != context.packageName) {
            Log.w(TAG, "Received intent from unauthorized package: ${intent.component?.packageName}")
            return
        }
        
        when (intent.action) {
            "com.droidrun.portal.DROIDRUN_INPUT_B64" -> {
                Log.d(TAG, "Received DROIDRUN_INPUT_B64 broadcast")
                handleBase64Input(context, intent)
            }
            "com.droidrun.portal.NATURAL_LANGUAGE_COMMAND" -> {
                Log.d(TAG, "Received NATURAL_LANGUAGE_COMMAND broadcast")
                handleNaturalLanguageCommand(context, intent)
            }
            "com.droidrun.portal.VOICE_COMMAND" -> {
                Log.d(TAG, "Received VOICE_COMMAND broadcast")
                handleVoiceCommand(context, intent)
            }
            "com.droidrun.portal.EXECUTE_ACTION" -> {
                Log.d(TAG, "Received EXECUTE_ACTION broadcast")
                handleExecuteAction(context, intent)
            }
            else -> {
                Log.w(TAG, "Received unexpected action: ${intent.action}")
            }
        }
    }
    
    private fun handleBase64Input(context: Context, intent: Intent) {
        val message = intent.getStringExtra("msg")
        if (message != null && isValidBase64(message)) {
            val forwardIntent = Intent("com.droidrun.portal.INTERNAL_INPUT_B64").apply {
                putExtra("msg", message)
                setPackage(context.packageName)
            }
            try {
                context.sendBroadcast(forwardIntent)
                Log.d(TAG, "Forwarded message to keyboard service")
            } catch (e: Exception) {
                Log.e(TAG, "Error forwarding broadcast to keyboard service", e)
            }
        } else {
            Log.w(TAG, "Received DROIDRUN_INPUT_B64 broadcast with invalid or no message")
        }
    }
    
    private fun handleNaturalLanguageCommand(context: Context, intent: Intent) {
        val command = intent.getStringExtra("command")
        if (command != null) {
            // Forward to accessibility service for processing
            val serviceIntent = Intent("com.droidrun.portal.PROCESS_NL_COMMAND").apply {
                putExtra("command", command)
                setPackage(context.packageName)
            }
            context.sendBroadcast(serviceIntent)
            Log.d(TAG, "Forwarded natural language command: $command")
        }
    }
    
    private fun handleVoiceCommand(context: Context, intent: Intent) {
        val command = intent.getStringExtra("command")
        if (command != null) {
            // Forward to accessibility service for processing
            val serviceIntent = Intent("com.droidrun.portal.PROCESS_VOICE_COMMAND").apply {
                putExtra("command", command)
                setPackage(context.packageName)
            }
            context.sendBroadcast(serviceIntent)
            Log.d(TAG, "Forwarded voice command: $command")
        }
    }
    
    private fun handleExecuteAction(context: Context, intent: Intent) {
        val actionType = intent.getStringExtra("actionType")
        val elementIndex = intent.getIntExtra("elementIndex", -1)
        val text = intent.getStringExtra("text")
        val x = intent.getIntExtra("x", -1)
        val y = intent.getIntExtra("y", -1)
        val direction = intent.getStringExtra("direction")
        
        // Forward to accessibility service for execution
        val serviceIntent = Intent("com.droidrun.portal.EXECUTE_UI_ACTION").apply {
            putExtra("actionType", actionType)
            putExtra("elementIndex", elementIndex)
            putExtra("text", text)
            putExtra("x", x)
            putExtra("y", y)
            putExtra("direction", direction)
            setPackage(context.packageName)
        }
        context.sendBroadcast(serviceIntent)
        Log.d(TAG, "Forwarded action execution: $actionType")
    }
    
    private fun isValidBase64(input: String): Boolean {
        return try {
            Base64.decode(input, Base64.DEFAULT)
            true
        } catch (e: IllegalArgumentException) {
            Log.w(TAG, "Invalid base64 input received")
            false
        }
    }
}

