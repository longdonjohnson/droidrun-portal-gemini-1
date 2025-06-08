# DroidRun Portal + Gemini Integration

This is the modified DroidRun Portal app with Google Gemini integration for natural language processing and on-device automation.

## What's New

- **Google Gemini Integration**: Natural language command processing using your API key
- **Voice Commands**: Speech recognition for hands-free control
- **Text Interface**: Simple dialog for typing commands
- **Action Execution**: Direct UI interaction through accessibility service

## Quick Setup

1. **Import into Android Studio**
   - Open Android Studio
   - File → Open → Select this folder
   - Wait for Gradle sync

2. **Build and Install**
   - Build → Make Project
   - Run → Run 'app' (or build APK manually)

3. **Enable Accessibility Service**
   - Settings → Accessibility → DroidRun Portal
   - Turn ON the service

4. **Grant Permissions**
   - Allow microphone access when prompted
   - Allow overlay permission if needed

## Usage

### Voice Commands
- Open "Voice Command" app from launcher
- Tap microphone button and speak your command
- Or type command in text field

### Example Commands
- "Click the search button"
- "Type hello world in the text field"
- "Scroll down"
- "Go to home screen"
- "Open settings app"

### ADB Commands (Original functionality preserved)
```bash
# Send natural language command via ADB
adb shell am broadcast -a com.droidrun.portal.NATURAL_LANGUAGE_COMMAND --es command "click the first button"

# Get elements (original DroidRun functionality)
adb shell am broadcast -a com.droidrun.portal.GET_ELEMENTS
```

## Technical Details

### Key Files Added/Modified
- `GeminiCommandProcessor.kt` - Handles Gemini API integration
- `VoiceCommandActivity.kt` - Voice/text input interface
- `PortalBroadcastReceiver.kt` - Extended to handle new command types
- `DroidrunPortalService.kt` - Added action execution capabilities

### API Key
The Gemini API key is hardcoded in `GeminiCommandProcessor.kt`:
```kotlin
private val API_KEY = "AIzaSyDiThnIxTCQf0WV_DodhHbNpAHevqoWUZU"
```

### Dependencies Added
- Kotlin Coroutines for async processing
- OkHttp for API calls
- Standard Android speech recognition

## How It Works

1. **User Input** → Voice or text command captured
2. **Gemini Processing** → Command sent to Gemini API with current UI context
3. **Action Generation** → Gemini returns specific UI actions (click, type, scroll)
4. **Execution** → Accessibility service performs the actions

## Troubleshooting

- **No response**: Check accessibility service is enabled
- **Voice not working**: Ensure microphone permission granted
- **API errors**: Verify internet connection and API key
- **Actions not executing**: Check accessibility permissions

The app maintains full backward compatibility with original DroidRun Portal functionality while adding the new Gemini-powered natural language capabilities.

