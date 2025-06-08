package com.droidrun.portal

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Context.RECEIVER_NOT_EXPORTED
import android.content.Intent
import android.content.IntentFilter
import android.inputmethodservice.InputMethodService
import android.os.Build
import android.util.Base64
import android.util.Log
import android.view.InputDevice
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.ExtractedTextRequest

class DroidrunKeyboardIME : InputMethodService() {
    private val TAG = "DroidrunKeyboardIME"
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
        Log.d(TAG, "DroidrunKeyboardIME: onCreate() called")
        
        // Register broadcast receiver when service is created
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
            
            // Use RECEIVER_NOT_EXPORTED for security and API 34+ compatibility
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                registerReceiver(mReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
            } else {
                registerReceiver(mReceiver, filter)
            }
            Log.d(TAG, "Broadcast receiver registered in onCreate()")
        }
    }

    override fun onCreateInputView(): View {
        Log.d(TAG, "onCreateInputView called")
        
        // Inflate the existing keyboard layout XML
        return layoutInflater.inflate(R.layout.keyboard_view, null)
    }

    override fun onStartInput(attribute: android.view.inputmethod.EditorInfo?, restarting: Boolean) {
        super.onStartInput(attribute, restarting)
        Log.d(TAG, "onStartInput called - restarting: $restarting")
    }

    override fun onStartInputView(attribute: android.view.inputmethod.EditorInfo?, restarting: Boolean) {
        super.onStartInputView(attribute, restarting)
        Log.d(TAG, "onStartInputView called - keyboard should be visible now")
    }

    override fun onDestroy() {
        Log.d(TAG, "DroidrunKeyboardIME: onDestroy() called")
        if (mReceiver != null) {
            try {
                unregisterReceiver(mReceiver)
                Log.d(TAG, "Broadcast receiver unregistered")
            } catch (e: Exception) {
                Log.e(TAG, "Error unregistering receiver", e)
            }
        }
        super.onDestroy()
    }

    inner class KeyboardReceiver : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            Log.d(TAG, "KeyboardReceiver: Received broadcast with action: ${intent.action}")
            Log.d(TAG, "KeyboardReceiver: Intent extras: ${intent.extras}")
            
            when (intent.action) {
                IME_MESSAGE -> {
                    Log.d(TAG, "Processing IME_MESSAGE")
                    // Normal text message
                    val msg = intent.getStringExtra("msg")
                    if (msg != null) {
                        Log.d(TAG, "Committing text: $msg")
                        val ic = currentInputConnection
                        if (ic != null) {
                            ic.commitText(msg, 1)
                            Log.d(TAG, "Text committed successfully")
                        } else {
                            Log.w(TAG, "No input connection available")
                        }
                    }

                    // Meta codes
                    val metaCodes = intent.getStringExtra("mcode")
                    metaCodes?.let {
                        Log.d(TAG, "Processing meta codes: $it")
                        val mcodes = it.split(",")
                        if (mcodes.size > 1) {
                            val ic = currentInputConnection
                            var i = 0
                            while (i < mcodes.size - 1) {
                                if (ic != null) {
                                    val ke = if (mcodes[i].contains("+")) {
                                        // Check metaState if more than one
                                        val arrCode = mcodes[i].split("+")
                                        KeyEvent(
                                            0, 0,
                                            KeyEvent.ACTION_DOWN,
                                            mcodes[i + 1].toInt(),
                                            0,
                                            arrCode[0].toInt() or arrCode[1].toInt(),
                                            0, 0,
                                            KeyEvent.FLAG_SOFT_KEYBOARD or KeyEvent.FLAG_KEEP_TOUCH_MODE,
                                            InputDevice.SOURCE_KEYBOARD
                                        )
                                    } else {
                                        // Only one metaState
                                        KeyEvent(
                                            0, 0,
                                            KeyEvent.ACTION_DOWN,
                                            mcodes[i + 1].toInt(),
                                            0,
                                            mcodes[i].toInt(),
                                            0, 0,
                                            KeyEvent.FLAG_SOFT_KEYBOARD or KeyEvent.FLAG_KEEP_TOUCH_MODE,
                                            InputDevice.SOURCE_KEYBOARD
                                        )
                                    }
                                    ic.sendKeyEvent(ke)
                                }
                                i += 2
                            }
                        }
                    }
                }

                IME_MESSAGE_B64 -> {
                    Log.d(TAG, "Processing IME_MESSAGE_B64")
                    val data = intent.getStringExtra("msg")
                    data?.let {
                        try {
                            Log.d(TAG, "Decoding base64 data: ${it.substring(0, minOf(20, it.length))}...")
                            val b64 = Base64.decode(it, Base64.DEFAULT)
                            val msg = String(b64, Charsets.UTF_8)
                            Log.d(TAG, "Decoded message: $msg")
                            val ic = currentInputConnection
                            if (ic != null) {
                                ic.commitText(msg, 1)
                                Log.d(TAG, "Base64 text committed successfully")
                            } else {
                                Log.w(TAG, "No input connection available for base64 message")
                            }
                        } catch (e: Exception) {
                            Log.e(TAG, "Error decoding base64 message", e)
                        }
                    }
                }

                IME_CHARS -> {
                    val chars = intent.getIntArrayExtra("chars")
                    chars?.let {
                        val msg = String(it.map { code -> code.toChar() }.toCharArray())
                        val ic = currentInputConnection
                        ic?.commitText(msg, 1)
                    }
                }

                IME_KEYCODE -> {
                    val code = intent.getIntExtra("code", -1)
                    if (code != -1) {
                        val ic = currentInputConnection
                        ic?.sendKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, code))
                        ic?.sendKeyEvent(KeyEvent(KeyEvent.ACTION_UP, code))
                    }
                }

                IME_EDITORCODE -> {
                    val code = intent.getIntExtra("code", -1)
                    if (code != -1) {
                        val ic = currentInputConnection
                        ic?.performEditorAction(code)
                    }
                }

                IME_CLEAR_TEXT -> {
                    val ic = currentInputConnection
                    ic?.let {
                        val extractedText = it.getExtractedText(ExtractedTextRequest(), 0)
                        if (extractedText != null) {
                            val curPos = extractedText.text
                            val beforePos = it.getTextBeforeCursor(curPos.length, 0)
                            val afterPos = it.getTextAfterCursor(curPos.length, 0)
                            it.deleteSurroundingText(beforePos?.length ?: 0, afterPos?.length ?: 0)
                        }
                    }
                }
            }
        }
    }
} 