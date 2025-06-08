package com.droidrun.portal

import java.text.SimpleDateFormat
import java.util.Date
import java.util.LinkedList
import java.util.Locale

object DebugLog {
    private const val MAX_LOG_SIZE = 100
    private val logEntries = LinkedList<String>()
    private val dateFormat = SimpleDateFormat("HH:mm:ss", Locale.getDefault())

    @Synchronized
    fun add(tag: String, message: String) {
        val timestamp = dateFormat.format(Date())
        val logString = "$timestamp $tag: $message"

        if (logEntries.size >= MAX_LOG_SIZE) {
            logEntries.removeFirst()
        }
        logEntries.addLast(logString)
    }

    @Synchronized
    fun getLogs(): List<String> {
        return ArrayList(logEntries) // Return a copy
    }

    @Synchronized
    fun clearLogs() {
        logEntries.clear()
    }
}
