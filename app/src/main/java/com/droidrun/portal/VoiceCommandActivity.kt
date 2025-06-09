package com.droidrun.portal

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.widget.Button
import android.widget.EditText
import android.widget.Toast
import android.util.Log

class VoiceCommandActivity : Activity() {
    private val TAG = "VoiceCommandActivity"
    private val SPEECH_REQUEST_CODE = 100
    
    private lateinit var editTextCommand: EditText
    private lateinit var buttonVoice: Button
    private lateinit var buttonSend: Button
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        // Simple layout programmatically
        val layout = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(50, 50, 50, 50)
        }
        
        editTextCommand = EditText(this).apply {
            hint = "Enter command or use voice..."
            minLines = 3
        }
        
        buttonVoice = Button(this).apply {
            text = "🎤 Voice Command"
            setOnClickListener { startVoiceRecognition() }
        }
        
        buttonSend = Button(this).apply {
            text = "Send Command"
            setOnClickListener { sendCommand() }
        }
        
        layout.addView(editTextCommand)
        layout.addView(buttonVoice)
        layout.addView(buttonSend)
        
        setContentView(layout)
    }
    
    private fun startVoiceRecognition() {
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            Toast.makeText(this, "Speech recognition not available", Toast.LENGTH_SHORT).show()
            return
        }
        
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_PROMPT, "Say your command...")
        }
        
        try {
            startActivityForResult(intent, SPEECH_REQUEST_CODE)
        } catch (e: Exception) {
            Toast.makeText(this, "Error starting voice recognition", Toast.LENGTH_SHORT).show()
        }
    }
    
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        
        if (requestCode == SPEECH_REQUEST_CODE && resultCode == RESULT_OK) {
            val results = data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)
            if (!results.isNullOrEmpty()) {
                val spokenText = results[0]
                editTextCommand.setText(spokenText)
                Log.d(TAG, "Voice input: $spokenText")
            }
        }
    }
    
    private fun sendCommand() {
        val command = editTextCommand.text.toString().trim()
        if (command.isEmpty()) {
            Toast.makeText(this, "Please enter a command", Toast.LENGTH_SHORT).show()
            return
        }
        
        // Send command to the accessibility service
        // Ensure this action matches one listened to by DroidrunPortalService
        val intent = Intent("com.droidrun.portal.PROCESS_VOICE_COMMAND").apply {
            putExtra("command", command)
            setPackage(packageName)
        }
        
        sendBroadcast(intent)
        Toast.makeText(this, "Command sent: $command", Toast.LENGTH_SHORT).show()
        Log.d(TAG, "Sent command: $command")
        
        // Clear the text field
        editTextCommand.setText("")
        
        // Close activity after sending
        finish()
    }
}

