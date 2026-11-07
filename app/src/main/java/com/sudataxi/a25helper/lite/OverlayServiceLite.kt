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
    // 원본은 getLastKnownLocation이 null이면 centerLat/Lng가 군포 기본값(37.3912,126.9498) 그대로 전송됨.
    // 수원(37.26)에 있는데 군포 중심으로 보내면 핫스팟 전체가 북쪽으로 ~11km 밀려서,
    // 웹앱에서 "A25 관내 목적지 11.4km"로 뜨고, 안내 3개는 8km 필터에 걸려 0개가 됨.
    private var centerLat = 37.3912
    private var centerLng = 126.9498
    private var hasLocation = false
    private var lastLocTime = 0L
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
    private var refPoint: PointF? = null  // 택시 아이콘 기준점
    private var isCalibrating = true
    // --- v8.14.38-LITE FIX: 축척(mpp) 고정 9.5가 틀어지는 원인 ---
    // 원본 mpp=9.5는 특정 줌에서만 맞음. A25 지도를 줌인/줌아웃하면 실제 mpp가 4~20으로 바뀜.
    // 그래서 탭한 거리 그대로 km가 틀어짐. 이제 오버레이에서 - / + 로 바꿀 수 있고, prefs에 저장됨.
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
            // 저장된 축척 복원
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
            // 1) 마지막 위치라도 "얼마나 오래됐는지"를 먼저 봄. 2분 넘으면 오래된 것으로 취급.
            val candidates = listOfNotNull(
                try { lm.getLastKnownLocation(LocationManager.GPS_PROVIDER) } catch (e: SecurityException) { null },
                try { lm.getLastKnownLocation(LocationManager.NETWORK_PROVIDER) } catch (e: SecurityException) { null }
            )
            val freshest = candidates.maxByOrNull { it.time }
            if (freshest != null) {
                val age = System.currentTimeMillis() - freshest.time
                centerLat = freshest.latitude
                centerLng = freshest.longitude
                lastLocTime = freshest.time
                // 2분 이내만 "있음"으로 인정. 그 이상은 아래에서 새로 요청함.
                hasLocation = age < 120_000
                if (!hasLocation) Toast.makeText(this, "GPS 위치가 ${age / 1000}초 전 것이라 새로 잡는 중…", Toast.LENGTH_SHORT).show()
            }
            // 2) 오래됐거나 없으면 1회성으로 새로 요청. 이 콜백이 오면 center가 갱신됨.
            if (!hasLocation) {
                try {
                    val listener = object : LocationListener {
                        override fun onLocationChanged(loc: Location) {
                            centerLat = loc.latitude
                            centerLng = loc.longitude
                            lastLocTime = loc.time
                            hasLocation = true
                            Toast.makeText(this@OverlayServiceLite, "GPS 갱신됨", Toast.LENGTH_SHORT).show()
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
        isCalibrating = true
        // 캡처 열 때 GPS 한 번 더 갱신 시도
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
                text = "🚕 택시 아이콘을 터치하세요  ·  축척 ${mpp}m/px"
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
            // --- FIX: 축척 조절 버튼. A25 지도를 줌인하면 - , 줌아웃하면 + 로 맞추면 됨 ---
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
                    // --- FIX: 좌표계 통일. 원본은 rawY에서 상태바를 뺐는데 점 찍을 때는 오버레이 좌표라서
                    // 점 위치가 상태바 높이만큼 위로 어긋남. e.x/e.y는 오버레이 기준이라 기준점/핫스팟 모두 같은 계열로 계산해야 함.
                    // 둘 다 e.x/e.y로 통일하면 상태바 오차는 상쇄됨.
                    val adjX = e.x
                    val adjY = e.y
                    if (isCalibrating) {
                        refPoint = PointF(adjX, adjY)
                        isCalibrating = false
                        hintText?.text = "축척 ${mpp}m/px  ·  핫스팟 3~10곳 터치"
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
            // === v8.14.41 FIX: 하단 버튼이 안드로이드 내비게이션 바(III, O, ◁) / 제스처 바에 가려져서 터치가 안 되던 문제 ===
            // 원본은 bottom padding 16px만 줘서 닫기 버튼이 네비게이션 바 밑에 깔림.
            // 이제 네비게이션 바 높이 + 24px를 아래에 확보해서 닫기가 항상 네비게이션 바 위에 뜨게 함.
            // 닫기는 선택 0개여도 항상 눌림. 상단 우측 X도 추가해서 어디서든 닫을 수 있게 함.
            val navBarH = getNavigationBarHeight()
            val bottomInset = navBarH + 28
            // 상단 우측 닫기 (캘리브레이션 전에도 닫을 수 있게 - 콜 오면 바로 닫고 나가야 함)
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
            // 닫기 버튼을 더 크고 눈에 띄게 - 선택 없어도 닫힘을 문구로 명시
            btnClose.text = "✕ 닫기 (선택 없어도 닫힘)"
            btnClose.setBackgroundColor(Color.parseColor("#DC2626"))
            val bottom = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setBackgroundColor(Color.parseColor("#CC000000"))
                setPadding(16, 12, 16, bottomInset)
                addView(btnSend, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 110).apply { bottomMargin = 10 })
                addView(btnScaleRow, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { bottomMargin = 10 })
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

    // === v8.14.41: 하단 내비게이션 바 높이 (제스처 바 / 3버튼 바 모두 대응) ===
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
        isCalibrating = true
    }

    private fun sendAll() {
        if (tappedPoints.isEmpty() || refPoint == null) return
        if (firebaseUrl.isBlank()) {
            Toast.makeText(this, "Firebase URL이 비어있어요", Toast.LENGTH_LONG).show()
            return
        }
        // --- FIX: GPS 없으면 군포 기본값으로 보내지 말고 막음 ---
        // 원본은 이 체크가 없어서 수원인데 군포 중심 데이터가 가서 11.4km가 됐음.
        getLocation()
        if (!hasLocation) {
            Toast.makeText(this, "GPS 위치를 아직 못 잡았어요. 잠시 후 다시 전송하세요 (군포 기본값으로는 안 보냄)", Toast.LENGTH_LONG).show()
            return
        }
        val centerAge = if (lastLocTime > 0) System.currentTimeMillis() - lastLocTime else -1L
        val stale = centerAge > 120_000
        if (stale) Toast.makeText(this, "GPS가 ${centerAge / 1000}초 전 위치예요. 그래도 보내지만 거리가 틀어질 수 있어요", Toast.LENGTH_LONG).show()
        val cx = refPoint!!.x
        val cy = refPoint!!.y
        val hotspots = tappedPoints.mapIndexed { i, pt ->
            val dx = pt.x - cx
            val dy = cy - pt.y
            val dLat = dy * mpp / 111320.0
            val dLng = dx * mpp / (111320.0 * cos(Math.toRadians(centerLat)))
            mapOf("lat" to centerLat + dLat, "lng" to centerLng + dLng, "level" to (10 - i))
        }
        val payload = mapOf(
            "timestamp" to System.currentTimeMillis(),
            "device" to "A25_HELPER_LITE",
            "center" to mapOf("lat" to centerLat, "lng" to centerLng),
            "count" to hotspots.size,
            "hotspots" to hotspots,
            // 웹앱이 무시해도 되는 진단 필드. 나중에 웹앱에서 "GPS 오래됨" 경고를 띄울 때 씀.
            "mpp" to mpp,
            "center_age_ms" to centerAge,
            "center_stale" to stale
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
                        Toast.makeText(this, "✅ ${hotspots.size}곳 전송 완료 (축척 ${mpp}m/px)", Toast.LENGTH_LONG).show()
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
