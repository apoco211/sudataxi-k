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
import android.location.LocationManager
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import android.util.Log
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.FrameLayout
import android.widget.TextView
import android.widget.Toast
import kotlin.math.cos
import org.json.JSONObject
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL

class OverlayServiceLite : Service() {
    private val TAG = "OverlayServiceLite"
    private var windowManager: WindowManager? = null
    private var floatingView: View? = null
    private var captureOverlay: View? = null
    private var firebaseUrl = ""
    private val tappedPoints = mutableListOf<PointF>()
    private val dotViews = mutableListOf<View>()
    private var centerLat = 37.3912
    private var centerLng = 126.9498
    private var screenW = 1080
    private var screenH = 1920
    private var wmParams: WindowManager.LayoutParams? = null
    private var startX = 0
    private var startY = 0
    private var touchX = 0f
    private var touchY = 0f
    private var moved = false
    private var btnSend: Button? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        try {
            if (!Settings.canDrawOverlays(this)) {
                Toast.makeText(this, "오버레이 권한을 허용해주세요", Toast.LENGTH_LONG).show()
                stopSelf()
                return START_NOT_STICKY
            }

            firebaseUrl = intent?.getStringExtra("firebase_url")
                ?: getSharedPreferences("a25_lite", MODE_PRIVATE).getString("firebase_url", "") ?: ""

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
            val loc = lm.getLastKnownLocation(LocationManager.GPS_PROVIDER) ?: lm.getLastKnownLocation(LocationManager.NETWORK_PROVIDER)
            if (loc != null) {
                centerLat = loc.latitude
                centerLng = loc.longitude
            }
        } catch (e: Exception) {}
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
        try {
            val p = WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY else WindowManager.LayoutParams.TYPE_PHONE,
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
                PixelFormat.TRANSLUCENT
            )
            val overlay = FrameLayout(this).apply { setBackgroundColor(Color.parseColor("#44000000")) }
            val hint = TextView(this).apply {
                text = "3~10곳 터치 (잘못찍으면 되돌리기)"
                setTextColor(Color.WHITE)
                textSize = 15f
                setBackgroundColor(Color.parseColor("#DD000000"))
                setPadding(24, 14, 24, 14)
                gravity = Gravity.CENTER
            }

            btnSend = Button(this).apply {
                text = "전송 (0)"
                setBackgroundColor(Color.parseColor("#16A34A"))
                setTextColor(Color.WHITE)
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
            val btnCancel = Button(this).apply {
                text = "취소"
                setBackgroundColor(Color.parseColor("#4B5563"))
                setTextColor(Color.WHITE)
                setOnClickListener { closeCaptureOverlay() }
            }
            val btnExit = Button(this).apply {
                text = "✕ 종료"
                setBackgroundColor(Color.parseColor("#DC2626"))
                setTextColor(Color.WHITE)
                setOnClickListener { stopSelf() }
            }

            overlay.setOnTouchListener { _, e ->
                if (e.action == MotionEvent.ACTION_DOWN) {
                    // 터치 좌표 보정 (상태바 등으로 인한 오프셋)
                    val statusBarOffset = getStatusBarHeight()
                    val adjX = e.rawX
                    val adjY = e.rawY - statusBarOffset

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
                true
            }

            overlay.addView(hint, FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT
            ).apply { gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL; topMargin = 100 })

            val bottom = FrameLayout(this).apply {
                addView(btnSend, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, 110).apply { bottomMargin = 340 })
                addView(btnUndo, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, 90).apply { bottomMargin = 240 })
                addView(btnCancel, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, 80).apply { bottomMargin = 150 })
                addView(btnExit, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, 70))
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

    private fun getStatusBarHeight(): Int {
        val resId = resources.getIdentifier("status_bar_height", "dimen", "android")
        return if (resId > 0) resources.getDimensionPixelSize(resId) else 0
    }

    private fun undoLast() {
        if (tappedPoints.isEmpty()) return
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
    }

    private fun sendAll() {
        if (tappedPoints.isEmpty()) return
        getLocation()
        val mpp = 9.5
        val cx = screenW / 2f
        val cy = screenH / 2f
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
            "hotspots" to hotspots
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
                        Toast.makeText(this, "✅ ${hotspots.size}곳 전송 완료", Toast.LENGTH_LONG).show()
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