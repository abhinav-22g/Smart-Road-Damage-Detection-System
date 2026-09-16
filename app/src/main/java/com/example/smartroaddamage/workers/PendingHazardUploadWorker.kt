package com.example.smartroaddamage.workers

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.example.smartroaddamage.data.FirestoreHazardWriteRepository
import com.example.smartroaddamage.data.PendingJourneyUploadStore
import java.util.concurrent.TimeUnit

class PendingHazardUploadWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val store = PendingJourneyUploadStore(applicationContext)
        val writeRepo = FirestoreHazardWriteRepository()
        val queuedJourneys = store.listQueuedJourneys()

        if (queuedJourneys.isEmpty()) return Result.success()

        var shouldRetry = false
        queuedJourneys.forEach { queued ->
            val result = writeRepo.uploadJourneyReports(
                journeyId = queued.journeyId,
                reporterUid = queued.reporterUid,
                anomalies = queued.anomalies.map { it.toAnomalyResult() }
            )
            if (result.error == null) {
                store.removeJourneyUpload(queued.journeyId)
            } else {
                shouldRetry = true
            }
        }

        return if (shouldRetry) Result.retry() else Result.success()
    }
}

object HazardUploadScheduler {
    private const val UNIQUE_WORK_NAME = "pending_hazard_uploads"

    fun enqueue(context: Context) {
        val request = OneTimeWorkRequestBuilder<PendingHazardUploadWorker>()
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .build()
            )
            .setBackoffCriteria(
                BackoffPolicy.EXPONENTIAL,
                15,
                TimeUnit.MINUTES
            )
            .build()

        WorkManager.getInstance(context).enqueueUniqueWork(
            UNIQUE_WORK_NAME,
            ExistingWorkPolicy.KEEP,
            request
        )
    }
}
