package com.datalakefaceauth.inference

import android.graphics.Bitmap
import android.util.Log
import org.tensorflow.lite.Interpreter
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Liveness detector based on MediaPipe FaceMesh landmarks.
 *
 * Loads `face_mesh_lite.tflite` from the given model directory to obtain
 * 468 facial landmarks. Liveness is confirmed by checking the Eye Aspect
 * Ratio (EAR) — a live face with an open eye has EAR > threshold.
 *
 * Fallback (model absent): Laplacian-variance texture analysis.
 * A printed photo or screen replay has markedly lower texture variance than a
 * live face under typical indoor lighting.
 *
 * Input contract (FaceMesh lite):
 *   Shape : 1 × 192 × 192 × 3  float32, range [0, 1]
 *
 * Output contract:
 *   Shape : 1 × 468 × 3  float32  (x, y, z landmark coords, normalised 0→1)
 *
 * Usage in the challenge flow:
 *   - [checkLiveness] is called on each authentication frame.
 *   - The [FaceAuthModule]'s [LivenessChallengeManager] handles the stateful
 *     blink → turn sequence separately by calling [updateWithFrame] on each
 *     frame; [checkLiveness] here simply returns a binary live/not-live signal
 *     for the final auth gate.
 */
class LivenessDetector(modelDir: String) {

    companion object {
        private const val TAG               = "LivenessDetector"
        private const val MODEL_FILE        = "face_mesh_lite.tflite"
        private const val MESH_INPUT_SIZE   = 192
        private const val NUM_LANDMARKS     = 468

        // Eye Aspect Ratio threshold — empirically tuned for indoor lighting
        private const val EAR_OPEN_THRESHOLD    = 0.20f

        // Texture variance threshold (Laplacian) — below this → likely spoof
        private const val TEXTURE_VAR_THRESHOLD = 80.0

        // MediaPipe FaceMesh canonical eye landmark indices
        // Left eye:  upper lid (159), lower lid (145), outer (33), inner (133), top2 (158), bot2 (153)
        private val LEFT_EYE  = intArrayOf(33, 160, 158, 133, 153, 144)
        // Right eye: upper lid (386), lower lid (374), outer (362), inner (263), top2 (385), bot2 (380)
        private val RIGHT_EYE = intArrayOf(362, 385, 387, 263, 373, 380)
    }

    private val interpreter: Interpreter?

    init {
        val modelFile = File(modelDir, MODEL_FILE)
        interpreter = if (modelFile.exists()) {
            try {
                val opts = Interpreter.Options().apply {
                    setNumThreads(4)
                    setUseXNNPACK(true)
                }
                Interpreter(modelFile, opts).also {
                    Log.d(TAG, "FaceMesh lite model loaded from $modelFile")
                }
            } catch (e: Exception) {
                Log.w(TAG, "Failed to load FaceMesh model, using texture fallback", e)
                null
            }
        } else {
            Log.w(TAG, "face_mesh_lite.tflite not found at $modelDir — using texture fallback")
            null
        }
    }

    /**
     * Returns `true` if [faceBitmap] represents a live face.
     *
     * With model loaded:   EAR ≥ [EAR_OPEN_THRESHOLD] on at least one eye.
     * Without model:       Laplacian variance ≥ [TEXTURE_VAR_THRESHOLD].
     */
    fun checkLiveness(faceBitmap: Bitmap): Boolean {
        return if (interpreter != null) {
            checkWithFaceMesh(faceBitmap)
        } else {
            checkWithTextureVariance(faceBitmap)
        }
    }

    // ─────────────────────────────────────────────────────────────
    //  FaceMesh-based EAR liveness
    // ─────────────────────────────────────────────────────────────

    private fun checkWithFaceMesh(bitmap: Bitmap): Boolean {
        val scaled = Bitmap.createScaledBitmap(bitmap, MESH_INPUT_SIZE, MESH_INPUT_SIZE, true)

        val inputBuf = ByteBuffer
            .allocateDirect(1 * MESH_INPUT_SIZE * MESH_INPUT_SIZE * 3 * Float.SIZE_BYTES)
            .apply { order(ByteOrder.nativeOrder()) }

        for (y in 0 until MESH_INPUT_SIZE) {
            for (x in 0 until MESH_INPUT_SIZE) {
                val px = scaled.getPixel(x, y)
                inputBuf.putFloat(((px shr 16) and 0xFF) / 255f)
                inputBuf.putFloat(((px shr 8) and 0xFF) / 255f)
                inputBuf.putFloat((px and 0xFF) / 255f)
            }
        }
        inputBuf.rewind()

        // FaceMesh lite outputs: landmarks (1,468,3) + confidence (1,1)
        val landmarks   = Array(1) { Array(NUM_LANDMARKS) { FloatArray(3) } }
        val confidence  = Array(1) { FloatArray(1) }
        val outputs     = mapOf(0 to landmarks, 1 to confidence)

        return try {
            interpreter!!.runForMultipleInputsOutputs(arrayOf(inputBuf), outputs)

            if (confidence[0][0] < 0.5f) {
                Log.d(TAG, "FaceMesh confidence too low: ${confidence[0][0]}")
                return false
            }

            val lm = landmarks[0]
            val leftEar  = computeEAR(lm, LEFT_EYE)
            val rightEar = computeEAR(lm, RIGHT_EYE)
            val avgEar   = (leftEar + rightEar) / 2f

            Log.d(TAG, "EAR: L=${"%.3f".format(leftEar)} R=${"%.3f".format(rightEar)} avg=${"%.3f".format(avgEar)}")
            avgEar >= EAR_OPEN_THRESHOLD
        } catch (e: Exception) {
            Log.e(TAG, "FaceMesh inference failed", e)
            // Degrade to texture analysis on failure
            checkWithTextureVariance(bitmap)
        }
    }

    /**
     * Eye Aspect Ratio (EAR) using 6 eye landmarks:
     *   EAR = (‖p2−p6‖ + ‖p3−p5‖) / (2 × ‖p1−p4‖)
     *
     * Indices follow the standard Soukupová & Čech (2016) ordering mapped
     * to MediaPipe's canonical face model.
     */
    private fun computeEAR(landmarks: Array<FloatArray>, eyeIdx: IntArray): Float {
        val p = Array(6) { i -> landmarks[eyeIdx[i]] }

        fun dist(a: FloatArray, b: FloatArray): Float {
            val dx = a[0] - b[0]
            val dy = a[1] - b[1]
            return sqrt((dx * dx + dy * dy).toDouble()).toFloat()
        }

        val vertical1  = dist(p[1], p[5])
        val vertical2  = dist(p[2], p[4])
        val horizontal = dist(p[0], p[3])

        return if (horizontal < 1e-6f) 0f
        else (vertical1 + vertical2) / (2f * horizontal)
    }

    // ─────────────────────────────────────────────────────────────
    //  Texture-variance fallback (anti-spoofing for print attacks)
    // ─────────────────────────────────────────────────────────────

    /**
     * Computes the Laplacian variance of a grayscale version of [bitmap].
     * Live skin texture has high variance; printed photos or screens tend to
     * be blurrier or overly smooth.
     */
    private fun checkWithTextureVariance(bitmap: Bitmap): Boolean {
        val gray   = toGrayscaleArray(bitmap)
        val width  = bitmap.width
        val height = bitmap.height
        val laplacian = mutableListOf<Double>()

        // Apply a 3×3 discrete Laplacian kernel
        for (y in 1 until height - 1) {
            for (x in 1 until width - 1) {
                val center = gray[y * width + x].toDouble()
                val lap = abs(
                    -center * 4
                    + gray[(y - 1) * width + x]
                    + gray[(y + 1) * width + x]
                    + gray[y * width + (x - 1)]
                    + gray[y * width + (x + 1)]
                )
                laplacian.add(lap)
            }
        }

        val mean     = laplacian.average()
        val variance = laplacian.map { (it - mean) * (it - mean) }.average()
        Log.d(TAG, "Texture variance (Laplacian): ${"%.1f".format(variance)}")
        return variance >= TEXTURE_VAR_THRESHOLD
    }

    private fun toGrayscaleArray(bitmap: Bitmap): IntArray {
        val w = bitmap.width
        val h = bitmap.height
        val result = IntArray(w * h)
        for (y in 0 until h) {
            for (x in 0 until w) {
                val px = bitmap.getPixel(x, y)
                val r  = (px shr 16) and 0xFF
                val g  = (px shr 8) and 0xFF
                val b  = px and 0xFF
                result[y * w + x] = (0.299f * r + 0.587f * g + 0.114f * b).toInt()
            }
        }
        return result
    }

    fun close() {
        interpreter?.close()
    }
}
