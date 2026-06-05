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
 * Background sync worker (WorkManager CoroutineWorker).
 *
 * Runs every 15 minutes when the device has network connectivity.
 * Implements an ACK-before-purge pattern:
 *   1. Fetch unsynced records from SQLCipher DB
 *   2. POST batch to AWS API Gateway (POST /attendance)
 *   3. Parse acknowledged_ids from response
 *   4. markAsSynced → deleteRecord only for acknowledged IDs
 *   → If network drops mid-batch, unacknowledged records remain and retry next cycle
 */
class SyncService(appCtx: Context, params: WorkerParameters) :
    CoroutineWorker(appCtx, params) {

    companion object {
        private const val TAG                    = "SyncService"
        private const val WORK_NAME              = "visionx_attendance_sync"
        private const val SYNC_INTERVAL_MINUTES  = 15L
        private const val BATCH_MAX              = 100  // API limit per API_SPEC.md

        val AWS_ENDPOINT: String get() = BuildConfig.AWS_SYNC_ENDPOINT
        private val API_KEY: String    get() = BuildConfig.AWS_API_KEY

        /** Call once from Application.onCreate() to register the periodic worker. */
        fun schedulePeriodic(context: Context) {
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()
            val req = PeriodicWorkRequestBuilder<SyncService>(SYNC_INTERVAL_MINUTES, TimeUnit.MINUTES)
                .setConstraints(constraints)
                .addTag(WORK_NAME)
                .build()
            WorkManager.getInstance(context)
                .enqueueUniquePeriodicWork(WORK_NAME, ExistingPeriodicWorkPolicy.KEEP, req)
            Log.d(TAG, "Periodic sync scheduled (interval=${SYNC_INTERVAL_MINUTES}m)")
        }

        fun cancelAll(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)
            Log.d(TAG, "Sync cancelled")
        }
    }

    override suspend fun doWork(): Result {
        Log.d(TAG, "Sync worker started (attempt=$runAttemptCount)")

        if (!isNetworkAvailable()) {
            Log.w(TAG, "No connectivity — retrying later")
            return Result.retry()
        }

        DatabaseManager.initialize(applicationContext)

        val pending = try {
            DatabaseManager.getPendingSyncRecords()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to read pending records", e)
            return Result.retry()
        }

        if (pending.isEmpty()) {
            Log.d(TAG, "Nothing to sync")
            return Result.success()
        }

        Log.d(TAG, "Syncing ${pending.size} record(s)")

        // Chunk into batches of max 100 (API_SPEC.md limit)
        for (batch in pending.chunked(BATCH_MAX)) {
            val payload = buildPayload(batch)
            val (code, body) = try {
                postToEndpoint(payload)
            } catch (e: Exception) {
                Log.e(TAG, "HTTP request failed", e)
                return Result.retry()
            }

            if (code == HttpURLConnection.HTTP_OK) {
                val acked = parseAckedIds(body)
                Log.d(TAG, "Acknowledged ${acked.size} record(s)")
                for (id in acked) {
                    DatabaseManager.markAsSynced(id)
                    DatabaseManager.deleteRecord(id)
                }
            } else {
                Log.w(TAG, "Sync batch failed HTTP $code: $body")
                return Result.retry()
            }
        }

        Log.d(TAG, "Sync complete")
        return Result.success()
    }

    // ─────────────────────────────────────────────────────────────

    private fun isNetworkAvailable(): Boolean {
        val cm = applicationContext.getSystemService(Context.CONNECTIVITY_SERVICE)
            as? ConnectivityManager ?: return false
        val net  = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(net) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
               caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    }

    private fun buildPayload(records: List<AttendanceRecord>): JSONObject {
        val arr = JSONArray()
        for (r in records) {
            arr.put(JSONObject().apply {
                put("id",             r.id)
                put("face_id",        r.faceId)
                put("timestamp",      r.timestamp)
                put("lat",            r.lat)
                put("lng",            r.lng)
                put("liveness_score", r.livenessScore.toDouble())
                put("auth_score",     r.authScore.toDouble())
            })
        }
        return JSONObject().put("records", arr)
    }

    private fun postToEndpoint(payload: JSONObject): Pair<Int, String> {
        val conn = URL(AWS_ENDPOINT).openConnection() as HttpURLConnection
        return try {
            conn.apply {
                requestMethod = "POST"
                doOutput      = true
                doInput       = true
                setRequestProperty("Content-Type", "application/json; charset=UTF-8")
                setRequestProperty("Accept",       "application/json")
                setRequestProperty("x-api-key",    API_KEY)
                connectTimeout = 30_000
                readTimeout    = 30_000
            }
            OutputStreamWriter(conn.outputStream, Charsets.UTF_8).use {
                it.write(payload.toString()); it.flush()
            }
            val code   = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream
                         else conn.errorStream ?: conn.inputStream
            val body = BufferedReader(InputStreamReader(stream, Charsets.UTF_8)).readText()
            code to body
        } finally {
            conn.disconnect()
        }
    }

    private fun parseAckedIds(body: String): List<String> = try {
        val json = JSONObject(body)
        val arr  = json.getJSONArray("acknowledged_ids")
        List(arr.length()) { arr.getString(it) }
    } catch (e: Exception) {
        Log.e(TAG, "Failed to parse acknowledged_ids", e)
        emptyList()
    }
}
