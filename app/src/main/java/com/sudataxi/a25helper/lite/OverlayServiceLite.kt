package com.sudataxi.a25helper.lite

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.PointF
import android.graphics.drawable.GradientDrawable
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import kotlin.math.cos
import org.json.JSONObject
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL

// v8.14.41: 하단 닫기 버튼 네비게이션 바 가림 수정 + 상단 X 추가 + 선택 0개여도 닫힘
// v8.14.43-LITE FIX: 기준점 GPS 얼림 - 기준점 찍은 순간의 GPS를 고정해서 전송 때 다시 가져온 GPS로 밀리는 문제 해결
// v8.14.45-LITE FIX: 플로팅 버튼 누르면 GPS 새로 갱신
// v8.14.44-LITE FIX: (1) lastKnown 캐시(37.29) 믿고 얼리는 버그 수정 - 15초 이내 + 100m 이내만 신선으로 인정, 나머지는 무조건 새로 요청
//                   (2) 기준점 찍을 때 GPS가 신선하지 않으면 얼리지 않고 막음 - "GPS 새로 잡는 중" 토스트 후 다시 찍게 함
//                   (3) 핀 찍을 때 바로 핀 좌표 힌트에 표시 - 본앱까지 가지 않고 그 자리에서 남쪽/서울 확인 가능
class OverlayServiceLite : Service() {
    private val TAG = "OverlayServiceLite"
    private var windowManager: WindowManager? = null
    private var floatingView: View? = null
    private var captureOverlay: View? = null
    private var firebaseUrl = ""
    private val tappedPoints = mutableListOf<PointF>()
    private val dotViews = mutableListOf<View>()
    private var refDotView: View? = null
    // --- v8.14.38-LITE FIX: 기본값 군포를 조용히 쓰지 않음 ---
    private var centerLat = 37.3912
    private var centerLng = 126.9498
    private var hasLocation = false
    private var lastLocTime = 0L
    private var lastAccuracy = Float.MAX_VALUE
    // --- v8.14.43 FIX: 기준점 찍은 순간의 GPS를 얼려둠 ---
    // 원본은 refPoint(화면 좌표)만 저장하고, centerLat/Lng는 sendAll() 때 다시 가져온 값으로 계산함.
    // 기준점 찍고 핀 찍는 사이에 GPS가 움직이면, dx/dy는 옛날 기준점 기준인데 center는 새 값이라 핀 전체가 밀림.
    // 이제 ref를 찍는 순간 center를 refCenterLat/Lng에 복사해두고, sendAll()에서는 그 얼린 값만 씀.
    // refPoint = 화면 상대위치, refCenter = 절대위치. 둘은 같은 순간에 찍혀야 의미가 있음.
    private var refCenterLat = 0.0
    private var refCenterLng = 0.0
    private var refCenterTime = 0L
    private var refCenterAccuracy = Float.MAX_VALUE
    private var screenW = 1080
    private var screenH = 1920
    private var wmParams: WindowManager.LayoutParams? = null
    private var startX = 0
    private var startY = 0
    private var touchX = 0f
    private var touchY = 0f
    private var moved = false
    private var btnSend: Button? = null
    private var hintText: TextView? = null
    private var refPoint: PointF? = null
    private var isCalibrating = true
    private var mpp = 9.5f
    private val MPP_STEPS = floatArrayOf(4f, 6f, 7.5f, 9.5f, 12f, 15f, 20f)
    private var mppIndex = 3

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        try {
            if (!Settings.canDrawOverlays(this)) {
                Toast.makeText(this, "오버레이 권한을 허용해주세요", Toast.LENGTH_LONG).show()
                stopSelf()
                return START_NOT_STICKY
            }
            firebaseUrl = intent?.getStringExtra("firebase_url")
                ?: getSharedPreferences("a25_lite", MODE_PRIVATE).getString("firebase_url", "") ?: ""
            val prefs = getSharedPreferences("a25_lite", MODE_PRIVATE)
            mpp = prefs.getFloat("mpp", 9.5f)
            mppIndex = MPP_STEPS.indexOfFirst { it == mpp }.takeIf { it >= 0 } ?: 3
            createChannel()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                startForeground(1, createNotification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(1, createNotification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
            } else {
                startForeground(1, createNotification())
            }
            getLocation()
            showFloatingButton()
        } catch (e: Exception) {
            Log.e(TAG, "onStartCommand", e)
            Toast.makeText(this, "시작 실패: ${e.message}", Toast.LENGTH_LONG).show()
            stopSelf()
        }
        return START_STICKY
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val ch = NotificationChannel("a25_lite", "A25 Lite", NotificationManager.IMPORTANCE_LOW)
            getSystemService(NotificationManager::class.java).createNotificationChannel(ch)
        }
    }

    private fun createNotification(): Notification {
        val b = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) Notification.Builder(this, "a25_lite") else Notification.Builder(this)
        return b.setContentTitle("A25 Lite").setContentText("핫스팟 저장").setSmallIcon(android.R.drawable.ic_menu_mylocation).build()
    }

    // v8.14.44 FIX: lastKnown 캐시를 믿지 않음. 카카오택시는 실시간 GPS를 쓰는데, 헬퍼만 캐시를 봐서 남쪽(37.29)에 멈춰 있던 게 이번 버그.
    private fun getLocation() {
        try {
            val lm = getSystemService(Context.LOCATION_SERVICE) as LocationManager
            val candidates = listOfNotNull(
                try { lm.getLastKnownLocation(LocationManager.GPS_PROVIDER) } catch (e: SecurityException) { null },
                try { lm.getLastKnownLocation(LocationManager.NETWORK_PROVIDER) } catch (e: SecurityException) { null }
            )
            val freshest = candidates.maxByOrNull { it.time }
            if (freshest != null) {
                val age = System.currentTimeMillis() - freshest.time
                // 15초 이내 + 정확도 100m 이내만 "신선"으로 인정. 그 외는 임시값으로만 두고 새로 잡는다.
                centerLat = freshest.latitude
                centerLng = freshest.longitude
                lastLocTime = freshest.time
                lastAccuracy = freshest.accuracy
                hasLocation = age < 15_000 && freshest.accuracy < 100f
                if (!hasLocation && age >= 15_000) Toast.makeText(this, "GPS가 ${age / 1000}초 전 위치라 새로 잡는 중…", Toast.LENGTH_SHORT).show()
            } else {
                hasLocation = false
            }
            // ★ hasLocation이 true여도 무조건 새로 요청. NETWORK 캐시(37.29)일 수 있어서 믿으면 안 됨.
            try {
                val listener = object : LocationListener {
                    override fun onLocationChanged(loc: Location) {
                        if (loc.accuracy > 100f) return
                        val locAge = System.currentTimeMillis() - loc.time
                        if (locAge > 15_000) return
                        if (loc.provider == LocationManager.NETWORK_PROVIDER && hasLocation && lastAccuracy < loc.accuracy) return
                        centerLat = loc.latitude
                        centerLng = loc.longitude
                        lastLocTime = loc.time
                        lastAccuracy = loc.accuracy
                        hasLocation = true
                        Toast.makeText(this@OverlayServiceLite, "GPS 갱신됨 (정확도 ${loc.accuracy.toInt()}m)", Toast.LENGTH_SHORT).show()
                        try { lm.removeUpdates(this) } catch (e: Exception) {}
                    }
                    override fun onStatusChanged(p: String?, s: Int, e: Bundle?) {}
                    override fun onProviderEnabled(p: String) {}
                    override fun onProviderDisabled(p: String) {}
                }
                lm.requestSingleUpdate(LocationManager.GPS_PROVIDER, listener, Looper.getMainLooper())
                lm.requestSingleUpdate(LocationManager.NETWORK_PROVIDER, listener, Looper.getMainLooper())
                // 5초 뒤에도 안 오면 한 번 더 요청 (GPS가 느릴 때)
                android.os.Handler(Looper.getMainLooper()).postDelayed({
                    if (!hasLocation || System.currentTimeMillis() - lastLocTime > 15_000) {
                        try { lm.requestSingleUpdate(LocationManager.GPS_PROVIDER, listener, Looper.getMainLooper()) } catch (e: Exception) {}
                    }
                }, 5000)
            } catch (e: SecurityException) {
                Toast.makeText(this, "위치 권한이 없어요", Toast.LENGTH_LONG).show()
            }
        } catch (e: Exception) { Log.e(TAG, "getLocation", e) }
    }

    // v8.14.43 FIX: freeze는 무조건 현재 시간으로 찍음. lastLocTime이 0이면 refCenterTime도 0이 돼서
    // sendAll에서 "기준점 먼저 찍으세요"로 계속 막히던 버그. GPS가 나중에 와도 refCenterTime은 0 그대로라서
    // 닫고 다시 열기 전까진 절대 전송이 안 됐음. 이제 System.currentTimeMillis()로 찍고,
    // GPS 원본 시간은 따로 저장해서 나이 계산에만 씀.
    private var refCenterLocTime = 0L
    private fun freezeRefCenter() {
        refCenterLat = centerLat
        refCenterLng = centerLng
        refCenterTime = System.currentTimeMillis()
        refCenterLocTime = lastLocTime
        refCenterAccuracy = lastAccuracy
        Log.d(TAG, "freezeRefCenter lat=$refCenterLat lng=$refCenterLng time=$refCenterTime locTime=$refCenterLocTime acc=$refCenterAccuracy")
    }

    private fun resetCalibration() {
        // 기준점 다시 찍기: 기존 핫스팟은 기준점이 바뀌면 의미가 없어지므로 같이 지움
        tappedPoints.clear()
        dotViews.forEach { (captureOverlay as? FrameLayout)?.removeView(it) }
        dotViews.clear()
        refDotView?.let { (captureOverlay as? FrameLayout)?.removeView(it) }
        refDotView = null
        refPoint = null
        refCenterTime = 0L
        refCenterLocTime = 0L
        isCalibrating = true
        btnSend?.text = "전송 (0)"
        btnSend?.isEnabled = false
        hintText?.text = "① 택시 아이콘을 다시 터치하세요 (노란 점)  ·  축척 ${mpp}m/px"
        Toast.makeText(this, "기준점 초기화 - 택시 아이콘 다시 터치", Toast.LENGTH_SHORT).show()
    }

    private fun showFloatingButton() {
        try {
            windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                val m = windowManager!!.currentWindowMetrics
                screenW = m.bounds.width()
                screenH = m.bounds.height()
            } else {
                @Suppress("DEPRECATION")
                val m = android.util.DisplayMetrics()
                @Suppress("DEPRECATION")
                windowManager!!.defaultDisplay.getMetrics(m)
                screenW = m.widthPixels
                screenH = m.heightPixels
            }
            wmParams = WindowManager.LayoutParams(
                160, 160,
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY else WindowManager.LayoutParams.TYPE_PHONE,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.TOP or Gravity.START
                x = 80
                y = 400
            }
            val root = FrameLayout(this)
            val btn = Button(this).apply {
                text = "📷"
                textSize = 24f
                setTextColor(Color.WHITE)
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(Color.parseColor("#EA580C"))
                    setStroke(6, Color.WHITE)
                }
                setOnTouchListener { _, e ->
                    val lp = wmParams ?: return@setOnTouchListener false
                    when (e.action) {
                        MotionEvent.ACTION_DOWN -> {
                            startX = lp.x
                            startY = lp.y
                            touchX = e.rawX
                            touchY = e.rawY
                            moved = false
                            // v8.14.45 FIX: 플로팅 버튼 누르면 GPS 새로 갱신. 오버레이 열릴 때쯤엔 신선한 GPS가 와 있음.
                            getLocation()
                        }
                        MotionEvent.ACTION_MOVE -> {
                            val dx = e.rawX - touchX
                            val dy = e.rawY - touchY
                            if (Math.abs(dx) > 12 || Math.abs(dy) > 12) moved = true
                            if (moved) {
                                lp.x = startX + dx.toInt()
                                lp.y = startY + dy.toInt()
                                windowManager?.updateViewLayout(root, lp)
                            }
                        }
                        MotionEvent.ACTION_UP -> {
                            if (!moved) showCaptureOverlay()
                        }
                    }
                    true
                }
            }
            root.addView(btn, FrameLayout.LayoutParams(160, 160))
            floatingView = root
            windowManager!!.addView(root, wmParams)
        } catch (e: Exception) {
            Log.e(TAG, "showFloatingButton", e)
            Toast.makeText(this, "버튼 실패: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun showCaptureOverlay() {
        if (captureOverlay != null) return
        tappedPoints.clear()
        dotViews.clear()
        refPoint = null
        refDotView = null
        refCenterTime = 0L
        isCalibrating = true
        getLocation()
        try {
            val p = WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY else WindowManager.LayoutParams.TYPE_PHONE,
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
                PixelFormat.TRANSLUCENT
            )
            val overlay = FrameLayout(this).apply { setBackgroundColor(Color.parseColor("#33000000")) }
            hintText = TextView(this).apply {
                text = "① 택시 아이콘을 터치하세요 (노란 점)  ·  축척 ${mpp}m/px"
                setTextColor(Color.WHITE)
                textSize = 15f
                setBackgroundColor(Color.parseColor("#CC000000"))
                setPadding(20, 12, 20, 12)
                gravity = Gravity.CENTER
            }
            btnSend = Button(this).apply {
                text = "전송 (0)"
                setBackgroundColor(Color.parseColor("#16A34A"))
                setTextColor(Color.WHITE)
                isEnabled = false
                setOnClickListener {
                    if (tappedPoints.size < 3) {
                        Toast.makeText(this@OverlayServiceLite, "최소 3곳", Toast.LENGTH_SHORT).show()
                        return@setOnClickListener
                    }
                    sendAll()
                    closeCaptureOverlay()
                }
            }
            val btnUndo = Button(this).apply {
                text = "↩ 되돌리기"
                setBackgroundColor(Color.parseColor("#F59E0B"))
                setTextColor(Color.BLACK)
                setOnClickListener { undoLast() }
            }
            // v8.14.43: 기준점 다시 찍기 버튼. 첫 터치 실수하면 닫고 다시 열 필요 없이 여기서 고침.
            val btnRecalibrate = Button(this).apply {
                text = "◎ 기준점 다시 찍기"
                setBackgroundColor(Color.parseColor("#7C3AED"))
                setTextColor(Color.WHITE)
                setOnClickListener { resetCalibration() }
            }
            val btnScaleRow = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                val btnMinus = Button(this@OverlayServiceLite).apply {
                    text = "− 축척"
                    setBackgroundColor(Color.parseColor("#1F2937"))
                    setTextColor(Color.WHITE)
                    setOnClickListener {
                        if (mppIndex > 0) { mppIndex--; mpp = MPP_STEPS[mppIndex]; saveMpp(); hintText?.text = "축척 ${mpp}m/px  ·  핫스팟 3~10곳 터치" }
                    }
                }
                val btnPlus = Button(this@OverlayServiceLite).apply {
                    text = "+ 축척"
                    setBackgroundColor(Color.parseColor("#1F2937"))
                    setTextColor(Color.WHITE)
                    setOnClickListener {
                        if (mppIndex < MPP_STEPS.lastIndex) { mppIndex++; mpp = MPP_STEPS[mppIndex]; saveMpp(); hintText?.text = "축척 ${mpp}m/px  ·  핫스팟 3~10곳 터치" }
                    }
                }
                addView(btnMinus, LinearLayout.LayoutParams(0, 80, 1f).apply { rightMargin = 8 })
                addView(btnPlus, LinearLayout.LayoutParams(0, 80, 1f))
            }
            val btnClose = Button(this).apply {
                text = "닫기"
                isEnabled = true
                setBackgroundColor(Color.parseColor("#4B5563"))
                setTextColor(Color.WHITE)
                setOnClickListener { closeCaptureOverlay() }
            }
            overlay.setOnTouchListener { _, e ->
                if (e.action == MotionEvent.ACTION_DOWN) {
                    // v8.14.43: 하단 버튼 영역 터치는 핀으로 안 셈. 전송 버튼 누른 게 핀으로 찍히면 좌표가 밑으로 밀림.
                    if (e.y > screenH - 420) return@setOnTouchListener false
                    val adjX = e.x
                    val adjY = e.y
                    if (isCalibrating) {
                        // v8.14.44 FIX: GPS가 신선하지 않으면 얼리지 않는다. 옛날 캐시(37.29) 그대로 얼리는 게 이번 버그였다.
                        val gpsAge = if (lastLocTime > 0) System.currentTimeMillis() - lastLocTime else Long.MAX_VALUE
                        if (lastLocTime == 0L || gpsAge > 30_000 || lastAccuracy > 100f) {
                            Toast.makeText(this, "GPS 새로 잡는 중… 2초 기다렸다가 다시 찍어주세요 (현재 ${String.format("%.5f", centerLat)})", Toast.LENGTH_SHORT).show()
                            getLocation()
                            return@setOnTouchListener true
                        }
                        refPoint = PointF(adjX, adjY)
                        // ★ 핵심: 이 순간 GPS를 얼림. 나중에 sendAll()에서 다시 가져온 GPS 쓰면 안 됨.
                        freezeRefCenter()
                        isCalibrating = false
                        val ageSec = if (refCenterTime > 0) (System.currentTimeMillis() - refCenterTime) / 1000 else -1
                        val accTxt = if (refCenterAccuracy < 10000f) "${refCenterAccuracy.toInt()}m" else "?"
                        hintText?.text = "기준점 고정: ${String.format("%.5f", refCenterLat)}, ${String.format("%.5f", refCenterLng)} (GPS ${ageSec}초 전, 정확도 $accTxt) · 핫스팟 3~10곳 터치"
                        btnSend?.isEnabled = true
                        val refDot = View(this).apply {
                            background = GradientDrawable().apply {
                                shape = GradientDrawable.OVAL
                                setColor(Color.YELLOW)
                            }
                            layoutParams = FrameLayout.LayoutParams(36, 36).apply {
                                leftMargin = (adjX - 18).toInt()
                                topMargin = (adjY - 18).toInt()
                            }
                        }
                        overlay.addView(refDot)
                        refDotView = refDot
                        Toast.makeText(this, "기준점 설정됨", Toast.LENGTH_SHORT).show()
                    } else {
                        tappedPoints.add(PointF(adjX, adjY))
                        // v8.14.44 FIX: 핀 찍을 때 바로 좌표 계산해서 힌트에 표시. 본앱까지 가지 않고 그 자리에서 남쪽/서울 확인 가능.
                        val cx = refPoint?.x ?: adjX
                        val cy = refPoint?.y ?: adjY
                        val dx = adjX - cx
                        val dy = cy - adjY
                        val dLat = dy * mpp / 111320.0
                        val dLng = dx * mpp / (111320.0 * cos(Math.toRadians(refCenterLat)))
                        val pinLat = refCenterLat + dLat
                        val pinLng = refCenterLng + dLng
                        hintText?.text = "핀 ${tappedPoints.size}: ${String.format("%.5f", pinLat)}, ${String.format("%.5f", pinLng)} · 기준점 ${String.format("%.5f", refCenterLat)}, ${String.format("%.5f", refCenterLng)} · 축척 ${mpp}m/px"
                        val dot = View(this).apply {
                            background = GradientDrawable().apply {
                                shape = GradientDrawable.OVAL
                                setColor(Color.RED)
                            }
                            layoutParams = FrameLayout.LayoutParams(28, 28).apply {
                                leftMargin = (adjX - 14).toInt()
                                topMargin = (adjY - 14).toInt()
                            }
                        }
                        overlay.addView(dot)
                        dotViews.add(dot)
                        btnSend?.text = "전송 (${tappedPoints.size})"
                    }
                }
                true
            }
            overlay.addView(hintText, FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT
            ).apply { gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL; topMargin = 80 })
            val navBarH = getNavigationBarHeight()
            val bottomInset = navBarH + 28
            val btnTopClose = Button(this).apply {
                text = "✕ 닫기"
                textSize = 14f
                setTextColor(Color.WHITE)
                setBackgroundColor(Color.parseColor("#DC2626"))
                setPadding(24, 16, 24, 16)
                setOnClickListener { closeCaptureOverlay() }
            }
            overlay.addView(btnTopClose, FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT
            ).apply { gravity = Gravity.TOP or Gravity.END; topMargin = 24; rightMargin = 16 })
            btnClose.text = "✕ 닫기 (선택 없어도 닫힘)"
            btnClose.setBackgroundColor(Color.parseColor("#DC2626"))
            val bottom = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setBackgroundColor(Color.parseColor("#CC000000"))
                setPadding(16, 12, 16, bottomInset)
                addView(btnSend, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 110).apply { bottomMargin = 10 })
                addView(btnScaleRow, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { bottomMargin = 10 })
                addView(btnRecalibrate, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 88).apply { bottomMargin = 10 })
                addView(btnUndo, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 88).apply { bottomMargin = 10 })
                addView(btnClose, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 96))
            }
            overlay.addView(bottom, FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT
            ).apply { gravity = Gravity.BOTTOM })
            captureOverlay = overlay
            windowManager!!.addView(overlay, p)
        } catch (e: Exception) {
            Log.e(TAG, "showCaptureOverlay", e)
        }
    }

    private fun saveMpp() {
        try { getSharedPreferences("a25_lite", MODE_PRIVATE).edit().putFloat("mpp", mpp).apply() } catch (e: Exception) {}
    }

    private fun getNavigationBarHeight(): Int {
        return try {
            val resId = resources.getIdentifier("navigation_bar_height", "dimen", "android")
            if (resId > 0) resources.getDimensionPixelSize(resId) else 96
        } catch (e: Exception) { 96 }
    }

    private fun getStatusBarHeight(): Int {
        val resId = resources.getIdentifier("status_bar_height", "dimen", "android")
        return if (resId > 0) resources.getDimensionPixelSize(resId) else 0
    }

    private fun undoLast() {
        if (tappedPoints.isEmpty()) {
            Toast.makeText(this, "지울 점 없음 (기준점은 닫기로 초기화)", Toast.LENGTH_SHORT).show()
            return
        }
        tappedPoints.removeAt(tappedPoints.lastIndex)
        if (dotViews.isNotEmpty()) {
            val last = dotViews.removeAt(dotViews.lastIndex)
            (captureOverlay as? FrameLayout)?.removeView(last)
        }
        btnSend?.text = "전송 (${tappedPoints.size})"
        Toast.makeText(this, "마지막 점 삭제", Toast.LENGTH_SHORT).show()
    }

    private fun closeCaptureOverlay() {
        try { captureOverlay?.let { windowManager?.removeView(it) } } catch (e: Exception) {}
        captureOverlay = null
        tappedPoints.clear()
        dotViews.clear()
        refDotView = null
        refPoint = null
        refCenterTime = 0L
        refCenterLocTime = 0L
        isCalibrating = true
    }

    private fun sendAll() {
        if (tappedPoints.isEmpty() || refPoint == null) return
        if (firebaseUrl.isBlank()) {
            Toast.makeText(this, "Firebase URL이 비어있어요", Toast.LENGTH_LONG).show()
            return
        }
        // v8.14.43: 게이트는 refPoint로만 판단. refCenterTime==0으로 판단하면 GPS 아직 없을 때
        // 기준점을 찍어도 0이 돼서 "기준점 먼저 찍으세요" 무한 반복이 됨.
        // refPoint가 있으면 기준점은 찍힌 거고, GPS 문제는 아래 정확도 체크로 따로 알려줌.
        if (refPoint == null) {
            Toast.makeText(this, "택시 아이콘(노란 점)을 먼저 터치하세요", Toast.LENGTH_LONG).show()
            return
        }
        // v8.14.43: 정확도 체크도 MAX_VALUE( GPS 아직 없음 )는 따로 구분.
        // MAX_VALUE면 "GPS 아직"이지 "정확도 낮음"이 아님. 둘을 섞으면 사용자가 뭘 해야 할지 모름.
        if (refCenterAccuracy == Float.MAX_VALUE) {
            Toast.makeText(this, "GPS를 아직 못 잡았어요. 잠시 기다렸다가 기준점 다시 찍기를 눌러보세요", Toast.LENGTH_LONG).show()
            return
        }
        if (refCenterAccuracy > 200f) {
            Toast.makeText(this, "기준점 GPS 정확도가 너무 낮아요 (${refCenterAccuracy.toInt()}m). 기준점 다시 찍어보세요", Toast.LENGTH_LONG).show()
            return
        }
        val refAge = System.currentTimeMillis() - refCenterTime
        val stale = refAge > 120_000
        if (stale) Toast.makeText(this, "기준점 GPS가 ${refAge / 1000}초 전 위치예요. 그래도 보내지만 거리가 틀어질 수 있어요", Toast.LENGTH_LONG).show()
        // ★ 얼린 기준점 기준으로 변환. 여기가 핵심 수정.
        val useLat = refCenterLat
        val useLng = refCenterLng
        val cx = refPoint!!.x
        val cy = refPoint!!.y
        val hotspots = tappedPoints.mapIndexed { i, pt ->
            val dx = pt.x - cx
            val dy = cy - pt.y
            val dLat = dy * mpp / 111320.0
            val dLng = dx * mpp / (111320.0 * cos(Math.toRadians(useLat)))
            mapOf("lat" to useLat + dLat, "lng" to useLng + dLng, "level" to (10 - i))
        }
        val payload = mapOf(
            "timestamp" to System.currentTimeMillis(),
            "device" to "A25_HELPER_LITE",
            // center는 이제 얼린 기준점. 아이폰이 보는 값과 일치해야 함.
            "center" to mapOf("lat" to useLat, "lng" to useLng),
            "count" to hotspots.size,
            "hotspots" to hotspots,
            "mpp" to mpp,
            "center_age_ms" to refAge,
            "center_stale" to stale,
            "center_accuracy_m" to refCenterAccuracy,
            // 디버깅용: 전송 순간의 라이브 GPS도 같이 보냄. 두 값이 다르면 GPS가 움직인 증거.
            "live_center" to mapOf("lat" to centerLat, "lng" to centerLng),
            "live_center_age_ms" to (System.currentTimeMillis() - lastLocTime)
        )
        Thread {
            try {
                val url = URL("$firebaseUrl/sudataxi/demand_hotspots.json")
                val conn = url.openConnection() as HttpURLConnection
                conn.requestMethod = "PUT"
                conn.setRequestProperty("Content-Type", "application/json")
                conn.doOutput = true
                OutputStreamWriter(conn.outputStream).use { it.write(JSONObject(payload).toString()) }
                val ok = conn.responseCode in 200..299
                android.os.Handler(mainLooper).post {
                    if (ok) {
                        Toast.makeText(this, "✅ ${hotspots.size}곳 전송 완료 (기준점 ${String.format("%.5f", useLat)}, ${String.format("%.5f", useLng)} / 축척 ${mpp}m/px)", Toast.LENGTH_LONG).show()
                    } else {
                        Toast.makeText(this, "❌ 전송 실패", Toast.LENGTH_LONG).show()
                    }
                }
            } catch (e: Exception) {
                android.os.Handler(mainLooper).post {
                    Toast.makeText(this, "❌ ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }.start()
    }

    override fun onDestroy() {
        super.onDestroy()
        try {
            floatingView?.let { windowManager?.removeView(it) }
            captureOverlay?.let { windowManager?.removeView(it) }
        } catch (e: Exception) {}
    }

    override fun onBind(intent: Intent?): IBinder? = null
}