package com.sudataxi.a25helper.lite

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity

class MainActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        val etUrl = findViewById<EditText>(R.id.etFirebaseUrl)
        val btnStart = findViewById<Button>(R.id.btnStart)
        val tvStatus = findViewById<TextView>(R.id.tvStatus)
        val saved = getSharedPreferences("a25_lite", MODE_PRIVATE).getString("firebase_url", "")
        etUrl.setText(saved)
        btnStart.setOnClickListener {
            val url = etUrl.text.toString().trim().trimEnd('/')
            if (!url.startsWith("https://")) {
                Toast.makeText(this, "https:// URL 필요", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            getSharedPreferences("a25_lite", MODE_PRIVATE).edit().putString("firebase_url", url).apply()
            if (!Settings.canDrawOverlays(this)) {
                startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
                Toast.makeText(this, "오버레이 권한 허용", Toast.LENGTH_LONG).show()
                return@setOnClickListener
            }
            // v8.14.49 FIX: 이 한 줄이 백그라운드에 안 뜨던 원인
            // 전: startService(...) -> 안드로이드 12+에서 백그라운드 서비스 시작이 막혀서
            //     서비스가 바로 죽고, 버튼도 안 뜨고, 백그라운드 목록에도 안 뜸
            // 후: startForegroundService(...) -> 포그라운드로 띄워서 백그라운드에도 남음
            val svcIntent = Intent(this, OverlayServiceLite::class.java).apply { putExtra("firebase_url", url) }
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    startForegroundService(svcIntent)
                } else {
                    startService(svcIntent)
                }
            } catch (e: Exception) {
                // 혹시 startForegroundService가 막히면, 일단 startService로 폴백
                try { startService(svcIntent) } catch (e2: Exception) { }
                Toast.makeText(this, "서비스 시작 재시도: ${e.message}", Toast.LENGTH_SHORT).show()
            }
            tvStatus.text = "✅ 실행중"
            Toast.makeText(this, "플로팅 버튼 생성됨! 카카오T로 이동", Toast.LENGTH_LONG).show()
        }
    }
}