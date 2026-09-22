package com.boostvn.gamebooster

import java.io.File

object CpuFrequencyHelper {
    private val cpuDirs = (0..15).map { "/sys/devices/system/cpu/cpu$it/cpufreq" }

    data class Frequency(val currentGhz: Float, val maxGhz: Float)

    fun getFrequency(): Frequency? {
        val values = cpuDirs.mapNotNull { dir ->
            val cur = readKhz(File("$dir/scaling_cur_freq")) ?: return@mapNotNull null
            val max = readKhz(File("$dir/scaling_max_freq")) ?: return@mapNotNull null
            if (cur > 0 && max > 0) cur to max else null
        }
        if (values.isNotEmpty()) {
            val maxCurrent = values.maxOf { it.first }
            val maxPossible = values.maxOf { it.second }
            return Frequency(maxCurrent / 1_000_000f, maxPossible / 1_000_000f)
        }

        if (ShizukuHelper.hasPermission()) {
            val out = ShizukuHelper.runShellCommandWithOutput(
                "for d in /sys/devices/system/cpu/cpu*/cpufreq; do c=\$(cat \"\$d/scaling_cur_freq\" 2>/dev/null); m=\$(cat \"\$d/scaling_max_freq\" 2>/dev/null); [ -n \"\$c\" ] && [ -n \"\$m\" ] && echo \"\$c \$m\"; done"
            )
            val pairs = out?.lines()?.mapNotNull { line ->
                val p = line.trim().split(Regex("\\s+"))
                if (p.size >= 2) p[0].toLongOrNull()?.let { c -> p[1].toLongOrNull()?.let { m -> c to m } } else null
            }.orEmpty()
            if (pairs.isNotEmpty()) {
                return Frequency(pairs.maxOf { it.first } / 1_000_000f, pairs.maxOf { it.second } / 1_000_000f)
            }
        }
        return null
    }

    fun getCurrentFreqGhz(): Float? = getFrequency()?.currentGhz

    private fun readKhz(file: File): Long? = runCatching { file.readText().trim().toLongOrNull() }.getOrNull()
}
