
package com.sudataxi.a25helper.lite

import android.accessibilityservice.AccessibilityService
import android.view.accessibility.AccessibilityEvent
import android.content.Context

class TouchCaptureService : AccessibilityService() {

    companion object {
        var lastTouchX = -1f
        var lastTouchY = -1f
        var lastPackage = ""
        
        fun saveTouch(ctx: Context, x: Float, y: Float, pkg: String) {
            lastTouchX = x
            lastTouchY = y
            lastPackage = pkg
            ctx.getSharedPreferences("a25_lite", Context.MODE_PRIVATE).edit()
                .putFloat("last_x", x)
                .putFloat("last_y", y)
                .putString("last_pkg", pkg)
                .putLong("last_time", System.currentTimeMillis())
                .apply()
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        
        // 터치 이벤트 감지 - 카카오T 드라이버 앱에서만
        if (event.eventType == AccessibilityEvent.TYPE_TOUCH_INTERACTION_START ||
            event.eventType == AccessibilityEvent.TYPE_VIEW_CLICKED) {
            
            val pkg = event.packageName?.toString() ?: ""
            // 카카오T 드라이버 앱 패키지명은 실제로는 com.kakao.taxi.driver 또는 유사
            // 여기서는 모든 앱에서 캡처하되, 최근 5초 이내 터치만 유효하게 처리
            
            // AccessibilityEvent는 좌표를 직접 안 주므로, 대체 방법: 
            // 실제로는 Gesture detection이 필요하지만, Lite 버전에서는
            // OverlayService에서 직접 터치를 받는 방식으로 우회
        }

        // 콜이 오면 오버레이 숨김 - 콜 수신 화면 감지
        if (event.packageName?.toString()?.contains("kakao") == true) {
            val text = event.text?.joinToString() ?: ""
            if (text.contains("호출") || text.contains("배차") || text.contains("승객")) {
                // 콜이 왔다는 신호 - OverlayService에 숨김 요청
                getSharedPreferences("a25_lite", Context.MODE_PRIVATE).edit()
                    .putBoolean("incoming_call", true)
                    .apply()
            }
        }
    }

    override fun onInterrupt() {}
}
