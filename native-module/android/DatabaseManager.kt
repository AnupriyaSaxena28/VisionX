package com.datalakefaceauth

import android.content.Context
import android.provider.Settings
import android.util.Log
import net.sqlcipher.database.SQLiteDatabase
import net.sqlcipher.database.SQLiteOpenHelper
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID

/**
 * Encrypted SQLite database manager using SQLCipher (AES-256).
 *
 * Manages two tables:
 * - **enrolled_faces**: stores face embeddings keyed by UUID
 * - **attendance_log**: stores timestamped authentication events with geo & scores
 *
 * The encryption key is derived from the device's Android ID combined with
 * a build-time secret ([BuildConfig.DB_SECRET]), ensuring the key is never
 * hardcoded and varies per device.
 *
 * Thread-safe singleton via [synchronized] double-check locking.
 */
object DatabaseManager {

    private const val TAG = "DatabaseManager"
    private const val DB_NAME = "faceauth.db"
    private const val DB_VERSION = 1
    private const val EMBEDDING_DIM = 128

    @Volatile
    private var dbHelper: DatabaseHelper? = null

    @Volatile
    private var database: SQLiteDatabase? = null

    // ──────────────────────────────────────────────────────────────
    //  Initialization
    // ──────────────────────────────────────────────────────────────

    /**
     * Opens or creates the encrypted database. Must be called once before
     * any other methods (typically from [FaceAuthModule.initialize]).
     */
    fun initialize(context: Context) {
        if (database != null) return
        synchronized(this) {
            if (database != null) return

            SQLiteDatabase.loadLibs(context)
            val key = deriveEncryptionKey(context)
            val helper = DatabaseHelper(context.applicationContext)
            dbHelper = helper
            database = helper.getWritableDatabase(key)
            Log.d(TAG, "Database initialized successfully")
        }
    }

    // ──────────────────────────────────────────────────────────────
    //  Enrollment operations
    // ──────────────────────────────────────────────────────────────

    /**
     * Inserts a new face enrollment.
     *
     * @param name      Display name for the enrolled person
     * @param embedding 128-dimensional float embedding vector
     * @return UUID string identifier for this enrollment
     */
    fun insertEnrollment(name: String, embedding: FloatArray): String {
        requireInitialized()
        require(embedding.size == EMBEDDING_DIM) {
            "Embedding must be $EMBEDDING_DIM floats, got ${embedding.size}"
        }

        val id = UUID.randomUUID().toString()
        val blob = floatArrayToByteArray(embedding)
        val timestamp = System.currentTimeMillis() / 1000L

        val sql = """
            INSERT INTO enrolled_faces (id, name, embedding, enrolled_at, synced)
            VALUES (?, ?, ?, ?, 0)
        """.trimIndent()
        database!!.execSQL(sql, arrayOf(id, name, blob, timestamp))
        Log.d(TAG, "Enrolled face: name=$name, id=$id")
        return id
    }

    /**
     * Returns all enrolled embeddings for matching.
     *
     * @return List of (id, FloatArray) pairs
     */
    fun getEmbeddingsForMatching(): List<Pair<String, FloatArray>> {
        requireInitialized()
        val results = mutableListOf<Pair<String, FloatArray>>()

        val cursor = database!!.rawQuery(
            "SELECT id, embedding FROM enrolled_faces",
            null
        )
        cursor.use {
            while (it.moveToNext()) {
                val id = it.getString(0)
                val blob = it.getBlob(1)
                val embedding = byteArrayToFloatArray(blob)
                results.add(id to embedding)
            }
        }
        return results
    }

    /**
     * Retrieves the enrolled name for a given id.
     *
     * @param id UUID of the enrolled face
     * @return The name, or null if not found
     */
    fun getNameById(id: String): String? {
        requireInitialized()

        val cursor = database!!.rawQuery(
            "SELECT name FROM enrolled_faces WHERE id = ?",
            arrayOf(id)
        )
        cursor.use {
            return if (it.moveToFirst()) it.getString(0) else null
        }
    }

    // ──────────────────────────────────────────────────────────────
    //  Attendance operations
    // ──────────────────────────────────────────────────────────────

    /**
     * Inserts a new attendance record.
     *
     * @return UUID string identifier for this record
     */
    fun insertAttendanceRecord(
        faceId: String,
        lat: Double,
        lng: Double,
        livenessScore: Float,
        authScore: Float
    ): String {
        requireInitialized()

        val id = UUID.randomUUID().toString()
        val timestamp = System.currentTimeMillis() / 1000L

        val sql = """
            INSERT INTO attendance_log
                (id, face_id, timestamp, lat, lng, liveness_score, auth_score, synced, aws_ack)
            VALUES (?, ?, ?, ?, ?, ?, ?, 0, 0)
        """.trimIndent()
        database!!.execSQL(
            sql,
            arrayOf(id, faceId, timestamp, lat, lng, livenessScore, authScore)
        )
        Log.d(TAG, "Attendance recorded: faceId=$faceId, id=$id")
        return id
    }

    /**
     * Fetches all attendance records that have not yet been synced to the cloud.
     */
    fun getPendingSyncRecords(): List<AttendanceRecord> {
        requireInitialized()
        val results = mutableListOf<AttendanceRecord>()

        val cursor = database!!.rawQuery(
            """
            SELECT id, face_id, timestamp, lat, lng, liveness_score, auth_score, synced, aws_ack
            FROM attendance_log
            WHERE synced = 0
            """.trimIndent(),
            null
        )
        cursor.use {
            while (it.moveToNext()) {
                results.add(
                    AttendanceRecord(
                        id = it.getString(0),
                        faceId = it.getString(1),
                        timestamp = it.getLong(2),
                        lat = it.getDouble(3),
                        lng = it.getDouble(4),
                        livenessScore = it.getFloat(5),
                        authScore = it.getFloat(6),
                        synced = it.getInt(7) == 1,
                        awsAck = it.getInt(8) == 1
                    )
                )
            }
        }
        return results
    }

    /**
     * Marks an attendance record as synced.
     */
    fun markAsSynced(id: String) {
        requireInitialized()
        database!!.execSQL(
            "UPDATE attendance_log SET synced = 1 WHERE id = ?",
            arrayOf(id)
        )
        Log.d(TAG, "Marked as synced: id=$id")
    }

    /**
     * Deletes an attendance record by id (for purge after sync).
     */
    fun deleteRecord(id: String) {
        requireInitialized()
        database!!.execSQL(
            "DELETE FROM attendance_log WHERE id = ?",
            arrayOf(id)
        )
        Log.d(TAG, "Deleted record: id=$id")
    }

    // ──────────────────────────────────────────────────────────────
    //  Private helpers
    // ──────────────────────────────────────────────────────────────

    private fun requireInitialized() {
        checkNotNull(database) {
            "DatabaseManager is not initialized. Call initialize(context) first."
        }
    }

    /**
     * Derives an encryption key from the device's Android ID + build secret.
     * This ensures keys are unique per device and never hardcoded.
     */
    private fun deriveEncryptionKey(context: Context): String {
        val androidId = Settings.Secure.getString(
            context.contentResolver,
            Settings.Secure.ANDROID_ID
        ) ?: "fallback_device_id"

        val secret = BuildConfig.DB_SECRET
        // Simple HMAC-style derivation; for production consider PBKDF2
        val combined = "$androidId:$secret"
        val digest = java.security.MessageDigest.getInstance("SHA-256")
        val hash = digest.digest(combined.toByteArray(Charsets.UTF_8))
        return hash.joinToString("") { "%02x".format(it) }
    }

    /**
     * Converts a 128-float embedding to a compact byte array (512 bytes).
     */
    private fun floatArrayToByteArray(floats: FloatArray): ByteArray {
        val buffer = ByteBuffer.allocate(floats.size * Float.SIZE_BYTES)
        buffer.order(ByteOrder.LITTLE_ENDIAN)
        for (f in floats) {
            buffer.putFloat(f)
        }
        return buffer.array()
    }

    /**
     * Converts a byte array back to a float embedding.
     */
    private fun byteArrayToFloatArray(bytes: ByteArray): FloatArray {
        val buffer = ByteBuffer.wrap(bytes)
        buffer.order(ByteOrder.LITTLE_ENDIAN)
        val result = FloatArray(bytes.size / Float.SIZE_BYTES)
        for (i in result.indices) {
            result[i] = buffer.getFloat()
        }
        return result
    }

    // ──────────────────────────────────────────────────────────────
    //  SQLiteOpenHelper
    // ──────────────────────────────────────────────────────────────

    private class DatabaseHelper(context: Context) :
        SQLiteOpenHelper(context, DB_NAME, null, DB_VERSION) {

        override fun onCreate(db: SQLiteDatabase) {
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS enrolled_faces (
                    id          TEXT PRIMARY KEY,
                    name        TEXT NOT NULL,
                    embedding   BLOB NOT NULL,
                    enrolled_at INTEGER NOT NULL,
                    synced      INTEGER DEFAULT 0
                )
                """.trimIndent()
            )
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS attendance_log (
                    id              TEXT PRIMARY KEY,
                    face_id         TEXT NOT NULL,
                    timestamp       INTEGER NOT NULL,
                    lat             REAL,
                    lng             REAL,
                    liveness_score  REAL,
                    auth_score      REAL,
                    synced          INTEGER DEFAULT 0,
                    aws_ack         INTEGER DEFAULT 0,
                    FOREIGN KEY (face_id) REFERENCES enrolled_faces(id)
                )
                """.trimIndent()
            )
            Log.d(TAG, "Database tables created")
        }

        override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
            Log.w(
                TAG,
                "Upgrading database from v$oldVersion to v$newVersion"
            )
            // Future migrations go here
        }
    }
}

/**
 * Data class representing a single attendance log record.
 */
data class AttendanceRecord(
    val id: String,
    val faceId: String,
    val timestamp: Long,
    val lat: Double,
    val lng: Double,
    val livenessScore: Float,
    val authScore: Float,
    val synced: Boolean,
    val awsAck: Boolean
)
