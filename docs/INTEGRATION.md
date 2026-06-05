# VisionX Integration Guide

> **Last updated**: 2026-06-04  
> **Status**: All four member modules merged and wired on `main`.

---

## Architecture Overview

```
┌─────────────────────────────────────────────────────────────────────┐
│  ml-pipeline/  (M1)           inference-engine/  (M2)              │
│  ─ colab_production_pipeline  ─ constants.py                        │
│  ─ enroll.py → JSON           ─ face_detector.py  (BlazeFace)      │
│  ─ SCHEMA.md  (contracts)     ─ liveness.py       (FaceMesh EAR)   │
│  ─ w600k_mbf.onnx             ─ similarity.py     (cosine)         │
│                               ─ inference_pipeline.py              │
│                               ─ main.py  (benchmark CLI)           │
└─────────────────┬───────────────────────┬───────────────────────────┘
                  │ .tflite files          │ Python reference
                  ▼                        ▼
┌─────────────────────────────────────────────────────────────────────┐
│  native-module/ + rn-app/android/  (M3)                            │
│  ─ inference/FaceDetector.kt   (BlazeFace TFLite)                  │
│  ─ inference/FaceEmbedder.kt   (MobileFaceNet TFLite, 512-d)       │
│  ─ inference/LivenessDetector.kt (FaceMesh EAR + texture)          │
│  ─ FaceAuthModule.kt           (React Native bridge)               │
│  ─ DatabaseManager.kt          (SQLCipher AES-256)                 │
│  ─ SyncService.kt              (WorkManager, 15 min, ACK-purge)    │
│  ─ FaceAuthPackage.kt          (RN package registration)           │
└─────────────────┬───────────────────────────────────────────────────┘
                  │ NativeModules.FaceAuthModule (JNI bridge)
                  ▼
┌─────────────────────────────────────────────────────────────────────┐
│  rn-app/  (M4)                                                      │
│  ─ src/NativeModule.ts         (M3→M4 TypeScript contract)         │
│  ─ src/services/FaceAuthService.ts  (calls native module)          │
│  ─ src/screens/CameraScreen.tsx     (authenticateFromPath)         │
│  ─ src/screens/EnrollmentScreen.tsx (enrollFace → native)          │
│  ─ src/screens/HistoryScreen.tsx    (reads attendance log)         │
└─────────────────────────────────────────────────────────────────────┘
```

---

## The 4 Contracts

### M1 → M2/M3: Enrollment Schema
File: [`ml-pipeline/enrollment/SCHEMA.md`](../ml-pipeline/enrollment/SCHEMA.md)

| Field | Type | Value |
|---|---|---|
| `name` | string | Person display name |
| `embedding` | float32[512] | L2-normalised, from w600k_mbf |
| `enrolled_at` | ISO 8601 | UTC timestamp |
| Input size | — | 96×96×3, normalised [0, 1] |
| **Threshold** | — | ≥ 0.6 = match (default) |

> ⚠️ `EMBEDDING_DIM = 512` — an earlier draft of `DatabaseManager.kt` had 128. **Fixed** in this integration.

---

### M2 → M3: Kotlin inference class signatures
All three classes live in `com.datalakefaceauth.inference`:

```kotlin
// FaceDetector.kt
class FaceDetector(modelDir: String) {
    fun detectFace(bitmap: Bitmap): Bitmap?   // returns 96×96 crop or null
    fun close()
}

// FaceEmbedder.kt
class FaceEmbedder(modelDir: String) {
    fun extractEmbedding(faceBitmap: Bitmap): FloatArray  // 512-d L2-normalised
    fun close()
}

// LivenessDetector.kt
class LivenessDetector(modelDir: String) {
    fun checkLiveness(faceBitmap: Bitmap): Boolean
    fun close()
}
```

---

### M3 → M4: TypeScript NativeModule contract
File: [`rn-app/DatalakeFaceAuth/src/NativeModule.ts`](../rn-app/DatalakeFaceAuth/src/NativeModule.ts)

```typescript
interface FaceAuthModuleInterface {
  initialize(modelPath: string): Promise<void>;
  enrollFace(name: string, imagePaths: string[]): Promise<EnrollResult>;
  authenticate(frameBase64: string): Promise<AuthResult>;
  authenticateFromPath(filePath: string): Promise<AuthResult>;  // ← preferred
  startLivenessChallenge(): Promise<void>;
  getLivenessChallengeState(): Promise<LivenessState>;
}
```

**Do not change these signatures** without a team discussion — M4 screens depend on them.

---

### M3 → M4: SQLite Schema
File: [`native-module/database/schema.sql`](../native-module/database/schema.sql)

Two tables:
- `enrolled_faces(id TEXT PK, name TEXT, embedding BLOB 2048 bytes, enrolled_at, synced)`
- `attendance_log(id TEXT PK, face_id→enrolled_faces, timestamp, lat, lng, liveness_score, auth_score, synced, aws_ack)`

`HistoryScreen.tsx` currently reads from the mock in `FaceAuthService.ts`.  
**M3 v2 task**: expose `getAllAttendanceRecords()` and `getPendingCount()` via the native module so the history screen shows real data.

---

## Android Build Setup

### Prerequisites
- Android Studio Hedgehog+
- NDK r26+
- `minSdkVersion 24` (SQLCipher + TFLite GPU)

### BuildConfig values  
Set in `rn-app/DatalakeFaceAuth/android/app/build.gradle`:

| Field | Default | Notes |
|---|---|---|
| `DB_SECRET` | `visionx_db_secret_hackathon_2026` | **Change before release** |
| `AWS_SYNC_ENDPOINT` | `https://api.visionx.example.com/v1/attendance` | Real URL from AWS team |
| `AWS_API_KEY` | `vx_dev_placeholder_key` | Per-device key provisioned at enrollment |

### Model files
Copy TFLite models to the device before running:

```bash
adb shell mkdir -p /data/data/com.datalakefaceauth/files/models
adb push blazeface.tflite         /data/data/com.datalakefaceauth/files/models/
adb push mobilefacenet.tflite     /data/data/com.datalakefaceauth/files/models/
adb push face_mesh_lite.tflite    /data/data/com.datalakefaceauth/files/models/
```

> In production, bundle models in `android/app/src/main/assets/models/` and copy to `filesDir` on first launch using `AssetManager`.

### Key dependencies added to `build.gradle`

```groovy
implementation "net.zetetic:android-database-sqlcipher:4.5.4"  // encrypted DB
implementation "org.tensorflow:tensorflow-lite:2.14.0"          // BlazeFace + MobileFaceNet
implementation "org.tensorflow:tensorflow-lite-support:0.4.4"
implementation "org.tensorflow:tensorflow-lite-gpu:2.14.0"      // GPU delegate
implementation "androidx.work:work-runtime-ktx:2.9.0"           // background sync
```

---

## Python Benchmark (M2)

```bash
cd inference-engine
pip install -r requirements.txt

python src/main.py \
  --model-dir ./models \
  --test-dir  ./test_images \
  --threshold 0.6
```

Output: `benchmarks/latency_log.csv` and `benchmarks/accuracy_report.csv`.

---

## Integration Sequence (Day 5)

```
M1 → export mobilefacenet.tflite (from ONNX via tf.lite.TFLiteConverter)
M2 → benchmark latency on Redmi Note 10; update benchmarks/latency_log.csv
M3 → push models to device; run integration test via adb
M4 → npm install; npx react-native run-android; confirm CameraScreen auth flow
     → take screenshot of history screen for slide deck
All → PR into main; M4 merges after 1 review
```

---

## Known Gaps / TODO

| # | Item | Owner | Priority |
|---|------|-------|----------|
| 1 | Convert w600k_mbf.onnx → mobilefacenet.tflite | M1 | 🔴 Critical |
| 2 | Expose `getAllAttendanceRecords` in NativeModule for real history screen | M3 | 🟡 High |
| 3 | Bundle model files in Android assets + copy-on-first-launch code | M3 | 🔴 Critical |
| 4 | Replace AWS endpoint placeholder with real API Gateway URL | M3 | 🟡 High |
| 5 | GPS permission runtime request in CameraScreen | M4 | 🟢 Medium |
| 6 | iOS Swift module parity (FaceAuthModule.swift) | M3 | 🟢 Medium |
| 7 | Accuracy-vs-threshold table for slide deck | M2 | 🟡 High |
