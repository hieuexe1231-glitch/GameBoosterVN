package com.boostvn.gamebooster

import android.app.ActivityManager
import android.content.Context
import android.os.Process
import android.os.SystemClock

/**
 * "Đặc biệt còn thiếu Booster Overhead Monitor" - app tăng tốc mà bản thân nó chiếm nhiều
 * CPU/RAM thì có thể TỰ GÂY LAG, đi ngược mục đích ban đầu. Đo overhead THẬT của chính
 * booster (không đoán, không suy diễn):
 * - CPU%: đọc /proc/self/stat - CHỈ đọc tiến trình CỦA CHÍNH MÌNH, không cần Shizuku/quyền
 *   gì đặc biệt (mọi app luôn được đọc các file trong thư mục /proc/self của chính nó).
 *   Cùng công thức chuẩn Linux mà lệnh `top` dùng: (Δutime + Δstime) / Δwall-time.
 * - RAM: ActivityManager.getProcessMemoryInfo() - API CHÍNH THỨC của Android, cho ra PSS
 *   (Proportional Set Size) - cùng con số hiển thị trong Settings > Apps > Bộ nhớ.
 */
object BoosterOverheadMonitor {
    data class Overhead(val cpuPercent: Float, val ramKb: Int)

    private var lastUtime = -1L
    private var lastStime = -1L
    private var lastWallMs = -1L
    // USER_HZ chuẩn trên hầu hết nhân Linux Android (sysconf(_SC_CLK_TCK)) - không có API
    // Java/Kotlin nào đọc trực tiếp giá trị này, nhưng 100 đúng cho tuyệt đại đa số thiết
    // bị Android/ARM. Nếu sai, kết quả chỉ lệch tỉ lệ đều, không lệch xu hướng tăng/giảm.
    private const val CLOCK_TICKS_PER_SEC = 100L

    fun sample(context: Context): Overhead {
        val cpuPct = try {
            val stat = java.io.File("/proc/self/stat").readText()
            // Field 14 (utime) và 15 (stime) TÍNH TỪ SAU dấu ')' cuối - tên tiến trình nằm
            // trong ngoặc có thể chứa khoảng trắng nên không thể split cứng theo vị trí.
            val afterParen = stat.substringAfterLast(')').trim()
            val fields = afterParen.split(Regex("\\s+"))
            // Sau ')': field[0]=state, field[1]=ppid, ... field[11]=utime, field[12]=stime
            val utime = fields.getOrNull(11)?.toLongOrNull()
            val stime = fields.getOrNull(12)?.toLongOrNull()
            if (utime == null || stime == null) {
                0f
            } else {
                val nowWall = SystemClock.elapsedRealtime()
                val result = if (lastUtime >= 0 && lastWallMs > 0) {
                    val dTicks = (utime + stime) - (lastUtime + lastStime)
                    val dWallMs = nowWall - lastWallMs
                    if (dWallMs > 0 && dTicks >= 0) {
                        val cpuSeconds = dTicks.toDouble() / CLOCK_TICKS_PER_SEC
                        val wallSeconds = dWallMs / 1000.0
                        (cpuSeconds / wallSeconds * 100.0).toFloat().coerceIn(0f, 100f)
                    } else 0f
                } else 0f
                lastUtime = utime; lastStime = stime; lastWallMs = nowWall
                result
            }
        } catch (_: Throwable) { 0f }

        val ramKb = try {
            val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            val info = am.getProcessMemoryInfo(intArrayOf(Process.myPid()))
            info?.firstOrNull()?.totalPss ?: 0
        } catch (_: Throwable) { 0 }

        return Overhead(cpuPct, ramKb)
    }
}
