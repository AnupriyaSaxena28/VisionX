"""
VisionX Inference Engine — Constants
=====================================
All shared configuration values for the Python inference pipeline.
These values must match the M3 Kotlin implementation exactly:
  - INPUT_SIZE / EMBEDDING_DIM must match FaceEmbedder.kt
  - SIMILARITY_THRESHOLD must match FaceAuthModule.kt (COSINE_SIMILARITY_THRESHOLD)
"""

# ── Model file names (relative to MODEL_DIR) ─────────────────────────────────

BLAZEFACE_MODEL     = "blazeface.tflite"
MOBILEFACENET_MODEL = "mobilefacenet.tflite"
FACEMESH_MODEL      = "face_mesh_lite.tflite"
ONNX_MODEL          = "w600k_mbf.onnx"        # M1 training artifact

# ── Face detection ────────────────────────────────────────────────────────────

BLAZE_INPUT_SIZE          = 128   # BlazeFace input: 128×128 RGB
BLAZE_NUM_ANCHORS         = 896
BLAZE_CONFIDENCE_THRESHOLD = 0.5

# ── Face embedding ────────────────────────────────────────────────────────────

FACE_INPUT_SIZE   = 96    # MobileFaceNet input: 96×96 RGB, normalised [0,1]
EMBEDDING_DIM     = 512   # L2-normalised float32 vector (w600k_mbf output)

# ── Liveness detection ────────────────────────────────────────────────────────

FACEMESH_INPUT_SIZE    = 192    # FaceMesh lite input: 192×192 RGB
NUM_LANDMARKS          = 468
EAR_OPEN_THRESHOLD     = 0.20   # Eye Aspect Ratio — below this = closed eye
TEXTURE_VAR_THRESHOLD  = 80.0   # Laplacian variance — below this = print spoof

# ── Similarity / matching ─────────────────────────────────────────────────────
# From M1 SCHEMA.md:
#   >= 0.6 → Matched (recommended default)
#   >= 0.5 → Lenient
#   >= 0.7 → Strict

SIMILARITY_THRESHOLD          = 0.6   # default
SIMILARITY_THRESHOLD_LENIENT  = 0.5
SIMILARITY_THRESHOLD_STRICT   = 0.7

# ── Preprocessing norms (ImageNet) for optional augmentation ─────────────────

MEAN = (0.485, 0.456, 0.406)
STD  = (0.229, 0.224, 0.225)
