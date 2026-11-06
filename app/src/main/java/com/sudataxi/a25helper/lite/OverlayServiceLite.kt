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
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import android.util.Log
import android.view.Gravity
import android.view.WindowManager
import android.widget.Button
import android.widget.FrameLayout
import android.widget.Toast

class OverlayServiceLite : Service() {
    private val TAG = "OverlayServiceLite"
    private var windowManager: WindowManager? = null
    private var floatingView: FrameLayout? = null
    private var firebaseUrl = ""

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

            showFloatingButton()
        } catch (e: Exception) {
            Log.e(TAG, "onStartCommand error", e)
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
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, "a25_lite")
        } else {
            Notification.Builder(this)
        }
        return builder
            .setContentTitle("A25 Lite 실행중")
            .setContentText("플로팅 버튼으로 핫스팟 저장")
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .build()
    }

    private fun showFloatingButton() {
        try {
            windowManager = getSystemService(WINDOW_SERVICE) as WindowManager

            val params = WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                else
                    WindowManager.LayoutParams.TYPE_PHONE,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                PixelFormat.TRANSLUCENT
            )
            params.gravity = Gravity.TOP or Gravity.START
            params.x = 40
            params.y = 200

            val container = FrameLayout(this)
            val btn = Button(this).apply {
                text = "🔥"
                textSize = 18f
                setBackgroundColor(Color.parseColor("#2DD4BF"))
                setTextColor(Color.BLACK)
                setPadding(24, 16, 24, 16)
                setOnClickListener {
                    Toast.makeText(this@OverlayServiceLite, "핫스팟 캡처 준비", Toast.LENGTH_SHORT).show()
                }
            }
            container.addView(btn)
            floatingView = container

            windowManager!!.addView(container, params)
            Toast.makeText(this, "플로팅 버튼 생성 완료", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Log.e(TAG, "showFloatingButton error", e)
            Toast.makeText(this, "버튼 생성 실패: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        try {
            floatingView?.let { windowManager?.removeView(it) }
        } catch (e: Exception) {
            Log.w(TAG, "removeView", e)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null
}