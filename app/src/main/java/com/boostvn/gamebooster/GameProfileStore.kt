package com.boostvn.gamebooster

import android.content.Context

/**
 * Profile theo từng game (package): downscale + chế độ hiệu năng.
 * BOOST game X → load profile X; đổi setting khi đang chọn game → lưu lại.
 * v4.0: danh sách game đã biết để Auto-Boost khi mở game.
 */
object GameProfileStore {
    private const val PREFS = "booster_game_profiles_v1"
    private const val KEY_KNOWN = "known_games_set"

    data class Profile(
        val downscale: String, // "off" | "0.55" | ...
        val modeName: String   // PerfMode.name
    )

    private fun p(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun save(ctx: Context, gamePackage: String, downscale: String, modeName: String) {
        if (gamePackage.isBlank()) return
        p(ctx).edit()
            .putString("$gamePackage|ds", downscale)
            .putString("$gamePackage|mode", modeName)
            .apply()
        markKnown(ctx, gamePackage)
    }

    fun load(ctx: Context, gamePackage: String): Profile? {
        if (gamePackage.isBlank()) return null
        val ds = p(ctx).getString("$gamePackage|ds", null) ?: return null
        val mode = p(ctx).getString("$gamePackage|mode", null) ?: return null
        return Profile(ds, mode)
    }

    fun clear(ctx: Context, gamePackage: String) {
        p(ctx).edit()
            .remove("$gamePackage|ds")
            .remove("$gamePackage|mode")
            .apply()
    }

    fun markKnown(ctx: Context, gamePackage: String) {
        if (gamePackage.isBlank()) return
        val set = knownPackages(ctx).toMutableSet()
        set.add(gamePackage)
        p(ctx).edit().putStringSet(KEY_KNOWN, set).apply()
    }

    fun knownPackages(ctx: Context): Set<String> {
        val stored = p(ctx).getStringSet(KEY_KNOWN, emptySet())?.toSet() ?: emptySet()
        // Luôn gồm FF / LQ phổ biến VN
        return stored + setOf("com.dts.freefireth", "com.garena.game.kgvn", "com.dts.freefiremax")
    }

    fun isKnownGame(ctx: Context, gamePackage: String): Boolean =
        gamePackage in knownPackages(ctx) || load(ctx, gamePackage) != null
}
