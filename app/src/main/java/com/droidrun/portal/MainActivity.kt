package com.droidrun.portal

import android.content.Context
import android.content.Context.RECEIVER_EXPORTED
import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import android.content.BroadcastReceiver
import android.content.IntentFilter
import android.text.Editable
import android.text.TextWatcher
import android.view.inputmethod.EditorInfo
import android.widget.SeekBar
import android.widget.Toast
import com.google.android.material.button.MaterialButton
import com.google.android.material.switchmaterial.SwitchMaterial
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import android.provider.Settings
import android.widget.ImageView
import android.view.View

class MainActivity : AppCompatActivity() {
    
    private lateinit var statusText: TextView
    private lateinit var responseText: TextView
    private lateinit var toggleOverlay: SwitchMaterial
    private lateinit var fetchButton: MaterialButton
    private lateinit var retriggerButton: MaterialButton
    private lateinit var offsetSlider: SeekBar
    private lateinit var offsetInput: TextInputEditText
    private lateinit var offsetInputLayout: TextInputLayout
    private lateinit var accessibilityIndicator: View
    private lateinit var accessibilityStatusText: TextView
    private lateinit var accessibilityStatusContainer: View
    private lateinit var accessibilityStatusCard: com.google.android.material.card.MaterialCardView
    
    // Flag to prevent infinite update loops
    private var isProgrammaticUpdate = false
    
    // Constants for the position offset slider
    companion object {
        private const val DEFAULT_OFFSET = -128
        private const val MIN_OFFSET = -256
        private const val MAX_OFFSET = 256
        private const val SLIDER_RANGE = MAX_OFFSET - MIN_OFFSET
        
        // Intent action for updating overlay offset
        const val ACTION_UPDATE_OVERLAY_OFFSET = "com.droidrun.portal.UPDATE_OVERLAY_OFFSET"
        const val EXTRA_OVERLAY_OFFSET = "overlay_offset"
    }
    
    // Broadcast receiver to get element data response
    private val elementDataReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            Log.e("DROIDRUN_MAIN", "Received broadcast: ${intent.action}")
            if (intent.action == DroidrunPortalService.ACTION_ELEMENTS_RESPONSE) {
                // Handle element data response
                val data = intent.getStringExtra(DroidrunPortalService.EXTRA_ELEMENTS_DATA)
                if (data != null) {
                    Log.e("DROIDRUN_MAIN", "Received element data: ${data.substring(0, Math.min(100, data.length))}...")
                    
                    // Update UI with the data
                    statusText.text = "Received data: ${data.length} characters"
                    responseText.text = data // Display the full JSON string
                    Toast.makeText(context, "Data received successfully!", Toast.LENGTH_SHORT).show()
                }
                
                // Handle retrigger response
                val retriggerStatus = intent.getStringExtra("retrigger_status")
                if (retriggerStatus != null) {
                    val count = intent.getIntExtra("elements_count", 0)
                    statusText.text = "Elements refreshed: $count UI elements restored"
                    Toast.makeText(context, "Refresh successful: $count elements", Toast.LENGTH_SHORT).show()
                }
                
                // Handle overlay toggle status
                if (intent.hasExtra("overlay_status")) {
                    val overlayVisible = intent.getBooleanExtra("overlay_status", true)
                    toggleOverlay.isChecked = overlayVisible
                }
                
                // Handle position offset response
                if (intent.hasExtra("current_offset")) {
                    val currentOffset = intent.getIntExtra("current_offset", DEFAULT_OFFSET)
                    updateOffsetSlider(currentOffset)
                    updateOffsetInputField(currentOffset)
                }
            }
        }
    }
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        
        // Initialize UI elements
        statusText = findViewById(R.id.status_text)
        responseText = findViewById(R.id.response_text)
        fetchButton = findViewById(R.id.fetch_button)
        retriggerButton = findViewById(R.id.retrigger_button)
        toggleOverlay = findViewById(R.id.toggle_overlay)
        offsetSlider = findViewById(R.id.offset_slider)
        offsetInput = findViewById(R.id.offset_input)
        offsetInputLayout = findViewById(R.id.offset_input_layout)
        accessibilityIndicator = findViewById(R.id.accessibility_indicator)
        accessibilityStatusText = findViewById(R.id.accessibility_status_text)
        accessibilityStatusContainer = findViewById(R.id.accessibility_status_container)
        accessibilityStatusCard = findViewById(R.id.accessibility_status_card)
        
        // Configure the offset slider and input
        setupOffsetSlider()
        setupOffsetInput()
        
        // Register for responses
        val filter = IntentFilter(DroidrunPortalService.ACTION_ELEMENTS_RESPONSE)
        registerReceiver(elementDataReceiver, filter, RECEIVER_EXPORTED)
        
        fetchButton.setOnClickListener {
            fetchElementData()
        }
        
        retriggerButton.setOnClickListener {
            retriggerElements()
        }
        
        toggleOverlay.setOnCheckedChangeListener { _, isChecked ->
            toggleOverlayVisibility(isChecked)
        }
        
        // Setup accessibility status container
        accessibilityStatusContainer.setOnClickListener {
            openAccessibilitySettings()
        }
        
        // Check initial accessibility status
        updateAccessibilityStatusIndicator()
    }
    
    override fun onResume() {
        super.onResume()
        // Update the accessibility status indicator when app resumes
        updateAccessibilityStatusIndicator()
    }
    
    private fun setupOffsetSlider() {
        // Initialize the slider with the new range
        offsetSlider.max = SLIDER_RANGE
        
        // Convert the default offset to slider position
        val initialSliderPosition = DEFAULT_OFFSET - MIN_OFFSET
        offsetSlider.progress = initialSliderPosition
        
        // Set listener for slider changes
        offsetSlider.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                // Convert slider position back to actual offset value (range -256 to +256)
                val offsetValue = progress + MIN_OFFSET
                
                // Update input field to match slider (only when user is sliding)
                if (fromUser) {
                    updateOffsetInputField(offsetValue)
                    updateOverlayOffset(offsetValue)
                }
            }
            
            override fun onStartTrackingTouch(seekBar: SeekBar?) {
                // Not needed
            }
            
            override fun onStopTrackingTouch(seekBar: SeekBar?) {
                // Final update when user stops sliding
                val offsetValue = seekBar?.progress?.plus(MIN_OFFSET) ?: DEFAULT_OFFSET
                updateOverlayOffset(offsetValue)
            }
        })
    }
    
    private fun setupOffsetInput() {
        // Set initial value
        isProgrammaticUpdate = true
        offsetInput.setText(DEFAULT_OFFSET.toString())
        isProgrammaticUpdate = false
        
        // Apply on enter key
        offsetInput.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_DONE) {
                applyInputOffset()
                true
            } else {
                false
            }
        }
        
        // Input validation and auto-apply
        offsetInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            
            override fun afterTextChanged(s: Editable?) {
                // Skip processing if this is a programmatic update
                if (isProgrammaticUpdate) return
                
                try {
                    val value = s.toString().toIntOrNull()
                    if (value != null) {
                        if (value < MIN_OFFSET || value > MAX_OFFSET) {
                            offsetInputLayout.error = "Value must be between $MIN_OFFSET and $MAX_OFFSET"
                        } else {
                            offsetInputLayout.error = null
                            // Auto-apply if value is valid and complete
                            if (s.toString().length > 1 || (s.toString().length == 1 && !s.toString().startsWith("-"))) {
                                applyInputOffset()
                            }
                        }
                    } else if (s.toString().isNotEmpty() && s.toString() != "-") {
                        offsetInputLayout.error = "Invalid number"
                    } else {
                        offsetInputLayout.error = null
                    }
                } catch (e: Exception) {
                    offsetInputLayout.error = "Invalid number"
                }
            }
        })
    }
    
    private fun applyInputOffset() {
        try {
            val inputText = offsetInput.text.toString()
            val offsetValue = inputText.toIntOrNull()
            
            if (offsetValue != null) {
                // Ensure the value is within bounds
                val boundedValue = offsetValue.coerceIn(MIN_OFFSET, MAX_OFFSET)
                
                if (boundedValue != offsetValue) {
                    // Update input if we had to bound the value
                    isProgrammaticUpdate = true
                    offsetInput.setText(boundedValue.toString())
                    isProgrammaticUpdate = false
                    Toast.makeText(this, "Value adjusted to valid range", Toast.LENGTH_SHORT).show()
                }
                
                // Update slider to match and apply the offset
                val sliderPosition = boundedValue - MIN_OFFSET
                offsetSlider.progress = sliderPosition
                updateOverlayOffset(boundedValue)
            } else {
                // Invalid input
                Toast.makeText(this, "Please enter a valid number", Toast.LENGTH_SHORT).show()
            }
        } catch (e: Exception) {
            Log.e("DROIDRUN_MAIN", "Error applying input offset: ${e.message}")
            Toast.makeText(this, "Error: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }
    
    private fun updateOffsetSlider(currentOffset: Int) {
        // Ensure the offset is within our new bounds
        val boundedOffset = currentOffset.coerceIn(MIN_OFFSET, MAX_OFFSET)
        
        // Update the slider to match the current offset from the service
        val sliderPosition = boundedOffset - MIN_OFFSET
        offsetSlider.progress = sliderPosition
    }
    
    private fun updateOffsetInputField(currentOffset: Int) {
        // Set flag to prevent TextWatcher from triggering
        isProgrammaticUpdate = true
        
        // Update the text input to match the current offset
        offsetInput.setText(currentOffset.toString())
        
        // Reset flag
        isProgrammaticUpdate = false
    }
    
    private fun updateOverlayOffset(offsetValue: Int) {
        try {
            val intent = Intent(ACTION_UPDATE_OVERLAY_OFFSET).apply {
                putExtra(EXTRA_OVERLAY_OFFSET, offsetValue)
            }
            sendBroadcast(intent)
            
            statusText.text = "Updating element offset to: $offsetValue"
            Log.e("DROIDRUN_MAIN", "Sent offset update: $offsetValue")
        } catch (e: Exception) {
            statusText.text = "Error updating offset: ${e.message}"
            Log.e("DROIDRUN_MAIN", "Error sending offset update: ${e.message}")
        }
    }
    
    override fun onDestroy() {
        super.onDestroy()
        try {
            unregisterReceiver(elementDataReceiver)
        } catch (e: Exception) {
            Log.e("DROIDRUN_MAIN", "Error unregistering receiver: ${e.message}")
        }
    }
    
    private fun fetchElementData() {
        try {
            // Send broadcast to request elements
            val intent = Intent(DroidrunPortalService.ACTION_GET_ELEMENTS)
            sendBroadcast(intent)
            
            statusText.text = "Request sent, awaiting response..."
            Log.e("DROIDRUN_MAIN", "Broadcast sent with action: ${DroidrunPortalService.ACTION_GET_ELEMENTS}")
        } catch (e: Exception) {
            statusText.text = "Error sending request: ${e.message}"
            Log.e("DROIDRUN_MAIN", "Error sending broadcast: ${e.message}")
        }
    }
    
    private fun toggleOverlayVisibility(visible: Boolean) {
        try {
            val intent = Intent(DroidrunPortalService.ACTION_TOGGLE_OVERLAY).apply {
                putExtra(DroidrunPortalService.EXTRA_OVERLAY_VISIBLE, visible)
            }
            sendBroadcast(intent)
            
            statusText.text = "Visualization overlays ${if (visible) "enabled" else "disabled"}"
            Log.e("DROIDRUN_MAIN", "Toggled overlay visibility to: $visible")
        } catch (e: Exception) {
            statusText.text = "Error changing visibility: ${e.message}"
            Log.e("DROIDRUN_MAIN", "Error toggling overlay: ${e.message}")
        }
    }
    
    private fun retriggerElements() {
        try {
            // Send broadcast to request element retrigger
            val intent = Intent(DroidrunPortalService.ACTION_RETRIGGER_ELEMENTS)
            sendBroadcast(intent)
            
            statusText.text = "Refreshing UI elements..."
            Log.e("DROIDRUN_MAIN", "Broadcast sent with action: ${DroidrunPortalService.ACTION_RETRIGGER_ELEMENTS}")
            Toast.makeText(this, "Refreshing elements...", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            statusText.text = "Error refreshing elements: ${e.message}"
            Log.e("DROIDRUN_MAIN", "Error sending retrigger broadcast: ${e.message}")
        }
    }
    
    // Check if the accessibility service is enabled
    private fun isAccessibilityServiceEnabled(): Boolean {
        val accessibilityServiceName = packageName + "/" + DroidrunPortalService::class.java.canonicalName
        
        try {
            val enabledServices = Settings.Secure.getString(
                contentResolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
            )
            
            return enabledServices?.contains(accessibilityServiceName) == true
        } catch (e: Exception) {
            Log.e("DROIDRUN_MAIN", "Error checking accessibility status: ${e.message}")
            return false
        }
    }
    
    // Update the accessibility status indicator based on service status
    private fun updateAccessibilityStatusIndicator() {
        val isEnabled = isAccessibilityServiceEnabled()
        
        if (isEnabled) {
            accessibilityIndicator.setBackgroundResource(R.drawable.circle_indicator_green)
            accessibilityStatusText.text = "ENABLED"
            accessibilityStatusCard.setCardBackgroundColor(resources.getColor(R.color.droidrun_secondary, null))
        } else {
            accessibilityIndicator.setBackgroundResource(R.drawable.circle_indicator_red)
            accessibilityStatusText.text = "DISABLED"
            accessibilityStatusCard.setCardBackgroundColor(resources.getColor(R.color.droidrun_secondary, null))
        }
    }
    
    // Open accessibility settings to enable the service
    private fun openAccessibilitySettings() {
        try {
            val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
            startActivity(intent)
            Toast.makeText(
                this,
                "Please enable Droidrun Portal in Accessibility Services",
                Toast.LENGTH_LONG
            ).show()
        } catch (e: Exception) {
            Log.e("DROIDRUN_MAIN", "Error opening accessibility settings: ${e.message}")
            Toast.makeText(
                this,
                "Error opening accessibility settings",
                Toast.LENGTH_SHORT
            ).show()
        }
    }
} 