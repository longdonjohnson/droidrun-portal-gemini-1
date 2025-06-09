package com.droidrun.portal

import android.content.Context
import android.util.Log
import com.droidrun.portal.DebugLog
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

class GeminiCommandProcessor(private val context: Context) {
    private val TAG = "GeminiCmdProc"
    private val API_KEY = "AIzaSyDiThnIxTCQf0WV_DodhHbNpAHevqoWUZU"
    private val API_URL_BASE = "https://generativelanguage.googleapis.com/v1beta/models/"
    private val MODEL_FLASH = "gemini-2.0-flash"
    private val API_ENDPOINT_ACTION = ":generateContent"

    enum class PromptType {
        INITIAL,
        CONTINUATION_VALIDATE
    }

    interface CommandCallback {
        fun onActionsReady(actions: List<UIAction>, forCommand: String, uiContextUsed: String)
        fun onError(error: String)
    }

    data class UIAction(
        val type: String,
        val elementIndex: Int = -1,
        val text: String = "",
        val x: Int = -1,
        val y: Int = -1,
        val direction: String = ""
    ) {
        override fun toString(): String {
            val parts = mutableListOf<String>()
            parts.add("type='$type'")
            if (elementIndex != -1) parts.add("index=$elementIndex")
            if (text.isNotEmpty()) parts.add("text='$text'")
            if (x != -1 || y != -1) parts.add("pos=($x,$y)")
            if (direction.isNotEmpty()) parts.add("dir='$direction'")
            return "UIAction(${parts.joinToString()})"
        }
    }

    private fun buildPrompt(type: PromptType, originalCommand: String, elements: String, lastAction: UIAction? = null, nextPlannedAction: UIAction? = null): String {
        DebugLog.add(TAG, "buildPrompt for type: $type")
        val commonInstructions = "You are an Android UI automation assistant.\n" +
            "Current UI Elements (JSON format, 0-based index):\n$elements\n\n" +
            "Respond ONLY with a valid JSON array of actions, or a single JSON object for the 'finish' action.\n" +
            "Each action in the array should be a JSON object with the following fields:\n" +
            "- \"type\": (string) Action type, e.g., \"click\", \"type\", \"scroll\", \"swipe\", \"home\", \"back\", \"recent\", \"finish\".\n" +
            "- \"elementIndex\": (int, optional) Index of the element to interact with.\n" +
            "- \"text\": (string, optional) Text to type for \"type\" actions.\n" +
            "- \"x\": (int, optional) X-coordinate for screen interaction if elementIndex is not applicable.\n" +
            "- \"y\": (int, optional) Y-coordinate for screen interaction if elementIndex is not applicable.\n" +
            "- \"direction\": (string, optional) Direction for \"scroll\" or \"swipe\" actions (\"up\", \"down\", \"left\", \"right\").\n\n" +
            "If the overall task is complete based on the current UI and instructions, respond with only the single action: {\"type\":\"finish\"}\n" +
            "Do not add any explanatory text, apologies, or any characters outside the JSON response itself."

        return when (type) {
            PromptType.INITIAL -> {
                DebugLog.add(TAG, "Building INITIAL prompt.")
                "$commonInstructions\n\nUser Command: \"$originalCommand\"\n\nBased on the user command and current UI elements, what is the first set of actions to perform to achieve the user's command?"
            }
            PromptType.CONTINUATION_VALIDATE -> {
                DebugLog.add(TAG, "Building CONTINUATION_VALIDATE prompt.")
                DebugLog.add(TAG, "  OriginalCmd: '$originalCommand'")
                DebugLog.add(TAG, "  Using lastAction: ${lastAction?.toString() ?: "None"}, nextPlannedAction: ${nextPlannedAction?.toString() ?: "None"}")
                "$commonInstructions\n\nThe original user command was: \"$originalCommand\"\n" +
                "The last action I performed was: ${lastAction?.toString() ?: "None (this is the first action of a resumed task or Gemini previously returned no actions)"}\n" +
                "The next action I had planned (if any) from a previous step was: ${nextPlannedAction?.toString() ?: "None"}\n\n" +
                "Based on the new screen state (above) and the original command:\n" +
                "1. If the 'next planned action' is still valid and the best next step, confirm it by returning it as the first (or only) action in your JSON array response.\n" +
                "2. If the 'next planned action' is NOT valid, or if a different action is now more appropriate, provide the new correct action(s) in a JSON array.\n" +
                "3. If the original command is now complete, respond with only the single JSON object: {\"type\":\"finish\"}"
            }
        }
    }

    fun makeGeminiRequest(
        type: PromptType,
        originalCommand: String,
        currentElements: String,
        lastAction: UIAction? = null,
        nextPlannedAction: UIAction? = null,
        callback: CommandCallback
    ) {
        DebugLog.add(TAG, "makeGeminiRequest called with PromptType: $type")
        DebugLog.add(TAG, "  originalCommand: '$originalCommand'")
        DebugLog.add(TAG, "  lastAction: ${lastAction?.toString() ?: "null"}")
        DebugLog.add(TAG, "  nextPlannedAction: ${nextPlannedAction?.toString() ?: "null"}")
        DebugLog.add(TAG, "  currentElements hash: ${currentElements.hashCode()}")

        val modelApiUrl = "$API_URL_BASE$MODEL_FLASH$API_ENDPOINT_ACTION"
        DebugLog.add(TAG, "Using Gemini API URL: $modelApiUrl")

        CoroutineScope(Dispatchers.IO).launch {
            try {
                val prompt = buildPrompt(type, originalCommand, currentElements, lastAction, nextPlannedAction)
                DebugLog.add(TAG, "Full Gemini prompt being sent:\n$prompt")
                val response = callGeminiAPI(prompt, modelApiUrl)
                val actions = parseResponse(response)
                
                withContext(Dispatchers.Main) {
                    callback.onActionsReady(actions, originalCommand, currentElements)
                }
            } catch (e: Exception) {
                DebugLog.add(TAG, "Error in makeGeminiRequest: ${e.message}")
                Log.e(TAG, "Error in makeGeminiRequest, full stack trace:", e)
                withContext(Dispatchers.Main) {
                    callback.onError(e.message ?: "Unknown error in makeGeminiRequest")
                }
            }
        }
    }
    
    private suspend fun callGeminiAPI(prompt: String, apiUrl: String): String {
        DebugLog.add(TAG, "callGeminiAPI with URL: $apiUrl")
        return withContext(Dispatchers.IO) {
            val url = URL("$apiUrl?key=$API_KEY")
            val connection = url.openConnection() as HttpURLConnection
            
            connection.requestMethod = "POST"
            connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            connection.doOutput = true
            
            val requestBodyJson = JSONObject().apply {
                put("contents", JSONArray().apply {
                    put(JSONObject().apply {
                        put("parts", JSONArray().apply {
                            put(JSONObject().apply {
                                put("text", prompt)
                            })
                        })
                    })
                })
            }
            val requestBodyString = requestBodyJson.toString()
            DebugLog.add(TAG, "Gemini request body: $requestBodyString")
            
            connection.outputStream.use { os ->
                os.write(requestBodyString.toByteArray(Charsets.UTF_8))
            }
            
            val responseCode = connection.responseCode
            if (responseCode == HttpURLConnection.HTTP_OK) {
                val responseBody = connection.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
                DebugLog.add(TAG, "Gemini raw response: $responseBody")
                responseBody
            } else {
                val errorContent = connection.errorStream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: "No error content"
                DebugLog.add(TAG, "Gemini API call failed with code: $responseCode, error: $errorContent")
                throw Exception("API call failed with code: $responseCode. Error: $errorContent")
            }
        }
    }
    
    private fun parseResponse(response: String): List<UIAction> {
        var jsonTextToParse = response.trim()
        DebugLog.add(TAG, "Attempting to parse response (first 200 chars): ${jsonTextToParse.take(200)}")

        try {
            if (jsonTextToParse.startsWith("{") && jsonTextToParse.contains("candidates")) {
                val jsonResponse = JSONObject(jsonTextToParse)
                val candidates = jsonResponse.optJSONArray("candidates")
                if (candidates == null || candidates.length() == 0) {
                    DebugLog.add(TAG, "Warning: Gemini response had no candidates or candidates array was null.")
                    val error = jsonResponse.optJSONObject("error")
                    if (error != null) {
                        val errorMessage = error.optString("message", "Unknown error in Gemini response structure")
                        DebugLog.add(TAG, "Gemini response indicates an error: $errorMessage")
                        throw Exception("Gemini API error: $errorMessage")
                    }
                    return emptyList()
                }
                val content = candidates.getJSONObject(0).optJSONObject("content")
                if (content == null) {
                     DebugLog.add(TAG, "Warning: Gemini response had no content object in candidate.")
                     return emptyList()
                }
                val parts = content.optJSONArray("parts")
                if (parts == null || parts.length() == 0) {
                    DebugLog.add(TAG, "Warning: Gemini response had no parts in content.")
                    return emptyList()
                }
                jsonTextToParse = parts.getJSONObject(0).optString("text", "").trim()
                if (jsonTextToParse.isEmpty()) {
                    DebugLog.add(TAG, "Warning: Extracted text from Gemini response was empty.")
                    return emptyList()
                }
                DebugLog.add(TAG, "Extracted text from verbose Gemini response: $jsonTextToParse")
            }

            val actions = mutableListOf<UIAction>()
            if (jsonTextToParse.startsWith("[")) {
                val actionsArray = JSONArray(jsonTextToParse)
                for (i in 0 until actionsArray.length()) {
                    val actionObj = actionsArray.getJSONObject(i)
                    actions.add(parseActionObject(actionObj))
                }
            } else if (jsonTextToParse.startsWith("{")) {
                val actionObj = JSONObject(jsonTextToParse)
                actions.add(parseActionObject(actionObj))
            } else {
                DebugLog.add(TAG, "Response is not a valid JSON array or object after extraction: '$jsonTextToParse'")
                throw Exception("Final text to parse is not a valid JSON array or object.")
            }

            DebugLog.add(TAG, "Parsed UIAction list: ${actions.joinToString { it.toString() }}")
            return actions
        } catch (e: Exception) {
            DebugLog.add(TAG, "Error parsing Gemini response JSON: ${e.message}. Response was (first 200 chars): ${response.take(200)}")
            Log.e(TAG, "Error parsing full response JSON", e)
            throw e
        }
    }

    private fun parseActionObject(actionObj: JSONObject): UIAction {
        val type = actionObj.getString("type")
        if (type.isBlank()) {
            throw Exception("Action type is blank in JSON object: $actionObj")
        }
        return UIAction(
            type = type,
            elementIndex = actionObj.optInt("elementIndex", -1),
            text = actionObj.optString("text", ""),
            x = actionObj.optInt("x", -1),
            y = actionObj.optInt("y", -1),
            direction = actionObj.optString("direction", "")
        )
    }
}
