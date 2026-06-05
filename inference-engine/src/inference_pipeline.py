"""
VisionX Inference Engine — Full Auth Pipeline
==============================================
Orchestrates the three-stage pipeline used for both Python benchmarking
and as the reference implementation for the Kotlin mobile app:

  Stage 1 — Detection:   BlazeFace   → 96×96 face crop
  Stage 2 — Liveness:    FaceMesh    → EAR check (+ texture fallback)
  Stage 3 — Recognition: MobileFaceNet → 512-d embedding → cosine similarity

Usage:
    from inference_pipeline import InferencePipeline

    pipe = InferencePipeline(model_dir="./models")
    pipe.enroll("Aarav", image_paths=["img1.jpg", "img2.jpg"])

    result = pipe.authenticate("probe.jpg")
    print(result)
    # {'matched': True, 'name': 'Aarav', 'score': 0.87, 'liveness_pass': True}
"""

from __future__ import annotations

import json
import logging
import time
from dataclasses import dataclass, field
from pathlib import Path
from typing import Optional

import cv2
import numpy as np

try:
    import tflite_runtime.interpreter as tflite
except ImportError:
    import tensorflow as tf
    tflite = tf.lite

from constants import (
    MOBILEFACENET_MODEL,
    FACE_INPUT_SIZE,
    EMBEDDING_DIM,
    SIMILARITY_THRESHOLD,
)
from face_detector import FaceDetector
from liveness import LivenessDetector
from similarity import l2_normalize, find_best_match

logger = logging.getLogger(__name__)


# ── Data structures ───────────────────────────────────────────────────────────

@dataclass
class AuthResult:
    matched: bool
    name: str
    score: float
    liveness_pass: bool
    latency_ms: dict = field(default_factory=dict)


@dataclass
class EnrolledFace:
    name: str
    embedding: np.ndarray       # shape (512,) float32, L2-normalised
    enrolled_at: str            # ISO 8601


# ── FaceEmbedder (Python equivalent of FaceEmbedder.kt) ──────────────────────

class FaceEmbedder:
    """
    MobileFaceNet TFLite embedder: 96×96 RGB [0,1] → 512-d L2-normalised vector.
    Falls back to random unit-norm vector when model file absent (CI / testing).
    """

    def __init__(self, model_dir: str | Path) -> None:
        model_path = Path(model_dir) / MOBILEFACENET_MODEL
        self._interpreter: Optional[tflite.Interpreter] = None

        if model_path.exists():
            try:
                self._interpreter = tflite.Interpreter(str(model_path))
                self._interpreter.allocate_tensors()
                self._in_idx  = self._interpreter.get_input_details()[0]["index"]
                self._out_idx = self._interpreter.get_output_details()[0]["index"]
                logger.info("MobileFaceNet loaded from %s", model_path)
            except Exception as exc:
                logger.warning("MobileFaceNet load failed (%s) — random fallback", exc)
        else:
            logger.warning("mobilefacenet.tflite not found — random embeddings (testing only!)")

    def extract(self, face_bgr: np.ndarray) -> np.ndarray:
        """Return a 512-d L2-normalised float32 embedding."""
        if self._interpreter is None:
            # Deterministic random fallback for testing
            rng = np.random.default_rng(hash(face_bgr.tobytes()) % (2**32))
            return l2_normalize(rng.random(EMBEDDING_DIM).astype(np.float32))

        rgb    = cv2.cvtColor(face_bgr, cv2.COLOR_BGR2RGB)
        scaled = cv2.resize(rgb, (FACE_INPUT_SIZE, FACE_INPUT_SIZE))
        inp    = (scaled.astype(np.float32) / 255.0)[np.newaxis, ...]   # [0,1]

        self._interpreter.set_tensor(self._in_idx, inp)
        self._interpreter.invoke()
        emb = self._interpreter.get_tensor(self._out_idx)[0]            # (512,)
        return l2_normalize(emb)


# ── InferencePipeline ─────────────────────────────────────────────────────────

class InferencePipeline:
    """
    End-to-end face authentication pipeline.

    Args:
        model_dir:  Directory containing all three TFLite models.
        threshold:  Cosine-similarity decision boundary (default 0.6).
        gallery:    Pre-loaded gallery; usually left empty and populated via enroll().
    """

    def __init__(
        self,
        model_dir: str | Path,
        threshold: float = SIMILARITY_THRESHOLD,
        gallery: Optional[list[EnrolledFace]] = None,
    ) -> None:
        self.model_dir = Path(model_dir)
        self.threshold = threshold
        self.gallery: list[EnrolledFace] = gallery or []

        self.detector = FaceDetector(model_dir)
        self.liveness = LivenessDetector(model_dir)
        self.embedder = FaceEmbedder(model_dir)

    # ── Enrollment ────────────────────────────────────────────────────────────

    def enroll(self, name: str, image_paths: list[str]) -> EnrolledFace:
        """
        Enroll a new person.  Averages embeddings from all provided images.

        Args:
            name:        Display name.
            image_paths: List of local image file paths (≥ 1 recommended ≥ 5).

        Returns:
            EnrolledFace stored in the in-memory gallery.

        Raises:
            ValueError: If no face can be detected in any of the images.
        """
        embeddings: list[np.ndarray] = []

        for path in image_paths:
            img = cv2.imread(path)
            if img is None:
                logger.warning("Cannot read image: %s", path)
                continue

            face = self.detector.detect(img)
            if face is None:
                logger.warning("No face in: %s", path)
                continue

            embeddings.append(self.embedder.extract(face))

        if not embeddings:
            raise ValueError(f"No face detected in any of the {len(image_paths)} images for '{name}'")

        avg_emb = l2_normalize(np.mean(np.stack(embeddings), axis=0))
        enrolled_at = time.strftime("%Y-%m-%dT%H:%M:%S.000000Z", time.gmtime())

        entry = EnrolledFace(name=name, embedding=avg_emb, enrolled_at=enrolled_at)
        # Replace existing entry with the same name
        self.gallery = [g for g in self.gallery if g.name != name]
        self.gallery.append(entry)
        logger.info("Enrolled '%s' from %d image(s)", name, len(embeddings))
        return entry

    def enroll_from_json(self, json_path: str) -> EnrolledFace:
        """
        Load a pre-computed enrollment JSON produced by M1's enroll.py.

        Expected schema (see ml-pipeline/enrollment/SCHEMA.md):
          { "name": str, "embedding": [512 floats], "enrolled_at": str }
        """
        with open(json_path) as f:
            data = json.load(f)

        emb   = np.array(data["embedding"], dtype=np.float32)
        entry = EnrolledFace(
            name        = data["name"],
            embedding   = l2_normalize(emb),
            enrolled_at = data["enrolled_at"],
        )
        self.gallery = [g for g in self.gallery if g.name != entry.name]
        self.gallery.append(entry)
        logger.info("Loaded enrollment for '%s' from JSON", entry.name)
        return entry

    # ── Authentication ────────────────────────────────────────────────────────

    def authenticate(self, image_path: str) -> AuthResult:
        """
        Full authentication pipeline on a single image.

        Returns:
            AuthResult with matched, name, score, liveness_pass, latency_ms.
        """
        img = cv2.imread(image_path)
        if img is None:
            return AuthResult(matched=False, name="", score=0.0, liveness_pass=False)

        return self.authenticate_frame(img)

    def authenticate_frame(self, frame_bgr: np.ndarray) -> AuthResult:
        """
        Full pipeline on a numpy BGR frame (for video/webcam use).
        """
        latency: dict[str, float] = {}

        # Stage 1: Face detection
        t0   = time.perf_counter()
        face = self.detector.detect(frame_bgr)
        latency["detect_ms"] = (time.perf_counter() - t0) * 1000

        if face is None:
            return AuthResult(matched=False, name="", score=0.0,
                              liveness_pass=False, latency_ms=latency)

        # Stage 2: Liveness
        t0            = time.perf_counter()
        liveness_pass = self.liveness.check_liveness(face)
        latency["liveness_ms"] = (time.perf_counter() - t0) * 1000

        # Stage 3: Embedding + matching
        t0    = time.perf_counter()
        query = self.embedder.extract(face)
        latency["embed_ms"] = (time.perf_counter() - t0) * 1000

        gallery_pairs = [(g.name, g.embedding) for g in self.gallery]
        matched_name, best_score = find_best_match(query, gallery_pairs, self.threshold)
        latency["match_ms"] = (time.perf_counter() - t0) * 1000

        matched = (matched_name is not None) and liveness_pass
        latency["total_ms"] = sum(latency.values())

        logger.debug(
            "Auth: matched=%s name=%s score=%.3f live=%s | %s",
            matched, matched_name, best_score, liveness_pass,
            {k: f"{v:.1f}" for k, v in latency.items()}
        )

        return AuthResult(
            matched      = matched,
            name         = matched_name or "",
            score        = best_score,
            liveness_pass= liveness_pass,
            latency_ms   = latency,
        )
