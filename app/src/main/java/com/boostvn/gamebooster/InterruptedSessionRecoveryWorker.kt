package com.boostvn.gamebooster

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import java.util.concurrent.TimeUnit

/**
 * Persistent recovery watchdog. It survives app process death/reboot through WorkManager.
 * It NEVER restores while the game process is still alive; if Shizuku is unavailable it
 * retries without deleting the snapshot.
 */
class InterruptedSessionRecoveryWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        if (!SessionSnapshotStore.isActive(applicationContext)) return Result.success()
        val pkg = SessionSnapshotStore.gamePackage(applicationContext)
        if (pkg.isNullOrBlank()) {
            return if (GameOptimizationEngine.restore(applicationContext)) Result.success() else Result.retry()
        }
        val running = if (ShizukuHelper.hasPermission()) {
            ShizukuHelper.runShellCommandWithOutput("pidof $pkg")?.trim().orEmpty().isNotEmpty()
        } else {
            return Result.retry()
        }
        if (running) return Result.retry()
        return if (GameOptimizationEngine.restore(applicationContext)) Result.success() else Result.retry()
    }

    companion object {
        private const val WORK_NAME = "booster_interrupted_session_recovery"

        fun schedule(context: Context) {
            try {
                val request = OneTimeWorkRequestBuilder<InterruptedSessionRecoveryWorker>()
                    .setInitialDelay(15, TimeUnit.MINUTES)
                    .setBackoffCriteria(BackoffPolicy.LINEAR, 15, TimeUnit.MINUTES)
                    .setConstraints(Constraints.Builder().setRequiresBatteryNotLow(false).build())
                    .build()
                WorkManager.getInstance(context).enqueueUniqueWork(
                    WORK_NAME, ExistingWorkPolicy.REPLACE, request
                )
            } catch (_: Throwable) { }
        }

        fun cancel(context: Context) {
            try { WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME) } catch (_: Throwable) { }
        }
    }
}
