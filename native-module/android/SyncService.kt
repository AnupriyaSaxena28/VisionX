package com.datalakefaceauth

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.util.Log
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.TimeUnit

/**
 * Background sync & purge worker that periodically uploads pending attendance
 * records to AWS API Gateway and purges acknowledged records locally.
 *
 * Uses Android WorkManager with a 15-minute periodic interval and
 * network-required constraints for reliable, battery-efficient syncing.
 *
 * Flow:
 * 1. Check network connectivity
 * 2. Fetch pending (unsynced) records from [DatabaseManager]
 * 3. Build a JSON batch payload
 * 4. POST to AWS API Gateway endpoint
 * 5. On success (HTTP 200): parse acknowledged IDs, mark synced, and purge
 * 6. On failure: return [Result.retry] for WorkManager back-off
 */
class SyncService(
    appContext: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(appContext, workerParams) {

    companion object {
        private const val TAG = "SyncService"
        private const val WORK_NAME = "faceauth_sync_periodic"
        private const val SYNC_INTERVAL_MINUTES = 15L

        /**
         * AWS API Gateway endpoint for batch attendance sync.
         * Configure via BuildConfig or remote config in production.
         */
        const val AWS_ENDPOINT: String = BuildConfig.AWS_SYNC_ENDPOINT

        /**
         * Device-scoped API key for authentication with the sync endpoint.
         * Provisioned per-device during enrollment; stored in BuildConfig.
         */
        private val API_KEY: String = BuildConfig.AWS_API_KEY

        /**
         * Enqueues a periodic sync work request with a 15-minute interval.
         * Uses [ExistingPeriodicWorkPolicy.KEEP] to avoid duplicate workers.
         *
         * @param context Application context
         */
        fun schedulePeriodic(context: Context) {
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()

            val workRequest = PeriodicWorkRequestBuilder<SyncService>(
                SYNC_INTERVAL_MINUTES, TimeUnit.MINUTES
            )
                .setConstraints(constraints)
                .addTag(WORK_NAME)
                .build()

            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                WORK_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                workRequest
            )
            Log.d(TAG, "Periodic sync scheduled (interval=${SYNC_INTERVAL_MINUTES}m)")
        }

        /**
         * Cancels all scheduled sync workers.
         *
         * @param context Application context
         */
        fun cancelAll(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)
            Log.d(TAG, "Periodic sync cancelled")
        }
    }

    override suspend fun doWork(): Result {
        Log.d(TAG, "Sync worker started (attempt=$runAttemptCount)")

        // 1. Check network connectivity
        if (!isNetworkAvailable()) {
            Log.w(TAG, "No network connectivity, retrying later")
            return Result.retry()
        }

        // 2. Fetch pending records
        val pendingRecords: List<AttendanceRecord>
        try {
            DatabaseManager.initialize(applicationContext)
            pendingRecords = DatabaseManager.getPendingSyncRecords()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to fetch pending records", e)
            return Result.retry()
        }

        // 3. Nothing to sync
        if (pendingRecords.isEmpty()) {
            Log.d(TAG, "No pending records, sync complete")
            return Result.success()
        }

        Log.d(TAG, "Found ${pendingRecords.size} pending record(s)")

        // 4. Build JSON batch payload
        val payload = buildBatchPayload(pendingRecords)

        // 5. POST to AWS endpoint
        return try {
            val response = postToEndpoint(payload)

            if (response.first == HttpURLConnection.HTTP_OK) {
                // 6. Parse acknowledged IDs and purge
                val acknowledgedIds = parseAcknowledgedIds(response.second)
                Log.d(TAG, "AWS acknowledged ${acknowledgedIds.size} record(s)")

                for (id in acknowledgedIds) {
                    DatabaseManager.markAsSynced(id)
                    DatabaseManager.deleteRecord(id)
                }

                Log.d(TAG, "Sync and purge complete")
                Result.success()
            } else {
                Log.w(
                    TAG,
                    "Sync failed with HTTP ${response.first}: ${response.second}"
                )
                Result.retry()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Sync request failed", e)
            Result.retry()
        }
    }

    // ──────────────────────────────────────────────────────────────
    //  Private helpers
    // ──────────────────────────────────────────────────────────────

    private fun isNetworkAvailable(): Boolean {
        val connectivityManager = applicationContext.getSystemService(
            Context.CONNECTIVITY_SERVICE
        ) as? ConnectivityManager ?: return false

        val network = connectivityManager.activeNetwork ?: return false
        val capabilities = connectivityManager.getNetworkCapabilities(network)
            ?: return false

        return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    }

    private fun buildBatchPayload(records: List<AttendanceRecord>): JSONObject {
        val recordsArray = JSONArray()

        for (record in records) {
            val obj = JSONObject().apply {
                put("id", record.id)
                put("face_id", record.faceId)
                put("timestamp", record.timestamp)
                put("lat", record.lat)
                put("lng", record.lng)
                put("liveness_score", record.livenessScore.toDouble())
                put("auth_score", record.authScore.toDouble())
            }
            recordsArray.put(obj)
        }

        return JSONObject().apply {
            put("records", recordsArray)
        }
    }

    /**
     * POSTs JSON payload to the AWS endpoint.
     *
     * @return Pair of (HTTP status code, response body)
     */
    private fun postToEndpoint(payload: JSONObject): Pair<Int, String> {
        val url = URL(AWS_ENDPOINT)
        val connection = url.openConnection() as HttpURLConnection

        return try {
            connection.apply {
                requestMethod = "POST"
                doOutput = true
                doInput = true
                setRequestProperty("Content-Type", "application/json; charset=UTF-8")
                setRequestProperty("Accept", "application/json")
                setRequestProperty("x-api-key", API_KEY)
                connectTimeout = 30_000
                readTimeout = 30_000
            }

            // Write request body
            OutputStreamWriter(
                connection.outputStream,
                Charsets.UTF_8
            ).use { writer ->
                writer.write(payload.toString())
                writer.flush()
            }

            // Read response
            val statusCode = connection.responseCode
            val responseStream = if (statusCode in 200..299) {
                connection.inputStream
            } else {
                connection.errorStream ?: connection.inputStream
            }

            val responseBody = BufferedReader(
                InputStreamReader(responseStream, Charsets.UTF_8)
            ).use { reader ->
                reader.readText()
            }

            statusCode to responseBody
        } finally {
            connection.disconnect()
        }
    }

    /**
     * Parses the list of acknowledged record IDs from the AWS response.
     * Expected response format: `{"acknowledged_ids": ["id1", "id2", ...]}`
     */
    private fun parseAcknowledgedIds(responseBody: String): List<String> {
        return try {
            val json = JSONObject(responseBody)
            val idsArray = json.getJSONArray("acknowledged_ids")
            val ids = mutableListOf<String>()
            for (i in 0 until idsArray.length()) {
                ids.add(idsArray.getString(i))
            }
            ids
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse acknowledged IDs from response", e)
            emptyList()
        }
    }
}
