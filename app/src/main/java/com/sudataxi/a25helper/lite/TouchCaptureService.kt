
package com.sudataxi.a25helper.lite

import android.accessibilityservice.AccessibilityService
import android.view.accessibility.AccessibilityEvent

class TouchCaptureService : AccessibilityService() {
    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}
    override fun onInterrupt() {}
}
