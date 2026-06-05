package com.datalakefaceauth

import android.content.Context
import android.provider.Settings
import android.util.Log
import net.sqlcipher.database.SQLiteDatabase
import net.sqlcipher.database.SQLiteOpenHelper
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID
import kotlin.math.sqrt

/**
 * Encrypted SQLite database manager (SQLCipher AES-256).
 *
 * Tables:
 *   enrolled_faces  — face embeddings per person (512-dim float32 BLOBs)
 *   attendance_log  — timestamped auth events with geo + scores
 *
 * EMBEDDING_DIM = 512 aligns with the w600k_mbf ONNX/TFLite model output
 * specified in M1's SCHEMA.md (512-dimensional L2-normalised float32 vectors).
 *
 * Encryption key = SHA-256(androidId + BuildConfig.DB_SECRET)
 */
object DatabaseManager {

    private const val TAG           = "DatabaseManager"
    private const val DB_NAME       = "faceauth.db"
    private const val DB_VERSION    = 1
    /**
     * IMPORTANT: 512 dimensions × 4 bytes = 2048-byte BLOB.
     * This matches M1's SCHEMA.md. Do NOT change without updating
     * FaceEmbedder and the Python enroll.py pipeline simultaneously.
     */
    const val EMBEDDING_DIM         = 192

    @Volatile private var dbHelper: DatabaseHelper? = null
    @Volatile private var database: SQLiteDatabase?  = null

    // ── Init ──────────────────────────────────────────────────────

    fun initialize(context: Context) {
        if (database != null) return
        synchronized(this) {
            if (database != null) return
            SQLiteDatabase.loadLibs(context)
            val key    = deriveKey(context)
            val helper = DatabaseHelper(context.applicationContext)
            dbHelper   = helper
            database   = helper.getWritableDatabase(key)
            Log.d(TAG, "Database initialized (SQLCipher v4)")
        }
    }

    // ── Enrollment ────────────────────────────────────────────────

    /**
     * Inserts a new enrolled face. Returns the UUID for that record.
     *
     * @param name      Display name of the enrolled person.
     * @param embedding 512-dimensional L2-normalised float32 vector.
     */
    fun insertEnrollment(name: String, embedding: FloatArray): String {
        requireInitialized()
        require(embedding.size == EMBEDDING_DIM) {
            "Embedding must be $EMBEDDING_DIM floats, got ${embedding.size}"
        }

        // Diagnostic: check if the embedding is a zero-vector (model not loaded)
        val norm = sqrt(embedding.sumOf { (it * it).toDouble() }).toFloat()
        if (norm < 1e-6f) {
            Log.e(TAG, "⚠️ ENROLLING ZERO-VECTOR embedding for name=$name! " +
                "This means the FaceEmbedder model is NOT loaded. " +
                "Verification will NEVER match this enrollment.")
        } else {
            Log.i(TAG, "Enrollment embedding norm=$norm (healthy — non-zero)")
        }

        val id        = UUID.randomUUID().toString()
        val blob      = floatToBytes(embedding)
        val timestamp = System.currentTimeMillis() / 1000L

        database!!.execSQL(
            "INSERT INTO enrolled_faces (id, name, embedding, enrolled_at, synced) VALUES (?,?,?,?,0)",
            arrayOf(id, name, blob, timestamp)
        )
        Log.d(TAG, "Enrolled: name=$name id=$id norm=$norm")
        return id
    }

    /** Returns all (id, embedding) pairs for cosine-similarity matching. */
    fun getEmbeddingsForMatching(): List<Pair<String, FloatArray>> {
        requireInitialized()
        val out = mutableListOf<Pair<String, FloatArray>>()
        database!!.rawQuery("SELECT id, embedding FROM enrolled_faces", null).use { c ->
            while (c.moveToNext()) {
                out.add(c.getString(0) to bytesToFloat(c.getBlob(1)))
            }
        }
        return out
    }

    /** Returns the enrolled name for a given face UUID, or null. */
    fun getNameById(id: String): String? {
        requireInitialized()
        database!!.rawQuery("SELECT name FROM enrolled_faces WHERE id=?", arrayOf(id)).use { c ->
            return if (c.moveToFirst()) c.getString(0) else null
        }
    }

    fun clearGallery() {
        requireInitialized()
        database!!.execSQL("DELETE FROM enrolled_faces")
        database!!.execSQL("DELETE FROM attendance_log")
        Log.i(TAG, "Gallery and attendance logs cleared")
    }

    /** Returns the number of enrolled faces in the gallery. */
    fun getEnrolledCount(): Int {
        requireInitialized()
        database!!.rawQuery("SELECT COUNT(*) FROM enrolled_faces", null).use { c ->
            return if (c.moveToFirst()) c.getInt(0) else 0
        }
    }

    // ── Attendance ────────────────────────────────────────────────

    /**
     * Inserts an attendance event. Returns the generated UUID.
     * synced=0, aws_ack=0 — will be updated by SyncService after upload.
     */
    fun insertAttendanceRecord(
        faceId: String,
        lat: Double,
        lng: Double,
        livenessScore: Float,
        authScore: Float
    ): String {
        requireInitialized()
        val id        = UUID.randomUUID().toString()
        val timestamp = System.currentTimeMillis() / 1000L
        database!!.execSQL(
            """INSERT INTO attendance_log
               (id, face_id, timestamp, lat, lng, liveness_score, auth_score, synced, aws_ack)
               VALUES (?,?,?,?,?,?,?,0,0)""",
            arrayOf(id, faceId, timestamp, lat, lng, livenessScore, authScore)
        )
        Log.d(TAG, "Attendance recorded: faceId=$faceId id=$id")
        return id
    }

    /** Fetches all records not yet uploaded (synced=0). */
    fun getPendingSyncRecords(): List<AttendanceRecord> {
        requireInitialized()
        val out = mutableListOf<AttendanceRecord>()
        database!!.rawQuery(
            "SELECT id,face_id,timestamp,lat,lng,liveness_score,auth_score,synced,aws_ack " +
            "FROM attendance_log WHERE synced=0", null
        ).use { c ->
            while (c.moveToNext()) {
                out.add(AttendanceRecord(
                    id            = c.getString(0),
                    faceId        = c.getString(1),
                    timestamp     = c.getLong(2),
                    lat           = c.getDouble(3),
                    lng           = c.getDouble(4),
                    livenessScore = c.getFloat(5),
                    authScore     = c.getFloat(6),
                    synced        = c.getInt(7) == 1,
                    awsAck        = c.getInt(8) == 1
                ))
            }
        }
        return out
    }

    /** All records for the JS history screen (ordered newest first). */
    fun getAllAttendanceRecords(limit: Int = 100): List<AttendanceRecord> {
        requireInitialized()
        val out = mutableListOf<AttendanceRecord>()
        database!!.rawQuery(
            "SELECT id,face_id,timestamp,lat,lng,liveness_score,auth_score,synced,aws_ack " +
            "FROM attendance_log ORDER BY timestamp DESC LIMIT ?",
            arrayOf(limit.toString())
        ).use { c ->
            while (c.moveToNext()) {
                out.add(AttendanceRecord(
                    id            = c.getString(0),
                    faceId        = c.getString(1),
                    timestamp     = c.getLong(2),
                    lat           = c.getDouble(3),
                    lng           = c.getDouble(4),
                    livenessScore = c.getFloat(5),
                    authScore     = c.getFloat(6),
                    synced        = c.getInt(7) == 1,
                    awsAck        = c.getInt(8) == 1
                ))
            }
        }
        return out
    }

    /** ACK-before-purge: mark synced first, then delete in the same transaction. */
    fun markAsSynced(id: String) {
        requireInitialized()
        database!!.execSQL("UPDATE attendance_log SET synced=1 WHERE id=?", arrayOf(id))
    }

    fun deleteRecord(id: String) {
        requireInitialized()
        database!!.execSQL("DELETE FROM attendance_log WHERE id=?", arrayOf(id))
    }

    fun getPendingCount(): Int {
        requireInitialized()
        database!!.rawQuery("SELECT COUNT(*) FROM attendance_log WHERE synced=0", null).use { c ->
            return if (c.moveToFirst()) c.getInt(0) else 0
        }
    }



    fun getLastSyncTimestamp(): Long {
        requireInitialized()
        database!!.rawQuery(
            "SELECT MAX(timestamp) FROM attendance_log WHERE synced=1", null
        ).use { c ->
            return if (c.moveToFirst()) c.getLong(0) else 0L
        }
    }

    // ── Helpers ───────────────────────────────────────────────────

    private fun requireInitialized() =
        checkNotNull(database) { "DatabaseManager not initialized. Call initialize() first." }

    private fun deriveKey(context: Context): String {
        val androidId = Settings.Secure.getString(
            context.contentResolver, Settings.Secure.ANDROID_ID
        ) ?: "fallback_id"
        val combined = "$androidId:${BuildConfig.DB_SECRET}"
        val hash     = java.security.MessageDigest.getInstance("SHA-256")
            .digest(combined.toByteArray(Charsets.UTF_8))
        return hash.joinToString("") { "%02x".format(it) }
    }

    private fun floatToBytes(arr: FloatArray): ByteArray {
        val buf = ByteBuffer.allocate(arr.size * Float.SIZE_BYTES)
        buf.order(ByteOrder.LITTLE_ENDIAN)
        arr.forEach { buf.putFloat(it) }
        return buf.array()
    }

    private fun bytesToFloat(bytes: ByteArray): FloatArray {
        val buf = ByteBuffer.wrap(bytes).apply { order(ByteOrder.LITTLE_ENDIAN) }
        return FloatArray(bytes.size / Float.SIZE_BYTES) { buf.getFloat() }
    }

    // ── SQLiteOpenHelper ──────────────────────────────────────────

    private class DatabaseHelper(ctx: Context) :
        SQLiteOpenHelper(ctx, DB_NAME, null, DB_VERSION) {

        override fun onCreate(db: SQLiteDatabase) {
            db.execSQL("""
                CREATE TABLE IF NOT EXISTS enrolled_faces (
                    id          TEXT PRIMARY KEY,
                    name        TEXT NOT NULL,
                    embedding   BLOB NOT NULL,
                    enrolled_at INTEGER NOT NULL DEFAULT (strftime('%s','now')),
                    synced      INTEGER NOT NULL DEFAULT 0 CHECK(synced IN (0,1))
                )
            """.trimIndent())

            db.execSQL("""
                CREATE TABLE IF NOT EXISTS attendance_log (
                    id              TEXT PRIMARY KEY,
                    face_id         TEXT NOT NULL REFERENCES enrolled_faces(id),
                    timestamp       INTEGER NOT NULL,
                    lat             REAL    NOT NULL DEFAULT 0,
                    lng             REAL    NOT NULL DEFAULT 0,
                    liveness_score  REAL    NOT NULL DEFAULT 0,
                    auth_score      REAL    NOT NULL DEFAULT 0,
                    synced          INTEGER NOT NULL DEFAULT 0 CHECK(synced IN (0,1)),
                    aws_ack         INTEGER NOT NULL DEFAULT 0 CHECK(aws_ack IN (0,1))
                )
            """.trimIndent())

            db.execSQL("CREATE INDEX IF NOT EXISTS idx_al_synced    ON attendance_log (synced)")
            db.execSQL("CREATE INDEX IF NOT EXISTS idx_al_face_id   ON attendance_log (face_id)")
            db.execSQL("CREATE INDEX IF NOT EXISTS idx_al_timestamp ON attendance_log (timestamp)")
            Log.d(TAG, "Database schema created (EMBEDDING_DIM=${ DatabaseManager.EMBEDDING_DIM })")
        }

        override fun onUpgrade(db: SQLiteDatabase, old: Int, new: Int) {
            Log.w(TAG, "DB upgrade v$old → v$new (future migrations here)")
        }
    }
}

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
