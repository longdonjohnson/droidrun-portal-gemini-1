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
import com.droidrun.portal.DebugLog // Added
import android.os.Build // Added

class MainActivity : AppCompatActivity() {
    
    private lateinit var statusText: TextView
    private lateinit var responseText: TextView
    // private lateinit var toggleOverlay: SwitchMaterial // Explicitly ensuring this is removed
    private lateinit var fetchButton: MaterialButton
    private lateinit var retriggerButton: MaterialButton
    private var launchVoiceCommandButton: MaterialButton? = null // Make nullable for safe find
    private lateinit var headerCard: MaterialCardView
    // private lateinit var offsetSlider: SeekBar // Removed
    // private lateinit var offsetInput: TextInputEditText // Removed
    // private lateinit var offsetInputLayout: TextInputLayout // Removed
    private lateinit var accessibilityIndicator: View
    private lateinit var accessibilityStatusText: TextView
    private lateinit var accessibilityStatusContainer: View
    private lateinit var accessibilityStatusCard: com.google.android.material.card.MaterialCardView
    
    // Flag to prevent infinite update loops
    private var isProgrammaticUpdate = false // Maintained for potential future use with other inputs
    private var isOverlayActuallyVisibleState: Boolean = true

    // State for 5-tap gesture
    private var tapCount = 0
    private var lastTapTime: Long = 0
    private val TAP_TIMEOUT = 500L
    private val REQUIRED_TAPS = 5
    // Note: headerCard is already declared as a class member above

    companion object {
       private const val TAG = "MainActivity" // Changed TAG
       internal const val PREFS_NAME = "DroidRunPrefs"
       internal const val KEY_OVERLAY_OFFSET = "overlay_offset"
       internal const val KEY_OVERLAY_VISIBLE = "overlay_visible" // Added
       internal const val KEY_FLOATING_BUTTON_VISIBLE = "floating_button_visible"

       internal const val DEFAULT_OFFSET = -128
       internal const val MIN_OFFSET = -256
       internal const val MAX_OFFSET = 256

       // Public for service and potentially other components
       const val ACTION_UPDATE_OVERLAY_OFFSET = "com.droidrun.portal.UPDATE_OVERLAY_OFFSET" // Ensured present
       const val EXTRA_OVERLAY_OFFSET = "overlay_offset"  // Ensured present
   }
    
    private val elementDataReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            DebugLog.add(TAG, "Received broadcast: ${intent.action}") // Using DebugLog
            if (intent.action == DroidrunPortalService.ACTION_ELEMENTS_RESPONSE) {
                val data = intent.getStringExtra(DroidrunPortalService.EXTRA_ELEMENTS_DATA)
                if (data != null) {
                    DebugLog.add(TAG, "Received element data: ${data.take(100)}...")
                    statusText.text = "Received data: ${data.length} characters"
                    responseText.text = data
                    // Toast.makeText(context, "Data received successfully!", Toast.LENGTH_SHORT).show()
                }
                val retriggerStatus = intent.getStringExtra("retrigger_status")
                if (retriggerStatus != null) {
                    val count = intent.getIntExtra("elements_count", 0)
                    statusText.text = "Elements refreshed: $count UI elements restored"
                    // Toast.makeText(context, "Refresh successful: $count elements", Toast.LENGTH_SHORT).show()
                }
                // No direct UI updates for overlay status or offset from here anymore
            }
        }
    }
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        DebugLog.add(TAG, "onCreate called")
        
        statusText = findViewById(R.id.status_text)
        responseText = findViewById(R.id.response_text)
        fetchButton = findViewById(R.id.fetch_button)
        retriggerButton = findViewById(R.id.retrigger_button)
        // Ensure launchVoiceCommandButton is found safely as it might not exist if activity_main.xml was reset
        try {
            launchVoiceCommandButton = findViewById(R.id.launch_voice_command_button)
        } catch (e: Exception) {
            DebugLog.add(TAG, "launch_voice_command_button not found, this is okay if layout is not updated.")
            launchVoiceCommandButton = null // Ensure it's null if not found
        }
        headerCard = findViewById(R.id.header_card)
        accessibilityIndicator = findViewById(R.id.accessibility_indicator)
        accessibilityStatusText = findViewById(R.id.accessibility_status_text)
        accessibilityStatusContainer = findViewById(R.id.accessibility_status_container)
        accessibilityStatusCard = findViewById(R.id.accessibility_status_card)

        val filter = IntentFilter(DroidrunPortalService.ACTION_ELEMENTS_RESPONSE)
        // Conditional receiver registration for Android Tiramisu (API 33+)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(elementDataReceiver, filter, Context.RECEIVER_EXPORTED)
        } else {
            registerReceiver(elementDataReceiver, filter)
        }
        
        fetchButton.setOnClickListener { fetchElementData() }
        retriggerButton.setOnClickListener { retriggerElements() }
        launchVoiceCommandButton?.setOnClickListener { // Use safe call
            DebugLog.add(TAG, "Launch Voice Command button clicked.")
            val voiceIntent = Intent(this, VoiceCommandActivity::class.java)
            startActivity(voiceIntent)
        }
        
        accessibilityStatusContainer.setOnClickListener { openAccessibilitySettings() }


        headerCard.setOnClickListener {
            val currentTime = System.currentTimeMillis()
            if (currentTime - lastTapTime < TAP_TIMEOUT) {
                tapCount++
            } else {
                tapCount = 1
            }
            lastTapTime = currentTime
            DebugLog.add(TAG, "Header card tapped. Count: $tapCount")
            if (tapCount == REQUIRED_TAPS) {
                tapCount = 0
                DebugLog.add(TAG, "Debug menu gesture detected. Showing fragment.")
                val debugMenu = DebugMenuFragment.newInstance(this)
                debugMenu.show(supportFragmentManager, DebugMenuFragment.TAG)
            }
        }
        
        // Initialize states from SharedPreferences and inform service
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

        isOverlayActuallyVisibleState = prefs.getBoolean(KEY_OVERLAY_VISIBLE, true) // Use local key
        DebugLog.add(TAG, "Initial overlay visibility from prefs: $isOverlayActuallyVisibleState. Notifying service.")
        toggleOverlayVisibility(isOverlayActuallyVisibleState) // This now also saves to prefs

        val shouldShowFabInitially = prefs.getBoolean(KEY_FLOATING_BUTTON_VISIBLE, false)
        DebugLog.add(TAG, "Initial FAB state from prefs: $shouldShowFabInitially. Notifying service.")
        setFloatingVoiceButtonVisibility(shouldShowFabInitially)

        val initialOffset = prefs.getInt(KEY_OVERLAY_OFFSET, DEFAULT_OFFSET)
        DebugLog.add(TAG, "Initial offset from prefs: $initialOffset. Notifying service.")
        val offsetIntent = Intent(DroidrunPortalService.ACTION_UPDATE_OVERLAY_OFFSET) // Use Service's constant
        offsetIntent.setPackage(packageName)
        offsetIntent.putExtra(DroidrunPortalService.EXTRA_OVERLAY_OFFSET, initialOffset) // Use Service's constant
        sendBroadcast(offsetIntent)

        updateAccessibilityStatusIndicator() // Call after prefs load
    }
    
    override fun onResume() {
        super.onResume()
        DebugLog.add(TAG, "onResume called")
        updateAccessibilityStatusIndicator()
        // Refresh overlay state from prefs
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        isOverlayActuallyVisibleState = prefs.getBoolean(KEY_OVERLAY_VISIBLE, true) // Use local key
        DebugLog.add(TAG, "Overlay visible state refreshed from prefs in onResume: $isOverlayActuallyVisibleState")
    }

    // This method is now primarily for internal SharedPreferences and broadcasting to service
    private fun updateOverlayOffset(offsetValue: Int) {
        val boundedOffsetValue = offsetValue.coerceIn(MIN_OFFSET, MAX_OFFSET)
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putInt(KEY_OVERLAY_OFFSET, boundedOffsetValue).apply()
        DebugLog.add(TAG, "Offset $boundedOffsetValue saved to prefs by updateOverlayOffset.")

        try {
            val intent = Intent(DroidrunPortalService.ACTION_UPDATE_OVERLAY_OFFSET)
            intent.setPackage(packageName)
            intent.putExtra(DroidrunPortalService.EXTRA_OVERLAY_OFFSET, boundedOffsetValue)
            sendBroadcast(intent)
            DebugLog.add(TAG, "Overlay offset set to: $boundedOffsetValue and broadcasted.")
        } catch (e: Exception) {
            DebugLog.add(TAG, "Error sending offset update: ${e.message}")
            Log.e(TAG, "Error sending offset update", e)
        }
    }
    
    override fun onDestroy() {
        super.onDestroy()
        DebugLog.add(TAG, "onDestroy called")
        try {
            unregisterReceiver(elementDataReceiver)
        } catch (e: IllegalArgumentException) { // More specific catch
            DebugLog.add(TAG, "Receiver not registered or already unregistered: ${e.message}")
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
    
    // This method is now the main way to control overlay visibility from MainActivity/DebugMenu
    private fun toggleOverlayVisibility(visible: Boolean) {
        isOverlayActuallyVisibleState = visible
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putBoolean(KEY_OVERLAY_VISIBLE, visible).apply() // Use local key for saving
        DebugLog.add(TAG, "Overlay visibility state persisted: $visible")

        try {
            val intent = Intent(DroidrunPortalService.ACTION_TOGGLE_OVERLAY)
            intent.setPackage(packageName)
            intent.putExtra(DroidrunPortalService.EXTRA_OVERLAY_VISIBLE, visible) // Service uses its own key for receiving
            sendBroadcast(intent)
            DebugLog.add(TAG, "Toggled overlay visibility to: $visible and broadcasted.")
        } catch (e: Exception) {
            DebugLog.add(TAG, "Error sending ACTION_TOGGLE_OVERLAY broadcast: ${e.message}")
            Log.e(TAG, "Error sending ACTION_TOGGLE_OVERLAY broadcast", e)
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

    // --- Methods for DebugMenuFragment ---
    fun isOverlayCurrentlyVisible(): Boolean {
        DebugLog.add(TAG, "DebugMenuFragment queried isOverlayCurrentlyVisible: $isOverlayActuallyVisibleState")
        return isOverlayActuallyVisibleState
    }

    fun getCurrentOffset(): Int {
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val offset = prefs.getInt(KEY_OVERLAY_OFFSET, DEFAULT_OFFSET)
        DebugLog.add(TAG, "DebugMenuFragment queried getCurrentOffset: $offset")
        return offset
    }

    fun setNewOverlayOffset(newOffset: Int) {
        // No direct UI update in MainActivity, call the method that saves and broadcasts
        updateOverlayOffset(newOffset)
    }

    fun toggleOverlayVisibilityExternally(show: Boolean) {
        DebugLog.add(TAG, "DebugMenuFragment called toggleOverlayVisibilityExternally with: $show")
        toggleOverlayVisibility(show)
    }

    fun setFloatingVoiceButtonVisibility(show: Boolean) {
        DebugLog.add(TAG, "Setting floating button visibility to $show in MainActivity (called from DebugMenu or self).")
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putBoolean(KEY_FLOATING_BUTTON_VISIBLE, show).apply()

        val intent = Intent(DroidrunPortalService.ACTION_TOGGLE_FLOATING_VOICE_BUTTON)
        intent.setPackage(packageName)
        intent.putExtra("show_button", show)
        sendBroadcast(intent)
        DebugLog.add(TAG, "Floating button visibility set to $show and broadcasted.")
    }
} 