package com.bixby.assistant

import android.accessibilityservice.AccessibilityService
import android.view.accessibility.AccessibilityEvent

class AssistantAccessibilityService : AccessibilityService() {
    override fun onServiceConnected() { super.onServiceConnected(); instance = this }
    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit
    override fun onInterrupt() = Unit
    fun goBack(): Boolean = performGlobalAction(GLOBAL_ACTION_BACK)
    override fun onDestroy() { if (instance === this) instance = null; super.onDestroy() }
    companion object {
        @Volatile var instance: AssistantAccessibilityService? = null
            private set
    }
}
