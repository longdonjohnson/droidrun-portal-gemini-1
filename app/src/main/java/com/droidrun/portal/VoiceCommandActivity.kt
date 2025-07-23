package com.droidrun.portal

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.widget.Button
import android.widget.EditText
import android.widget.Toast
// import android.util.Log // Replaced with DebugLog
import com.droidrun.portal.DebugLog // Added DebugLog

class VoiceCommandActivity : Activity() {
    companion object { // Added companion object for TAG
        private const val TAG = "VoiceCommandActivity"
    }
    private val SPEECH_REQUEST_CODE = 100
    
    private lateinit var editTextCommand: EditText
    private lateinit var buttonVoice: Button
    private lateinit var buttonSend: Button
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        DebugLog.add(TAG, "onCreate: Activity starting.")
        
        // Simple layout programmatically
        val layout = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(50, 50, 50, 50)
            DebugLog.add(TAG, "onCreate: LinearLayout created and configured.")
        }
        
        editTextCommand = EditText(this).apply {
            hint = "Enter command or use voice..."
            minLines = 3
        }
        DebugLog.add(TAG, "onCreate: editTextCommand initialized.")
        
        buttonVoice = Button(this).apply {
            text = "🎤 Voice Command"
            setOnClickListener {
                DebugLog.add(TAG, "buttonVoice onClick: Voice Command button clicked.")
                startVoiceRecognition()
            }
        }
        DebugLog.add(TAG, "onCreate: buttonVoice initialized and listener set.")
        
        buttonSend = Button(this).apply {
            text = "Send Command"
            setOnClickListener {
                DebugLog.add(TAG, "buttonSend onClick: Send Command button clicked.")
                sendCommand()
            }
        }
        DebugLog.add(TAG, "onCreate: buttonSend initialized and listener set.")
        
        layout.addView(editTextCommand)
        layout.addView(buttonVoice)
        layout.addView(buttonSend)
        DebugLog.add(TAG, "onCreate: Views added to layout.")
        
        setContentView(layout)
        DebugLog.add(TAG, "onCreate: setContentView called.")
        DebugLog.add(TAG, "onCreate: Activity started successfully.")
    }
    
    private fun startVoiceRecognition() {
        DebugLog.add(TAG, "startVoiceRecognition: Attempting to start voice recognition.")
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            DebugLog.add(TAG, "startVoiceRecognition: Speech recognition not available.")
            Toast.makeText(this, "Speech recognition not available", Toast.LENGTH_SHORT).show()
            return
        }
        
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_PROMPT, "Say your command...")
        }
        DebugLog.add(TAG, "startVoiceRecognition: Intent for speech recognition created: $intent")
        
        try {
            DebugLog.add(TAG, "startVoiceRecognition: Calling startActivityForResult for speech input.")
            startActivityForResult(intent, SPEECH_REQUEST_CODE)
        } catch (e: Exception) {
            DebugLog.add(TAG, "startVoiceRecognition: Error starting voice recognition activity: ${e.toString()}")
            Toast.makeText(this, "Error starting voice recognition", Toast.LENGTH_SHORT).show()
        }
    }
    
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        DebugLog.add(TAG, "onActivityResult: RequestCode: $requestCode, ResultCode: $resultCode")
        
        if (requestCode == SPEECH_REQUEST_CODE && resultCode == RESULT_OK) {
            val results = data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)
            if (!results.isNullOrEmpty()) {
                val spokenText = results[0]
                DebugLog.add(TAG, "onActivityResult: Voice input successful. Spoken text: '$spokenText'")
                editTextCommand.setText(spokenText)
            } else {
                DebugLog.add(TAG, "onActivityResult: Voice input successful but no results returned.")
            }
        } else {
            DebugLog.add(TAG, "onActivityResult: Voice input failed or was cancelled. ResultCode: $resultCode")
        }
    }
    
    private fun sendCommand() {
        val command = editTextCommand.text.toString().trim()
        DebugLog.add(TAG, "sendCommand: Current command text: '$command'")
        if (command.isEmpty()) {
            DebugLog.add(TAG, "sendCommand: Command is empty. Showing toast.")
            Toast.makeText(this, "Please enter a command", Toast.LENGTH_SHORT).show()
            return
        }
        
        // Send command to the accessibility service
        val intent = Intent("com.droidrun.portal.NATURAL_LANGUAGE_COMMAND").apply {
            putExtra("command", command)
            setPackage(packageName)
        }
        
        sendBroadcast(intent)
        DebugLog.add(TAG, "sendCommand: Broadcast sent for NATURAL_LANGUAGE_COMMAND with command: '$command'")
        Toast.makeText(this, "Command sent: $command", Toast.LENGTH_SHORT).show()
        
        // Clear the text field
        editTextCommand.setText("")
        DebugLog.add(TAG, "sendCommand: EditText cleared.")
        
        // Close activity after sending
        DebugLog.add(TAG, "sendCommand: Finishing VoiceCommandActivity.")
        finish()
    }
}

