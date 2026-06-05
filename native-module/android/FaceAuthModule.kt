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

class FaceAuthModule(reactContext: ReactApplicationContext) :
    ReactContextBaseJavaModule(reactContext) {

    companion object {
        private const val TAG = "FaceAuthModule"
        private const val MODULE_NAME = "FaceAuthModule"
        private const val COSINE_SIMILARITY_THRESHOLD = 0.7
    }

    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    private var faceDetector: FaceDetector? = null
    private var faceEmbedder: FaceEmbedder? = null
    private var livenessDetector: LivenessDetector? = null
    private var isInitialized = false

    private var livenessChallenge: LivenessChallengeManager? = null

    override fun getName(): String = MODULE_NAME

    /**
     * Loads TFLite models into memory on a background thread.
     * Must be called before any other methods.
     *
     * @param modelPath Base directory path containing TFLite model files
     * @param promise   Resolved on success, rejected on failure
     */
    @ReactMethod
    fun initialize(modelPath: String, promise: Promise) {
        scope.launch {
            try {
                val modelDir = File(modelPath)
                if (!modelDir.exists() || !modelDir.isDirectory) {
                    promise.reject(
                        "INIT_ERROR",
                        "Model directory does not exist: $modelPath"
                    )
                    return@launch
                }

                withContext(Dispatchers.IO) {
                    faceDetector = FaceDetector(modelPath)
                    faceEmbedder = FaceEmbedder(modelPath)
                    livenessDetector = LivenessDetector(modelPath)
                }

                DatabaseManager.initialize(reactApplicationContext)
                isInitialized = true
                promise.resolve(null)
            } catch (e: Exception) {
                promise.reject(
                    "INIT_ERROR",
                    "Failed to initialize models: ${e.message}",
                    e
                )
            }
        }
    }

    /**
     * Runs the enrollment pipeline: decode each image, detect face, extract embedding,
     * average the embeddings, and store in the database.
     *
     * @param name       Display name for the enrolled person
     * @param imagePaths ReadableArray of local file paths to face images
     * @param promise    Resolved with {success: Boolean, id: String (UUID)}
     */
    @ReactMethod
    fun enrollFace(name: String, imagePaths: ReadableArray, promise: Promise) {
        if (!ensureInitialized(promise)) return

        scope.launch {
            try {
                val detector = faceDetector!!
                val embedder = faceEmbedder!!
                val embeddings = mutableListOf<FloatArray>()

                withContext(Dispatchers.IO) {
                    for (i in 0 until imagePaths.size()) {
                        val path = imagePaths.getString(i)
                            ?: throw IllegalArgumentException(
                                "Image path at index $i is null"
                            )

                        val bitmap = decodeBitmapFromFile(path)
                            ?: throw IllegalArgumentException(
                                "Failed to decode image at: $path"
                            )

                        val face = detector.detectFace(bitmap)
                            ?: throw IllegalStateException(
                                "No face detected in image: $path"
                            )

                        val embedding = embedder.extractEmbedding(face)
                        embeddings.add(embedding)

                        bitmap.recycle()
                    }
                }

                if (embeddings.isEmpty()) {
                    promise.reject(
                        "ENROLL_ERROR",
                        "No valid face images provided"
                    )
                    return@launch
                }

                val averagedEmbedding = averageEmbeddings(embeddings)
                val enrollmentId = DatabaseManager.insertEnrollment(
                    name,
                    averagedEmbedding
                )

                val result: WritableMap = Arguments.createMap().apply {
                    putBoolean("success", true)
                    putString("id", enrollmentId)
                }
                promise.resolve(result)
            } catch (e: Exception) {
                promise.reject(
                    "ENROLL_ERROR",
                    "Enrollment failed: ${e.message}",
                    e
                )
            }
        }
    }

    /**
     * Full authentication pipeline: decode base64 frame, detect face, check liveness,
     * extract embedding, and compare against all enrolled embeddings using cosine
     * similarity with a threshold of 0.7.
     *
     * @param frameBase64 Base64-encoded image frame
     * @param promise     Resolved with {matched, name, score, livenessPass}
     */
    @ReactMethod
    fun authenticate(frameBase64: String, promise: Promise) {
        if (!ensureInitialized(promise)) return

        scope.launch {
            try {
                val detector = faceDetector!!
                val embedder = faceEmbedder!!
                val liveness = livenessDetector!!

                val bitmap = withContext(Dispatchers.IO) {
                    decodeBase64ToBitmap(frameBase64)
                } ?: run {
                    promise.reject(
                        "AUTH_ERROR",
                        "Failed to decode base64 frame"
                    )
                    return@launch
                }

                val face = detector.detectFace(bitmap)
                if (face == null) {
                    bitmap.recycle()
                    val result: WritableMap = Arguments.createMap().apply {
                        putBoolean("matched", false)
                        putString("name", "")
                        putDouble("score", 0.0)
                        putBoolean("livenessPass", false)
                    }
                    promise.resolve(result)
                    return@launch
                }

                val livenessPass = liveness.checkLiveness(face)
                val queryEmbedding = embedder.extractEmbedding(face)
                bitmap.recycle()

                val enrolledEmbeddings = withContext(Dispatchers.IO) {
                    DatabaseManager.getEmbeddingsForMatching()
                }

                var bestMatchName = ""
                var bestScore = -1.0
                var bestMatchId = ""

                for ((id, storedEmbedding) in enrolledEmbeddings) {
                    val score = cosineSimilarity(queryEmbedding, storedEmbedding)
                    if (score > bestScore) {
                        bestScore = score
                        bestMatchId = id
                    }
                }

                // Retrieve the name for the best match from enrolled data
                if (bestMatchId.isNotEmpty()) {
                    bestMatchName = enrolledEmbeddings
                        .firstOrNull { it.first == bestMatchId }
                        ?.first ?: ""
                    // The enrolled data is List<Pair<String, FloatArray>> where
                    // first = id. We need the name, so we query specifically.
                    bestMatchName = withContext(Dispatchers.IO) {
                        DatabaseManager.getNameById(bestMatchId)
                    } ?: ""
                }

                val matched = bestScore >= COSINE_SIMILARITY_THRESHOLD && livenessPass

                val result: WritableMap = Arguments.createMap().apply {
                    putBoolean("matched", matched)
                    putString("name", if (matched) bestMatchName else "")
                    putDouble("score", bestScore)
                    putBoolean("livenessPass", livenessPass)
                }
                promise.resolve(result)
            } catch (e: Exception) {
                promise.reject(
                    "AUTH_ERROR",
                    "Authentication failed: ${e.message}",
                    e
                )
            }
        }
    }

    /**
     * Starts a blink+turn liveness challenge sequence.
     * The challenge state machine progresses: blink → turn → done.
     *
     * @param promise Resolved when the challenge is initialized
     */
    @ReactMethod
    fun startLivenessChallenge(promise: Promise) {
        try {
            livenessChallenge = LivenessChallengeManager()
            livenessChallenge?.start()
            promise.resolve(null)
        } catch (e: Exception) {
            promise.reject(
                "LIVENESS_ERROR",
                "Failed to start liveness challenge: ${e.message}",
                e
            )
        }
    }

    /**
     * Polls the current state of an active liveness challenge.
     *
     * @param promise Resolved with {step: String, progress: Int}
     */
    @ReactMethod
    fun getLivenessChallengeState(promise: Promise) {
        try {
            val challenge = livenessChallenge
            if (challenge == null) {
                promise.reject(
                    "LIVENESS_ERROR",
                    "No active liveness challenge. Call startLivenessChallenge first."
                )
                return
            }

            val result: WritableMap = Arguments.createMap().apply {
                putString("step", challenge.currentStep.value)
                putInt("progress", challenge.progress)
            }
            promise.resolve(result)
        } catch (e: Exception) {
            promise.reject(
                "LIVENESS_ERROR",
                "Failed to get liveness state: ${e.message}",
                e
            )
        }
    }

    override fun onCatalystInstanceDestroy() {
        super.onCatalystInstanceDestroy()
        scope.cancel()
        faceDetector = null
        faceEmbedder = null
        livenessDetector = null
        livenessChallenge = null
    }

    // ──────────────────────────────────────────────────────────────
    //  Private helpers
    // ──────────────────────────────────────────────────────────────

    private fun ensureInitialized(promise: Promise): Boolean {
        if (!isInitialized) {
            promise.reject(
                "NOT_INITIALIZED",
                "FaceAuthModule is not initialized. Call initialize() first."
            )
            return false
        }
        return true
    }

    private fun decodeBitmapFromFile(path: String): Bitmap? {
        val file = File(path)
        if (!file.exists()) return null
        return BitmapFactory.decodeFile(file.absolutePath)
    }

    private fun decodeBase64ToBitmap(base64String: String): Bitmap? {
        return try {
            val decodedBytes = Base64.decode(base64String, Base64.DEFAULT)
            BitmapFactory.decodeByteArray(decodedBytes, 0, decodedBytes.size)
        } catch (e: Exception) {
            null
        }
    }

    private fun averageEmbeddings(embeddings: List<FloatArray>): FloatArray {
        val size = embeddings.first().size
        val averaged = FloatArray(size)
        for (embedding in embeddings) {
            for (i in embedding.indices) {
                averaged[i] += embedding[i]
            }
        }
        val count = embeddings.size.toFloat()
        for (i in averaged.indices) {
            averaged[i] /= count
        }
        return averaged
    }

    private fun cosineSimilarity(a: FloatArray, b: FloatArray): Double {
        require(a.size == b.size) { "Embedding dimensions must match" }
        var dotProduct = 0.0
        var normA = 0.0
        var normB = 0.0
        for (i in a.indices) {
            dotProduct += a[i] * b[i]
            normA += a[i] * a[i]
            normB += b[i] * b[i]
        }
        val denominator = Math.sqrt(normA) * Math.sqrt(normB)
        return if (denominator == 0.0) 0.0 else dotProduct / denominator
    }

    // ──────────────────────────────────────────────────────────────
    //  Liveness Challenge State Machine
    // ──────────────────────────────────────────────────────────────

    /**
     * Manages a multi-step liveness challenge (blink → turn → done).
     * Each step tracks progress from 0 to 100. The challenge transitions
     * to 'failed' if any step times out.
     */
    class LivenessChallengeManager {

        enum class Step(val value: String) {
            BLINK("blink"),
            TURN("turn"),
            DONE("done"),
            FAILED("failed")
        }

        var currentStep: Step = Step.BLINK
            private set

        var progress: Int = 0
            private set

        private var startTimeMillis: Long = 0L

        companion object {
            private const val STEP_TIMEOUT_MS = 10_000L // 10s per step
        }

        fun start() {
            currentStep = Step.BLINK
            progress = 0
            startTimeMillis = System.currentTimeMillis()
        }

        /**
         * Called with each new camera frame to advance the challenge.
         *
         * @param blinkDetected  true if blink was detected in the current frame
         * @param turnDetected   true if head turn was detected in the current frame
         */
        fun updateWithFrame(blinkDetected: Boolean, turnDetected: Boolean) {
            val elapsed = System.currentTimeMillis() - startTimeMillis

            when (currentStep) {
                Step.BLINK -> {
                    if (elapsed > STEP_TIMEOUT_MS) {
                        currentStep = Step.FAILED
                        progress = 0
                        return
                    }
                    if (blinkDetected) {
                        progress = 100
                        // Advance to next step
                        currentStep = Step.TURN
                        progress = 0
                        startTimeMillis = System.currentTimeMillis()
                    } else {
                        // Progress reflects time remaining as a hint
                        progress = ((elapsed.toFloat() / STEP_TIMEOUT_MS) * 50)
                            .toInt()
                            .coerceIn(0, 99)
                    }
                }
                Step.TURN -> {
                    if (elapsed > STEP_TIMEOUT_MS) {
                        currentStep = Step.FAILED
                        progress = 0
                        return
                    }
                    if (turnDetected) {
                        progress = 100
                        currentStep = Step.DONE
                    } else {
                        progress = ((elapsed.toFloat() / STEP_TIMEOUT_MS) * 50)
                            .toInt()
                            .coerceIn(0, 99)
                    }
                }
                Step.DONE, Step.FAILED -> {
                    // Terminal states — no transitions
                }
            }
        }

        fun reset() {
            currentStep = Step.BLINK
            progress = 0
            startTimeMillis = 0L
        }
    }
}
