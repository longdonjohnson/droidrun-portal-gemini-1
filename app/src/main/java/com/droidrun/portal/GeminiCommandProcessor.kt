package com.droidrun.portal

import android.content.Context
import android.util.Log
import com.droidrun.portal.DebugLog
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL
import java.net.UnknownHostException
import kotlinx.coroutines.delay

// Custom Exception classes
class GeminiApiException(message: String, val originalException: Throwable? = null, val responseCode: Int? = null) : Exception(message)
class GeminiResponseParseException(message: String, val problematicResponse: String? = null, val originalException: Throwable? = null) : Exception(message)

class GeminiCommandProcessor(private val context: Context) {
    private val TAG = "GeminiCmdProc"
    private val MAX_RETRIES = 3
    private val INITIAL_BACKOFF_MS = 1000L
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
            } catch (e: GeminiApiException) {
                val errorMsg = "Gemini API call failed after retries: ${e.message}"
                DebugLog.add(TAG, "$errorMsg. OriginalCommand: '$originalCommand', PromptType: $type, UIContextHash: ${currentElements.hashCode()}")
                Log.e(TAG, errorMsg, e.originalException ?: e)
                withContext(Dispatchers.Main) {
                    callback.onError("Failed to get a response from the assistant after multiple attempts. Please check your connection or try again later. (Details: ${e.message})")
                }
            } catch (e: GeminiResponseParseException) {
                val errorMsg = "Failed to parse Gemini response: ${e.message}"
                DebugLog.add(TAG, "$errorMsg. OriginalCommand: '$originalCommand', PromptType: $type, UIContextHash: ${currentElements.hashCode()}, ProblematicResponse: '${e.problematicResponse?.take(100)}...'")
                Log.e(TAG, errorMsg, e.originalException ?: e)
                withContext(Dispatchers.Main) {
                    callback.onError("The assistant's response was not understood. Please try again. (Details: ${e.message})")
                }
            } catch (e: Exception) { // Catch-all for other unexpected errors
                val errorMsg = "Unexpected error in makeGeminiRequest: ${e.message}"
                DebugLog.add(TAG, "$errorMsg. OriginalCommand: '$originalCommand', PromptType: $type, UIContextHash: ${currentElements.hashCode()}")
                Log.e(TAG, errorMsg, e)
                withContext(Dispatchers.Main) {
                    callback.onError("An unexpected error occurred: ${e.message}")
                }
            }
        }
    }
    
    private suspend fun callGeminiAPI(prompt: String, apiUrl: String): String {
        DebugLog.add(TAG, "callGeminiAPI with URL: $apiUrl")
        var currentDelay = INITIAL_BACKOFF_MS
        for (attempt in 1..MAX_RETRIES) {
            try {
                return withContext(Dispatchers.IO) {
                    val url = URL("$apiUrl?key=$API_KEY")
                    val connection = url.openConnection() as HttpURLConnection
                    connection.connectTimeout = 10000 // 10 seconds
                    connection.readTimeout = 10000    // 10 seconds

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
                    DebugLog.add(TAG, "Gemini request body (attempt $attempt): $requestBodyString")

                    connection.outputStream.use { os ->
                        os.write(requestBodyString.toByteArray(Charsets.UTF_8))
                    }

                    val responseCode = connection.responseCode
                    if (responseCode == HttpURLConnection.HTTP_OK) {
                        val responseBody = connection.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
                        DebugLog.add(TAG, "Gemini raw response (attempt $attempt): $responseBody")
                        return@withContext responseBody
                    } else {
                        val errorContent = connection.errorStream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: "No error content"
                        DebugLog.add(TAG, "Gemini API call failed on attempt $attempt with code: $responseCode, error: $errorContent")
                        if (attempt == MAX_RETRIES) {
                            throw GeminiApiException("API call failed after $MAX_RETRIES attempts with code: $responseCode. Last error: $errorContent", responseCode = responseCode)
                        }
                        // Continue to retry logic below
                    }
                }
            } catch (e: UnknownHostException) {
                DebugLog.add(TAG, "Gemini API call attempt $attempt failed: UnknownHostException - ${e.message}")
                if (attempt == MAX_RETRIES) {
                    throw GeminiApiException("API call failed after $MAX_RETRIES attempts due to UnknownHostException: ${e.message}", originalException = e)
                }
            } catch (e: SocketTimeoutException) {
                DebugLog.add(TAG, "Gemini API call attempt $attempt failed: SocketTimeoutException - ${e.message}")
                if (attempt == MAX_RETRIES) {
                    throw GeminiApiException("API call failed after $MAX_RETRIES attempts due to SocketTimeoutException: ${e.message}", originalException = e)
                }
            } catch (e: GeminiApiException) { // Catch already wrapped GeminiApiException to rethrow if it's the last attempt
                 if (attempt == MAX_RETRIES) throw e
                 // else it was a non-network http error, retry logic will apply
            } catch (e: Exception) { // Catch any other unexpected exceptions during the API call
                DebugLog.add(TAG, "Gemini API call attempt $attempt failed with unexpected exception: ${e.message}")
                Log.e(TAG, "Unexpected exception in callGeminiAPI attempt $attempt", e)
                if (attempt == MAX_RETRIES) {
                    throw GeminiApiException("API call failed after $MAX_RETRIES attempts due to an unexpected error: ${e.message}", originalException = e)
                }
            }

            DebugLog.add(TAG, "Retrying Gemini API call. Waiting for $currentDelay ms.")
            delay(currentDelay)
            currentDelay *= 2 // Exponential backoff
        }
        // Should not be reached if MAX_RETRIES > 0, but as a fallback:
        throw GeminiApiException("API call failed after $MAX_RETRIES attempts. Unknown error.")
    }
    
    private fun parseResponse(response: String): List<UIAction> {
        var jsonTextToParse = response.trim()
        DebugLog.add(TAG, "Attempting to parse response (first 200 chars): ${jsonTextToParse.take(200)}")

        try {
            if (jsonTextToParse.startsWith("{") && jsonTextToParse.contains("candidates")) {
                val jsonResponse = JSONObject(jsonTextToParse)
                val candidates = jsonResponse.optJSONArray("candidates")
                if (candidates == null || candidates.length() == 0) {
                    val error = jsonResponse.optJSONObject("error")
                    if (error != null) {
                        val errorMessage = error.optString("message", "Unknown error in Gemini response structure")
                        DebugLog.add(TAG, "Gemini response indicates an error: $errorMessage")
                        throw GeminiResponseParseException("Gemini API error: $errorMessage", problematicResponse = response)
                    }
                    DebugLog.add(TAG, "Gemini response had no candidates or candidates array was null.")
                    throw GeminiResponseParseException("Gemini response had no candidates.", problematicResponse = response)
                }
                val firstCandidate = candidates.optJSONObject(0)
                if (firstCandidate == null) {
                     DebugLog.add(TAG, "First candidate in Gemini response was null.")
                    throw GeminiResponseParseException("First candidate in Gemini response was null.", problematicResponse = response)
                }
                val content = firstCandidate.optJSONObject("content")
                if (content == null) {
                     DebugLog.add(TAG, "Gemini response had no content object in candidate.")
                     throw GeminiResponseParseException("Gemini response had no content object in candidate.", problematicResponse = response)
                }
                val parts = content.optJSONArray("parts")
                if (parts == null || parts.length() == 0) {
                    DebugLog.add(TAG, "Gemini response had no parts in content.")
                    throw GeminiResponseParseException("Gemini response had no parts in content.", problematicResponse = response)
                }
                val firstPart = parts.optJSONObject(0)
                if (firstPart == null) {
                    DebugLog.add(TAG, "First part in Gemini response content was null.")
                    throw GeminiResponseParseException("First part in Gemini response content was null.", problematicResponse = response)
                }
                jsonTextToParse = firstPart.optString("text", "").trim()
                if (jsonTextToParse.isEmpty()) {
                    DebugLog.add(TAG, "Extracted text from Gemini response was empty.")
                    throw GeminiResponseParseException("Extracted text from Gemini response was empty.", problematicResponse = response)
                }
                DebugLog.add(TAG, "Extracted text from verbose Gemini response: $jsonTextToParse")
            }

            val actions = mutableListOf<UIAction>()
            if (jsonTextToParse.startsWith("[")) {
                val actionsArray = JSONArray(jsonTextToParse)
                for (i in 0 until actionsArray.length()) {
                    val actionObj = actionsArray.getJSONObject(i)
                    actions.add(parseActionObject(actionObj, jsonTextToParse))
                }
            } else if (jsonTextToParse.startsWith("{")) {
                val actionObj = JSONObject(jsonTextToParse)
                actions.add(parseActionObject(actionObj, jsonTextToParse))
            } else {
                DebugLog.add(TAG, "Response is not a valid JSON array or object after extraction: '$jsonTextToParse'")
                throw GeminiResponseParseException("Final text to parse is not a valid JSON array or object.", problematicResponse = jsonTextToParse)
            }

            DebugLog.add(TAG, "Parsed UIAction list: ${actions.joinToString { it.toString() }}")
            return actions
        } catch (e: GeminiResponseParseException) { // Re-throw custom parse exceptions
            throw e
        } catch (e: Exception) { // Wrap other JSON parsing exceptions
            DebugLog.add(TAG, "Error parsing Gemini response JSON: ${e.message}. Response was (first 200 chars): ${response.take(200)}")
            Log.e(TAG, "Error parsing full response JSON", e)
            throw GeminiResponseParseException("Failed to parse JSON response: ${e.message}", problematicResponse = response, originalException = e)
        }
    }

    private fun parseActionObject(actionObj: JSONObject, originalJsonText: String): UIAction {
        val type = actionObj.optString("type")
        if (type.isBlank()) {
            throw GeminiResponseParseException("Action type is blank in JSON object", problematicResponse = originalJsonText)
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
