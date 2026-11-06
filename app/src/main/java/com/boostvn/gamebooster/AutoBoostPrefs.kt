package com.boostvn.gamebooster

import android.content.Context

/** Công tắc Auto-Boost khi mở game + trạng thái session. */
object AutoBoostPrefs {
    private const val PREFS = "booster_auto_boost_v1"
    private const val KEY_ENABLED = "enabled"
    private const val KEY_NOTIFY = "notify"

    private fun p(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun isEnabled(ctx: Context): Boolean = p(ctx).getBoolean(KEY_ENABLED, false)
    fun setEnabled(ctx: Context, on: Boolean) = p(ctx).edit().putBoolean(KEY_ENABLED, on).apply()

    fun notifyOnApply(ctx: Context): Boolean = p(ctx).getBoolean(KEY_NOTIFY, true)
    fun setNotifyOnApply(ctx: Context, on: Boolean) = p(ctx).edit().putBoolean(KEY_NOTIFY, on).apply()
}
