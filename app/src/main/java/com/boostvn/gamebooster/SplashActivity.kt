package com.boostvn.gamebooster

import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.animation.AccelerateDecelerateInterpolator
import android.view.animation.AlphaAnimation
import android.view.animation.Animation
import android.view.animation.AnimationSet
import android.view.animation.ScaleAnimation
import androidx.appcompat.app.AppCompatActivity

/**
 * Màn hình mở đầu (splash) phong cách RedMagic: nền đen-đỏ-cam, logo phóng to +
 * phát sáng dần, 3 chấm neon nhấp nháy như đang "nạp năng lượng", rồi tự chuyển
 * sang màn hình chính. Thuần hiệu ứng hình ảnh, không ảnh hưởng logic tối ưu máy.
 */
class SplashActivity : AppCompatActivity() {

    private val mainHandler = Handler(Looper.getMainLooper())
    private val totalSplashDurationMs = 1900L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        try {
            setContentView(R.layout.activity_splash)
            playIntroAnimation()

            mainHandler.postDelayed({
                if (isFinishing || isDestroyed) return@postDelayed
                goToMainActivity()
            }, totalSplashDurationMs)
        } catch (e: Exception) {
            // Nếu splash lỗi vì bất kỳ lý do gì, đừng chặn người dùng vào app chính
            goToMainActivity()
        }
    }

    private fun playIntroAnimation() {
        val logo = findViewById<android.view.View>(R.id.imgSplashLogo)
        val title = findViewById<android.view.View>(R.id.tvSplashTitle)
        val subtitle = findViewById<android.view.View>(R.id.tvSplashSubtitle)

        // Logo: phóng to nhẹ từ 60% lên 100% kèm mờ dần hiện ra
        val scale = ScaleAnimation(
            0.6f, 1.0f, 0.6f, 1.0f,
            Animation.RELATIVE_TO_SELF, 0.5f,
            Animation.RELATIVE_TO_SELF, 0.5f
        )
        val fadeInLogo = AlphaAnimation(0f, 1f)
        val logoSet = AnimationSet(true).apply {
            addAnimation(scale)
            addAnimation(fadeInLogo)
            duration = 550
            interpolator = AccelerateDecelerateInterpolator()
        }
        logo.startAnimation(logoSet)
        logo.alpha = 1f

        // Tiêu đề: hiện ra sau logo 1 chút
        title.postDelayed({
            val fadeTitle = AlphaAnimation(0f, 1f).apply { duration = 400 }
            title.startAnimation(fadeTitle)
            title.alpha = 1f
        }, 300)

        // Phụ đề: hiện ra cuối cùng
        subtitle.postDelayed({
            val fadeSub = AlphaAnimation(0f, 1f).apply { duration = 400 }
            subtitle.startAnimation(fadeSub)
            subtitle.alpha = 1f
        }, 550)

        // 3 chấm neon nhấp nháy nối tiếp nhau, giống hiệu ứng "đang nạp"
        animateDot(R.id.dot1, 700)
        animateDot(R.id.dot2, 850)
        animateDot(R.id.dot3, 1000)
    }

    private fun animateDot(dotId: Int, startDelay: Long) {
        val dot = findViewById<android.view.View>(dotId)
        dot.postDelayed({
            if (isFinishing || isDestroyed) return@postDelayed
            val pulse = AlphaAnimation(0.2f, 1f).apply {
                duration = 350
                repeatMode = Animation.REVERSE
                repeatCount = Animation.INFINITE
            }
            dot.startAnimation(pulse)
        }, startDelay)
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
