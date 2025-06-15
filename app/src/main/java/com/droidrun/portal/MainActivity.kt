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
        DebugLog.add(TAG, "onCreate: Activity creating...")
        setContentView(R.layout.activity_main)
        
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
        registerReceiver(elementDataReceiver, filter, null, mainHandler, receiverFlags)
        DebugLog.add(TAG, "onCreate: elementDataReceiver registered for action ${DroidrunPortalService.ACTION_ELEMENTS_RESPONSE}")
        
        fetchButton.setOnClickListener {
            DebugLog.add(TAG, "Fetch Element Data button clicked")
            fetchElementData()
        }
        retriggerButton.setOnClickListener {
            DebugLog.add(TAG, "Retrigger Elements button clicked")
            retriggerElements()
        }
        launchVoiceCommandButton.setOnClickListener {
            DebugLog.add(TAG, "Launch Voice Command button clicked.")
            val voiceIntent = Intent(this, VoiceCommandActivity::class.java)
            startActivity(voiceIntent)
        }
        
        headerCard.setOnClickListener {
            // Tap counting logic...
            val currentTime = System.currentTimeMillis()
            if (currentTime - lastTapTime < TAP_TIMEOUT) {
                tapCount++
            } else {
                tapCount = 1 // Reset count
            }
            lastTapTime = currentTime
            // DebugLog.add(TAG, "Header card tapped. Current tap count: $tapCount") // Can be noisy
            if (tapCount == REQUIRED_TAPS) {
                tapCount = 0 // Reset after triggering
                DebugLog.add(TAG, "Header card: Debug menu gesture detected ($REQUIRED_TAPS taps). Showing fragment.")
                val debugMenu = DebugMenuFragment.newInstance(this)
                debugMenu.show(supportFragmentManager, DebugMenuFragment.TAG)
            }
        }
        
        accessibilityStatusContainer.setOnClickListener {
            DebugLog.add(TAG, "Accessibility Status container clicked, opening settings.")
            openAccessibilitySettings()
        }

        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

        isOverlayActuallyVisibleState = prefs.getBoolean(KEY_OVERLAY_VISIBLE, true)
        DebugLog.add(TAG, "onCreate: Initial overlay visibility from prefs: $isOverlayActuallyVisibleState.")
        // Call the method that also broadcasts, to ensure service syncs
        toggleOverlayVisibilityExternally(isOverlayActuallyVisibleState) // Log inside this method

        val shouldShowFabInitially = prefs.getBoolean(KEY_FLOATING_BUTTON_VISIBLE, false)
        DebugLog.add(TAG, "onCreate: Initial FAB state from prefs: $shouldShowFabInitially.")
        setFloatingVoiceButtonVisibility(shouldShowFabInitially) // Log inside this method

        val initialOffset = prefs.getInt(KEY_OVERLAY_OFFSET, DEFAULT_OFFSET)
        DebugLog.add(TAG, "onCreate: Initial Y-offset from prefs: $initialOffset.")
        setNewOverlayOffset(initialOffset) // Log inside this method

        val initialOffsetX = prefs.getInt(KEY_OVERLAY_OFFSET_X, DEFAULT_OFFSET_X)
        DebugLog.add(TAG, "onCreate: Initial X-offset from prefs: $initialOffsetX.")
        setNewOverlayOffsetX(initialOffsetX) // Log inside this method

        updateAccessibilityStatusIndicator() // Log inside this method
        DebugLog.add(TAG, "onCreate: Activity creation complete.")
    }

    // getMainLooper() for registerReceiver
    private val mainHandler by lazy { Handler(Looper.getMainLooper()) }

    override fun onStart() {
        super.onStart()
        DebugLog.add(TAG, "onStart called")
    }

    override fun onResume() {
        super.onResume()
        DebugLog.add(TAG, "onResume: Activity resuming.")
        updateAccessibilityStatusIndicator() // Log inside this method
        // Refresh overlay state from prefs in case it was changed while activity was paused
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val refreshedOverlayState = prefs.getBoolean(KEY_OVERLAY_VISIBLE, true)
        if (isOverlayActuallyVisibleState != refreshedOverlayState) {
            DebugLog.add(TAG, "onResume: Overlay visibility state changed in Prefs from $isOverlayActuallyVisibleState to $refreshedOverlayState. Updating local state.")
            isOverlayActuallyVisibleState = refreshedOverlayState
            // No need to call toggleOverlayVisibilityExternally here if DebugMenuFragment is the only modifier when activity is paused
            // as it directly updates prefs and broadcasts. If other mechanisms could change it, a sync call might be needed.
        } else {
            DebugLog.add(TAG, "onResume: Overlay visibility state ($isOverlayActuallyVisibleState) consistent with Prefs.")
        }
        DebugLog.add(TAG, "onResume: Activity resumed.")
    }

    override fun onPause() {
        super.onPause()
        DebugLog.add(TAG, "onPause called")
    }

    override fun onStop() {
        super.onStop()
        DebugLog.add(TAG, "onStop called")
    }
    
    override fun onDestroy() {
        super.onDestroy()
        DebugLog.add(TAG, "onDestroy: Activity destroying.")
        try {
            unregisterReceiver(elementDataReceiver)
            DebugLog.add(TAG, "onDestroy: elementDataReceiver unregistered successfully.")
        } catch (e: IllegalArgumentException) {
            DebugLog.add(TAG, "onDestroy: elementDataReceiver was not registered or already unregistered: ${e.message}")
        }
        DebugLog.add(TAG, "onDestroy: Activity destroyed.")
    }
    
    private fun fetchElementData() {
        DebugLog.add(TAG, "fetchElementData: Requesting element data from service.")
        try {
            val intent = Intent(DroidrunPortalService.ACTION_GET_ELEMENTS)
            intent.setPackage(packageName) // Important for explicit broadcast
            sendBroadcast(intent)
            statusText.text = "Requesting element data..." // UI update
        } catch (e: Exception) {
            statusText.text = "Error sending request: ${e.message}" // UI update
            DebugLog.add(TAG, "fetchElementData: Error sending ${DroidrunPortalService.ACTION_GET_ELEMENTS} broadcast: ${e.message}")
            Log.e(TAG, "fetchElementData broadcast error", e)
        }
    }

    private fun retriggerElements() {
        DebugLog.add(TAG, "retriggerElements: Requesting element retrigger from service.")
        try {
            val intent = Intent(DroidrunPortalService.ACTION_RETRIGGER_ELEMENTS)
            intent.setPackage(packageName) // Important for explicit broadcast
            sendBroadcast(intent)
            statusText.text = "Refreshing UI elements..." // UI update
        } catch (e: Exception) {
            statusText.text = "Error refreshing elements: ${e.message}" // UI update
            DebugLog.add(TAG, "retriggerElements: Error sending ${DroidrunPortalService.ACTION_RETRIGGER_ELEMENTS} broadcast: ${e.message}")
            Log.e(TAG, "retriggerElements broadcast error", e)
        }
    }

    private fun isAccessibilityServiceEnabled(): Boolean {
        val accessibilityServiceName = "$packageName/${DroidrunPortalService::class.java.canonicalName}"
        // DebugLog.add(TAG, "isAccessibilityServiceEnabled: Checking for service: $accessibilityServiceName") // Can be noisy
        try {
            val enabledServices = Settings.Secure.getString(contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
            val isEnabled = enabledServices?.contains(accessibilityServiceName) == true
            // DebugLog.add(TAG, "isAccessibilityServiceEnabled: Enabled services: $enabledServices, result for $accessibilityServiceName: $isEnabled") // Can be very noisy
            return isEnabled
        } catch (e: Exception) {
            DebugLog.add(TAG, "isAccessibilityServiceEnabled: Error checking accessibility status: ${e.message}")
            Log.e(TAG, "isAccessibilityServiceEnabled: Error", e)
            return false
        }
    }

    private fun updateAccessibilityStatusIndicator() {
        val isEnabled = isAccessibilityServiceEnabled()
        DebugLog.add(TAG, "updateAccessibilityStatusIndicator: Accessibility service enabled: $isEnabled")
        if (isEnabled) {
            accessibilityIndicator.setBackgroundResource(R.drawable.circle_indicator_green)
            accessibilityStatusText.text = "ENABLED"
        } else {
            accessibilityIndicator.setBackgroundResource(R.drawable.circle_indicator_red)
            accessibilityStatusText.text = "DISABLED"
        }
    }

    private fun openAccessibilitySettings() {
        DebugLog.add(TAG, "openAccessibilitySettings: Opening accessibility settings.")
        try {
            val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
            startActivity(intent)
            Toast.makeText(this, "Please enable Droidrun Portal in Accessibility Services", Toast.LENGTH_LONG).show()
        } catch (e: Exception) {
            DebugLog.add(TAG, "openAccessibilitySettings: Error opening settings: ${e.message}")
            Log.e(TAG, "openAccessibilitySettings: Error", e)
            Toast.makeText(this, "Error opening accessibility settings", Toast.LENGTH_SHORT).show()
        }
    }

    // --- Methods for DebugMenuFragment ---
    fun isOverlayCurrentlyVisible(): Boolean {
        DebugLog.add(TAG, "DebugMenu: isOverlayCurrentlyVisible queried, returning: $isOverlayActuallyVisibleState")
        return isOverlayActuallyVisibleState
    }

    fun getCurrentOffset(): Int { // This is for Y-offset
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val offset = prefs.getInt(KEY_OVERLAY_OFFSET, DEFAULT_OFFSET)
        DebugLog.add(TAG, "DebugMenu: getCurrentOffset (Y) from Prefs, returning: $offset")
        return offset
    }

    fun getCurrentOffsetX(): Int {
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val offsetX = prefs.getInt(KEY_OVERLAY_OFFSET_X, DEFAULT_OFFSET_X)
        DebugLog.add(TAG, "DebugMenu: getCurrentOffsetX from Prefs, returning: $offsetX")
        return offsetX
    }

    fun setNewOverlayOffset(newOffset: Int) { // This is for Y-offset
        val boundedOffset = newOffset.coerceIn(MIN_OFFSET, MAX_OFFSET)
        DebugLog.add(TAG, "DebugMenu: setNewOverlayOffset (Y) called with: $newOffset, bounded to: $boundedOffset")
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putInt(KEY_OVERLAY_OFFSET, boundedOffset).apply()

        val intent = Intent(ACTION_UPDATE_OVERLAY_OFFSET)
        intent.setPackage(packageName)
        intent.putExtra(EXTRA_OVERLAY_OFFSET, boundedOffset)
        sendBroadcast(intent)
        DebugLog.add(TAG, "DebugMenu: Overlay Y-offset updated to $boundedOffset, saved to Prefs and broadcasted.")
    }

    fun setNewOverlayOffsetX(newOffsetX: Int) {
        val boundedOffsetX = newOffsetX.coerceIn(MIN_OFFSET, MAX_OFFSET) // Using existing MIN/MAX for now
        DebugLog.add(TAG, "DebugMenu: setNewOverlayOffsetX called with: $newOffsetX, bounded to: $boundedOffsetX")
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putInt(KEY_OVERLAY_OFFSET_X, boundedOffsetX).apply()

        val intent = Intent(ACTION_UPDATE_OVERLAY_OFFSET_X)
        intent.setPackage(packageName)
        intent.putExtra(EXTRA_OVERLAY_OFFSET_X, boundedOffsetX)
        sendBroadcast(intent)
        DebugLog.add(TAG, "DebugMenu: Overlay X-offset updated to $boundedOffsetX, saved to Prefs and broadcasted.")
    }

    fun toggleOverlayVisibilityExternally(show: Boolean) {
        DebugLog.add(TAG, "DebugMenu: toggleOverlayVisibilityExternally called with: $show")
        isOverlayActuallyVisibleState = show
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putBoolean(KEY_OVERLAY_VISIBLE, show).apply()
        DebugLog.add(TAG, "DebugMenu: Overlay visibility set to $show, saved to Prefs (KEY_OVERLAY_VISIBLE).")

        val intent = Intent(DroidrunPortalService.ACTION_TOGGLE_OVERLAY)
        intent.setPackage(packageName)
        intent.putExtra(DroidrunPortalService.EXTRA_OVERLAY_VISIBLE, show)
        sendBroadcast(intent)
        DebugLog.add(TAG, "DebugMenu: Overlay visibility toggle broadcasted to service: $show")
    }

    fun setFloatingVoiceButtonVisibility(show: Boolean) {
        DebugLog.add(TAG, "setFloatingVoiceButtonVisibility: Setting FAB visibility to $show.")

        if (show) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this)) {
                DebugLog.add(TAG, "setFloatingVoiceButtonVisibility: SYSTEM_ALERT_WINDOW permission NOT granted. Requesting permission.")
                Toast.makeText(this, "DroidRun Portal needs 'Draw over other apps' permission for the floating button. Please grant it.", Toast.LENGTH_LONG).show()
                val intent = Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:$packageName")
                )
                startActivity(intent)
                // Save desired state even if permission is not granted yet.
                // Service will re-check permission.
                val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                prefs.edit().putBoolean(KEY_FLOATING_BUTTON_VISIBLE, true).apply()
                DebugLog.add(TAG, "setFloatingVoiceButtonVisibility: Saved desired FAB visible: true to Prefs. Permission activity launched.")
                // Broadcast to service; service will handle permission check internally
                val broadcastIntent = Intent(DroidrunPortalService.ACTION_TOGGLE_FLOATING_VOICE_BUTTON)
                broadcastIntent.setPackage(packageName)
                broadcastIntent.putExtra("show_button", true)
                sendBroadcast(broadcastIntent)
                DebugLog.add(TAG, "setFloatingVoiceButtonVisibility: Broadcasted show_button=true to service (permission pending).")
                return // Return after launching settings
            } else {
                 DebugLog.add(TAG, "setFloatingVoiceButtonVisibility: SYSTEM_ALERT_WINDOW permission IS granted or not required (SDK < M).")
            }
        }

        // Proceed to save preference and send broadcast if permission granted or not trying to show
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putBoolean(KEY_FLOATING_BUTTON_VISIBLE, show).apply()

        val broadcastIntent = Intent(DroidrunPortalService.ACTION_TOGGLE_FLOATING_VOICE_BUTTON)
        broadcastIntent.setPackage(packageName)
        broadcastIntent.putExtra("show_button", show)
        sendBroadcast(broadcastIntent)
        DebugLog.add(TAG, "setFloatingVoiceButtonVisibility: Floating button visibility set to $show, saved to Prefs and broadcasted to service.")
    }
}
