package com.boostvn.gamebooster

import android.content.Context

/**
 * Persistent crash-safe session snapshot for temporary game tweaks.
 * Only stores values that the booster is allowed to change and restores them
 * after an interrupted session. No root is required; shell access is supplied by Shizuku.
 */
object SessionSnapshotStore {
    private const val PREFS = "booster_session_snapshot_v3"
    private const val ACTIVE = "active"
    private const val GAME = "game_package"
    private const val SESSION_ID = "session_id"
    private const val STARTED_AT = "started_at"

    private fun p(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun begin(context: Context, gamePackage: String) {
        val prefs = p(context)
        if (!prefs.getBoolean(ACTIVE, false)) {
            val id = System.currentTimeMillis().toString(36)
            prefs.edit()
                .putBoolean(ACTIVE, true)
                .putString(GAME, gamePackage)
                .putString(SESSION_ID, id)
                .putLong(STARTED_AT, System.currentTimeMillis())
                .putInt("schema_version", 4)
                .apply()
            InterruptedSessionRecoveryWorker.schedule(context)
        }
    }

    fun isActive(context: Context): Boolean = p(context).getBoolean(ACTIVE, false)
    fun gamePackage(context: Context): String? = p(context).getString(GAME, null)
    fun sessionId(context: Context): String? = p(context).getString(SESSION_ID, null)
    fun startedAt(context: Context): Long = p(context).getLong(STARTED_AT, 0L)

    fun save(context: Context, key: String, value: String?) {
        if (value == null) return
        p(context).edit().putString(key, value).apply()
    }

    fun get(context: Context, key: String): String? = p(context).getString(key, null)

    fun saveBoolean(context: Context, key: String, value: Boolean) {
        p(context).edit().putBoolean(key, value).apply()
    }

    fun getBoolean(context: Context, key: String): Boolean? {
        val prefs = p(context)
        return if (prefs.contains(key)) prefs.getBoolean(key, false) else null
    }

    fun clear(context: Context) {
        p(context).edit().clear().apply()
        InterruptedSessionRecoveryWorker.cancel(context)
    }
}
