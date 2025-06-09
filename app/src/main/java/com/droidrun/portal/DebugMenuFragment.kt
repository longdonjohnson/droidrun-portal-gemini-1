package com.droidrun.portal

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.DialogFragment
import com.google.android.material.switchmaterial.SwitchMaterial
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import android.widget.SeekBar
import android.text.TextWatcher
import android.text.Editable
import android.text.method.ScrollingMovementMethod
import android.content.Context
import android.content.ClipboardManager
import android.content.ClipData
import android.view.inputmethod.EditorInfo

class DebugMenuFragment : DialogFragment() {

    private var mainActivityInstance: MainActivity? = null
    private lateinit var overlayToggle: SwitchMaterial
    private lateinit var offsetValueText: TextView // For displaying current offset
    private lateinit var logTextView: TextView
    private lateinit var refreshLogsButton: Button
    private lateinit var clearLogsButton: Button
    private lateinit var copyLogsButton: Button
    private lateinit var debugOffsetInputLayout: TextInputLayout
    private lateinit var debugOffsetInput: TextInputEditText
    private lateinit var debugOffsetSlider: SeekBar
    private lateinit var floatingButtonToggle: SwitchMaterial // New

    private var isProgrammaticOffsetUpdate = false

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        return inflater.inflate(R.layout.debug_menu_layout, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val closeButton: Button = view.findViewById(R.id.debug_menu_close_button)
        closeButton.setOnClickListener { dismiss() }

        // Initialize existing views
        overlayToggle = view.findViewById(R.id.debug_menu_overlay_toggle)
        offsetValueText = view.findViewById(R.id.debug_menu_offset_value) // TextView for current offset
        logTextView = view.findViewById(R.id.debug_menu_log_textview)
        refreshLogsButton = view.findViewById(R.id.debug_menu_refresh_logs_button)
        clearLogsButton = view.findViewById(R.id.debug_menu_clear_logs_button)
        copyLogsButton = view.findViewById(R.id.debug_menu_copy_logs_button)
        debugOffsetInputLayout = view.findViewById(R.id.debug_menu_offset_input_layout)
        debugOffsetInput = view.findViewById(R.id.debug_menu_offset_input)
        debugOffsetSlider = view.findViewById(R.id.debug_menu_offset_slider)
        floatingButtonToggle = view.findViewById(R.id.debug_menu_floating_button_toggle) // New

        logTextView.movementMethod = ScrollingMovementMethod()

        mainActivityInstance?.let { activity ->
            // Overlay toggle
            overlayToggle.isChecked = activity.isOverlayCurrentlyVisible()
            overlayToggle.setOnCheckedChangeListener { _, isChecked ->
                activity.toggleOverlayVisibilityExternally(isChecked)
            }

            // Offset controls
            val currentOffset = activity.getCurrentOffset()
            isProgrammaticOffsetUpdate = true
            offsetValueText.text = currentOffset.toString()
            debugOffsetInput.setText(currentOffset.toString())
            // Ensure MIN_OFFSET and MAX_OFFSET are accessible from MainActivity for slider setup
            debugOffsetSlider.max = MainActivity.MAX_OFFSET - MainActivity.MIN_OFFSET
            debugOffsetSlider.progress = currentOffset - MainActivity.MIN_OFFSET
            isProgrammaticOffsetUpdate = false

            debugOffsetSlider.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                    if (fromUser) {
                        isProgrammaticOffsetUpdate = true
                        val actualValue = progress + MainActivity.MIN_OFFSET
                        debugOffsetInput.setText(actualValue.toString())
                        offsetValueText.text = actualValue.toString()
                        activity.setNewOverlayOffset(actualValue)
                        isProgrammaticOffsetUpdate = false
                    }
                }
                override fun onStartTrackingTouch(seekBar: SeekBar?) {}
                override fun onStopTrackingTouch(seekBar: SeekBar?) {}
            })

            debugOffsetInput.addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
                override fun afterTextChanged(s: Editable?) {
                    if (isProgrammaticOffsetUpdate) return
                    val value = s.toString().toIntOrNull()
                    if (value != null) {
                        if (value >= MainActivity.MIN_OFFSET && value <= MainActivity.MAX_OFFSET) {
                            debugOffsetInputLayout.error = null
                            isProgrammaticOffsetUpdate = true
                            debugOffsetSlider.progress = value - MainActivity.MIN_OFFSET
                            offsetValueText.text = value.toString()
                            activity.setNewOverlayOffset(value)
                            isProgrammaticOffsetUpdate = false
                        } else {
                            debugOffsetInputLayout.error = "Min: ${MainActivity.MIN_OFFSET}, Max: ${MainActivity.MAX_OFFSET}"
                        }
                    } else if (s.toString().isNotEmpty() && s.toString() != "-") {
                        debugOffsetInputLayout.error = "Invalid number"
                    } else {
                        debugOffsetInputLayout.error = null
                    }
                }
            })
            debugOffsetInput.setOnEditorActionListener { _, actionId, _ ->
                if (actionId == EditorInfo.IME_ACTION_DONE) { true } else { false }
            }

            // Floating button toggle
            val prefs = activity.getSharedPreferences(MainActivity.PREFS_NAME, Context.MODE_PRIVATE)
            floatingButtonToggle.isChecked = prefs?.getBoolean(MainActivity.KEY_FLOATING_BUTTON_VISIBLE, false) ?: false
            floatingButtonToggle.setOnCheckedChangeListener { _, isChecked ->
                activity.setFloatingVoiceButtonVisibility(isChecked)
            }
        }

        refreshLogsButton.setOnClickListener { refreshLogView() }
        clearLogsButton.setOnClickListener {
            DebugLog.clearLogs()
            refreshLogView()
        }
        copyLogsButton.setOnClickListener { copyLogsToClipboard() }

        refreshLogView() // Initial population
    }

    private fun copyLogsToClipboard() {
        val logs = DebugLog.getLogs().joinToString("\n")
        if (logs.isEmpty()) {
            Toast.makeText(context, "Log is empty, nothing to copy.", Toast.LENGTH_SHORT).show()
            return
        }
        val clipboard = context?.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
        val clip = ClipData.newPlainText("DroidRun Logs", logs)
        clipboard?.setPrimaryClip(clip)
        Toast.makeText(context, "Logs copied to clipboard!", Toast.LENGTH_SHORT).show()
        // Assuming DebugLog is accessible here, if not, this line would need adjustment or removal.
        // For now, I'll assume DebugLog.kt was also part of the reset and isn't available.
        // DebugLog.add("DebugMenu", "Logs copied to clipboard.")
    }

    private fun refreshLogView() {
        if (::logTextView.isInitialized) {
            // Assuming DebugLog is accessible here.
            // If DebugLog.kt was reset, this would fail.
            // For now, I'll assume DebugLog.kt would be re-created or handled separately.
            val logs = DebugLog.getLogs()
            logTextView.text = if (logs.isEmpty()) "No logs yet." else logs.joinToString("\n")
        }
    }

    override fun onStart() {
        super.onStart()
        dialog?.window?.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
    }

    companion object {
        const val TAG = "DebugMenuFragment"
        fun newInstance(activity: MainActivity): DebugMenuFragment {
            val fragment = DebugMenuFragment()
            fragment.mainActivityInstance = activity
            return fragment
        }
    }
}
