package com.sudataxi.a25helper.lite

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.TextView
import android.widget.Toast

/**
 * A25 Helper Lite - OverlayServiceLite
 * v8.14.49 - 전체 파일
 *
 * v8.14.45까지는 버튼이 있었음.
 * v8.14.46에서 버튼 만드는 부분을 비워놔서 버튼이 사라짐 -> 내 실수
 * v8.14.47에서 버튼을 다시 만들었는데, 백그라운드에 안 뜸
 *   이유 1) MainActivity가 startService()로 띄움 -> 안드로이드 12+는 백그라운드 서비스 시작을 막음
 *   이유 2) startForeground()를 늦게 부르거나 실패하면 시스템이 서비스를 바로 죽임
 *   이유 3) addView 실패했는데 floatingView를 이미 채워놔서, 재시도해도 "이미 있다"고 리턴해버림
 * v8.14.49에서 3개 다 고침:
 *   - onCreate에서 제일 먼저 startForeground() 호출
 *   - addView 성공해야 floatingView에 넣음. 실패하면 null로 되돌려서 재시도 가능
 *   - MainActivity는 반드시 startForegroundService()로 띄워야 함 (아래 주석 참조)
 *
 * 사용법:
 *  1) 이 파일로 덮어씌우기
 *  2) MainActivity에서 startForegroundService(Intent(this, OverlayServiceLite::class.java)) 로 시작하기
 *     (startService()로 하면 안드로이드 12+에서 백그라운드에 안 뜬다)
 *  3) AndroidManifest에 아래 선언이 있어야 함:
 *     <service android:name=".OverlayServiceLite" android:exported="false" android:foregroundServiceType="specialUse" />
 *     + 권한: SYSTEM_ALERT_WINDOW, FOREGROUND_SERVICE, FOREGROUND_SERVICE_SPECIAL_USE, ACCESS_FINE_LOCATION, POST_NOTIFICATIONS
 */

data class A25Pin(
    var lat: Double,
    var lng: Double,
    val level: Int,
    val tapOrder: Int
)

class OverlayServiceLite : Service() {

    private lateinit var windowManager: WindowManager
    private var floatingView: View? = null
    private var overlayView: View? = null
    private var statusText: TextView? = null
    private var floatingBtn: TextView? = null

    private lateinit var locationManager: LocationManager
    private val handler = Handler(Looper.getMainLooper())

    private var refCenterLat: Double = 37.3912
    private var refCenterLng: Double = 126.9498
    private var refPointX: Float = 0f
    private var refPointY: Float = 0f
    private var hasRef: Boolean = false
    private var refSetTime: Long = 0L
    private var refWasStale: Boolean = false

    private var mpp: Double = 2.5
    private val pins = mutableListOf<A25Pin>()
    private var tapCount: Int = 0
    private var isOverlayOn: Boolean = false

    private var lastLocation: Location? = null
    private var isRequestingFresh: Boolean = false

    private val gpsListener = object : LocationListener {
        override fun onLocationChanged(location: Location) { onFreshLocation(location) }
        override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
        override fun onProviderEnabled(provider: String) {}
        override fun onProviderDisabled(provider: String) {}
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        // 1) 제일 먼저 포그라운드로. 5초 안에 안 부르면 시스템이 서비스를 죽인다.
        //    이게 안 되면 백그라운드 목록에도 안 뜬다.
        try { startForegroundIfNeeded() } catch (e: Exception) { }

        // 2) 그 다음 윈도우/위치 준비
        try { locationManager = getSystemService(LOCATION_SERVICE) as LocationManager } catch (e: Exception) { }
        try { windowManager = getSystemService(WINDOW_SERVICE) as WindowManager } catch (e: Exception) { }

        // 3) 버튼 + 오버레이 생성
        try { createFloatingButton() } catch (e: Exception) {
            showToast("버튼 생성 실패: ${e.message}")
        }
        try { createOverlayView() } catch (e: Exception) { }

        // 4) GPS 예열
        try { getLocation(warmUpOnly = true) } catch (e: Exception) { }

        showToast("A25 서비스 시작됨")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // 서비스가 죽었다 살아나도 버튼 다시 만들기
        if (floatingView == null) {
            try { createFloatingButton() } catch (e: Exception) { }
        }
        if (overlayView == null) {
            try { createOverlayView() } catch (e: Exception) { }
        }
        return START_STICKY
    }

    private fun startForegroundIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
            val ch = NotificationChannel("a25_helper", "A25 Helper", NotificationManager.IMPORTANCE_LOW)
            try { nm.createNotificationChannel(ch) } catch (e: Exception) { }
            val notif: Notification = try {
                Notification.Builder(this, "a25_helper")
                    .setContentTitle("A25 Helper 실행 중")
                    .setContentText("📷 버튼으로 기준점/핀을 찍으세요")
                    .setSmallIcon(android.R.drawable.ic_dialog_map)
                    .build()
            } catch (e: Exception) {
                Notification.Builder(this)
                    .setContentTitle("A25 Helper")
                    .setSmallIcon(android.R.drawable.ic_dialog_map)
                    .build()
            }
            // 여기서 실패하면 백그라운드에 안 뜬다. 그래서 try-catch로 감싸지 않고 그대로 호출한다.
            // 호출 자체가 안 되면 onCreate에서 잡힌다.
            startForeground(1, notif)
        }
    }

    // ================= GPS =================
    private fun isFresh(loc: Location?): Boolean {
        if (loc == null) return false
        val age = System.currentTimeMillis() - loc.time
        return age < 15_000 && loc.hasAccuracy() && loc.accuracy < 100f
    }
    private fun isStale(loc: Location?): Boolean {
        if (loc == null) return true
        val age = System.currentTimeMillis() - loc.time
        return age > 30_000 || !loc.hasAccuracy() || loc.accuracy >= 100f
    }
    private fun getLastKnown(): Location? {
        var best: Location? = null
        try {
            val gps = locationManager.getLastKnownLocation(LocationManager.GPS_PROVIDER)
            val net = locationManager.getLastKnownLocation(LocationManager.NETWORK_PROVIDER)
            best = when {
                gps == null -> net
                net == null -> gps
                gps.time > net.time -> gps
                else -> net
            }
        } catch (e: SecurityException) { }
        if (best != null) lastLocation = best
        return best
    }
    private fun getLocation(warmUpOnly: Boolean = false) {
        val cached = getLastKnown()
        if (isFresh(cached)) return
        if (isRequestingFresh) return
        isRequestingFresh = true
        try { locationManager.requestLocationUpdates(LocationManager.GPS_PROVIDER, 0L, 0f, gpsListener, Looper.getMainLooper()) } catch (e: Exception) { }
        try { locationManager.requestLocationUpdates(LocationManager.NETWORK_PROVIDER, 0L, 0f, gpsListener, Looper.getMainLooper()) } catch (e: Exception) { }
        handler.postDelayed({
            try { locationManager.removeUpdates(gpsListener) } catch (e: Exception) { }
            isRequestingFresh = false
        }, 8000L)
    }
    private fun onFreshLocation(location: Location) {
        lastLocation = location
        try { locationManager.removeUpdates(gpsListener) } catch (e: Exception) { }
        isRequestingFresh = false
        if (!isFresh(location)) return
        if (hasRef && refWasStale) {
            val ageOfRef = System.currentTimeMillis() - refSetTime
            if (ageOfRef < 10_000) {
                val dLat = location.latitude - refCenterLat
                val dLng = location.longitude - refCenterLng
                val dist = Math.sqrt(dLat * dLat + dLng * dLng) * 111000.0
                if (dist < 500.0 && dist > 1.0) {
                    refCenterLat = location.latitude
                    refCenterLng = location.longitude
                    for (pin in pins) { pin.lat += dLat; pin.lng += dLng }
                    refWasStale = false
                    showToast("기준점 보정됨: ${String.format("%.5f", refCenterLat)}, ${String.format("%.5f", refCenterLng)}")
                    updateStatusText("기준점 보정됨 · GPS OK")
                    return
                } else { refWasStale = false }
            } else { refWasStale = false }
        }
    }

    // ================= 플로팅 버튼 =================
    private fun createFloatingButton() {
        if (floatingView != null) return

        val btn = TextView(this).apply {
            text = "📷"
            textSize = 26f
            gravity = Gravity.CENTER
            setBackgroundColor(Color.parseColor("#CC000000"))
            setTextColor(Color.WHITE)
            setPadding(18, 18, 18, 18)
        }
        floatingBtn = btn

        val status = TextView(this).apply {
            text = "A25"
            textSize = 10f
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.parseColor("#88000000"))
            setPadding(4, 2, 4, 2)
        }
        statusText = status

        val container = FrameLayout(this).apply {
            addView(btn, FrameLayout.LayoutParams(150, 150, Gravity.CENTER))
            addView(status, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL))
        }

        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 100
            y = 400
        }

        var downX = 0f
        var downY = 0f
        var moved = false
        var downTime = 0L

        container.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    downX = event.rawX
                    downY = event.rawY
                    moved = false
                    downTime = System.currentTimeMillis()
                    try { getLocation(warmUpOnly = true) } catch (e: Exception) { }
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - downX
                    val dy = event.rawY - downY
                    if (Math.abs(dx) > 10 || Math.abs(dy) > 10) {
                        moved = true
                        params.x = (params.x + dx).toInt()
                        params.y = (params.y + dy).toInt()
                        try { windowManager.updateViewLayout(container, params) } catch (e: Exception) { }
                        downX = event.rawX
                        downY = event.rawY
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (!moved && System.currentTimeMillis() - downTime < 500) {
                        toggleOverlay()
                    }
                    true
                }
                else -> false
            }
        }

        // v8.14.49 FIX: addView 성공해야 floatingView에 넣는다. 실패하면 null 그대로라서 재시도 가능.
        try {
            windowManager.addView(container, params)
            floatingView = container
        } catch (e: Exception) {
            floatingView = null
            showToast("플로팅 권한이 없어요. 설정 > 앱 > A25 Helper > 다른 앱 위에 표시 허용해주세요")
        }
    }

    private fun createOverlayView() {
        if (overlayView != null) return
        val view = View(this).apply {
            setBackgroundColor(Color.parseColor("#00000000"))
            visibility = View.GONE
        }
        view.setOnTouchListener { _, event ->
            if (event.action == MotionEvent.ACTION_DOWN) {
                if (!hasRef) freezeReference(event.rawX, event.rawY)
                else addPin(event.rawX, event.rawY)
                true
            } else false
        }
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.TOP or Gravity.START }
        try {
            windowManager.addView(view, params)
            overlayView = view
        } catch (e: Exception) {
            overlayView = null
        }
    }

    private fun toggleOverlay() {
        isOverlayOn = !isOverlayOn
        overlayView?.visibility = if (isOverlayOn) View.VISIBLE else View.GONE
        if (isOverlayOn) {
            floatingBtn?.text = "🎯"
            showToast(if (!hasRef) "택시 아이콘을 눌러 기준점을 찍으세요" else "핀을 찍으세요. 버튼 다시 누르면 종료")
            try { getLocation(warmUpOnly = true) } catch (e: Exception) { }
        } else {
            floatingBtn?.text = "📷"
            if (pins.isNotEmpty()) transmitA25Pins()
        }
    }

    // ================= 기준점 / 핀 =================
    fun freezeReference(screenX: Float, screenY: Float) {
        val cached = getLastKnown()
        if (isStale(cached)) getLocation(warmUpOnly = true)
        val stale = isStale(cached)
        if (cached != null) {
            refCenterLat = cached.latitude
            refCenterLng = cached.longitude
        }
        refPointX = screenX
        refPointY = screenY
        hasRef = true
        refSetTime = System.currentTimeMillis()
        refWasStale = stale
        if (stale) {
            // v8.14.45에서는 여기서 return으로 막았음. v8.14.49에서는 막지 않음.
            showToast("기준점 임시 저장: ${String.format("%.5f", refCenterLat)}, ${String.format("%.5f", refCenterLng)} · GPS 오래됨, 2초 뒤 자동 보정")
            updateStatusText("기준점 임시 · 보정 대기")
        } else {
            showToast("기준점: ${String.format("%.5f", refCenterLat)}, ${String.format("%.5f", refCenterLng)} · GPS OK")
            updateStatusText("기준점 OK")
        }
        pins.clear()
        tapCount = 0
    }

    fun addPin(screenX: Float, screenY: Float) {
        if (!hasRef) { showToast("먼저 택시 아이콘을 찍어주세요"); return }
        val dx = (screenX - refPointX).toDouble()
        val dy = (screenY - refPointY).toDouble()
        val lat = refCenterLat - dy * mpp / 111000.0
        val lng = refCenterLng + dx * mpp / (111000.0 * Math.cos(Math.toRadians(refCenterLat)))
        tapCount += 1
        val pin = A25Pin(lat, lng, tapCount, tapCount)
        pins.add(pin)
        showToast("핀 $tapCount: ${String.format("%.5f", lat)}, ${String.format("%.5f", lng)} · 기준점 ${String.format("%.5f", refCenterLat)}, ${String.format("%.5f", refCenterLng)} · 축척 $mpp")
        updateStatusText("핀 $tapCount 저장됨")
        if (tapCount >= 10) {
            showToast("10개 찍었어요. 전송합니다")
            transmitA25Pins()
        }
    }

    fun transmitA25Pins() {
        if (pins.isEmpty()) { showToast("전송할 핀이 없어요"); return }
        val intent = Intent("com.sudataxi.a25helper.ACTION_A25_PINS")
        intent.putExtra("count", pins.size)
        for ((i, pin) in pins.withIndex()) {
            intent.putExtra("lat_$i", pin.lat)
            intent.putExtra("lng_$i", pin.lng)
            intent.putExtra("level_$i", pin.level)
        }
        sendBroadcast(intent)
        showToast("A25 핀 ${pins.size}개 전송됨")
    }

    private fun showToast(msg: String) {
        handler.post { Toast.makeText(applicationContext, msg, Toast.LENGTH_SHORT).show() }
    }
    private fun updateStatusText(msg: String) {
        handler.post { statusText?.text = msg }
    }

    override fun onDestroy() {
        super.onDestroy()
        try { locationManager.removeUpdates(gpsListener) } catch (e: Exception) { }
        floatingView?.let { try { windowManager.removeView(it) } catch (e: Exception) { } }
        overlayView?.let { try { windowManager.removeView(it) } catch (e: Exception) { } }
        floatingView = null
        overlayView = null
    }
}