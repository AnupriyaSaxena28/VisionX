# VisionX — Inference Engine (Python Reference)

> **Owner:** Member 2 — Inference Pipeline / Benchmarks  
> **Role:** Canonical Python reference for the on-device Android Kotlin pipeline

---

## Overview

The inference engine is a **Python reference implementation** of the VisionX face-authentication pipeline. It mirrors the Kotlin classes in `rn-app/DatalakeFaceAuth/android/.../inference/` so that:

- Thresholds, input sizes, and preprocessing can be validated before mobile integration
- Latency and accuracy benchmarks can be run on a desktop or CI environment
- M3 (Android) and M2 (Python) can compare outputs on the same test images

This module does **not** run on the mobile device. The production app uses TensorFlow Lite directly in Kotlin via `FaceAuthModule.kt`.

```
┌─────────────────────────────────────────────────────────────────────┐
│  inference-engine/ (M2)          rn-app/android/ (M3)               │
│  ─ face_detector.py              ─ FaceDetector.kt                  │
│  ─ liveness.py                   ─ LivenessDetector.kt              │
│  ─ inference_pipeline.py         ─ FaceEmbedder.kt + FaceAuthModule │
│  ─ similarity.py                 ─ cosine match in FaceAuthModule   │
│  ─ constants.py  ◄──────────────►  same thresholds & dimensions   │
└─────────────────────────────────────────────────────────────────────┘
```

---

## Pipeline Architecture

```
Input Image (BGR)
      │
      ▼
┌─────────────┐   128×128 RGB [−1, 1]    confidence ≥ 0.5
│  BlazeFace  │ ────────────────────────► 96×96 face crop
│  (Stage 1)  │   blazeface.tflite
└──────┬──────┘
       │
       ▼
┌─────────────┐   192×192 RGB [0, 1]     EAR ≥ 0.20
│  FaceMesh   │ ────────────────────────► liveness_pass: bool
│  (Stage 2)  │   face_mesh_lite.tflite  (+ Laplacian texture fallback)
└──────┬──────┘
       │
       ▼
┌─────────────┐   96×96 RGB [0, 1]       512-d L2-normalised
│ MobileFaceNet│ ───────────────────────► embedding vector
│  (Stage 3)  │   mobilefacenet.tflite
└──────┬──────┘
       │
       ▼
┌─────────────┐   cosine similarity      score ≥ 0.6 AND liveness?
│   Gallery   │ ────────────────────────► matched / name / score
│   Match     │
└─────────────┘
```

### Decision Rule

Authentication succeeds when **both** conditions are met:

```
matched = (best_cosine_score >= threshold) AND liveness_pass
```

Default threshold: **0.6** (per `ml-pipeline/enrollment/SCHEMA.md` and `FaceAuthModule.kt`).

---

## Directory Structure

```
inference-engine/
├── README.md                    # This file
├── requirements.txt             # Python dependencies (see Setup)
├── benchmark.md                 # Benchmark notes (optional)
├── src/
│   ├── main.py                  # CLI benchmark runner
│   ├── inference_pipeline.py    # Full enroll + authenticate orchestration
│   ├── face_detector.py         # BlazeFace detector (FaceDetector.kt parity)
│   ├── liveness.py              # FaceMesh EAR + texture spoof (LivenessDetector.kt)
│   ├── similarity.py            # L2 normalise, cosine match, TAR/FAR/FRR metrics
│   └── constants.py             # Shared config — must match Kotlin exactly
├── test/
│   └── test_auth.py             # Unit tests (pytest)
└── benchmarks/                  # Generated output (created on first run)
    ├── latency_log.csv          # Per-frame timing (appended)
    └── accuracy_report.csv      # TAR/FAR/FRR per threshold (overwritten)
```

---

## Shared Constants Contract

All values in `src/constants.py` must stay in sync with the Android Kotlin implementation.

| Constant | Value | Kotlin equivalent |
|----------|-------|-------------------|
| `BLAZE_INPUT_SIZE` | 128 | `FaceDetector.kt` |
| `BLAZE_CONFIDENCE_THRESHOLD` | 0.5 | `FaceDetector.kt` |
| `FACE_INPUT_SIZE` | 96 | `FaceEmbedder.kt` |
| `EMBEDDING_DIM` | 512 | `FaceEmbedder.kt` |
| `FACEMESH_INPUT_SIZE` | 192 | `LivenessDetector.kt` |
| `EAR_OPEN_THRESHOLD` | 0.20 | `LivenessDetector.kt` |
| `TEXTURE_VAR_THRESHOLD` | 80.0 | `LivenessDetector.kt` |
| `SIMILARITY_THRESHOLD` | **0.6** | `FaceAuthModule.COSINE_SIMILARITY_THRESHOLD` |
| `SIMILARITY_THRESHOLD_LENIENT` | 0.5 | — |
| `SIMILARITY_THRESHOLD_STRICT` | 0.7 | — |

### Model File Names

| Constant | File | Purpose |
|----------|------|---------|
| `BLAZEFACE_MODEL` | `blazeface.tflite` | Face detection |
| `MOBILEFACENET_MODEL` | `mobilefacenet.tflite` | 512-d embedding |
| `FACEMESH_MODEL` | `face_mesh_lite.tflite` | Liveness (EAR) |
| `ONNX_MODEL` | `w600k_mbf.onnx` | M1 training artifact (not used at runtime) |

---

## Prerequisites

- Python 3.9 or later
- TFLite models in a local `models/` directory (see [Model Setup](#model-setup))
- Optional: test images for benchmarking (see [Test Dataset Layout](#test-dataset-layout))

---

## Setup

### 1. Create a Virtual Environment (recommended)

```bash
cd inference-engine
python -m venv .venv

# Windows
.venv\Scripts\activate

# macOS / Linux
source .venv/bin/activate
```

### 2. Install Dependencies

```bash
pip install numpy opencv-python tensorflow
```

For lightweight TFLite-only inference (no full TensorFlow):

```bash
pip install numpy opencv-python tflite-runtime
```

> `requirements.txt` is currently empty. Install the packages above manually until it is populated.

The code auto-selects the TFLite backend:

```python
try:
    import tflite_runtime.interpreter as tflite
except ImportError:
    import tensorflow as tf
    tflite = tf.lite
```

---

## Model Setup

Place all three TFLite models in a `models/` directory:

```
inference-engine/models/
├── blazeface.tflite
├── mobilefacenet.tflite
└── face_mesh_lite.tflite
```

| Model | Source | Notes |
|-------|--------|-------|
| `w600k_mbf.onnx` | `ml-pipeline/models/` | Training artifact; convert to `mobilefacenet.tflite` for inference |
| `blazeface.tflite` | Convert / obtain separately | ~1.5 MB |
| `face_mesh_lite.tflite` | Convert / obtain separately | FaceMesh lite for EAR |

### Graceful Fallbacks (no models)

When model files are absent, the pipeline still runs for CI and structural testing:

| Component | Fallback behavior |
|-----------|-------------------|
| `FaceDetector` | Centre-crop (80% of image) → 96×96 |
| `FaceEmbedder` | Deterministic random unit-norm 512-d vector |
| `LivenessDetector` | Laplacian texture variance check |

> Fallback mode is for testing only. Benchmarks and accuracy tables require real TFLite models.

---

## Test Dataset Layout

The CLI expects this directory structure under `--test-dir`:

```
test_images/
├── enroll/
│   ├── Aarav/
│   │   ├── img1.jpg
│   │   ├── img2.jpg
│   │   ├── img3.jpg
│   │   ├── img4.jpg
│   │   └── img5.jpg
│   └── Priya/
│       └── ...
└── probe/
    ├── correct/              # Genuine pairs (same person as enrolled)
    │   ├── Aarav_probe.jpg
    │   └── Priya_probe.jpg
    └── impostor/             # Different person (impostor attacks)
        └── Unknown_probe.jpg
```

**Naming convention for accuracy table:** probe filenames in `correct/` should start with the enrolled person's name (e.g. `Aarav_probe.jpg` → matches gallery entry `Aarav`).

Supported image formats: `.jpg`, `.png`

---

## CLI Usage

### Basic Benchmark

```bash
cd inference-engine
python src/main.py \
  --model-dir ./models \
  --test-dir  ./test_images \
  --threshold 0.6
```

### All Options

| Flag | Default | Description |
|------|---------|-------------|
| `--model-dir` | `./models` | Directory containing TFLite model files |
| `--test-dir` | `./test_images` | Root of enroll + probe dataset |
| `--threshold` | `0.6` | Primary cosine-similarity decision boundary |
| `--warmup` | `3` | Warmup frames before timing (excluded from stats) |
| `--enroll-dir` | `<test-dir>/enroll` | Override enrollment directory |
| `--probe-dir` | `<test-dir>/probe` | Override probe directory |

### Example Output

```
=== VisionX Inference Benchmark ===
Enrolled 2 person(s)
--- Running benchmark ---
  Aarav_probe.jpg → matched=True name='Aarav' score=0.871 live=True total=42.3ms
Latency — avg: 41.2 ms, p50: 39.8 ms, p95: 48.1 ms, p99: 52.4 ms
Match rate: 2/3 (66.7%)

 Threshold      TAR      FAR      FRR   Accuracy
--------------------------------------------------
      0.50   0.9800   0.0100   0.0200     0.9850
      0.60   0.9600   0.0050   0.0400     0.9775
      0.70   0.9200   0.0020   0.0800     0.9590
```

### Benchmark Outputs

| File | Mode | Contents |
|------|------|----------|
| `benchmarks/latency_log.csv` | Append | `timestamp`, `detect_ms`, `liveness_ms`, `embed_ms`, `match_ms`, `total_ms` |
| `benchmarks/accuracy_report.csv` | Overwrite | `threshold`, `TAR`, `FAR`, `FRR`, `accuracy` |

---

## Programmatic API

### Full Pipeline

```python
from inference_pipeline import InferencePipeline

pipe = InferencePipeline(model_dir="./models", threshold=0.6)

# Enroll from image files (averages embeddings)
pipe.enroll("Aarav", ["img1.jpg", "img2.jpg", "img3.jpg"])

# Or load a pre-computed M1 enrollment JSON
pipe.enroll_from_json("Aarav_Kohli_enrollment.json")

# Authenticate a single image
result = pipe.authenticate("probe.jpg")
print(result.matched)        # True
print(result.name)           # "Aarav"
print(result.score)          # 0.87
print(result.liveness_pass)  # True
print(result.latency_ms)     # {"detect_ms": 12.1, "liveness_ms": 8.4, ...}
```

### Individual Components

```python
import cv2
from face_detector import FaceDetector
from liveness import LivenessDetector
from inference_pipeline import FaceEmbedder
from similarity import cosine_similarity, find_best_match, l2_normalize

detector = FaceDetector("./models")
liveness = LivenessDetector("./models")
embedder = FaceEmbedder("./models")

img  = cv2.imread("photo.jpg")
face = detector.detect(img)           # 96×96 BGR crop or None
live = liveness.check_liveness(face)    # bool
emb  = embedder.extract(face)           # (512,) float32, L2-normalised

name, score = find_best_match(emb, gallery_pairs, threshold=0.6)
```

### AuthResult Structure

```python
@dataclass
class AuthResult:
    matched: bool
    name: str
    score: float
    liveness_pass: bool
    latency_ms: dict   # detect_ms, liveness_ms, embed_ms, match_ms, total_ms
```

---

## Kotlin Parity Reference

| Python | Kotlin | Location |
|--------|--------|----------|
| `FaceDetector.detect()` | `FaceDetector.detectFace()` | `inference/FaceDetector.kt` |
| `FaceEmbedder.extract()` | `FaceEmbedder.extractEmbedding()` | `inference/FaceEmbedder.kt` |
| `LivenessDetector.check_liveness()` | `LivenessDetector.checkLiveness()` | `inference/LivenessDetector.kt` |
| `find_best_match()` | Inline in `FaceAuthModule.runAuthPipeline()` | `FaceAuthModule.kt` |
| `InferencePipeline.enroll()` | `FaceAuthModule.enrollFace()` | `FaceAuthModule.kt` |
| `InferencePipeline.authenticate()` | `FaceAuthModule.authenticateFromPath()` | `FaceAuthModule.kt` |

### Preprocessing Parity Checklist

| Stage | Python | Kotlin |
|-------|--------|--------|
| BlazeFace input | 128×128 RGB, `(pixel / 127.5) - 1.0` | Same |
| BlazeFace output | 96×96 BGR crop | Same |
| FaceMesh input | 192×192 RGB, `pixel / 255.0` | Same |
| MobileFaceNet input | 96×96 RGB, `pixel / 255.0` | Same |
| Embedding output | 512-d, L2-normalised | Same |
| Match metric | Cosine similarity (dot product on unit vectors) | Same |

---

## Metrics Reference

Used by `similarity.accuracy_at_threshold()` for the slide-deck table:

| Metric | Definition |
|--------|------------|
| **TAR** (True Acceptance Rate) | `TP / (TP + FN)` — genuine users correctly accepted |
| **FAR** (False Acceptance Rate) | `FP / (FP + TN)` — impostors incorrectly accepted |
| **FRR** (False Rejection Rate) | `FN / (FN + TP)` — genuine users incorrectly rejected |
| **Accuracy** | `(TP + TN) / total` |

### Recommended Thresholds

| Threshold | Use case |
|-----------|----------|
| ≥ 0.5 | Lenient — fewer false rejections |
| ≥ **0.6** | **Default** — balanced (production) |
| ≥ 0.7 | Strict — fewer false acceptances |

---

## Testing

```bash
cd inference-engine
pip install pytest

python -m pytest test/ -v
```

`test/test_auth.py` is currently a placeholder. Tests can run without TFLite models thanks to the fallback implementations.

### Manual Smoke Test (no models)

```bash
python -c "
from inference_pipeline import InferencePipeline
pipe = InferencePipeline('./models')
print('Pipeline initialised OK')
"
```

---

## Integration with Other Modules

| Module | Integration point |
|--------|-------------------|
| **M1 — ml-pipeline** | `enroll_from_json()` loads M1's `enrollment/SCHEMA.md` JSON; `w600k_mbf.onnx` is the source model for `mobilefacenet.tflite` |
| **M3 — native-module** | Kotlin classes must match `constants.py`; benchmark results validate mobile latency targets |
| **M4 — rn-app** | No direct dependency; mobile app calls Kotlin `FaceAuthModule`, not this Python code |

See also: [`docs/INTEGRATION.md`](../docs/INTEGRATION.md)

---

## Known Gaps & TODO

| # | Item | Priority |
|---|------|----------|
| 1 | Populate `requirements.txt` with pinned versions | Medium |
| 2 | Implement `test/test_auth.py` with unit tests for `similarity.py` | Medium |
| 3 | Document benchmark results in `benchmark.md` | Medium |
| 4 | Add webcam / video-stream mode via `authenticate_frame()` | Low |
| 5 | ONNX runtime path for direct `w600k_mbf.onnx` inference (dev only) | Low |

---

## Related Documentation

| Document | Path |
|----------|------|
| Monorepo overview | [`../README.md`](../README.md) |
| Integration guide | [`../docs/INTEGRATION.md`](../docs/INTEGRATION.md) |
| ML model & enrollment schema | [`../ml-pipeline/README.md`](../ml-pipeline/README.md) |
| Enrollment JSON contract | [`../ml-pipeline/enrollment/SCHEMA.md`](../ml-pipeline/enrollment/SCHEMA.md) |
| Android Kotlin inference | [`../rn-app/DatalakeFaceAuth/android/.../inference/`](../rn-app/DatalakeFaceAuth/android/app/src/main/java/com/datalakefaceauth/inference/) |
| Native module bridge | [`../native-module/README.md`](../native-module/README.md) |
