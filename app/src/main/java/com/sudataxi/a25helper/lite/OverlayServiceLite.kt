
package com.sudataxi.a25helper.lite

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.PointF
import android.os.Build
import android.os.IBinder
import android.view.*
import android.widget.Button
import android.widget.FrameLayout
import android.widget.Toast
import kotlin.math.cos

class OverlayServiceLite : Service() {

    private var windowManager: WindowManager? = null
    private var floatingView: View? = null
    private var captureOverlay: View? = null
    private var firebaseUrl = ""
    
    // 저사양 최적화: 비트맵 없음, 가상디스플레이 없음, 10MB 미만
    private val tappedPoints = mutableListOf<PointF>() // 터치 좌표 직접 저장
    private var centerLat = 37.3912
    private var centerLng = 126.9498
    private var screenW = 1080
    private var screenH = 1920

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        firebaseUrl = intent?.getStringExtra("firebase_url") ?: 
            getSharedPreferences("a25_lite", MODE_PRIVATE).getString("firebase_url", "") ?: ""

        createChannel()
        startForeground(1, createNotification())

        LocationHelperLite(this).getCurrentLocation { lat, lng ->
            centerLat = lat
            centerLng = lng
        }

        showFloatingButton()

        return START_STICKY
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val ch = NotificationChannel("a25_lite", "A25 Lite", NotificationManager.IMPORTANCE_LOW)
            ch.description = "저사양용 - 10MB 미만"
            getSystemService(NotificationManager::class.java).createNotificationChannel(ch)
        }
    }

    private fun createNotification(): Notification {
        val b = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) Notification.Builder(this, "a25_lite")
                else Notification.Builder(this)
        return b.setContentTitle("A25 Lite 실행중 (저사양 모드)")
            .setContentText("🔥 버튼으로 3~10곳 저장 - 메모리 10MB")
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .build()
    }

    private fun showFloatingButton() {
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        val metrics = android.util.DisplayMetrics()
        windowManager!!.defaultDisplay.getMetrics(metrics)
        screenW = metrics.widthPixels
        screenH = metrics.heightPixels

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else WindowManager.LayoutParams.TYPE_PHONE,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        )
        params.gravity = Gravity.TOP or Gravity.START
        params.x = 30
        params.y = 300

        val container = FrameLayout(this)
        val btn = Button(this).apply {
            text = "🔥 0"
            textSize = 14f
            setBackgroundColor(Color.parseColor("#2DD4BF"))
            setTextColor(Color.BLACK)
            // 저사양: 리스너 1개만
            setOnClickListener { onFloatingClick() }
            setOnLongClickListener { 
                sendAll()
                true
            }
        }
        
        // 드래그 가능하게 (가볍게)
        var initialX = 0
        var initialY = 0
        var initialTouchX = 0f
        var initialTouchY = 0f
        var moved = false
        
        btn.setOnTouchListener { v, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    initialX = params.x
                    initialY = params.y
                    initialTouchX = event.rawX
                    initialTouchY = event.rawY
                    moved = false
                    false
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (event.rawX - initialTouchX).toInt()
                    val dy = (event.rawY - initialTouchY).toInt()
                    if (kotlin.math.abs(dx) > 10 || kotlin.math.abs(dy) > 10) moved = true
                    params.x = initialX + dx
                    params.y = initialY + dy
                    windowManager!!.updateViewLayout(container, params)
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (moved) true else false
                }
                else -> false
            }
        }

        container.addView(btn)
        floatingView = container
        windowManager!!.addView(container, params)
        updateButtonText(btn)
    }

    private fun onFloatingClick() {
        // 저사양 핵심: 스크린샷 없이 투명 오버레이로 직접 탭 받기
        // 카카오T 지도 위에 반투명 레이어 깔고, 거기서 핫스팟 터치 받음
        showCaptureOverlay()
    }

    private fun showCaptureOverlay() {
        if (captureOverlay != null) return

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else WindowManager.LayoutParams.TYPE_PHONE,
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH,
            PixelFormat.TRANSLUCENT
        )

        val overlay = FrameLayout(this).apply {
            setBackgroundColor(Color.parseColor("#33000000")) // 20% 어둡게 - 카카오 지도 보임
        }

        // 안내 텍스트
        val hint = android.widget.TextView(this).apply {
            text = "핫스팟 3~10곳을 터치하세요\n(카카오 지도 위를 직접 터치)\n완료되면 아래 전송 버튼"
            setTextColor(Color.WHITE)
            textSize = 14f
            setBackgroundColor(Color.parseColor("#AA000000"))
            setPadding(20,20,20,20)
            gravity = Gravity.CENTER
        }

        // 전송 버튼
        val btnSend = Button(this).apply {
            text = "전송 (${tappedPoints.size}곳)"
            setBackgroundColor(Color.parseColor("#2DD4BF"))
            setOnClickListener { 
                if (tappedPoints.size < 3) {
                    Toast.makeText(this@OverlayServiceLite, "최소 3곳 터치 필요", Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
                sendAll()
                closeCaptureOverlay()
            }
        }

        val btnCancel = Button(this).apply {
            text = "취소"
            setBackgroundColor(Color.DKGRAY)
            setTextColor(Color.WHITE)
            setOnClickListener { closeCaptureOverlay() }
        }

        // 터치 캡처 - 여기서 좌표 저장 (가장 가벼운 방법)
        overlay.setOnTouchListener { _, event ->
            if (event.action == MotionEvent.ACTION_DOWN) {
                // 상태바 제외한 좌표
                tappedPoints.add(PointF(event.rawX, event.rawY))
                
                // 시각적 피드백 - 빨간 점 (View 1개만, 비트맵 없음)
                val dot = View(this).apply {
                    setBackgroundColor(Color.RED)
                    layoutParams = FrameLayout.LayoutParams(24, 24).apply {
                        leftMargin = (event.rawX - 12).toInt()
                        topMargin = (event.rawY - 12).toInt()
                    }
                }
                overlay.addView(dot)
                
                // 번호 표시
                val num = android.widget.TextView(this).apply {
                    text = "${tappedPoints.size}"
                    setTextColor(Color.WHITE)
                    textSize = 10f
                    setBackgroundColor(Color.RED)
                    layoutParams = FrameLayout.LayoutParams(24, 24).apply {
                        leftMargin = (event.rawX - 12).toInt()
                        topMargin = (event.rawY - 36).toInt()
                    }
                    gravity = Gravity.CENTER
                }
                overlay.addView(num)
                
                btnSend.text = "전송 (${tappedPoints.size}곳)"
                updateFloatingButton()
                
                Toast.makeText(this, "${tappedPoints.size}곳 저장됨", Toast.LENGTH_SHORT).show()
            }
            true
        }

        overlay.addView(hint, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT).apply {
            gravity = Gravity.TOP
            topMargin = 100
        })
        
        val bottomPanel = FrameLayout(this).apply {
            addView(btnSend, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, 120).apply {
                bottomMargin = 140
            })
            addView(btnCancel, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, 100))
        }
        
        overlay.addView(bottomPanel, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT).apply {
            gravity = Gravity.BOTTOM
        })

        captureOverlay = overlay
        windowManager!!.addView(overlay, params)
    }

    private fun closeCaptureOverlay() {
        captureOverlay?.let { windowManager?.removeView(it) }
        captureOverlay = null
    }

    private fun updateButtonText(btn: Button? = null) {
        val b = (floatingView as? FrameLayout)?.getChildAt(0) as? Button
        b?.text = "🔥 ${tappedPoints.size}"
    }

    private fun updateFloatingButton() {
        updateButtonText()
    }

    private fun sendAll() {
        if (tappedPoints.isEmpty()) return

        LocationHelperLite(this).getCurrentLocation { lat, lng ->
            centerLat = lat
            centerLng = lng

            // 저사양 최적화: 고정 줌 14, 1픽셀 9.5m
            val metersPerPixel = 9.5
            val centerX = screenW / 2f
            val centerY = screenH / 2f

            val hotspots = tappedPoints.mapIndexed { idx, pt ->
                val dx = pt.x - centerX
                val dy = centerY - pt.y
                val dLat = dy * metersPerPixel / 111320.0
                val dLng = dx * metersPerPixel / (111320.0 * cos(Math.toRadians(centerLat)))
                mapOf(
                    "lat" to centerLat + dLat,
                    "lng" to centerLng + dLng,
                    "level" to (10 - idx),
                    "pixelX" to pt.x,
                    "pixelY" to pt.y
                )
            }

            val payload = mapOf(
                "timestamp" to System.currentTimeMillis(),
                "device" to "A25_HELPER_LITE",
                "center" to mapOf("lat" to centerLat, "lng" to centerLng),
                "count" to hotspots.size,
                "hotspots" to hotspots,
                "lite" to true,
                "memory" to "10MB"
            )

            FirebaseManagerLite.send(firebaseUrl, payload) { ok ->
                if (ok) {
                    Toast.makeText(this, "✅ ${hotspots.size}곳 전송 완료! (저사양 모드)", Toast.LENGTH_LONG).show()
                    tappedPoints.clear()
                    updateFloatingButton()
                } else {
                    Toast.makeText(this, "❌ 전송 실패", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun updateFloatingButtonCount() {
        val btn = (floatingView as? FrameLayout)?.getChildAt(0) as? Button
        btn?.text = "🔥 ${tappedPoints.size}"
    }

    override fun onDestroy() {
        super.onDestroy()
        floatingView?.let { windowManager?.removeView(it) }
        captureOverlay?.let { windowManager?.removeView(it) }
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
