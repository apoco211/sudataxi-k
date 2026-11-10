package com.sudataxi.a25helper.lite

import android.app.Service
import android.content.Intent
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
import android.view.WindowManager
import android.widget.TextView
import android.widget.Toast

/**
 * A25 Helper Lite - OverlayServiceLite
 * v8.14.46
 *
 * v8.14.44: getLocation()을 lastKnown만 믿지 않고 fresh 요청으로 변경, 핀 좌표 즉시 표시
 * v8.14.45: 플로팅 버튼 ACTION_DOWN에서 getLocation()으로 GPS 예열, GPS stale이면 기준점 차단
 * v8.14.46 FIX: 첫 실행 기준점 안 찍히는 문제 해결
 *   - 문제: 첫 실행엔 GPS 캐시가 오래돼서(>30s) 기준점 찍기를 차단했음.
 *     껐다 켜면 그 사이 GPS가 따뜻해져서 되니까, 사용자는 "처음엔 안 되고 재실행하면 된다"고 느낌.
 *   - 해결: stale이어도 기준점을 막지 않고 일단 찍는다.
 *     "GPS 오래됨 · 임시 저장, 2초 뒤 자동 보정" 토스트 띄우고,
 *     fresh GPS가 오면 기준점을 자동 보정한다. 핀이 이미 있으면 핀도 같이 이동.
 *   - 서울 전용 아님. 37.5 하드코딩 없음. 나이(<30s) + 정확도(<100m)만 본다.
 */

data class A25Pin(
    var lat: Double,
    var lng: Double,
    val level: Int,      // tap 순서 = level. 10 - tapOrder 아님, 그냥 탭 순서.
    val tapOrder: Int
)

class OverlayServiceLite : Service() {

    private lateinit var windowManager: WindowManager
    private var floatingView: View? = null
    private var overlayView: View? = null
    private var statusText: TextView? = null

    private lateinit var locationManager: LocationManager
    private val handler = Handler(Looper.getMainLooper())

    // ---- 기준점 (Reference) ----
    // 기본값 범계. GPS가 잡히면 바로 덮어쓴다. 하드코딩된 서울 좌표 없음.
    private var refCenterLat: Double = 37.3912
    private var refCenterLng: Double = 126.9498
    private var refPointX: Float = 0f
    private var refPointY: Float = 0f
    private var hasRef: Boolean = false
    private var refSetTime: Long = 0L
    private var refWasStale: Boolean = false
    private var refStaleLat: Double = 0.0
    private var refStaleLng: Double = 0.0

    // ---- 축척 (meters per pixel) ----
    // 카카오택시 지도 줌에 따라 -/+ 버튼으로 조절. 기본값은 일반적인 줌 기준.
    private var mpp: Double = 2.5

    // ---- 핀 ----
    private val pins = mutableListOf<A25Pin>()
    private var tapCount: Int = 0

    // ---- GPS ----
    private var lastLocation: Location? = null
    private var lastLocationTime: Long = 0L
    private var isRequestingFresh: Boolean = false

    private val gpsListener = object : LocationListener {
        override fun onLocationChanged(location: Location) {
            onFreshLocation(location)
        }
        override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
        override fun onProviderEnabled(provider: String) {}
        override fun onProviderDisabled(provider: String) {}
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        locationManager = getSystemService(LOCATION_SERVICE) as LocationManager
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager

        setupFloatingButton()
        setupOverlayView()

        // v8.14.46: 서비스 시작 즉시 GPS 예열. 첫 탭 전에 캐시를 따뜻하게 해둔다.
        // 이게 있어도 첫 탭을 막지 않는다. 예열은 보조일 뿐이다.
        try {
            getLocation(warmUpOnly = true)
        } catch (e: Exception) { }
    }

    // ================= GPS =================

    /**
     * GPS가 fresh한지: 나이 <15s + 정확도 <100m
     * stale 판단: 나이 >30s 또는 정확도 >100m 또는 없음
     */
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
            // 더 최근 것을 선택
            best = when {
                gps == null -> net
                net == null -> gps
                gps.time > net.time -> gps
                else -> net
            }
        } catch (e: SecurityException) { }
        if (best != null) {
            lastLocation = best
            lastLocationTime = best.time
        }
        return best
    }

    /**
     * GPS 요청. lastKnown만 믿지 않는다.
     * warmUpOnly = true면 토스트 없이 조용히 예열만 한다.
     */
    private fun getLocation(warmUpOnly: Boolean = false) {
        val cached = getLastKnown()
        // 이미 fresh면 바로 반환
        if (isFresh(cached)) {
            if (!warmUpOnly) {
                // 이미 fresh면 별도 토스트 없음
            }
            return
        }
        // fresh가 아니면 실제로 요청
        if (isRequestingFresh) return
        isRequestingFresh = true
        try {
            // GPS + NETWORK 둘 다 요청
            locationManager.requestLocationUpdates(
                LocationManager.GPS_PROVIDER, 0L, 0f, gpsListener, Looper.getMainLooper()
            )
        } catch (e: Exception) { }
        try {
            locationManager.requestLocationUpdates(
                LocationManager.NETWORK_PROVIDER, 0L, 0f, gpsListener, Looper.getMainLooper()
            )
        } catch (e: Exception) { }

        // 8초 뒤에도 안 오면 요청 해제
        handler.postDelayed({
            try { locationManager.removeUpdates(gpsListener) } catch (e: Exception) { }
            isRequestingFresh = false
        }, 8000L)
    }

    /**
     * Fresh GPS가 도착했을 때 호출
     * v8.14.46 핵심: stale로 찍은 기준점이 있으면 자동 보정
     */
    private fun onFreshLocation(location: Location) {
        lastLocation = location
        lastLocationTime = location.time
        try { locationManager.removeUpdates(gpsListener) } catch (e: Exception) { }
        isRequestingFresh = false

        if (!isFresh(location)) return

        // stale 기준점 자동 보정
        if (hasRef && refWasStale) {
            val ageOfRef = System.currentTimeMillis() - refSetTime
            // 기준점 찍은 지 10초 이내이고, 아직 보정 안 했으면 보정
            if (ageOfRef < 10_000) {
                val dLat = location.latitude - refCenterLat
                val dLng = location.longitude - refCenterLng
                // 너무 큰 점프(500m 이상)는 이상하니까 보정 안 함. 실제로는 10~100m 수준이어야 함.
                val dist = Math.sqrt(dLat * dLat + dLng * dLng) * 111000.0
                if (dist < 500.0 && dist > 1.0) {
                    refCenterLat = location.latitude
                    refCenterLng = location.longitude
                    // 이미 찍은 핀들도 같은 만큼 이동
                    for (pin in pins) {
                        pin.lat += dLat
                        pin.lng += dLng
                    }
                    refWasStale = false
                    showToast("기준점 보정됨: ${String.format("%.5f", refCenterLat)}, ${String.format("%.5f", refCenterLng)}")
                    updateStatusText("기준점 보정됨 · GPS OK")
                    return
                } else {
                    // 점프가 없으면 그냥 stale 해제
                    refWasStale = false
                }
            } else {
                refWasStale = false
            }
        }
    }

    // ================= UI =================

    private fun setupFloatingButton() {
        // 실제 프로젝트의 플로팅 버튼 레이아웃에 맞게 연결
        // 여기서는 핵심 로직만 보여준다. 레이아웃 id는 기존 파일과 동일하게 유지할 것.
        // floatingView = LayoutInflater.from(this).inflate(R.layout.view_floating, null)
        // ...
        // 아래 onFloatingTouch가 핵심이다.
    }

    private fun setupOverlayView() {
        // 오버레이 투명 뷰. 카카오택시 앱 위에 덮인다.
        // overlayView = ...
    }

    private fun showToast(msg: String) {
        handler.post {
            Toast.makeText(applicationContext, msg, Toast.LENGTH_SHORT).show()
        }
    }

    private fun updateStatusText(msg: String) {
        handler.post { statusText?.text = msg }
    }

    // ================= 플로팅 버튼 =================
    // v8.14.45 유지: ACTION_DOWN에서 GPS 예열 시작
    fun onFloatingTouch(event: MotionEvent): Boolean {
        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                // 버튼을 누르는 순간 GPS 예열 시작. 기준점 찍기 전에 미리 따뜻하게.
                try { getLocation(warmUpOnly = true) } catch (e: Exception) { }
                return true
            }
            MotionEvent.ACTION_UP -> {
                // 버튼 클릭 -> 오버레이 토글 등 기존 동작
                return true
            }
        }
        return false
    }

    // ================= 기준점 찍기 =================
    /**
     * v8.14.46 FIX: stale이어도 막지 않는다.
     * - stale면: 일단 찍고, "임시 저장, 2초 뒤 자동 보정" 띄우고, GPS 재요청
     * - fresh면: 그대로 찍고 "GPS OK" 띄움
     */
    fun freezeReference(screenX: Float, screenY: Float) {
        val cached = getLastKnown()
        // fresh 요청도 같이 걸어둔다. stale이면 곧 fresh가 와서 보정될 것이다.
        if (isStale(cached)) {
            getLocation(warmUpOnly = true)
        }

        val stale = isStale(cached)
        if (cached != null) {
            refCenterLat = cached.latitude
            refCenterLng = cached.longitude
        }
        // cached가 null이면 기본값(범계) 유지. 그 경우에도 찍기는 한다. GPS 오면 보정됨.
        refPointX = screenX
        refPointY = screenY
        hasRef = true
        refSetTime = System.currentTimeMillis()
        refWasStale = stale
        if (stale) {
            refStaleLat = refCenterLat
            refStaleLng = refCenterLng
        }

        if (stale) {
            // v8.14.45에서는 여기서 return으로 막았음. v8.14.46에서는 막지 않음.
            showToast("기준점 임시 저장: ${String.format("%.5f", refCenterLat)}, ${String.format("%.5f", refCenterLng)} · GPS 오래됨, 2초 뒤 자동 보정")
            updateStatusText("기준점 임시 저장 · GPS 보정 대기 중")
            // 핀은 아직 없으므로 보정은 onFreshLocation에서 자동으로 된다.
        } else {
            showToast("기준점: ${String.format("%.5f", refCenterLat)}, ${String.format("%.5f", refCenterLng)} · GPS OK")
            updateStatusText("기준점 OK · GPS OK")
        }
        // 핀 초기화: 기준점을 다시 찍으면 핀도 초기화 (기존 동작 유지)
        pins.clear()
        tapCount = 0
    }

    // ================= 핀 찍기 =================
    fun addPin(screenX: Float, screenY: Float) {
        if (!hasRef) {
            showToast("먼저 택시 아이콘(기준점)을 찍어주세요")
            return
        }
        // v8.14.44 유지: 핀 좌표 즉시 표시
        val dx = (screenX - refPointX).toDouble()
        val dy = (screenY - refPointY).toDouble()
        // 화면 y는 아래가 +이므로, 위도는 반대로 간다. 기존 공식 유지.
        val lat = refCenterLat - dy * mpp / 111000.0
        val lng = refCenterLng + dx * mpp / (111000.0 * Math.cos(Math.toRadians(refCenterLat)))

        tapCount += 1
        val level = tapCount // level = 탭 순서. 수요 강도 아님.
        val pin = A25Pin(lat, lng, level, tapCount)
        pins.add(pin)

        showToast("핀 $tapCount: ${String.format("%.5f", lat)}, ${String.format("%.5f", lng)} · 기준점 ${String.format("%.5f", refCenterLat)}, ${String.format("%.5f", refCenterLng)} · 축척 ${mpp}")
        updateStatusText("핀 $tapCount 저장됨")
    }

    // ================= 축척 -/+ =================
    fun zoomIn() { mpp /= 1.2; showToast("축척: $mpp") }
    fun zoomOut() { mpp *= 1.2; showToast("축척: $mpp") }

    // ================= 전송 =================
    fun transmitA25Pins() {
        if (pins.isEmpty()) {
            showToast("전송할 핀이 없어요")
            return
        }
        // 기존 전송 로직 유지: 메인 앱으로 lat/lng/level 전달
        // Intent / Broadcast / AIDL 등 기존 방식 그대로
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

    fun clearPins() {
        pins.clear()
        tapCount = 0
        showToast("핀 초기화됨")
    }

    override fun onDestroy() {
        super.onDestroy()
        try { locationManager.removeUpdates(gpsListener) } catch (e: Exception) { }
        floatingView?.let { try { windowManager.removeView(it) } catch (e: Exception) { } }
        overlayView?.let { try { windowManager.removeView(it) } catch (e: Exception) { } }
        super.onDestroy()
    }
}