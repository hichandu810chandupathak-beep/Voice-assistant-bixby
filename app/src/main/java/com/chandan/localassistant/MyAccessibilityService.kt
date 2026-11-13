package com.chandan.localassistant

import android.accessibilityservice.AccessibilityService
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

class MyAccessibilityService : AccessibilityService() {

    companion object {
        private const val TAG = "MyAccessibilityService"
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event?.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            Log.d(TAG, "Current package: ${event.packageName}")
        }
    }

    override fun onInterrupt() {
        Log.d(TAG, "Accessibility service interrupted")
    }

    /**
     * Finds visible text in the active window and clicks its node only when
     * the matching node is explicitly clickable.
     */
    private fun performClickOnText(
        node: AccessibilityNodeInfo?,
        targetText: String
    ): Boolean {
        if (node == null || targetText.isBlank()) return false

        val nodeText = node.text?.toString()
        if (node.isClickable &&
            !nodeText.isNullOrBlank() &&
            nodeText.contains(targetText, ignoreCase = true)
        ) {
            return node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        }

        for (index in 0 until node.childCount) {
            if (performClickOnText(node.getChild(index), targetText)) {
                return true
            }
        }

        return false
    }
}
