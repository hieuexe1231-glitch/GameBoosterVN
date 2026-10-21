package com.boostvn.gamebooster

import android.os.Bundle
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

/**
 * Màn hình này chỉ hiện ra khi app bị lỗi (crash). Nó hiện toàn bộ chi tiết lỗi
 * dưới dạng chữ để người dùng chụp màn hình gửi đi debug, thay vì app tự đóng
 * lại không rõ lý do.
 */
class CrashActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val info = intent.getStringExtra("crash_info") ?: "Không có thông tin lỗi"

        val textView = TextView(this).apply {
            text = "ỨNG DỤNG BỊ LỖI - Chụp màn hình này gửi để sửa:\n\n$info"
            setTextColor(0xFFFFFFFF.toInt())
            setBackgroundColor(0xFF0F1226.toInt())
            setPadding(32, 64, 32, 32)
            textSize = 12f
            setTextIsSelectable(true)
        }

        val scrollView = ScrollView(this).apply {
            setBackgroundColor(0xFF0F1226.toInt())
            addView(textView)
        }

        setContentView(scrollView)
    }
}
