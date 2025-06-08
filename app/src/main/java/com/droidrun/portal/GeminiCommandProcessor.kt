package com.droidrun.portal

import android.content.Context
import android.util.Log
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

class GeminiCommandProcessor(private val context: Context) {
    private val TAG = "GeminiCommandProcessor"
    private val API_KEY = "AIzaSyDiThnIxTCQf0WV_DodhHbNpAHevqoWUZU"
    private val API_URL = "https://generativelanguage.googleapis.com/v1beta/models/gemini-pro:generateContent"
    
    interface CommandCallback {
        fun onCommandProcessed(actions: List<UIAction>)
        fun onError(error: String)
    }
    
    data class UIAction(
        val type: String, // "click", "type", "scroll", "swipe"
        val elementIndex: Int = -1,
        val text: String = "",
        val x: Int = -1,
        val y: Int = -1,
        val direction: String = ""
    )
    
    fun processCommand(command: String, currentElements: String, callback: CommandCallback) {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val prompt = buildPrompt(command, currentElements)
                val response = callGeminiAPI(prompt)
                val actions = parseResponse(response)
                
                withContext(Dispatchers.Main) {
                    callback.onCommandProcessed(actions)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error processing command", e)
                withContext(Dispatchers.Main) {
                    callback.onError(e.message ?: "Unknown error")
                }
            }
        }
    }
    
    private fun buildPrompt(command: String, elements: String): String {
        return """
You are an Android UI automation assistant. Given a user command and current UI elements, generate specific actions to execute.

User Command: "$command"

Current UI Elements: $elements

Respond with a JSON array of actions. Each action should have:
- type: "click", "type", "scroll", "swipe", "home", "back"
- elementIndex: index of element to interact with (if applicable)
- text: text to type (if type action)
- x, y: coordinates (if no element index)
- direction: "up", "down", "left", "right" (for scroll/swipe)

Examples:
- To click button at index 5: [{"type":"click","elementIndex":5}]
- To type text: [{"type":"type","elementIndex":3,"text":"hello"}]
- To scroll down: [{"type":"scroll","direction":"down"}]
- To go home: [{"type":"home"}]

Respond only with the JSON array, no other text.
        """.trimIndent()
    }
    
    private suspend fun callGeminiAPI(prompt: String): String {
        return withContext(Dispatchers.IO) {
            val url = URL("$API_URL?key=$API_KEY")
            val connection = url.openConnection() as HttpURLConnection
            
            connection.requestMethod = "POST"
            connection.setRequestProperty("Content-Type", "application/json")
            connection.doOutput = true
            
            val requestBody = JSONObject().apply {
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
            
            connection.outputStream.use { os ->
                os.write(requestBody.toString().toByteArray())
            }
            
            val responseCode = connection.responseCode
            if (responseCode == HttpURLConnection.HTTP_OK) {
                connection.inputStream.bufferedReader().use { it.readText() }
            } else {
                throw Exception("API call failed with code: $responseCode")
            }
        }
    }
    
    private fun parseResponse(response: String): List<UIAction> {
        try {
            val jsonResponse = JSONObject(response)
            val candidates = jsonResponse.getJSONArray("candidates")
            val content = candidates.getJSONObject(0).getJSONObject("content")
            val parts = content.getJSONArray("parts")
            val text = parts.getJSONObject(0).getString("text")
            
            // Extract JSON array from the text
            val jsonStart = text.indexOf('[')
            val jsonEnd = text.lastIndexOf(']') + 1
            val jsonText = text.substring(jsonStart, jsonEnd)
            
            val actionsArray = JSONArray(jsonText)
            val actions = mutableListOf<UIAction>()
            
            for (i in 0 until actionsArray.length()) {
                val actionObj = actionsArray.getJSONObject(i)
                val action = UIAction(
                    type = actionObj.getString("type"),
                    elementIndex = actionObj.optInt("elementIndex", -1),
                    text = actionObj.optString("text", ""),
                    x = actionObj.optInt("x", -1),
                    y = actionObj.optInt("y", -1),
                    direction = actionObj.optString("direction", "")
                )
                actions.add(action)
            }
            
            return actions
        } catch (e: Exception) {
            Log.e(TAG, "Error parsing response", e)
            return emptyList()
        }
    }
}

