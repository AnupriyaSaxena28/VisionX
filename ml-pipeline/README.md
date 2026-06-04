# ml-pipeline — Offline Face Recognition for Datalake 3.0

## Architecture

```
Camera → BlazeFace (detect) → MobileFaceNet (embed) → Cosine Match → Auth
              1.5 MB               13 MB ONNX              threshold ≥ 0.6
```

## Model: InsightFace MobileFaceNet (`w600k_mbf`)

| Metric | Value |
|--------|-------|
| Architecture | MobileFaceNet (depthwise separable convolutions) |
| Training data | WebFace600K (600,000 identities, 10M+ images) |
| Loss function | ArcFace (additive angular margin) |
| Input | `(1, 3, 112, 112)` NCHW, RGB, `(x-127.5)/127.5` |
| Output | 512-d L2-normalized embedding |
| Format | ONNX |
| Size | 13 MB |

## Benchmark Results

| Condition | Accuracy | TAR@FAR=0.001 | TAR@FAR=0.01 |
|-----------|----------|---------------|--------------|
| Standard | **99.30%** | 0.984 | 0.990 |
| Harsh Sun | **98.69%** | 0.952 | 0.984 |
| Low Light | **99.10%** | 0.910 | 0.986 |
| Shadow | **99.10%** | 0.974 | 0.988 |

**Latency:** 14ms avg | **Model Size:** 13 MB

## Repository Structure

```
ml-pipeline/
├── models/
│   └── w600k_mbf.onnx          ← THE model for app integration
├── enrollment/
│   ├── enroll.py                ← Enroll a person from face images → JSON
│   └── SCHEMA.md               ← JSON/SQLite schema for Member 3
├── benchmarks/
│   ├── accuracy_report.csv      ← LFW results (4 lighting conditions)
│   └── latency_log.csv         ← Inference timing
├── training/
│   └── colab_production_pipeline.py  ← Reproducible Colab script
└── README.md
```

## For Member 2/3 (App Integration)

### React Native

```bash
npm install onnxruntime-react-native
```

```javascript
import { InferenceSession, Tensor } from 'onnxruntime-react-native';

// Load model
const session = await InferenceSession.create('w600k_mbf.onnx');

// Preprocess: 112x112 RGB, normalized to [-1, 1], NCHW layout
const input = new Tensor('float32', preprocessedData, [1, 3, 112, 112]);
const output = await session.run({ input: input });
const embedding = output.values().next().value.data; // 512-d float32

// Compare
const similarity = dotProduct(liveEmbedding, storedEmbedding);
if (similarity >= 0.6) { /* AUTH GRANTED */ }
```

### Enrollment JSON

See [`enrollment/SCHEMA.md`](enrollment/SCHEMA.md) for the full schema + SQLite integration code.

```json
{
  "name": "Virat Kohli",
  "embedding": [0.0123, -0.0456, ...],
  "enrolled_at": "2026-06-04T10:00:00Z"
}
```

## Reproducing the Model

1. Open [Google Colab](https://colab.research.google.com)
2. Paste contents of `training/colab_production_pipeline.py` into a cell
3. Press Play — downloads model, benchmarks on LFW, exports CSVs
4. Download `w600k_mbf.onnx` from Colab file browser
