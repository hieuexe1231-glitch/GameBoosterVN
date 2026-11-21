package com.boostvn.gamebooster

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager

/**
 * Bypass Charging (tạm dừng sạc khi đang cắm dây chơi game) để giảm nhiệt.
 *
 * Cơ chế best-effort qua Shizuku (không cần root đầy đủ):
 * 1. Ưu tiên `settings put system pass_through 1` (một số máy hỗ trợ USB-PD pass-through).
 * 2. Fallback: `dumpsys battery set usb 0` + `set ac 0` (mô phỏng rút sạc – giảm sạc ảo).
 * 3. Thử ghi sysfs charging_enabled nếu shell có quyền (hiếm khi thành công trên SELinux chặt).
 *
 * Luôn lưu trạng thái gốc và khôi phục khi tắt / kết thúc phiên.
 * Không phải mọi máy đều hỗ trợ – hàm trả về false rõ ràng khi không làm được gì.
 */
object BypassChargingHelper {

    private const val KEY_ACTIVE = "bypass_charging_active"
    private const val KEY_PASS_THROUGH = "bypass_orig_pass_through"
    private const val KEY_USED_DUMPSYS = "bypass_used_dumpsys"

    val isActive: Boolean
        get() = SessionSnapshotStore.get(BoosterApp.appContext, KEY_ACTIVE) == "1"

    fun isPlugged(context: Context): Boolean {
        return try {
            val intent = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            val plugged = intent?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) ?: 0
            plugged != 0
        } catch (_: Throwable) {
            false
        }
    }

    /**
     * Bật bypass. Trả về true nếu ít nhất 1 phương pháp thành công.
     * Chỉ nên gọi khi đang cắm sạc và có Shizuku.
     */
    fun enable(context: Context): Boolean {
        if (!ShizukuHelper.hasPermission()) return false
        if (isActive) return true

        var success = false

        // 1) pass_through (USB-PD bypass trên một số máy / kernel)
        val currentPt = ShizukuHelper.runShellCommandWithOutput("settings get system pass_through")?.trim()
        if (currentPt != null && currentPt != "null") {
            SessionSnapshotStore.save(context, KEY_PASS_THROUGH, currentPt)
            if (ShizukuHelper.runShellCommand("settings put system pass_through 1")) {
                val verify = ShizukuHelper.runShellCommandWithOutput("settings get system pass_through")?.trim()
                if (verify == "1") success = true
            }
        }

        // 2) dumpsys battery (mô phỏng rút sạc – giúp hệ thống giảm sạc / nhiệt trên nhiều máy)
        val dumpsysOk = ShizukuHelper.runShellCommand("dumpsys battery set usb 0") &&
            ShizukuHelper.runShellCommand("dumpsys battery set ac 0")
        if (dumpsysOk) {
            SessionSnapshotStore.save(context, KEY_USED_DUMPSYS, "1")
            success = true
        }

        // 3) sysfs (best-effort, thường cần root thật)
        val sysfsPaths = listOf(
            "/sys/class/power_supply/battery/charging_enabled",
            "/sys/class/power_supply/battery/input_suspend",
            "/sys/class/power_supply/battery/charge_control_limit"
        )
        for (path in sysfsPaths) {
            val value = if (path.endsWith("input_suspend") || path.endsWith("charge_control_limit")) "1" else "0"
            if (ShizukuHelper.runShellCommand("echo $value > $path 2>/dev/null")) {
                // Không verify sâu vì nhiều máy không cho đọc lại
                success = true
                break
            }
        }

        if (success) {
            SessionSnapshotStore.save(context, KEY_ACTIVE, "1")
        }
        return success
    }

    /** Tắt bypass và khôi phục trạng thái sạc gốc. */
    fun disable(context: Context): Boolean {
        if (!isActive && SessionSnapshotStore.get(context, KEY_PASS_THROUGH) == null) return true
        if (!ShizukuHelper.hasPermission()) {
            // Vẫn xóa flag để lần sau không kẹt
            SessionSnapshotStore.save(context, KEY_ACTIVE, "")
            return false
        }

        var ok = true

        // Khôi phục pass_through
        val origPt = SessionSnapshotStore.get(context, KEY_PASS_THROUGH)
        if (!origPt.isNullOrBlank() && origPt != "null") {
            ok = ShizukuHelper.runShellCommand("settings put system pass_through $origPt") && ok
        } else {
            // Nếu trước đó bật 1 thì tắt về 0
            ShizukuHelper.runShellCommand("settings put system pass_through 0")
        }

        // Reset dumpsys battery về trạng thái thật
        if (SessionSnapshotStore.get(context, KEY_USED_DUMPSYS) == "1") {
            ShizukuHelper.runShellCommand("dumpsys battery reset")
        }

        // Thử bật lại charging_enabled
        ShizukuHelper.runShellCommand("echo 1 > /sys/class/power_supply/battery/charging_enabled 2>/dev/null")
        ShizukuHelper.runShellCommand("echo 0 > /sys/class/power_supply/battery/input_suspend 2>/dev/null")

        SessionSnapshotStore.save(context, KEY_ACTIVE, "")
        SessionSnapshotStore.save(context, KEY_PASS_THROUGH, "")
        SessionSnapshotStore.save(context, KEY_USED_DUMPSYS, "")
        return ok
    }

    /** Gọi khi kết thúc phiên / force restore để chắc chắn không kẹt bypass. */
    fun forceRestore(context: Context) {
        try {
            disable(context)
        } catch (_: Throwable) { }
    }
}
