package com.boostvn.gamebooster

import android.app.ActivityManager
import android.content.Context

/**
 * v2.5 UltraSmooth — ưu tiên không gây giật:
 * - Dọn rất ít trước trận
 * - Polling thưa
 * - Bỏ dumpsys gfxinfo trên mọi máy (bản thân lệnh này gây hitch)
 */
object DeviceProfileHelper {

    /** Vivo Y21 V2111 / Helio P35 tuned profile: conservative sustained-load policy. */
    fun isVivoY21(): Boolean = android.os.Build.MANUFACTURER.equals("vivo", true) &&
        android.os.Build.MODEL.equals("V2111", true)

    fun isLowRamDevice(context: Context): Boolean = try {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        am.isLowRamDevice
    } catch (_: Throwable) { false }

    fun recommendedCleanupCount(context: Context): Int = when { isVivoY21() -> 2; isLowRamDevice(context) -> 2; else -> 1 }

    fun thermalSafeThresholdC(context: Context): Float = when { isVivoY21() -> 42f; isLowRamDevice(context) -> 40f; else -> 42f }

    /** Polling tối thiểu cao — booster ít thức giấc hơn = ít giật hơn */
    fun minPollingIntervalMs(context: Context): Long = when { isVivoY21() -> 22_000L; isLowRamDevice(context) -> 25_000L; else -> 20_000L }

    /**
     * Frame diagnostics are not disabled outright. They are sampled adaptively:
     * sparse while stable, faster only when lag is suspected. This preserves the
     * diagnostic signal without turning dumpsys gfxinfo into a periodic hitch source.
     */
    fun shouldSkipFrameJankSampling(context: Context): Boolean = false
}
