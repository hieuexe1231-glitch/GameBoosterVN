package com.boostvn.gamebooster

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Build
import android.os.PowerManager

object TemperatureHelper {
    fun getBatteryTemperatureC(context: Context): Float? = try {
        val intent = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val tenths = intent?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, -1) ?: -1
        if (tenths in 0..900) tenths / 10f else null
    } catch (_: Throwable) { null }

    /** Ưu tiên thermal zone có tên CPU/GPU/SOC; không lấy max của mọi sensor. */
    fun getCpuTemperatureC(): Float? {
        if (!ShizukuHelper.hasPermission()) return null
        val raw = ShizukuHelper.runShellCommandWithOutput(
            "for z in /sys/class/thermal/thermal_zone*; do n=\$(cat \"\$z/type\" 2>/dev/null); t=\$(cat \"\$z/temp\" 2>/dev/null); case \"\$n\" in *cpu*|*CPU*|*gpu*|*GPU*|*soc*|*SOC*|*tsens*|*TSENS*) echo \"\$n \$t\";; esac; done"
        ) ?: return null
        val temps = raw.lines().mapNotNull { line ->
            val p = line.trim().split(Regex("\\s+"))
            val value = p.lastOrNull()?.toFloatOrNull() ?: return@mapNotNull null
            val c = if (value > 1000f) value / 1000f else value
            c.takeIf { it in 15f..100f }
        }
        return temps.maxOrNull()
    }

    /** API CHÍNH THỨC của Android (PowerManager.getCurrentThermalStatus(), từ API 29) -
     * đây là tín hiệu "hệ thống TỰ ĐÁNH GIÁ nó đang phải giảm hiệu năng vì nhiệt" do chính
     * nhà sản xuất máy cấu hình theo đúng đặc tính tản nhiệt của từng dòng máy - đáng tin
     * hơn hẳn việc tự đoán một ngưỡng độ C cố định (mỗi máy chịu nhiệt khác nhau: máy có
     * quạt/vapor chamber chịu 45°C vẫn ổn, máy giá rẻ 40°C đã throttling). KHÔNG cần
     * Shizuku, không cần quyền gì đặc biệt. Trả về null nếu máy chạy Android <10 (API<29)
     * hoặc ROM không hỗ trợ đúng chuẩn (một số ROM Trung Quốc báo sai/luôn NONE).
     *
     * Giá trị trả về khớp PowerManager.THERMAL_STATUS_*: 0=NONE, 1=LIGHT, 2=MODERATE,
     * 3=SEVERE, 4=CRITICAL, 5=EMERGENCY, 6=SHUTDOWN. */
    fun getThermalStatus(context: Context): Int? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return null
        return try {
            val pm = context.getSystemService(Context.POWER_SERVICE) as? PowerManager ?: return null
            pm.currentThermalStatus
        } catch (_: Throwable) { null }
    }

    fun thermalStatusLabel(status: Int?): String = when (status) {
        PowerManager.THERMAL_STATUS_NONE -> "Bình thường"
        PowerManager.THERMAL_STATUS_LIGHT -> "Hơi nóng"
        PowerManager.THERMAL_STATUS_MODERATE -> "Nóng vừa"
        PowerManager.THERMAL_STATUS_SEVERE -> "Nóng nặng - đang tự giảm hiệu năng"
        PowerManager.THERMAL_STATUS_CRITICAL -> "Rất nóng - nguy cơ giảm hiệu năng mạnh"
        PowerManager.THERMAL_STATUS_EMERGENCY, PowerManager.THERMAL_STATUS_SHUTDOWN -> "Nguy hiểm"
        else -> "Không xác định"
    }
}
