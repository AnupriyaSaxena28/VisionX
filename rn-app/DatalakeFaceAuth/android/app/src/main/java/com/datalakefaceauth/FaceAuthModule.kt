package com.datalakefaceauth

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Base64
import com.facebook.react.bridge.Arguments
import com.facebook.react.bridge.Promise
import com.facebook.react.bridge.ReactApplicationContext
import com.facebook.react.bridge.ReactContextBaseJavaModule
import com.facebook.react.bridge.ReactMethod
import com.facebook.react.bridge.ReadableArray
import com.facebook.react.bridge.WritableMap
import com.datalakefaceauth.inference.FaceDetector
import com.datalakefaceauth.inference.FaceEmbedder
import com.datalakefaceauth.inference.LivenessDetector
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID
import kotlin.math.sqrt

/**
 * React Native bridge module for face authentication.
 *
 * Exposes the following methods to JS (see NativeModule.ts for the full
 * TypeScript contract):
 *   - initialize(modelPath)
 *   - enrollFace(name, imagePaths)
 *   - authenticate(frameBase64)
 *   - authenticateFromPath(filePath)     ← added for VisionCamera integration
 *   - startLivenessChallenge()
 *   - getLivenessChallengeState()
 */
class FaceAuthModule(reactContext: ReactApplicationContext) :
    ReactContextBaseJavaModule(reactContext) {

    companion object {
        private const val TAG = "FaceAuthModule"
        private const val MODULE_NAME = "FaceAuthModule"
        // Threshold agreed in M1 SCHEMA.md: ≥ 0.6 → matched
        // Increased to 0.85 to minimize false positives in production.
        private const val COSINE_SIMILARITY_THRESHOLD = 0.85f
    }

    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    private var faceDetector: FaceDetector? = null
    private var faceEmbedder: FaceEmbedder? = null
    private var livenessDetector: LivenessDetector? = null
    private var isInitialized = false

    private var livenessChallenge: LivenessChallengeManager? = null

    override fun getName(): String = MODULE_NAME

    // ──────────────────────────────────────────────────────────────
    //  initialize
    // ──────────────────────────────────────────────────────────────

    /**
     * Loads TFLite models from [modelPath] directory.
     * Must be called once before any other method (called from App.tsx on mount).
     */
    @ReactMethod
    fun initialize(modelPath: String, promise: Promise) {
        scope.launch {
            try {
                val modelDir = File(modelPath)
                if (!modelDir.exists()) {
                    modelDir.mkdirs()
                }

                // Copy models from APK assets to the modelPath so they can be loaded
                val assetManager = reactApplicationContext.assets
                val modelsToCopy = arrayOf("blazeface.tflite", "mobilefacenet.tflite", "face_mesh_lite.tflite")
                
                for (modelName in modelsToCopy) {
                    val targetFile = File(modelDir, modelName)
                    if (!targetFile.exists() || targetFile.length() == 0L) {
                        try {
                            assetManager.open("models/$modelName").use { inputStream ->
                                java.io.FileOutputStream(targetFile).use { outputStream ->
                                    val buffer = ByteArray(1024 * 8)
                                    var read: Int
                                    while (inputStream.read(buffer).also { read = it } != -1) {
                                        outputStream.write(buffer, 0, read)
                                    }
                                    outputStream.flush()
                                }
                            }
                            android.util.Log.d(TAG, "Copied $modelName to $modelPath")
                        } catch (e: Exception) {
                            android.util.Log.e(TAG, "Failed to copy $modelName from assets", e)
                        }
                    }
                }

                if (!modelDir.exists() || !modelDir.isDirectory) {
                    promise.reject("INIT_ERROR", "Model directory does not exist: $modelPath")
                    return@launch
                }

                withContext(Dispatchers.IO) {
                    faceDetector     = FaceDetector(modelPath)
                    faceEmbedder     = FaceEmbedder(modelPath)
                    livenessDetector = LivenessDetector(modelPath)
                }

                DatabaseManager.initialize(reactApplicationContext)
                isInitialized = true
                promise.resolve(null)
            } catch (e: Exception) {
                promise.reject("INIT_ERROR", "Failed to initialize: ${e.message}", e)
            }
        }
    }

    // ──────────────────────────────────────────────────────────────
    //  enrollFace
    // ──────────────────────────────────────────────────────────────

    /**
     * Enrollment pipeline: for each path in [imagePaths], detect face,
     * extract embedding, average all embeddings, persist to DB.
     *
     * Returns { success: true, id: "<UUID>" } on success.
     */
    @ReactMethod
    fun enrollFace(name: String, imagePaths: ReadableArray, promise: Promise) {
        if (!ensureInitialized(promise)) return

        scope.launch {
            try {
                val embeddings = mutableListOf<FloatArray>()

                withContext(Dispatchers.IO) {
                    for (i in 0 until imagePaths.size()) {
                        val path = imagePaths.getString(i)
                            ?: throw IllegalArgumentException("Null path at index $i")
                        val bitmap = decodeBitmapFromFile(path)
                            ?: throw IllegalArgumentException("Cannot decode image: $path")
                        val face = faceDetector!!.detectFace(bitmap)
                            ?: throw IllegalStateException("No face detected in: $path")
                        embeddings.add(faceEmbedder!!.extractEmbedding(face))
                        bitmap.recycle()
                    }
                }

                if (embeddings.isEmpty()) {
                    promise.reject("ENROLL_ERROR", "No valid face images provided")
                    return@launch
                }

                val averaged = averageEmbeddings(embeddings)
                val id = DatabaseManager.insertEnrollment(name, averaged)

                val result: WritableMap = Arguments.createMap().apply {
                    putBoolean("success", true)
                    putString("id", id)
                }
                promise.resolve(result)
            } catch (e: Exception) {
                promise.reject("ENROLL_ERROR", "Enrollment failed: ${e.message}", e)
            }
        }
    }

    // ──────────────────────────────────────────────────────────────
    //  authenticate  (base64 frame)
    // ──────────────────────────────────────────────────────────────

    /**
     * Full auth pipeline on a base64-encoded camera frame.
     * Returns { matched, name, score, livenessPass }.
     */
    @ReactMethod
    fun authenticate(frameBase64: String, promise: Promise) {
        if (!ensureInitialized(promise)) return

        scope.launch {
            try {
                val bitmap = withContext(Dispatchers.IO) { decodeBase64ToBitmap(frameBase64) }
                    ?: run {
                        promise.reject("AUTH_ERROR", "Failed to decode base64 frame")
                        return@launch
                    }
                runAuthPipeline(bitmap, promise)
            } catch (e: Exception) {
                promise.reject("AUTH_ERROR", "Authentication failed: ${e.message}", e)
            }
        }
    }

    // ──────────────────────────────────────────────────────────────
    //  authenticateFromPath  ← NEW — called by CameraScreen via takePhoto()
    // ──────────────────────────────────────────────────────────────

    /**
     * Full auth pipeline on a local file path (from VisionCamera takePhoto).
     * Avoids the JS-side overhead of reading the file as base64.
     *
     * Returns the same { matched, name, score, livenessPass } shape as
     * [authenticate], so the TypeScript layer is identical.
     */
    @ReactMethod
    fun authenticateFromPath(filePath: String, promise: Promise) {
        if (!ensureInitialized(promise)) return

        scope.launch {
            try {
                val bitmap = withContext(Dispatchers.IO) { decodeBitmapFromFile(filePath) }
                    ?: run {
                        promise.reject("AUTH_ERROR", "Failed to decode image at: $filePath")
                        return@launch
                    }
                runAuthPipeline(bitmap, promise)
            } catch (e: Exception) {
                promise.reject("AUTH_ERROR", "Authentication from path failed: ${e.message}", e)
            }
        }
    }

    // ──────────────────────────────────────────────────────────────
    //  Liveness challenge
    // ──────────────────────────────────────────────────────────────

    @ReactMethod
    fun startLivenessChallenge(promise: Promise) {
        try {
            livenessChallenge = LivenessChallengeManager()
            livenessChallenge?.start()
            promise.resolve(null)
        } catch (e: Exception) {
            promise.reject("LIVENESS_ERROR", "Failed to start liveness challenge: ${e.message}", e)
        }
    }

    @ReactMethod
    fun getLivenessChallengeState(promise: Promise) {
        try {
            val challenge = livenessChallenge ?: run {
                promise.reject("LIVENESS_ERROR", "No active liveness challenge")
                return
            }
            val result: WritableMap = Arguments.createMap().apply {
                putString("step", challenge.currentStep.value)
                putDouble("progress", challenge.progress.toDouble())
            }
            promise.resolve(result)
        } catch (e: Exception) {
            promise.reject("LIVENESS_ERROR", "Failed to get liveness state: ${e.message}", e)
        }
    }

    @ReactMethod
    fun clearGallery(promise: Promise) {
        try {
            DatabaseManager.clearGallery()
            promise.resolve(null)
        } catch (e: Exception) {
            promise.reject("DB_ERROR", "Failed to clear gallery: ${e.message}", e)
        }
    }

    @ReactMethod
    fun getEnrolledCount(promise: Promise) {
        try {
            val count = DatabaseManager.getEnrolledCount()
            promise.resolve(count)
        } catch (e: Exception) {
            promise.reject("DB_ERROR", "Failed to get enrolled count: ${e.message}", e)
        }
    }

    // ──────────────────────────────────────────────────────────────
    //  Shared auth pipeline (used by both authenticate + authenticateFromPath)
    // ──────────────────────────────────────────────────────────────

    private suspend fun runAuthPipeline(bitmap: Bitmap, promise: Promise) {
        val face = faceDetector!!.detectFace(bitmap)
        if (face == null) {
            bitmap.recycle()
            android.util.Log.d(TAG, "No face detected in frame")
            val noFace: WritableMap = Arguments.createMap().apply {
                putBoolean("matched", false)
                putString("name", "")
                putDouble("score", 0.0)
                putBoolean("livenessPass", false)
            }
            promise.resolve(noFace)
            return
        }

        val livenessPass   = livenessDetector!!.checkLiveness(face)
        val queryEmbedding = faceEmbedder!!.extractEmbedding(face)
        bitmap.recycle()

        // Diagnostic: check if the query embedding is a zero-vector
        val queryNorm = sqrt(queryEmbedding.sumOf { (it * it).toDouble() }).toFloat()
        if (queryNorm < 1e-6f) {
            android.util.Log.e(TAG, "⚠️ Query embedding is a ZERO vector! " +
                "The FaceEmbedder model is NOT loaded. Matching will always fail.")
        } else {
            android.util.Log.d(TAG, "Query embedding norm=$queryNorm (healthy)")
        }

        val gallery = withContext(Dispatchers.IO) {
            DatabaseManager.getEmbeddingsForMatching()
        }
        android.util.Log.i(TAG, "Gallery size: ${gallery.size}")

        if (gallery.isEmpty()) {
            android.util.Log.w(TAG, "⚠️ Gallery is EMPTY — no enrolled faces. " +
                "Please enroll at least one face before attempting verification.")
        }

        var bestId    = ""
        var bestScore = -1.0

        for ((id, stored) in gallery) {
            val storedNorm = sqrt(stored.sumOf { (it * it).toDouble() }).toFloat()
            val score = cosineSimilarity(queryEmbedding, stored)
            android.util.Log.d(TAG, "Match candidate id=$id storedNorm=$storedNorm score=$score")
            if (storedNorm < 1e-6f) {
                android.util.Log.e(TAG, "⚠️ Stored embedding for id=$id is a ZERO vector! " +
                    "This enrollment was done with the model NOT loaded. Re-enroll this face.")
            }
            if (score > bestScore) {
                bestScore = score
                bestId    = id
            }
        }

        android.util.Log.i(TAG, "Best match: id=$bestId score=$bestScore threshold=$COSINE_SIMILARITY_THRESHOLD")

        val bestName = if (bestId.isNotEmpty()) {
            withContext(Dispatchers.IO) { DatabaseManager.getNameById(bestId) } ?: ""
        } else ""

        val matched = bestScore >= COSINE_SIMILARITY_THRESHOLD && livenessPass

        // Record attendance if matched
        if (matched && bestId.isNotEmpty()) {
            withContext(Dispatchers.IO) {
                DatabaseManager.insertAttendanceRecord(
                    faceId        = bestId,
                    lat           = 0.0,   // GPS will be injected by JS layer in future
                    lng           = 0.0,
                    livenessScore = 1.0f,
                    authScore     = bestScore.toFloat()
                )
            }
        }

        val result: WritableMap = Arguments.createMap().apply {
            putBoolean("matched", matched)
            putString("name", if (matched) bestName else "")
            putDouble("score", bestScore)
            putBoolean("livenessPass", livenessPass)
        }
        android.util.Log.i(TAG, "Auth result: matched=$matched name=${if (matched) bestName else ""} score=$bestScore livenessPass=$livenessPass")
        promise.resolve(result)
    }

    // ──────────────────────────────────────────────────────────────
    //  Lifecycle
    // ──────────────────────────────────────────────────────────────

    override fun onCatalystInstanceDestroy() {
        super.onCatalystInstanceDestroy()
        scope.cancel()
        faceDetector?.close()
        faceEmbedder?.close()
        livenessDetector?.close()
        faceDetector     = null
        faceEmbedder     = null
        livenessDetector = null
        livenessChallenge = null
    }

    // ──────────────────────────────────────────────────────────────
    //  Private helpers
    // ──────────────────────────────────────────────────────────────

    private fun ensureInitialized(promise: Promise): Boolean {
        if (!isInitialized) {
            promise.reject("NOT_INITIALIZED", "Call initialize() first.")
            return false
        }
        return true
    }

    private fun decodeBitmapFromFile(path: String): Bitmap? {
        val file = File(path)
        return if (file.exists()) BitmapFactory.decodeFile(file.absolutePath) else null
    }

    private fun decodeBase64ToBitmap(base64: String): Bitmap? = try {
        val bytes = Base64.decode(base64, Base64.DEFAULT)
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
    } catch (e: Exception) { null }

    private fun averageEmbeddings(list: List<FloatArray>): FloatArray {
        val size = list.first().size
        val avg  = FloatArray(size)
        for (e in list) for (i in e.indices) avg[i] += e[i]
        val n = list.size.toFloat()
        for (i in avg.indices) avg[i] /= n
        return avg
    }

    private fun cosineSimilarity(a: FloatArray, b: FloatArray): Double {
        require(a.size == b.size) { "Embedding size mismatch: ${a.size} vs ${b.size}" }
        var dot = 0.0; var na = 0.0; var nb = 0.0
        for (i in a.indices) { dot += a[i] * b[i]; na += a[i] * a[i]; nb += b[i] * b[i] }
        val denom = Math.sqrt(na) * Math.sqrt(nb)
        return if (denom == 0.0) 0.0 else dot / denom
    }

    // ──────────────────────────────────────────────────────────────
    //  Liveness Challenge State Machine (blink → turn → done)
    // ──────────────────────────────────────────────────────────────

    class LivenessChallengeManager {

        enum class Step(val value: String) {
            BLINK("blink"), TURN("turn"), DONE("done"), FAILED("failed")
        }

        var currentStep: Step = Step.BLINK
            private set
        var progress: Int = 0
            private set

        private var startMs: Long = 0L

        companion object {
            private const val STEP_TIMEOUT_MS = 10_000L
        }

        fun start() {
            currentStep = Step.BLINK; progress = 0; startMs = System.currentTimeMillis()
        }

        fun updateWithFrame(blinkDetected: Boolean, turnDetected: Boolean) {
            val elapsed = System.currentTimeMillis() - startMs
            when (currentStep) {
                Step.BLINK -> {
                    if (elapsed > STEP_TIMEOUT_MS) { currentStep = Step.FAILED; progress = 0; return }
                    if (blinkDetected) {
                        currentStep = Step.TURN; progress = 0; startMs = System.currentTimeMillis()
                    } else {
                        progress = ((elapsed.toFloat() / STEP_TIMEOUT_MS) * 50).toInt().coerceIn(0, 99)
                    }
                }
                Step.TURN -> {
                    if (elapsed > STEP_TIMEOUT_MS) { currentStep = Step.FAILED; progress = 0; return }
                    if (turnDetected) { progress = 100; currentStep = Step.DONE }
                    else progress = ((elapsed.toFloat() / STEP_TIMEOUT_MS) * 50).toInt().coerceIn(0, 99)
                }
                Step.DONE, Step.FAILED -> { /* terminal */ }
            }
        }

        fun reset() { currentStep = Step.BLINK; progress = 0; startMs = 0L }
    }
}
