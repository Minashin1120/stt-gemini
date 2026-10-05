package com.minashin1120.voxcribe.task

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.minashin1120.voxcribe.MainActivity
import com.minashin1120.voxcribe.R
import com.minashin1120.voxcribe.VoxcribeApp
import com.minashin1120.voxcribe.data.BatchRow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit

/**
 * アプリを閉じている間も Batch ジョブの完了を検知して通知する（15分ごとの定期ワーカー）。
 * 進行中のジョブがある間だけスケジュールし、無くなったら止める。
 */
object BatchWork {
    private const val UNIQUE_NAME = "voxcribe_batch_check"
    private const val CHANNEL = "voxcribe_batch"

    fun ensureScheduled(ctx: Context) {
        val request = PeriodicWorkRequestBuilder<BatchCheckWorker>(15, TimeUnit.MINUTES)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .build()
        WorkManager.getInstance(ctx).enqueueUniquePeriodicWork(UNIQUE_NAME, ExistingPeriodicWorkPolicy.KEEP, request)
    }

    fun cancel(ctx: Context) {
        WorkManager.getInstance(ctx).cancelUniqueWork(UNIQUE_NAME)
    }

    internal fun notifyDone(ctx: Context, job: BatchRow) {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CHANNEL) == null) {
            nm.createNotificationChannel(NotificationChannel(CHANNEL, "Batch完了", NotificationManager.IMPORTANCE_DEFAULT))
        }
        val pi = PendingIntent.getActivity(
            ctx, 0, Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val notification = NotificationCompat.Builder(ctx, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_voxcribe)
            .setContentTitle("Batch処理が完了しました")
            .setContentText("結果はBatch画面から取り込めます")
            .setAutoCancel(true)
            .setContentIntent(pi)
            .build()
        try {
            if (NotificationManagerCompat.from(ctx).areNotificationsEnabled()) {
                NotificationManagerCompat.from(ctx).notify(2000 + job.id.toInt(), notification)
            }
        } catch (_: SecurityException) {
        }
    }
}

class BatchCheckWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result {
        val app = applicationContext as? VoxcribeApp ?: return Result.success()
        val done = withContext(Dispatchers.IO) { app.batchRunner.refreshAll() }
        done.forEach { BatchWork.notifyDone(applicationContext, it) }
        val stillRunning = withContext(Dispatchers.IO) { app.db.batches().any { it.status == "running" } }
        if (!stillRunning) BatchWork.cancel(applicationContext)
        return Result.success()
    }
}
