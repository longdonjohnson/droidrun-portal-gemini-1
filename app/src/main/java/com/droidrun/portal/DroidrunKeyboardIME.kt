package com.droidrun.portal

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Context.RECEIVER_NOT_EXPORTED
import android.content.Intent
import android.content.IntentFilter
import android.inputmethodservice.InputMethodService
import android.os.Build
import android.util.Base64
// import android.util.Log // Replaced with DebugLog
import com.droidrun.portal.DebugLog // Added DebugLog
import android.view.InputDevice
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.ExtractedTextRequest

class DroidrunKeyboardIME : InputMethodService() {
    private val TAG = "DroidrunIME" // Consistent TAG, shortened
    private val IME_MESSAGE = "com.droidrun.portal.DROIDRUN_INPUT_TEXT"
    private val IME_CHARS = "com.droidrun.portal.DROIDRUN_INPUT_CHARS"
    private val IME_KEYCODE = "com.droidrun.portal.DROIDRUN_INPUT_CODE"
    private val IME_META_KEYCODE = "com.droidrun.portal.DROIDRUN_INPUT_MCODE"
    private val IME_EDITORCODE = "com.droidrun.portal.DROIDRUN_EDITOR_CODE"
    private val IME_MESSAGE_B64 = "com.droidrun.portal.INTERNAL_INPUT_B64"
    private val IME_CLEAR_TEXT = "com.droidrun.portal.DROIDRUN_CLEAR_TEXT"
    private var mReceiver: BroadcastReceiver? = null

    override fun onCreate() {
        super.onCreate()
        DebugLog.add(TAG, "onCreate: DroidrunKeyboardIME service creating.")
        
        if (mReceiver == null) {
            mReceiver = KeyboardReceiver()
            val filter = IntentFilter().apply {
                addAction(IME_MESSAGE)
                addAction(IME_CHARS)
                addAction(IME_KEYCODE)
                addAction(IME_META_KEYCODE)
                addAction(IME_EDITORCODE)
                addAction(IME_MESSAGE_B64)
                addAction(IME_CLEAR_TEXT)
            }
            
            DebugLog.add(TAG, "onCreate: Registering KeyboardReceiver for actions: ${filter.actionsIterator().asSequence().joinToString()}")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                registerReceiver(mReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
            } else {
                registerReceiver(mReceiver, filter)
            }
            DebugLog.add(TAG, "onCreate: KeyboardReceiver registered successfully.")
        }
        DebugLog.add(TAG, "onCreate: DroidrunKeyboardIME service created.")
    }

    override fun onCreateInputView(): View {
        DebugLog.add(TAG, "onCreateInputView: Creating input view.")
        // Inflate the existing keyboard layout XML
        val view = layoutInflater.inflate(R.layout.keyboard_view, null)
        DebugLog.add(TAG, "onCreateInputView: Input view created.")
        return view
    }

    override fun onStartInput(attribute: android.view.inputmethod.EditorInfo?, restarting: Boolean) {
        super.onStartInput(attribute, restarting)
        DebugLog.add(TAG, "onStartInput: Called. Restarting: $restarting, EditorInfo: ${attribute?.inputType}, Package: ${attribute?.packageName}")
    }

    override fun onStartInputView(attribute: android.view.inputmethod.EditorInfo?, restarting: Boolean) {
        super.onStartInputView(attribute, restarting)
        DebugLog.add(TAG, "onStartInputView: Keyboard view is now being shown. Restarting: $restarting, EditorInfo: ${attribute?.inputType}")
    }

    override fun onFinishInputView(finishingInput: Boolean) {
        super.onFinishInputView(finishingInput)
        DebugLog.add(TAG, "onFinishInputView: Keyboard view is being hidden. Finishing input: $finishingInput")
    }

    override fun onFinishInput() {
        super.onFinishInput()
        DebugLog.add(TAG, "onFinishInput: Called when input is finished for current editor.")
    }

    override fun onDestroy() {
        DebugLog.add(TAG, "onDestroy: DroidrunKeyboardIME service destroying.")
        if (mReceiver != null) {
            try {
                unregisterReceiver(mReceiver)
                DebugLog.add(TAG, "onDestroy: KeyboardReceiver unregistered successfully.")
            } catch (e: Exception) {
                DebugLog.add(TAG, "onDestroy: Error unregistering KeyboardReceiver: ${e.message}")
                // Log.e(TAG, "Error unregistering receiver", e) // Keep for stack trace if needed
            }
            mReceiver = null
        }
        super.onDestroy()
        DebugLog.add(TAG, "onDestroy: DroidrunKeyboardIME service destroyed.")
    }

    inner class KeyboardReceiver : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val action = intent.action ?: "null_action"
            DebugLog.add(TAG, "KeyboardReceiver.onReceive: Received broadcast. Action: $action")
            // For security, consider checking intent.getPackage() if these are not system-protected broadcasts
            
            val ic = currentInputConnection
            if (ic == null) {
                DebugLog.add(TAG, "KeyboardReceiver.onReceive: No InputConnection available. Cannot process action: $action. Ensure keyboard is active.")
                return
            }

            when (action) {
                IME_MESSAGE -> {
                    val msg = intent.getStringExtra("msg")
                    if (msg != null) {
                        DebugLog.add(TAG, "KeyboardReceiver.onReceive (IME_MESSAGE): Committing text: \"$msg\"")
                        ic.commitText(msg, 1) // New cursor position is 1 character after committed text
                    } else {
                        DebugLog.add(TAG, "KeyboardReceiver.onReceive (IME_MESSAGE): Received null message.")
                    }

                    val metaCodes = intent.getStringExtra("mcode")
                    metaCodes?.let {
                        DebugLog.add(TAG, "KeyboardReceiver.onReceive (IME_MESSAGE): Processing meta codes: $it")
                        val mcodes = it.split(",")
                        if (mcodes.size > 1 && mcodes.size % 2 == 0) { // Ensure pairs
                            var i = 0
                            while (i < mcodes.size) {
                                val metaStateStr = mcodes[i]
                                val keyCode = mcodes[i + 1].toIntOrNull()
                                if (keyCode == null) {
                                    DebugLog.add(TAG, "KeyboardReceiver.onReceive (IME_MESSAGE): Invalid keyCode ${mcodes[i+1]}. Skipping.")
                                    i += 2
                                    continue
                                }

                                val metaStates = metaStateStr.split("+").mapNotNull { it.toIntOrNull() }
                                var combinedMetaState = 0
                                metaStates.forEach { combinedMetaState = combinedMetaState or it }

                                DebugLog.add(TAG, "KeyboardReceiver.onReceive (IME_MESSAGE): Sending KeyEvent with meta: $combinedMetaState, code: $keyCode")
                                val keDown = KeyEvent(0, 0, KeyEvent.ACTION_DOWN, keyCode, 0, combinedMetaState, 0, 0, KeyEvent.FLAG_SOFT_KEYBOARD or KeyEvent.FLAG_KEEP_TOUCH_MODE, InputDevice.SOURCE_KEYBOARD)
                                val keUp = KeyEvent(0, 0, KeyEvent.ACTION_UP, keyCode, 0, combinedMetaState, 0, 0, KeyEvent.FLAG_SOFT_KEYBOARD or KeyEvent.FLAG_KEEP_TOUCH_MODE, InputDevice.SOURCE_KEYBOARD)
                                ic.sendKeyEvent(keDown)
                                ic.sendKeyEvent(keUp)
                                i += 2
                            }
                        } else {
                             DebugLog.add(TAG, "KeyboardReceiver.onReceive (IME_MESSAGE): Invalid meta codes format: $it")
                        }
                    }
                }

                IME_MESSAGE_B64 -> {
                    val data = intent.getStringExtra("msg")
                    if (data == null) {
                        DebugLog.add(TAG, "KeyboardReceiver.onReceive (IME_MESSAGE_B64): Received null data.")
                        return
                    }
                    DebugLog.add(TAG, "KeyboardReceiver.onReceive (IME_MESSAGE_B64): Received Base64 data (first 20 chars): ${data.take(20)}...")
                    try {
                        val b64 = Base64.decode(data, Base64.DEFAULT)
                        val msg = String(b64, Charsets.UTF_8)
                        DebugLog.add(TAG, "KeyboardReceiver.onReceive (IME_MESSAGE_B64): Decoded message: \"$msg\". Committing text.")
                        ic.commitText(msg, 1)
                    } catch (e: IllegalArgumentException) {
                        DebugLog.add(TAG, "KeyboardReceiver.onReceive (IME_MESSAGE_B64): Error decoding Base64 message: ${e.message}")
                    } catch (e: Exception) {
                        DebugLog.add(TAG, "KeyboardReceiver.onReceive (IME_MESSAGE_B64): Unexpected error processing Base64 message: ${e.message}")
                    }
                }

                IME_CHARS -> {
                    val chars = intent.getIntArrayExtra("chars")
                    if (chars != null && chars.isNotEmpty()) {
                        val msg = String(chars.map { code -> code.toChar() }.toCharArray())
                        DebugLog.add(TAG, "KeyboardReceiver.onReceive (IME_CHARS): Committing characters from char array: \"$msg\"")
                        ic.commitText(msg, 1)
                    } else {
                        DebugLog.add(TAG, "KeyboardReceiver.onReceive (IME_CHARS): Received null or empty char array.")
                    }
                }

                IME_KEYCODE -> {
                    val code = intent.getIntExtra("code", -1)
                    if (code != -1) {
                        DebugLog.add(TAG, "KeyboardReceiver.onReceive (IME_KEYCODE): Sending KeyEvent for code: $code")
                        ic.sendKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, code))
                        ic.sendKeyEvent(KeyEvent(KeyEvent.ACTION_UP, code))
                    } else {
                        DebugLog.add(TAG, "KeyboardReceiver.onReceive (IME_KEYCODE): Received invalid key code (-1).")
                    }
                }

                IME_EDITORCODE -> {
                    val code = intent.getIntExtra("code", -1)
                    if (code != -1) {
                        DebugLog.add(TAG, "KeyboardReceiver.onReceive (IME_EDITORCODE): Performing editor action for code: $code")
                        ic.performEditorAction(code)
                    } else {
                        DebugLog.add(TAG, "KeyboardReceiver.onReceive (IME_EDITORCODE): Received invalid editor action code (-1).")
                    }
                }

                IME_CLEAR_TEXT -> {
                    DebugLog.add(TAG, "KeyboardReceiver.onReceive (IME_CLEAR_TEXT): Attempting to clear text in current field.")
                    // Ensure we are operating on the current state of the text field
                    val currentText = ic.getExtractedText(ExtractedTextRequest(), 0)?.text ?: ""
                    if (currentText.isNotEmpty()) {
                        // A common way to clear is to select all and delete, or delete by length.
                        // Deleting large amounts of text with deleteSurroundingText can be slow.
                        // A more robust way might be to set an empty string if supported, or select all and replace.
                        // For now, using deleteSurroundingText with large numbers to simulate clearing.
                        // The numbers 100000 are arbitrary large numbers.
                        ic.deleteSurroundingText(currentText.length, 0) // Delete text before cursor (effectively all if cursor at end)
                         DebugLog.add(TAG, "KeyboardReceiver.onReceive (IME_CLEAR_TEXT): Cleared text field (length: ${currentText.length}).")
                    } else {
                        DebugLog.add(TAG, "KeyboardReceiver.onReceive (IME_CLEAR_TEXT): Text field is already empty.")
                    }
                }
                else -> {
                     DebugLog.add(TAG, "KeyboardReceiver.onReceive: Unhandled action: $action")
                }
            }
        }
    }
}