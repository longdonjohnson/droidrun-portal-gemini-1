package com.droidrun.portal

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import androidx.fragment.app.DialogFragment
import com.google.android.material.switchmaterial.SwitchMaterial // Added
import android.widget.TextView // Added
import android.text.method.ScrollingMovementMethod // Added

class DebugMenuFragment : DialogFragment() {

    private var mainActivityInstance: MainActivity? = null
    private lateinit var overlayToggle: SwitchMaterial
    private lateinit var offsetValueText: TextView
    private lateinit var logTextView: TextView
    private lateinit var refreshLogsButton: Button
    private lateinit var clearLogsButton: Button

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        // Inflate the layout for this fragment
        return inflater.inflate(R.layout.debug_menu_layout, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        overlayToggle = view.findViewById(R.id.debug_menu_overlay_toggle)
        offsetValueText = view.findViewById(R.id.debug_menu_offset_value)
        val closeButton: Button = view.findViewById(R.id.debug_menu_close_button)
        logTextView = view.findViewById(R.id.debug_menu_log_textview)
        refreshLogsButton = view.findViewById(R.id.debug_menu_refresh_logs_button)
        clearLogsButton = view.findViewById(R.id.debug_menu_clear_logs_button)

        logTextView.movementMethod = ScrollingMovementMethod()

        closeButton.setOnClickListener {
            dismiss()
        }

        mainActivityInstance?.let { activity ->
            // Set initial state of the toggle
            overlayToggle.isChecked = activity.isOverlayCurrentlyVisible()

            // Set initial offset value
            offsetValueText.text = activity.getCurrentOffset().toString()

            // Set listener for the toggle
            overlayToggle.setOnCheckedChangeListener { _, isChecked ->
                activity.toggleOverlayVisibilityExternally(isChecked)
            }
        }

        refreshLogsButton.setOnClickListener {
            refreshLogView()
        }

        clearLogsButton.setOnClickListener {
            DebugLog.clearLogs()
            refreshLogView()
        }

        refreshLogView() // Initial population
    }

    private fun refreshLogView() {
        if (::logTextView.isInitialized) { // Check if logTextView has been initialized
            val logs = DebugLog.getLogs()
            logTextView.text = if (logs.isEmpty()) "No logs yet." else logs.joinToString("\n")
            // Optional: Scroll to bottom, but might be complex with nested ScrollView
            // For now, simple text set is fine. Android TextView with scrollbars will handle it.
        }
    }

    override fun onStart() {
        super.onStart()
        // Optional: Set dialog dimensions or other properties
        dialog?.window?.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        // You might want to adjust height further, e.g., 80% of screen height, or specific dp.
        // For now, WRAP_CONTENT with the ScrollView's layout_weight will make it expandable.
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
