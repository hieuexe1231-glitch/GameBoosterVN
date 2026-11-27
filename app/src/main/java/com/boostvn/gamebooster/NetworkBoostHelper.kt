package com.boostvn.gamebooster

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import android.os.Build

/**
 * Khi BOOST: đẩy mạng ưu tiên chơi game tối đa (ping ổn + bớt radio sleep/scan).
 * Không tăng tốc nhà mạng; tối ưu cấu hình thiết bị trong phiên chơi, có restore.
 */
object NetworkBoostHelper {

    enum class Kind {
        WIFI_STABLE,
        WIFI_WEAK,
        CELLULAR,
        OTHER,
        NONE
    }

    data class Info(
        val kind: Kind,
        val label: String,
        val isCellular: Boolean,
        val isMetered: Boolean,
        val wifiRssi: Int?
    )

    fun detect(context: Context): Info {
        return try {
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val net = cm.activeNetwork ?: return Info(Kind.NONE, "Không mạng", false, false, null)
            val caps = cm.getNetworkCapabilities(net) ?: return Info(Kind.NONE, "Không mạng", false, false, null)
            val metered = !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
            val cellular = caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)
            val wifi = caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
            val rssi = if (wifi) readWifiRssi(context) else null

            when {
                cellular && !wifi -> Info(Kind.CELLULAR, "4G/5G di động", true, true, null)
                wifi -> {
                    val weak = metered || (rssi != null && rssi < -72)
                    if (weak) Info(
                        Kind.WIFI_WEAK,
                        if (metered) "Wi‑Fi metered/lạ" else "Wi‑Fi tín hiệu yếu (${rssi}dBm)",
                        false, metered, rssi
                    ) else Info(
                        Kind.WIFI_STABLE,
                        "Wi‑Fi ổn định" + (rssi?.let { " (${it}dBm)" } ?: ""),
                        false, false, rssi
                    )
                }
                else -> Info(Kind.OTHER, "Mạng khác", false, metered, null)
            }
        } catch (_: Throwable) {
            Info(Kind.NONE, "Không đọc được mạng", false, false, null)
        }
    }

    private fun readWifiRssi(context: Context): Int? {
        return try {
            @Suppress("DEPRECATION")
            val wm = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
            val info = wm?.connectionInfo ?: return null
            val r = info.rssi
            if (r == -127 || r == Int.MIN_VALUE) null else r
        } catch (_: Throwable) {
            null
        }
    }

    /**
     * Gọi mỗi lần BOOST: luôn áp gói MAX mạng game + nhánh theo loại mạng.
     */
    fun applyForGaming(context: Context, actions: MutableList<String>): Int {
        val info = detect(context)
        actions += "Mạng hiện tại: ${info.label}"
        var n = 0
        n += applyMaxGameNetworkPack(context, actions, info)
        when (info.kind) {
            Kind.CELLULAR, Kind.WIFI_WEAK -> n += applyAggressiveMobilePack(context, actions, info)
            Kind.WIFI_STABLE -> n += applyLightWifiPack(context, actions)
            else -> { /* vẫn đã có max pack */ }
        }
        return n
    }

    private fun snapPut(
        context: Context,
        key: String,
        getCmd: String,
        putCmd: String,
        expect: String
    ): Boolean {
        if (!ShizukuHelper.hasPermission()) return false
        if (SessionSnapshotStore.get(context, key) == null) {
            val cur = ShizukuHelper.runShellCommandWithOutput(getCmd)?.trim()
            if (!cur.isNullOrBlank() && cur != "null") SessionSnapshotStore.save(context, key, cur)
        }
        if (!ShizukuHelper.runShellCommand(putCmd)) return false
        val got = ShizukuHelper.runShellCommandWithOutput(getCmd)?.trim()?.lowercase()
        return got == expect.lowercase() || got?.contains(expect.lowercase()) == true
    }

    /** Gói MAX áp cho mọi lần BOOST (Wi‑Fi nhà / 4G đều). */
    private fun applyMaxGameNetworkPack(
        context: Context,
        actions: MutableList<String>,
        info: Info
    ): Int {
        var n = 0
        if (!ShizukuHelper.hasPermission()) {
            actions += "Mạng MAX: cần Shizuku để đẩy DNS/radio/Data Saver"
            return 0
        }
        actions += "Mạng MAX: ưu tiên game (Data Saver off · Speed Mode · radio ổn)"

        // Data Saver / restrict background — tránh bóp băng thông game
        if (snapPut(
                context, "net_data_saver",
                "settings get global data_saver_mode",
                "settings put global data_saver_mode 0", "0"
            )
        ) {
            actions += "Tắt Data Saver: OK"
            n++
        }
        // Một số ROM dùng tên khác
        ShizukuHelper.runShellCommand("cmd netpolicy set restrict-background false")

        // Speed Mode QoS (BrotX / Android socket priority)
        if (snapPut(
                context, "net_speed_mode",
                "settings get global speed_mode",
                "settings put global speed_mode 1", "1"
            )
        ) {
            actions += "Speed Mode (QoS mạng game): ON"
            n++
        }

        // Private DNS off — giảm trễ resolve khi combat
        if (snapPut(
                context, "net_private_dns",
                "settings get global private_dns_mode",
                "settings put global private_dns_mode off", "off"
            )
        ) {
            actions += "Private DNS off (phiên game): OK"
            n++
        }

        // Giữ data/radio khi màn sáng — ít spike
        if (snapPut(
                context, "net_mobile_always",
                "settings get global mobile_data_always_on",
                "settings put global mobile_data_always_on 1", "1"
            )
        ) {
            actions += "mobile_data_always_on: OK"
            n++
        }

        // Tắt scan nền tranh radio
        if (snapPut(
                context, "net_wifi_scan",
                "settings get global wifi_scan_always_enabled",
                "settings put global wifi_scan_always_enabled 0", "0"
            )
        ) {
            actions += "Tắt Wi‑Fi scan nền: OK"
            n++
        }
        if (snapPut(
                context, "net_ble_scan",
                "settings get global ble_scan_always_enabled",
                "settings put global ble_scan_always_enabled 0", "0"
            )
        ) {
            actions += "Tắt BLE scan: OK"
            n++
        }

        // Không chuyển mạng giữa trận
        if (snapPut(
                context, "net_adaptive",
                "settings get secure adaptive_connectivity_enabled",
                "settings put secure adaptive_connectivity_enabled 0", "0"
            )
        ) {
            actions += "Tắt adaptive connectivity: OK"
            n++
        }
        if (snapPut(
                context, "net_recommend",
                "settings get global network_recommendations_enabled",
                "settings put global network_recommendations_enabled 0", "0"
            )
        ) {
            actions += "Tắt network recommendations: OK"
            n++
        }

        // Wi‑Fi power save / wakeup — best effort
        snapPut(
            context, "net_wifi_wakeup",
            "settings get global wifi_wakeup_enabled",
            "settings put global wifi_wakeup_enabled 0", "0"
        )
        snapPut(
            context, "net_wifi_notif",
            "settings get global wifi_networks_available_notification_on",
            "settings put global wifi_networks_available_notification_on 0", "0"
        )

        // Tắt airplane nếu lỡ bật (không snapshot — an toàn)
        ShizukuHelper.runShellCommand("settings put global airplane_mode_on 0")
        ShizukuHelper.runShellCommand("cmd connectivity airplane-mode disable")

        // Ưu tiên game trên Wi‑Fi: lock performance (OEM, ignore fail)
        if (info.kind == Kind.WIFI_STABLE || info.kind == Kind.WIFI_WEAK) {
            ShizukuHelper.runShellCommand("settings put global wifi_power_save 0")
            // Một số máy: settings put system wifi_sleep_policy 2 (never)
            snapPut(
                context, "net_wifi_sleep",
                "settings get global wifi_sleep_policy",
                "settings put global wifi_sleep_policy 2", "2"
            )
        }

        return n
    }

    private fun applyLightWifiPack(context: Context, actions: MutableList<String>): Int {
        // Đã nằm trong max pack
        actions += "Wi‑Fi nhà: đã áp MAX (scan off · DNS · QoS)"
        return 0
    }

    private fun applyAggressiveMobilePack(
        context: Context,
        actions: MutableList<String>,
        info: Info
    ): Int {
        var n = 0
        actions += if (info.isCellular) {
            "4G/5G: giữ radio + giảm chuyển mạng giữa trận"
        } else {
            "Wi‑Fi yếu/lạ: giảm nhiễu scan/DNS"
        }
        if (!ShizukuHelper.hasPermission()) return 0

        // Thêm lần nữa mobile always on nếu cellular
        if (info.isCellular) {
            if (snapPut(
                    context, "net_mobile_always",
                    "settings get global mobile_data_always_on",
                    "settings put global mobile_data_always_on 1", "1"
                )
            ) n++
        }
        return n
    }

    fun restore(context: Context) {
        if (!ShizukuHelper.hasPermission()) return
        fun restoreKey(key: String, putPrefix: String) {
            val v = SessionSnapshotStore.get(context, key) ?: return
            if (v == "__MISSING__" || v.isBlank() || v.equals("null", true)) return
            ShizukuHelper.runShellCommand("$putPrefix $v")
        }
        restoreKey("net_data_saver", "settings put global data_saver_mode")
        restoreKey("net_speed_mode", "settings put global speed_mode")
        restoreKey("net_private_dns", "settings put global private_dns_mode")
        restoreKey("net_mobile_always", "settings put global mobile_data_always_on")
        restoreKey("net_wifi_scan", "settings put global wifi_scan_always_enabled")
        restoreKey("net_ble_scan", "settings put global ble_scan_always_enabled")
        restoreKey("net_recommend", "settings put global network_recommendations_enabled")
        restoreKey("net_adaptive", "settings put secure adaptive_connectivity_enabled")
        restoreKey("net_wifi_notif", "settings put global wifi_networks_available_notification_on")
        restoreKey("net_wifi_wakeup", "settings put global wifi_wakeup_enabled")
        restoreKey("net_wifi_sleep", "settings put global wifi_sleep_policy")
        try {
            ShizukuHelper.runShellCommand("cmd netpolicy set restrict-background false")
        } catch (_: Throwable) { }
    }
}
