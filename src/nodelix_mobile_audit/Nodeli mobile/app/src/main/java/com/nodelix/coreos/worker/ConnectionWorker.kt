package com.nodelix.coreos.worker

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import androidx.core.app.NotificationCompat
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.nodelix.coreos.R
import com.nodelix.coreos.core.App
import com.nodelix.coreos.core.AppResult
import com.nodelix.coreos.util.Constants
import java.util.concurrent.TimeUnit

/**
 * Periodic WorkManager task (Part 2 & Part 20) that performs lightweight, OS-compliant
 * server health & update checks when connected to a network without claiming a permanent
 * background WebSocket connection.
 */
class ConnectionWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val app = applicationContext as? App ?: return Result.success()
        val container = app.container

        val activeProfile = container.serverRepository.activeProfile.value ?: return Result.success()
        val probe = container.serverRepository.testConnection(activeProfile)
        if (probe is AppResult.Failure) {
            container.logStore.warn("Worker", "Periodic check: server '${activeProfile.name}' unreachable.")
            return Result.retry()
        }

        if (container.sessionManager.sessionState.value.isAuthenticated) {
            container.updateRepository.refreshUpdateCenter()
            val update = container.updateRepository.serverUpdateState.value
            if (update?.updateAvailable == true && update.latestShort.isNotBlank()) {
                postLocalAlertNotification(
                    title = "NodelixcoreOS Update (${update.latestShort})",
                    body = update.commits.firstOrNull()?.subject
                        ?: "A new server commit is available on ${update.repo}."
                )
            }
        }
        return Result.success()
    }

    private fun postLocalAlertNotification(title: String, body: String) {
        // Android 13+ requires an explicit runtime grant; skip silently when revoked
        // instead of tripping a SecurityException inside WorkManager.
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            val granted = androidx.core.content.ContextCompat.checkSelfPermission(
                applicationContext,
                android.Manifest.permission.POST_NOTIFICATIONS
            ) == android.content.pm.PackageManager.PERMISSION_GRANTED
            if (!granted) {
                runCatching {
                    ((applicationContext as? App)?.container?.logStore)
                        ?.warn("Worker", "Update available but notification permission is revoked; skipping alert.")
                }
                return
            }
        }
        val nm = applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val channel = NotificationChannel(
            Constants.ALERT_NOTIFICATION_CHANNEL_ID,
            applicationContext.getString(R.string.notification_channel_name),
            NotificationManager.IMPORTANCE_DEFAULT
        ).apply {
            description = applicationContext.getString(R.string.notification_channel_desc)
        }
        nm.createNotificationChannel(channel)

        val notification = NotificationCompat.Builder(applicationContext, Constants.ALERT_NOTIFICATION_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_nodelix_logo)
            .setContentTitle(title)
            .setContentText(body)
            .setAutoCancel(true)
            .build()

        runCatching { nm.notify(1001, notification) }
    }

    companion object {
        private const val UNIQUE_WORK_NAME = "nodelix_periodic_health_sync"

        fun schedulePeriodicSync(context: Context) {
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()

            val workRequest = PeriodicWorkRequestBuilder<ConnectionWorker>(30, TimeUnit.MINUTES)
                .setConstraints(constraints)
                .build()

            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                UNIQUE_WORK_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                workRequest
            )
        }
    }
}
