package com.boostvn.gamebooster

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager

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
}
