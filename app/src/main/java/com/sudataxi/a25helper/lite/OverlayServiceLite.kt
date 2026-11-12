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
// v8.14.91-LITE FIX: 기준점 GPS 얼림 - 기준점 찍은 순간의 GPS를 고정해서 전송 때 다시 가져온 GPS로 밀리는 문제 해결
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


    // v8.14.91 FIX: Firebase URL 정규화 + 매번 재독
    // 원인1: onStartCommand에서 한 번만 읽어서, 서비스 켠 뒤에 URL을 저장하면 계속 빈 URL로 전송 시도함.
    // 원인2: 끝에 / 나 공백이 있으면 ...com//sudataxi/... 가 됨. 아이폰은 trim+strip을 하는데 헬퍼는 안 했음.
    private fun normalizeFirebaseUrl(raw: String?): String {
        var u = (raw ?: "").trim()
        if (u.isEmpty()) return ""
        // 사용자가 전체 경로를 복사해온 경우도 처리
        if (u.endsWith("/sudataxi/demand_hotspots.json")) u = u.removeSuffix("/sudataxi/demand_hotspots.json")
        else if (u.endsWith("/sudataxi/demand_hotspots")) u = u.removeSuffix("/sudataxi/demand_hotspots")
        else if (u.endsWith("/sudataxi")) u = u.removeSuffix("/sudataxi")
        if (u.endsWith(".json")) u = u.removeSuffix(".json")
        while (u.endsWith("/")) u = u.dropLast(1)
        u = u.trim()
        return u
    }
    private fun refreshFirebaseUrl(): String {
        // 매 전송마다 SharedPreferences에서 다시 읽음. 서비스 재시작 없이 URL 저장해도 다음 전송에 바로 반영됨.
        val saved = try { getSharedPreferences("a25_lite", MODE_PRIVATE).getString("firebase_url", "") ?: "" } catch (e: Exception) { "" }
        // Intent extra가 있으면 그게 우선이지만, prefs에 더 새 값이 있으면 prefs를 씀. 둘 다 있으면 prefs(사용자가 마지막에 저장한 값)를 우선.
        val raw = if (saved.isNotBlank()) saved else firebaseUrl
        firebaseUrl = normalizeFirebaseUrl(raw)
        return firebaseUrl
    }
    // v8.14.91 FIX: 기준점 찍은 뒤 GPS가 좋아졌으면 얼린 값을 최신 좋은 값으로 교체
    // 원인: 기준점을 너무 빨리 찍으면 refCenterAccuracy=MAX_VALUE로 얼려지고, GPS가 나중에 좋아져도 sendAll이 계속 막힘.
    private fun tryRefreshRefCenterFromLastKnown(): Boolean {
        try {
            val lm = getSystemService(Context.LOCATION_SERVICE) as LocationManager
            val candidates = listOfNotNull(
                try { lm.getLastKnownLocation(LocationManager.GPS_PROVIDER) } catch (e: SecurityException) { null },
                try { lm.getLastKnownLocation(LocationManager.NETWORK_PROVIDER) } catch (e: SecurityException) { null }
            )
            val best = candidates.filter { it.accuracy < 200f }.minByOrNull { it.accuracy } ?: return false
            val age = System.currentTimeMillis() - best.time
            if (age > 120_000) return false
            // 얼린 값보다 확실히 좋을 때만 교체 (같은 위치를 왔다갔다 하지 않게)
            if (refCenterAccuracy == Float.MAX_VALUE || best.accuracy + 20f < refCenterAccuracy) {
                refCenterLat = best.latitude
                refCenterLng = best.longitude
                refCenterAccuracy = best.accuracy
                refCenterTime = System.currentTimeMillis()
                refCenterLocTime = best.time
                // 현재 위치도 함께 갱신해 둠
                centerLat = best.latitude
                centerLng = best.longitude
                lastLocTime = best.time
                lastAccuracy = best.accuracy
                hasLocation = true
                return true
            }
        } catch (e: Exception) { Log.e(TAG, "tryRefreshRefCenter", e) }
        return false
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        try {
            if (!Settings.canDrawOverlays(this)) {
                Toast.makeText(this, "오버레이 권한을 허용해주세요", Toast.LENGTH_LONG).show()
                stopSelf()
                return START_NOT_STICKY
            }
            // v8.14.91: Intent보다 prefs(마지막 저장값)를 우선 + 정규화. 시작 뒤 URL 저장해도 재시작 없이 다음 전송에 반영되게 sendAll에서 다시 읽음.
            val _rawUrl = getSharedPreferences("a25_lite", MODE_PRIVATE).getString("firebase_url", "")?.takeIf { it.isNotBlank() }
                ?: intent?.getStringExtra("firebase_url") ?: ""
            firebaseUrl = normalizeFirebaseUrl(_rawUrl)
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

    private fun getLocation() {
        try {
            val lm = getSystemService(Context.LOCATION_SERVICE) as LocationManager
            val candidates = listOfNotNull(
                try { lm.getLastKnownLocation(LocationManager.GPS_PROVIDER) } catch (e: SecurityException) { null },
                try { lm.getLastKnownLocation(LocationManager.NETWORK_PROVIDER) } catch (e: SecurityException) { null }
            )
            val freshest = candidates.maxByOrNull { it.time }
            if (freshest == null) {
                // v8.14.43: 후보가 없으면 hasLocation을 false로 명확히 내림. 예전 값 유지하면 안 됨.
                hasLocation = false
            } else {
                val age = System.currentTimeMillis() - freshest.time
                centerLat = freshest.latitude
                centerLng = freshest.longitude
                lastLocTime = freshest.time
                lastAccuracy = freshest.accuracy
                // v8.14.43: 정확도 체크 추가. NETWORK 500m~1km짜리가 최신이라고 들어오면 그걸 기준으로 쓰면 안 됨.
                // 200m보다 나쁘면 "없음"으로 취급하고 새로 요청함.
                hasLocation = age < 120_000 && freshest.accuracy < 200f
                if (!hasLocation) {
                    if (age >= 120_000) Toast.makeText(this, "GPS 위치가 ${age / 1000}초 전 것이라 새로 잡는 중…", Toast.LENGTH_SHORT).show()
                    else if (freshest.accuracy >= 200f) Toast.makeText(this, "GPS 정확도 낮음 (${freshest.accuracy.toInt()}m), 다시 잡는 중…", Toast.LENGTH_SHORT).show()
                }
            }
            if (!hasLocation) {
                try {
                    val listener = object : LocationListener {
                        override fun onLocationChanged(loc: Location) {
                            // 정확도 나쁜 NETWORK 위치가 좋은 GPS를 덮지 않게 함
                            if (loc.accuracy > 200f) return
                            // 이미 더 좋은 위치가 있으면 굳이 덮지 않음
                            if (hasLocation && loc.accuracy > lastAccuracy + 20f) return
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
                } catch (e: SecurityException) {
                    Toast.makeText(this, "위치 권한이 없어요", Toast.LENGTH_LONG).show()
                }
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
                    // v8.14.91: sendAll이 false면 전송 안 된 거라 닫지 않음. 닫으면 핀이 다 날아가서 "보냈는데 안 옴"이 됨.
                    if (sendAll()) closeCaptureOverlay()
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

    // v8.14.91: Boolean 반환. false면 전송 안 된 거라 오버레이를 닫으면 안 됨 (핀 다 날아감).
    // 기존 버그: sendAll이 URL/GPS 이유로 early-return해도 버튼이 무조건 closeCaptureOverlay()를 불러서 핀이 지워짐.
    // 사용자는 "보냈다"고 생각하는데 실제론 전송 안 된 상태가 됨. 이번 캡처(재시작해도 옛날 TEST_INJECT)가 바로 그 케이스.
    private fun sendAll(): Boolean {
        // URL은 매번 다시 읽고 정규화
        refreshFirebaseUrl()
        if (tappedPoints.isEmpty() || refPoint == null) {
            Toast.makeText(this, "기준점(노란 점) + 핀 3곳 이상을 먼저 찍어주세요", Toast.LENGTH_LONG).show()
            return false
        }
        if (firebaseUrl.isBlank()) {
            Toast.makeText(this, "Firebase URL이 비어있어요. A25 앱에 아이폰과 똑같은 URL을 저장하고 다시 전송해주세요", Toast.LENGTH_LONG).show()
            return false
        }
        if (!firebaseUrl.startsWith("https://")) {
            Toast.makeText(this, "Firebase URL이 https:// 로 시작해야 해요: $firebaseUrl", Toast.LENGTH_LONG).show()
            return false
        }
        // 기준점 찍은 뒤 GPS가 좋아졌으면 얼린 값 교체 시도. 그래도 안 좋으면 그때 막음.
        if (refCenterAccuracy == Float.MAX_VALUE || refCenterAccuracy > 200f) {
            val refreshed = tryRefreshRefCenterFromLastKnown()
            if (refreshed) {
                Toast.makeText(this, "GPS 갱신됨 (${refCenterAccuracy.toInt()}m) - 갱신된 기준으로 전송합니다", Toast.LENGTH_SHORT).show()
            }
        }
        if (refCenterAccuracy == Float.MAX_VALUE) {
            Toast.makeText(this, "GPS를 아직 못 잡았어요. 잠시 기다렸다가 기준점 다시 찍기를 눌러보세요", Toast.LENGTH_LONG).show()
            return false
        }
        if (refCenterAccuracy > 200f) {
            Toast.makeText(this, "기준점 GPS 정확도가 너무 낮아요 (${refCenterAccuracy.toInt()}m). 기준점 다시 찍어보세요", Toast.LENGTH_LONG).show()
            return false
        }
        val refAge = System.currentTimeMillis() - refCenterTime
        val stale = refAge > 120_000
        if (stale) Toast.makeText(this, "기준점 GPS가 ${refAge / 1000}초 전 위치예요. 그래도 보내지만 거리가 틀어질 수 있어요", Toast.LENGTH_LONG).show()
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
            "center" to mapOf("lat" to useLat, "lng" to useLng),
            "count" to hotspots.size,
            "hotspots" to hotspots,
            "mpp" to mpp,
            "center_age_ms" to refAge,
            "center_stale" to stale,
            "center_accuracy_m" to refCenterAccuracy,
            "live_center" to mapOf("lat" to centerLat, "lng" to centerLng),
            "live_center_age_ms" to (System.currentTimeMillis() - lastLocTime)
        )
        val host = try { java.net.URL(firebaseUrl).host } catch (e: Exception) { firebaseUrl }
        Toast.makeText(this, "전송 중... ($host / ${hotspots.size}곳)", Toast.LENGTH_SHORT).show()
        Log.d(TAG, "sendAll host=$host count=${hotspots.size} center=$useLat,$useLng acc=$refCenterAccuracy url_len=${firebaseUrl.length}")
        Thread {
            try {
                val url = java.net.URL("$firebaseUrl/sudataxi/demand_hotspots.json")
                val conn = url.openConnection() as HttpURLConnection
                conn.requestMethod = "PUT"
                conn.setRequestProperty("Content-Type", "application/json")
                conn.doOutput = true
                conn.connectTimeout = 8000
                conn.readTimeout = 8000
                OutputStreamWriter(conn.outputStream).use { it.write(JSONObject(payload).toString()) }
                val code = conn.responseCode
                val ok = code in 200..299
                android.os.Handler(mainLooper).post {
                    if (ok) {
                        Toast.makeText(this, "✅ ${hotspots.size}곳 전송 완료 (기준점 ${String.format("%.5f", useLat)}, ${String.format("%.5f", useLng)} / 축척 ${mpp}m/px)", Toast.LENGTH_LONG).show()
                    } else {
                        Toast.makeText(this, "❌ 전송 실패 (HTTP $code) - URL/규칙 확인", Toast.LENGTH_LONG).show()
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "sendAll PUT", e)
                android.os.Handler(mainLooper).post {
                    Toast.makeText(this, "❌ ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }.start()
        return true
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