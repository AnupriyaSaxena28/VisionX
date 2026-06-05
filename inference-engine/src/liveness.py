"""
VisionX Inference Engine — Liveness Detection
==============================================
Two-signal liveness checker:

  1. FaceMesh EAR (Eye Aspect Ratio) — live faces have open eyes (EAR > 0.20).
  2. Laplacian-variance texture analysis — print/screen attacks have low texture.

Mirrors LivenessDetector.kt logic so Python benchmarks are comparable to
the on-device results.

Reference:
  Soukupová & Čech, "Real-Time Eye Blink Detection using Facial Landmarks"
  (CVWW 2016) — EAR formula and landmark indices.
"""

from __future__ import annotations

import logging
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
    FACEMESH_MODEL,
    FACEMESH_INPUT_SIZE,
    NUM_LANDMARKS,
    EAR_OPEN_THRESHOLD,
    TEXTURE_VAR_THRESHOLD,
)

logger = logging.getLogger(__name__)

# MediaPipe FaceMesh canonical eye landmark indices (same as LivenessDetector.kt)
_LEFT_EYE  = [33, 160, 158, 133, 153, 144]
_RIGHT_EYE = [362, 385, 387, 263, 373, 380]


class LivenessDetector:
    """
    Liveness detector: FaceMesh EAR + texture variance.

    Args:
        model_dir: Directory containing ``face_mesh_lite.tflite``.
    """

    def __init__(self, model_dir: str | Path) -> None:
        model_path = Path(model_dir) / FACEMESH_MODEL
        self._interpreter: Optional[tflite.Interpreter] = None

        if model_path.exists():
            try:
                self._interpreter = tflite.Interpreter(str(model_path))
                self._interpreter.allocate_tensors()
                self._in_idx  = self._interpreter.get_input_details()[0]["index"]
                self._lm_idx  = self._interpreter.get_output_details()[0]["index"]  # landmarks
                self._conf_idx = self._interpreter.get_output_details()[1]["index"] # confidence
                logger.info("FaceMesh lite loaded from %s", model_path)
            except Exception as exc:
                logger.warning("FaceMesh load failed (%s) — texture fallback", exc)
        else:
            logger.warning("face_mesh_lite.tflite not found — texture fallback active")

    # ─────────────────────────────────────────────────────────────────────────

    def check_liveness(self, face_bgr: np.ndarray) -> bool:
        """
        Returns True if ``face_bgr`` represents a live face.

        Args:
            face_bgr: 96×96 (or any size) BGR face crop.
        """
        if self._interpreter is not None:
            return self._check_with_facemesh(face_bgr)
        return self._check_texture_variance(face_bgr)

    # ── FaceMesh EAR ─────────────────────────────────────────────────────────

    def _check_with_facemesh(self, face_bgr: np.ndarray) -> bool:
        rgb    = cv2.cvtColor(face_bgr, cv2.COLOR_BGR2RGB)
        scaled = cv2.resize(rgb, (FACEMESH_INPUT_SIZE, FACEMESH_INPUT_SIZE))
        inp    = (scaled.astype(np.float32) / 255.0)[np.newaxis, ...]

        self._interpreter.set_tensor(self._in_idx, inp)
        self._interpreter.invoke()

        conf = float(self._interpreter.get_tensor(self._conf_idx)[0, 0])
        if conf < 0.5:
            logger.debug("FaceMesh confidence too low (%.2f)", conf)
            return False

        lm = self._interpreter.get_tensor(self._lm_idx)[0]  # (468, 3)

        left_ear  = self._compute_ear(lm, _LEFT_EYE)
        right_ear = self._compute_ear(lm, _RIGHT_EYE)
        avg_ear   = (left_ear + right_ear) / 2.0

        logger.debug("EAR: L=%.3f R=%.3f avg=%.3f threshold=%.2f",
                     left_ear, right_ear, avg_ear, EAR_OPEN_THRESHOLD)
        return avg_ear >= EAR_OPEN_THRESHOLD

    @staticmethod
    def _compute_ear(landmarks: np.ndarray, eye_idx: list[int]) -> float:
        """
        Eye Aspect Ratio using 6 landmark points:
          EAR = (‖p2−p6‖ + ‖p3−p5‖) / (2 × ‖p1−p4‖)
        """
        p = landmarks[eye_idx, :2]   # only x, y

        def dist(a: np.ndarray, b: np.ndarray) -> float:
            return float(np.linalg.norm(a - b))

        v1 = dist(p[1], p[5])
        v2 = dist(p[2], p[4])
        h  = dist(p[0], p[3])
        return (v1 + v2) / (2.0 * h) if h > 1e-6 else 0.0

    # ── Texture variance ──────────────────────────────────────────────────────

    @staticmethod
    def _check_texture_variance(face_bgr: np.ndarray) -> bool:
        """
        Laplacian-variance anti-spoofing.
        A live face has high spatial frequency; print/screen attacks are blurrier.
        """
        gray = cv2.cvtColor(face_bgr, cv2.COLOR_BGR2GRAY)
        lap  = cv2.Laplacian(gray, cv2.CV_64F)
        var  = float(lap.var())
        logger.debug("Texture variance (Laplacian): %.1f threshold=%.1f", var, TEXTURE_VAR_THRESHOLD)
        return var >= TEXTURE_VAR_THRESHOLD
