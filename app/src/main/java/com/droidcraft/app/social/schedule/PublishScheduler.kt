package com.droidcraft.app.social.schedule

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import com.droidcraft.app.data.local.AppDatabase
import com.droidcraft.app.social.work.PublishWorker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.util.concurrent.TimeUnit

/**
 * Publish scheduler — exact time पर Worker चलाता है.
 * - WorkManager: Doze/app बंद होने पर भी pending work चलती है
 * - AlarmManager: exact timing के लिए (setExactAndAllowWhileIdle)
 * - Batch: 50-100 posts एक साथ schedule
 */
object PublishScheduler {

    private const val TAG = "PublishScheduler"
    private const val MIN_GAP_MS = 5 * 60 * 1000L // posts के बीच कम से कम 5 मिनट (rate limit)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** एक post को exact time पर schedule करो. */
    fun schedule(context: Context, postId: String, scheduledAt: Long) {
        val delay = (scheduledAt - System.currentTimeMillis()).coerceAtLeast(0)
        val request = OneTimeWorkRequestBuilder<PublishWorker>()
            .setInitialDelay(delay, TimeUnit.MILLISECONDS)
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .setRequiresBatteryNotLow(false)
                    .build()
            )
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 60, TimeUnit.SECONDS)
            .setInputData(workDataOf(PublishWorker.KEY_POST_ID to postId))
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(
            "post_$postId", ExistingWorkPolicy.REPLACE, request
        )
        // Exact alarm backup (कबी WorkManager delay खराब हो तो)
        setAlarm(context, postId, scheduledAt)
    }

    /** 50-100 posts एक साथ — बीच में gap रखकर (rate limit). */
    fun scheduleBatch(context: Context, posts: List<Pair<String, Long>>) {
        // टाइमलाइन sort + gap enforce
        var last = 0L
        val planned = posts.sortedBy { it.second }.map { (id, at) ->
            val fixed = maxOf(at, last + MIN_GAP_MS)
            last = fixed
            id to fixed
        }
        planned.forEach { (id, at) ->
            schedule(context, id, at)
            scope.launch {
                AppDatabase.getDatabase(context).postQueueDao().reschedule(id, at)
            }
        }
    }

    fun cancel(context: Context, postId: String) {
        WorkManager.getInstance(context).cancelUniqueWork("post_$postId")
        val intent = Intent(context, PublishAlarmReceiver::class.java)
        val pi = PendingIntent.getBroadcast(
            context, postId.hashCode(), intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        am.cancel(pi)
    }

    private fun setAlarm(context: Context, postId: String, at: Long) {
        val intent = Intent(context, PublishAlarmReceiver::class.java)
            .putExtra(PublishAlarmReceiver.EXTRA_POST_ID, postId)
        val pi = PendingIntent.getBroadcast(
            context, postId.hashCode(), intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && !am.canScheduleExactAlarms()) {
            Log.w(TAG, "exact alarms permission नहीं है — WorkManager ही चलेगा")
            return
        }
        runCatching {
            am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
        }.onFailure { Log.e(TAG, "alarm set fail", it) }
    }
}

/** Exact time पर Worker kick. */
class PublishAlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val postId = intent.getStringExtra(EXTRA_POST_ID) ?: return
        val request = OneTimeWorkRequestBuilder<PublishWorker>()
            .setInputData(workDataOf(PublishWorker.KEY_POST_ID to postId))
            .build()
        WorkManager.getInstance(context).enqueue(request)
    }

    companion object {
        const val EXTRA_POST_ID = "post_id"
    }
}
