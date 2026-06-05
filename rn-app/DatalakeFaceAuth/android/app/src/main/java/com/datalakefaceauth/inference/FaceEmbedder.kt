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
        private const val INPUT_SIZE     = 96
        private const val EMBEDDING_DIM  = 512
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
                    Log.d(TAG, "MobileFaceNet model loaded from $modelFile")
                }
            } catch (e: Exception) {
                Log.w(TAG, "Failed to load MobileFaceNet model", e)
                null
            }
        } else {
            Log.w(TAG, "mobilefacenet.tflite not found at $modelDir — embeddings will be zero-vectors")
            null
        }
    }

    /**
     * Extracts a 512-dimensional L2-normalised embedding from [faceBitmap].
     *
     * @param faceBitmap Aligned face crop (any size; will be resized to 96×96).
     * @return L2-normalised float32 array of length 512.
     */
    fun extractEmbedding(faceBitmap: Bitmap): FloatArray {
        if (interpreter == null) {
            Log.w(TAG, "Model not loaded — returning zero embedding")
            return FloatArray(EMBEDDING_DIM)
        }

        val scaled = Bitmap.createScaledBitmap(faceBitmap, INPUT_SIZE, INPUT_SIZE, true)

        val inputBuf = ByteBuffer
            .allocateDirect(1 * INPUT_SIZE * INPUT_SIZE * 3 * Float.SIZE_BYTES)
            .apply { order(ByteOrder.nativeOrder()) }

        for (y in 0 until INPUT_SIZE) {
            for (x in 0 until INPUT_SIZE) {
                val px = scaled.getPixel(x, y)
                inputBuf.putFloat(((px shr 16) and 0xFF) / 255f)  // R
                inputBuf.putFloat(((px shr 8) and 0xFF) / 255f)   // G
                inputBuf.putFloat((px and 0xFF) / 255f)            // B
            }
        }
        inputBuf.rewind()

        val output = Array(1) { FloatArray(EMBEDDING_DIM) }
        try {
            interpreter.run(inputBuf, output)
        } catch (e: Exception) {
            Log.e(TAG, "Embedding extraction failed", e)
            return FloatArray(EMBEDDING_DIM)
        }

        return l2Normalize(output[0])
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
