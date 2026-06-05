package com.datalakefaceauth.inference

import android.graphics.Bitmap
import android.util.Log
import org.tensorflow.lite.Interpreter
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.exp

/**
 * BlazeFace-based face detector.
 *
 * Loads `blazeface.tflite` from the given model directory. If the model file
 * is absent (e.g., during early development), falls back to a 80 % center-crop
 * so the rest of the pipeline still runs.
 *
 * Input contract (model):
 *   Shape  : 1 × 128 × 128 × 3  float32
 *   Range  : [−1, 1]  (pixel / 127.5 − 1)
 *
 * Output contract:
 *   Returns a 96 × 96 Bitmap aligned face crop, or null if confidence < 0.5.
 */
class FaceDetector(modelDir: String) {

    companion object {
        private const val TAG = "FaceDetector"
        private const val MODEL_FILE = "blazeface.tflite"
        private const val INPUT_SIZE = 128          // BlazeFace input resolution
        private const val FACE_OUTPUT_SIZE = 96     // FaceEmbedder input resolution
        private const val CONFIDENCE_THRESHOLD = 0.5f
        private const val NUM_ANCHORS = 896
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
                    Log.d(TAG, "BlazeFace model loaded from $modelFile")
                }
            } catch (e: Exception) {
                Log.w(TAG, "Failed to load BlazeFace model, using center-crop fallback", e)
                null
            }
        } else {
            Log.w(TAG, "blazeface.tflite not found at $modelDir — using center-crop fallback")
            null
        }
    }

    /**
     * Detects the highest-confidence face in [bitmap] and returns a 96×96
     * aligned crop, or `null` if no face is found above the confidence threshold.
     */
    fun detectFace(bitmap: Bitmap): Bitmap? {
        return if (interpreter != null) {
            runModelDetection(bitmap)
        } else {
            centerCropFallback(bitmap)
        }
    }

    // ─────────────────────────────────────────────────────────────
    //  TFLite model inference
    // ─────────────────────────────────────────────────────────────

    private fun runModelDetection(bitmap: Bitmap): Bitmap? {
        val scaled = Bitmap.createScaledBitmap(bitmap, INPUT_SIZE, INPUT_SIZE, true)

        // Build NHWC float32 input buffer
        val inputBuf = ByteBuffer
            .allocateDirect(1 * INPUT_SIZE * INPUT_SIZE * 3 * Float.SIZE_BYTES)
            .apply { order(ByteOrder.nativeOrder()) }

        for (y in 0 until INPUT_SIZE) {
            for (x in 0 until INPUT_SIZE) {
                val px = scaled.getPixel(x, y)
                inputBuf.putFloat(((px shr 16) and 0xFF) / 127.5f - 1f)  // R
                inputBuf.putFloat(((px shr 8) and 0xFF) / 127.5f - 1f)   // G
                inputBuf.putFloat((px and 0xFF) / 127.5f - 1f)            // B
            }
        }
        inputBuf.rewind()

        // BlazeFace outputs: regressors (896,16) + classifiers (896,1)
        val regressors   = Array(1) { Array(NUM_ANCHORS) { FloatArray(16) } }
        val classifiers  = Array(1) { Array(NUM_ANCHORS) { FloatArray(1) } }
        val outputs      = mapOf(0 to regressors, 1 to classifiers)

        try {
            interpreter!!.runForMultipleInputsOutputs(arrayOf(inputBuf), outputs)
        } catch (e: Exception) {
            Log.e(TAG, "BlazeFace inference failed", e)
            return centerCropFallback(bitmap)
        }

        // Find the anchor with the highest sigmoid score
        var bestScore = 0f
        var bestIndex = -1

        for (i in 0 until NUM_ANCHORS) {
            val score = sigmoid(classifiers[0][i][0])
            if (score > bestScore) {
                bestScore = score
                bestIndex = i
            }
        }

        if (bestScore < CONFIDENCE_THRESHOLD || bestIndex < 0) {
            Log.d(TAG, "No face detected (best score=${"%.2f".format(bestScore)})")
            return null
        }

        // Decode bounding box: [cx, cy, w, h] in pixel coords of 128×128 space
        val box = regressors[0][bestIndex]
        val cx  = box[0] / INPUT_SIZE
        val cy  = box[1] / INPUT_SIZE
        val bw  = box[2] / INPUT_SIZE
        val bh  = box[3] / INPUT_SIZE

        // Map to original bitmap dimensions
        val x1 = ((cx - bw / 2f) * bitmap.width).toInt().coerceIn(0, bitmap.width  - 1)
        val y1 = ((cy - bh / 2f) * bitmap.height).toInt().coerceIn(0, bitmap.height - 1)
        val x2 = ((cx + bw / 2f) * bitmap.width).toInt().coerceIn(x1 + 1, bitmap.width)
        val y2 = ((cy + bh / 2f) * bitmap.height).toInt().coerceIn(y1 + 1, bitmap.height)

        val crop = Bitmap.createBitmap(bitmap, x1, y1, x2 - x1, y2 - y1)
        return Bitmap.createScaledBitmap(crop, FACE_OUTPUT_SIZE, FACE_OUTPUT_SIZE, true)
            .also { Log.d(TAG, "Face detected at (${x1},${y1})→(${x2},${y2}), score=${"%.2f".format(bestScore)}") }
    }

    // ─────────────────────────────────────────────────────────────
    //  Fallback: 80 % center crop
    // ─────────────────────────────────────────────────────────────

    private fun centerCropFallback(bitmap: Bitmap): Bitmap {
        val mx = (bitmap.width  * 0.10f).toInt()
        val my = (bitmap.height * 0.10f).toInt()
        val crop = Bitmap.createBitmap(
            bitmap, mx, my,
            bitmap.width  - 2 * mx,
            bitmap.height - 2 * my
        )
        return Bitmap.createScaledBitmap(crop, FACE_OUTPUT_SIZE, FACE_OUTPUT_SIZE, true)
    }

    private fun sigmoid(x: Float): Float = (1.0 / (1.0 + exp(-x.toDouble()))).toFloat()

    fun close() {
        interpreter?.close()
    }
}
