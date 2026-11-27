package com.boostvn.gamebooster

import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.animation.AlphaAnimation
import androidx.appcompat.app.AppCompatActivity

/**
 * Màn hình mở đầu (splash) - dùng ảnh nền do người dùng cung cấp (đã có sẵn logo, tên app
 * và chấm tải bên trong ảnh, xem activity_splash.xml) - KHÔNG còn các view logo/tiêu đề/
 * chấm động riêng như bản trước (đã bị xoá khỏi layout để tránh lặp hình 2 lần). Chỉ còn
 * hiệu ứng mờ dần cho toàn màn hình lúc mở app. SỬA LỖI BUILD: bản trước vẫn còn tham chiếu
 * tvSplashSubtitle/dot1/dot2/dot3 dù các view đó đã bị xoá khỏi layout, gây lỗi
 * "Unresolved reference" - đã dọn sạch.
 */
class SplashActivity : AppCompatActivity() {

    private val mainHandler = Handler(Looper.getMainLooper())
    private val totalSplashDurationMs = 900L // ngắn hơn — vào app nhanh, ít tốn RAM

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        try {
            setContentView(R.layout.activity_splash)
            playIntroAnimation()

            mainHandler.postDelayed({
                if (isFinishing || isDestroyed) return@postDelayed
                goToMainActivity()
            }, totalSplashDurationMs)
        } catch (_: Exception) {
            goToMainActivity()
        }
    }

    private fun playIntroAnimation() {
        try {
            val bg = findViewById<android.view.View>(R.id.imgSplashBackground) ?: return
            val fadeIn = AlphaAnimation(0.4f, 1f).apply { duration = 280 }
            bg.startAnimation(fadeIn)
            bg.alpha = 1f
        } catch (_: Throwable) { }
    }

    private fun goToMainActivity() {
        startActivity(Intent(this, MainActivity::class.java))
        finish()
        @Suppress("DEPRECATION")
        overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out)
    }

    override fun onDestroy() {
        super.onDestroy()
        mainHandler.removeCallbacksAndMessages(null)
    }
}
