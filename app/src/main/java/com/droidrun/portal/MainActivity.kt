package com.droidrun.portal

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri // For Uri.parse
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.view.View
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.droidrun.portal.DebugLog
// import com.droidrun.portal.R // Only if actual R.string.x values are used and not hardcoded

class MainActivity : AppCompatActivity() {

    private val TAG = "MainActivity"

    private lateinit var statusText: TextView
    private lateinit var responseText: TextView
    private lateinit var fetchButton: MaterialButton
    private lateinit var retriggerButton: MaterialButton
    private lateinit var launchVoiceCommandButton: MaterialButton // Re-enabled as lateinit
    private lateinit var headerCard: MaterialCardView

    private lateinit var accessibilityIndicator: View
    private lateinit var accessibilityStatusText: TextView
    private lateinit var accessibilityStatusContainer: View
    private lateinit var accessibilityStatusCard: MaterialCardView

    private var tapCount = 0
    private var lastTapTime: Long = 0
    private val TAP_TIMEOUT = 500L
    private val REQUIRED_TAPS = 5

    private var isOverlayActuallyVisibleState: Boolean = true

    companion object {
       internal const val PREFS_NAME = "DroidRunPrefs"
       internal const val KEY_OVERLAY_OFFSET = "overlay_offset"
       internal const val KEY_OVERLAY_VISIBLE = "overlay_visible"
       internal const val KEY_FLOATING_BUTTON_VISIBLE = "floating_button_visible"

       internal const val DEFAULT_OFFSET = -128
       internal const val MIN_OFFSET = -256
       internal const val MAX_OFFSET = 256

       const val ACTION_UPDATE_OVERLAY_OFFSET = "com.droidrun.portal.UPDATE_OVERLAY_OFFSET"
       const val EXTRA_OVERLAY_OFFSET = "overlay_offset"

       internal const val KEY_OVERLAY_OFFSET_X = "overlay_offset_x"
       internal const val DEFAULT_OFFSET_X = 0
       const val ACTION_UPDATE_OVERLAY_OFFSET_X = "com.droidrun.portal.UPDATE_OVERLAY_OFFSET_X"
       const val EXTRA_OVERLAY_OFFSET_X = "overlay_offset_x"
   }
    
    private val elementDataReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            DebugLog.add(TAG, "elementDataReceiver received: ${intent.action}")
            if (intent.action == DroidrunPortalService.ACTION_ELEMENTS_RESPONSE) {
                val data = intent.getStringExtra(DroidrunPortalService.EXTRA_ELEMENTS_DATA)
                if (data != null) {
                    DebugLog.add(TAG, "Received element data: ${data.take(100)}...")
                    statusText.text = "Data: ${data.length} chars"
                    responseText.text = data
                }

                val retriggerStatus = intent.getStringExtra("retrigger_status")
                if (retriggerStatus != null) {
                    val count = intent.getIntExtra("elements_count", 0)
                    statusText.text = "Refreshed: $count elements"
                }
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
        launchVoiceCommandButton = findViewById(R.id.launch_voice_command_button) // Re-enabled
        headerCard = findViewById(R.id.header_card)
        accessibilityIndicator = findViewById(R.id.accessibility_indicator)
        accessibilityStatusText = findViewById(R.id.accessibility_status_text)
        accessibilityStatusContainer = findViewById(R.id.accessibility_status_container)
        accessibilityStatusCard = findViewById(R.id.accessibility_status_card)

        val filter = IntentFilter(DroidrunPortalService.ACTION_ELEMENTS_RESPONSE)
        val receiverFlags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) Context.RECEIVER_EXPORTED else 0
        registerReceiver(elementDataReceiver, filter, null, mainHandler, receiverFlags) // Use mainHandler for broadcasts on main thread
        
        fetchButton.setOnClickListener { fetchElementData() }
        retriggerButton.setOnClickListener { retriggerElements() }
        launchVoiceCommandButton.setOnClickListener { // Re-enabled, no safe call
            DebugLog.add(TAG, "Launch Voice Command button clicked.")
            val voiceIntent = Intent(this, VoiceCommandActivity::class.java)
            startActivity(voiceIntent)
        }
        
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
        
        accessibilityStatusContainer.setOnClickListener { openAccessibilitySettings() }

        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

        isOverlayActuallyVisibleState = prefs.getBoolean(KEY_OVERLAY_VISIBLE, true)
        DebugLog.add(TAG, "Initial overlay visibility from prefs: $isOverlayActuallyVisibleState. Notifying service.")
        // Call the method that also broadcasts, to ensure service syncs if it missed the initial state from its own onCreate
        toggleOverlayVisibilityExternally(isOverlayActuallyVisibleState)

        val shouldShowFabInitially = prefs.getBoolean(KEY_FLOATING_BUTTON_VISIBLE, false)
        DebugLog.add(TAG, "Initial FAB state from prefs: $shouldShowFabInitially. Notifying service.")
        setFloatingVoiceButtonVisibility(shouldShowFabInitially)

        val initialOffset = prefs.getInt(KEY_OVERLAY_OFFSET, DEFAULT_OFFSET)
        DebugLog.add(TAG, "Initial offset from prefs: $initialOffset. Notifying service.")
        setNewOverlayOffset(initialOffset) // This saves to prefs (redundantly here) and broadcasts

        val initialOffsetX = prefs.getInt(KEY_OVERLAY_OFFSET_X, DEFAULT_OFFSET_X)
        DebugLog.add(TAG, "Initial X-offset from prefs: $initialOffsetX. Notifying service.")
        setNewOverlayOffsetX(initialOffsetX)

        updateAccessibilityStatusIndicator()
    }

    // getMainLooper() for registerReceiver
    private val mainHandler by lazy { Handler(Looper.getMainLooper()) }

    override fun onResume() {
        super.onResume()
        DebugLog.add(TAG, "onResume called")
        updateAccessibilityStatusIndicator()
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        isOverlayActuallyVisibleState = prefs.getBoolean(KEY_OVERLAY_VISIBLE, true)
        DebugLog.add(TAG, "Overlay visible state refreshed from prefs in onResume: $isOverlayActuallyVisibleState")
    }
    
    override fun onDestroy() {
        super.onDestroy()
        DebugLog.add(TAG, "onDestroy called")
        try {
            unregisterReceiver(elementDataReceiver)
        } catch (e: IllegalArgumentException) {
            DebugLog.add(TAG, "Receiver not registered or already unregistered: ${e.message}")
        }
    }
    
    private fun fetchElementData() {
        DebugLog.add(TAG, "fetchElementData called")
        try {
            val intent = Intent(DroidrunPortalService.ACTION_GET_ELEMENTS)
            intent.setPackage(packageName)
            sendBroadcast(intent)
            statusText.text = "Requesting element data..."
        } catch (e: Exception) {
            statusText.text = "Error sending request: ${e.message}"
            DebugLog.add(TAG, "Error sending ${DroidrunPortalService.ACTION_GET_ELEMENTS}: ${e.message}")
        }
    }

    private fun retriggerElements() {
        DebugLog.add(TAG, "retriggerElements called")
        try {
            val intent = Intent(DroidrunPortalService.ACTION_RETRIGGER_ELEMENTS)
            intent.setPackage(packageName)
            sendBroadcast(intent)
            statusText.text = "Refreshing UI elements..."
        } catch (e: Exception) {
            statusText.text = "Error refreshing elements: ${e.message}"
            DebugLog.add(TAG, "Error sending ${DroidrunPortalService.ACTION_RETRIGGER_ELEMENTS}: ${e.message}")
        }
    }

    private fun isAccessibilityServiceEnabled(): Boolean {
        val accessibilityServiceName = "$packageName/${DroidrunPortalService::class.java.canonicalName}"
        try {
            val enabledServices = Settings.Secure.getString(contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
            return enabledServices?.contains(accessibilityServiceName) == true
        } catch (e: Exception) {
            DebugLog.add(TAG, "Error checking accessibility status: ${e.message}")
            Log.e(TAG, "Error checking accessibility status", e)
            return false
        }
    }

    private fun updateAccessibilityStatusIndicator() {
        val isEnabled = isAccessibilityServiceEnabled()
        DebugLog.add(TAG, "updateAccessibilityStatusIndicator: service enabled = $isEnabled")
        if (isEnabled) {
            accessibilityIndicator.setBackgroundResource(R.drawable.circle_indicator_green)
            accessibilityStatusText.text = "ENABLED"
        } else {
            accessibilityIndicator.setBackgroundResource(R.drawable.circle_indicator_red)
            accessibilityStatusText.text = "DISABLED"
        }
    }

    private fun openAccessibilitySettings() {
        DebugLog.add(TAG, "openAccessibilitySettings called")
        try {
            val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
            startActivity(intent)
            Toast.makeText(this, "Please enable Droidrun Portal in Accessibility Services", Toast.LENGTH_LONG).show()
        } catch (e: Exception) {
            DebugLog.add(TAG, "Error opening accessibility settings: ${e.message}")
            Log.e(TAG, "Error opening accessibility settings", e)
            Toast.makeText(this, "Error opening accessibility settings", Toast.LENGTH_SHORT).show()
        }
    }

    // --- Methods for DebugMenuFragment ---
    fun isOverlayCurrentlyVisible(): Boolean {
        // Read from the state variable which is kept in sync with SharedPreferences
        DebugLog.add(TAG, "DebugMenuFragment queried isOverlayCurrentlyVisible: $isOverlayActuallyVisibleState")
        return isOverlayActuallyVisibleState
    }

    fun getCurrentOffset(): Int { // This is for Y-offset
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val offset = prefs.getInt(KEY_OVERLAY_OFFSET, DEFAULT_OFFSET)
        DebugLog.add(TAG, "DebugMenuFragment queried getCurrentOffset (Y) from Prefs: $offset")
        return offset
    }

    fun getCurrentOffsetX(): Int {
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val offsetX = prefs.getInt(KEY_OVERLAY_OFFSET_X, DEFAULT_OFFSET_X)
        DebugLog.add(TAG, "getCurrentOffsetX returning from Prefs: $offsetX")
        return offsetX
    }

    fun setNewOverlayOffset(newOffset: Int) { // This is for Y-offset
        val boundedOffset = newOffset.coerceIn(MIN_OFFSET, MAX_OFFSET)
        DebugLog.add(TAG, "setNewOverlayOffset (from DebugMenu) called with: $newOffset, bounded to: $boundedOffset")
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putInt(KEY_OVERLAY_OFFSET, boundedOffset).apply()

        val intent = Intent(ACTION_UPDATE_OVERLAY_OFFSET)
        intent.setPackage(packageName)
        intent.putExtra(EXTRA_OVERLAY_OFFSET, boundedOffset)
        sendBroadcast(intent)
        DebugLog.add(TAG, "Overlay Y-offset updated to $boundedOffset by DebugMenu and broadcasted to service.")
    }

    fun setNewOverlayOffsetX(newOffsetX: Int) {
        // Assuming MIN_OFFSET and MAX_OFFSET can apply to X offset too, or define new X-specific min/max
        val boundedOffsetX = newOffsetX.coerceIn(MIN_OFFSET, MAX_OFFSET) // Using existing MIN/MAX for now
        DebugLog.add(TAG, "setNewOverlayOffsetX (from DebugMenu) called with: $newOffsetX, bounded to: $boundedOffsetX")
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putInt(KEY_OVERLAY_OFFSET_X, boundedOffsetX).apply()

        val intent = Intent(ACTION_UPDATE_OVERLAY_OFFSET_X)
        intent.setPackage(packageName)
        intent.putExtra(EXTRA_OVERLAY_OFFSET_X, boundedOffsetX)
        sendBroadcast(intent)
        DebugLog.add(TAG, "Overlay X-offset updated to $boundedOffsetX by DebugMenu and broadcasted to service.")
    }

    fun toggleOverlayVisibilityExternally(show: Boolean) {
        DebugLog.add(TAG, "DebugMenuFragment called toggleOverlayVisibilityExternally with: $show")
        isOverlayActuallyVisibleState = show
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putBoolean(KEY_OVERLAY_VISIBLE, show).apply()
        DebugLog.add(TAG, "Overlay visibility set to $show, saved to prefs (KEY_OVERLAY_VISIBLE).")

        val intent = Intent(DroidrunPortalService.ACTION_TOGGLE_OVERLAY)
        intent.setPackage(packageName)
        intent.putExtra(DroidrunPortalService.EXTRA_OVERLAY_VISIBLE, show)
        sendBroadcast(intent)
        DebugLog.add(TAG, "Overlay visibility toggle broadcasted to service: $show")
    }

    fun setFloatingVoiceButtonVisibility(show: Boolean) {
        DebugLog.add(TAG, "setFloatingVoiceButtonVisibility called with: $show")

        if (show) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this)) {
                DebugLog.add(TAG, "SYSTEM_ALERT_WINDOW permission not granted. Requesting.")
                Toast.makeText(this, "DroidRun Portal needs 'Draw over other apps' permission for the floating button. Please grant it.", Toast.LENGTH_LONG).show()
                val intent = Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:$packageName")
                )
                // We're not using startActivityForResult here as managing the result
                // to automatically toggle the switch can be complex and might require onActivityResult handling.
                // The user will grant permission and can then re-toggle the switch if needed,
                // or the service will pick up the state on its next check/restart.
                // For simplicity, we'll just save the desired state and send the broadcast.
                // The service's showFloatingVoiceButton() also has a Settings.canDrawOverlays() check.
                startActivity(intent)

                // Even if permission is not granted yet, save the desired state.
                // The service will only show the button if permission is active.
                val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                prefs.edit().putBoolean(KEY_FLOATING_BUTTON_VISIBLE, true).apply() // Save desired 'true' state

                // Send broadcast to service anyway. Service will do its own canDrawOverlays check.
                val broadcastIntent = Intent(DroidrunPortalService.ACTION_TOGGLE_FLOATING_VOICE_BUTTON)
                broadcastIntent.setPackage(packageName)
                broadcastIntent.putExtra("show_button", true)
                sendBroadcast(broadcastIntent)
                DebugLog.add(TAG, "Saved FAB visible: true to Prefs and broadcasted. Permission activity launched.")
                return // Return after launching settings
            }
            // If permission is already granted or not needed (older Android version)
        }

        // Proceed to save preference and send broadcast
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putBoolean(KEY_FLOATING_BUTTON_VISIBLE, show).apply()

        val broadcastIntent = Intent(DroidrunPortalService.ACTION_TOGGLE_FLOATING_VOICE_BUTTON)
        broadcastIntent.setPackage(packageName)
        broadcastIntent.putExtra("show_button", show)
        sendBroadcast(broadcastIntent)
        DebugLog.add(TAG, "Floating button visibility set to $show, saved to Prefs and broadcasted to service.")
    }
}
