package com.droidrun.portal

import android.view.accessibility.AccessibilityNodeInfo

object UiUtils {

    fun findFirstScrollableNode(rootNode: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
        if (rootNode == null) return null

        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(rootNode)

        while (queue.isNotEmpty()) {
            val node = queue.removeFirst()

            if (node.isScrollable) {
                return node
            }

            for (i in 0 until node.childCount) {
                node.getChild(i)?.let { childNode ->
                    queue.add(childNode)
                }
            }
        }
        return null
    }

    fun AccessibilityNodeInfo.toSimpleString(): String {
        return "Class: ${className}, Text: '${text}', ContentDesc: '${contentDescription}', ViewId: ${viewIdResourceName}, Bounds: ${getBoundsInScreen(android.graphics.Rect())}"
    }
}
