package com.sudataxi.a25helper.lite

import android.content.Intent
import android.net.Uri
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
            startService(Intent(this, OverlayServiceLite::class.java).apply { putExtra("firebase_url", url) })
            tvStatus.text = "✅ 실행중"
            Toast.makeText(this, "플로팅 버튼 생성됨! 카카오T로 이동", Toast.LENGTH_LONG).show()
        }
    }
}