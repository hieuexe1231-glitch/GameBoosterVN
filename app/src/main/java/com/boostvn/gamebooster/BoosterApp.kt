package com.boostvn.gamebooster

import android.app.Application
import android.content.Intent
import android.util.Log

class BoosterApp : Application() {
    override fun onCreate() {
        super.onCreate()

        val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()

        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                val stackTrace = Log.getStackTraceString(throwable)
                val intent = Intent(applicationContext, CrashActivity::class.java).apply {
                    putExtra("crash_info", stackTrace)
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
                }
                startActivity(intent)
                android.os.Process.killProcess(android.os.Process.myPid())
                System.exit(1)
            } catch (e: Exception) {
                defaultHandler?.uncaughtException(thread, throwable)
            }
        }
    }
}
