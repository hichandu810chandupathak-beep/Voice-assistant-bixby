package com.chandan.localassistant

import android.accessibilityservice.AccessibilityService
import android.os.Bundle
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

class MyAccessibilityService : AccessibilityService() {

    companion object {
        private const val TAG = "MyAccessibilityService"

        const val NAV_BACK = 1
        const val NAV_HOME = 2

        @Volatile
        private var connectedService: MyAccessibilityService? = null

        fun connectedInstance(): MyAccessibilityService? = connectedService
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        connectedService = this
        Log.i(TAG, "Bixby accessibility command bridge connected.")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event?.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            Log.d(TAG, "Current package: ${event.packageName}")
        }
    }

    override fun onInterrupt() {
        Log.w(TAG, "Accessibility service interrupted.")
    }

    override fun onDestroy() {
        if (connectedService === this) {
            connectedService = null
        }
        super.onDestroy()
    }

    /**
     * Performs Android global navigation actions through AccessibilityService.
     */
    fun performNavigation(action: Int): String {
        val globalAction = when (action) {
            NAV_BACK -> GLOBAL_ACTION_BACK
            NAV_HOME -> GLOBAL_ACTION_HOME
            else -> return "Unsupported navigation action."
        }

        return try {
            if (performGlobalAction(globalAction)) {
                if (action == NAV_BACK) "Going back." else "Going to Home."
            } else {
                "Android did not perform that navigation action."
            }
        } catch (error: RuntimeException) {
            Log.e(TAG, "Navigation action failed.", error)
            "Navigation action failed."
        }
    }

    /**
     * Searches the active window for text/content-description matches.
     * Clicks the matching node or its nearest clickable ancestor.
     */
    fun clickTextOnActiveWindow(targetText: String): String {
        if (targetText.isBlank()) return "Tell me what item to click."

        val root = rootInActiveWindow
            ?: return "No active app window is available to inspect."

        return try {
            val target = findMatchingNode(root, targetText)
                ?: return "I couldn't find '$targetText' on the current screen."

            val clickable = findClickableAncestor(target)
            val clicked = clickable?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true

            if (clicked) {
                "Clicked '$targetText'."
            } else {
                "Found '$targetText', but Android did not allow it to be clicked."
            }
        } catch (error: RuntimeException) {
            Log.e(TAG, "Unable to click node matching: $targetText", error)
            "Couldn't interact with '$targetText' on this screen."
        } finally {
            root.recycle()
        }
    }

    /**
     * Finds an editable node by its visible text, hint, content description,
     * or resource ID. Does not type into an arbitrary field if no match exists.
     */
    fun enterTextIntoField(fieldDescription: String, text: String): String {
        if (fieldDescription.isBlank() || text.isEmpty()) {
            return "Specify both the text and the field name."
        }

        val root = rootInActiveWindow
            ?: return "No active app window is available to inspect."

        return try {
            val field = findEditableField(root, fieldDescription)
                ?: return "I couldn't find an editable field matching '$fieldDescription'."

            val focused = field.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
            val arguments = Bundle().apply {
                putCharSequence(
                    AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,
                    text
                )
            }
            val entered = field.performAction(
                AccessibilityNodeInfo.ACTION_SET_TEXT,
                arguments
            )

            when {
                entered -> "Entered text into '$fieldDescription'."
                !focused -> "Found '$fieldDescription', but couldn't focus it."
                else -> "Android did not allow text entry in '$fieldDescription'."
            }
        } catch (error: RuntimeException) {
            Log.e(TAG, "Unable to enter text into: $fieldDescription", error)
            "Couldn't enter text into '$fieldDescription'."
        } finally {
            root.recycle()
        }
    }

    private fun findMatchingNode(
        node: AccessibilityNodeInfo,
        target: String
    ): AccessibilityNodeInfo? {
        if (!node.isVisibleToUser) return null

        if (nodeMatches(node, target)) return node

        for (index in 0 until node.childCount) {
            val child = node.getChild(index) ?: continue
            val match = findMatchingNode(child, target)
            if (match != null) return match
        }
        return null
    }

    private fun findEditableField(
        node: AccessibilityNodeInfo,
        target: String
    ): AccessibilityNodeInfo? {
        if (!node.isVisibleToUser) return null

        if (node.isEditable && nodeMatches(node, target)) return node

        for (index in 0 until node.childCount) {
            val child = node.getChild(index) ?: continue
            val match = findEditableField(child, target)
            if (match != null) return match
        }
        return null
    }

    private fun nodeMatches(node: AccessibilityNodeInfo, target: String): Boolean {
        val query = target.trim()
        if (query.isEmpty()) return false

        val candidates = listOfNotNull(
            node.text?.toString(),
            node.contentDescription?.toString(),
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                node.hintText?.toString()
            } else {
                null
            },
            node.viewIdResourceName
        )

        return candidates.any { candidate ->
            candidate.contains(query, ignoreCase = true)
        }
    }

    /**
     * If the matched label is inside a clickable row/button, click the nearest
     * clickable ancestor. The original node is returned when no ancestor is.
     */
    private fun findClickableAncestor(
        start: AccessibilityNodeInfo
    ): AccessibilityNodeInfo? {
        var current: AccessibilityNodeInfo? = AccessibilityNodeInfo.obtain(start)
        var fallback: AccessibilityNodeInfo? = null

        repeat(8) {
            val node = current ?: return@repeat
            if (node.isClickable) {
                fallback?.recycle()
                return node
            }

            val parent = node.parent
            if (parent == null) {
                if (fallback == null) fallback = node
                else node.recycle()
                current = null
            } else {
                if (fallback == null) fallback = node else node.recycle()
                current = parent
            }
        }

        return fallback
    }
}
