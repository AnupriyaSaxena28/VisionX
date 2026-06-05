package com.datalakefaceauth.inference

import android.graphics.Bitmap
import android.util.Log
import org.tensorflow.lite.Interpreter
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.sqrt

/**
 * MobileFaceNet-based face embedder.
 *
 * Loads `mobilefacenet.tflite` from the given model directory. If the model
 * file is absent, returns a deterministic zero-vector so that callers fail
 * gracefully rather than crash.
 *
 * Input contract (model):
 *   Shape  : 1 × 96 × 96 × 3  float32
 *   Range  : [0, 1]  (pixel / 255)        ← per M1 SCHEMA.md
 *
 * Output contract:
 *   Shape  : 1 × 512  float32
 *   The output is L2-normalised before returning so that cosine similarity
 *   equals a simple dot-product, matching the SCHEMA.md threshold guidance.
 *
 * Embedding dimension: 512  (fixes the 128-dim mismatch in an earlier draft of
 * DatabaseManager — EMBEDDING_DIM in DatabaseManager must also be 512).
 */
class FaceEmbedder(modelDir: String) {

    companion object {
        private const val TAG            = "FaceEmbedder"
        private const val MODEL_FILE     = "mobilefacenet.tflite"
        private const val INPUT_SIZE     = 112
        private const val EMBEDDING_DIM  = 192
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
                    val inputShape = it.getInputTensor(0).shape().joinToString(", ")
                    val outputShape = it.getOutputTensor(0).shape().joinToString(", ")
                    Log.i(TAG, "MobileFaceNet model loaded. Input: [$inputShape], Output: [$outputShape]")
                }
            } catch (e: Exception) {
                Log.e(TAG, "⚠️ CRITICAL: Failed to load MobileFaceNet model", e)
                null
            }
        } else {
            Log.e(TAG, "⚠️ CRITICAL: mobilefacenet.tflite NOT FOUND at $modelDir")
            null
        }
    }

    /**
     * Extracts a 192-dimensional L2-normalised embedding from [faceBitmap].
     *
     * @param faceBitmap Aligned face crop (any size; will be resized to 112×112).
     * @return L2-normalised float32 array of length 192.
     */
    fun extractEmbedding(faceBitmap: Bitmap): FloatArray {
        if (interpreter == null) {
            Log.e(TAG, "⚠️ Model not loaded — returning ZERO embedding.")
            return FloatArray(EMBEDDING_DIM)
        }

        val scaled = Bitmap.createScaledBitmap(faceBitmap, INPUT_SIZE, INPUT_SIZE, true)

        val inputBuf = ByteBuffer
            .allocateDirect(1 * INPUT_SIZE * INPUT_SIZE * 3 * Float.SIZE_BYTES)
            .apply { order(ByteOrder.nativeOrder()) }

        // InsightFace standard: NHWC format confirmed by model metadata [1, 112, 112, 3]
        for (y in 0 until INPUT_SIZE) {
            for (x in 0 until INPUT_SIZE) {
                val px = scaled.getPixel(x, y)
                inputBuf.putFloat((((px shr 16) and 0xFF) - 127.5f) / 128f)  // R
                inputBuf.putFloat((((px shr 8) and 0xFF) - 127.5f) / 128f)   // G
                inputBuf.putFloat(((px and 0xFF) - 127.5f) / 128f)            // B
            }
        }
        inputBuf.rewind()

        val output = mutableMapOf<Int, Any>()
        val embeddingArray = Array(1) { FloatArray(EMBEDDING_DIM) }
        output[0] = embeddingArray

        try {
            interpreter.runForMultipleInputsOutputs(arrayOf(inputBuf), output)
            val firstFew = embeddingArray[0].take(5).joinToString(", ")
            Log.d(TAG, "Embedding generated (first 5): $firstFew")
        } catch (e: Exception) {
            Log.e(TAG, "Embedding extraction failed: ${e.message}", e)
            return FloatArray(EMBEDDING_DIM)
        }

        val normalized = l2Normalize(embeddingArray[0])
        val norm = sqrt(normalized.sumOf { (it * it).toDouble() }).toFloat()
        Log.d(TAG, "Embedding extracted: dim=${normalized.size}, L2 norm=$norm")
        return normalized
    }

    // ─────────────────────────────────────────────────────────────
    //  L2 normalisation
    // ─────────────────────────────────────────────────────────────

    private fun l2Normalize(v: FloatArray): FloatArray {
        val norm = sqrt(v.sumOf { (it * it).toDouble() }).toFloat()
        return if (norm > 1e-6f) FloatArray(v.size) { v[it] / norm } else v
    }

    fun close() {
        interpreter?.close()
    }
}
