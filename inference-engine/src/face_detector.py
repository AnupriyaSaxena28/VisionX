"""
VisionX Inference Engine — Face Detector
=========================================
BlazeFace-based front-camera face detector.

Pipeline:
  1. Resize input to 128×128, normalise to [−1, 1]
  2. Run BlazeFace TFLite interpreter
  3. Decode the highest-confidence anchor into a bounding box
  4. Crop and resize the face to 96×96 for FaceEmbedder

Falls back to a centre-crop if the model file is absent or confidence < 0.5.

Reference: Bazarevsky et al., "BlazeFace: Sub-millisecond Neural Face Detection
           on Mobile GPUs", 2019 (https://arxiv.org/abs/1907.05047)
"""

from __future__ import annotations

import logging
from pathlib import Path
from typing import Optional, Tuple

import cv2
import numpy as np

try:
    import tflite_runtime.interpreter as tflite
except ImportError:
    import tensorflow as tf
    tflite = tf.lite

from constants import (
    BLAZEFACE_MODEL,
    BLAZE_INPUT_SIZE,
    BLAZE_NUM_ANCHORS,
    BLAZE_CONFIDENCE_THRESHOLD,
    FACE_INPUT_SIZE,
)

logger = logging.getLogger(__name__)

BBox = Tuple[int, int, int, int]   # x1, y1, x2, y2


class FaceDetector:
    """
    BlazeFace face detector with graceful centre-crop fallback.

    Args:
        model_dir: Directory containing ``blazeface.tflite``.
    """

    def __init__(self, model_dir: str | Path) -> None:
        model_path = Path(model_dir) / BLAZEFACE_MODEL
        self._interpreter: Optional[tflite.Interpreter] = None

        if model_path.exists():
            try:
                self._interpreter = tflite.Interpreter(str(model_path))
                self._interpreter.allocate_tensors()
                self._in_idx  = self._interpreter.get_input_details()[0]["index"]
                self._reg_idx = self._interpreter.get_output_details()[0]["index"]  # regressors
                self._cls_idx = self._interpreter.get_output_details()[1]["index"]  # classifiers
                logger.info("BlazeFace loaded from %s", model_path)
            except Exception as exc:
                logger.warning("Failed to load BlazeFace (%s) — using centre-crop fallback", exc)
        else:
            logger.warning("blazeface.tflite not found — using centre-crop fallback")

    # ─────────────────────────────────────────────────────────────────────────

    def detect(self, image: np.ndarray) -> Optional[np.ndarray]:
        """
        Detect the largest face in ``image`` (BGR uint8) and return a
        96×96 BGR aligned crop, or ``None`` if no face found.
        """
        if self._interpreter is not None:
            return self._detect_with_model(image)
        return self._centre_crop(image)

    # ── Model inference ───────────────────────────────────────────────────────

    def _detect_with_model(self, image: np.ndarray) -> Optional[np.ndarray]:
        h, w = image.shape[:2]
        rgb    = cv2.cvtColor(image, cv2.COLOR_BGR2RGB)
        scaled = cv2.resize(rgb, (BLAZE_INPUT_SIZE, BLAZE_INPUT_SIZE))

        # Normalise to [−1, 1]
        inp = (scaled.astype(np.float32) / 127.5) - 1.0
        inp = inp[np.newaxis, ...]   # (1, 128, 128, 3)

        self._interpreter.set_tensor(self._in_idx, inp)
        self._interpreter.invoke()

        regressors  = self._interpreter.get_tensor(self._reg_idx)[0]   # (896, 16)
        classifiers = self._interpreter.get_tensor(self._cls_idx)[0]   # (896, 1)

        scores = self._sigmoid(classifiers[:, 0])
        best   = int(np.argmax(scores))

        if scores[best] < BLAZE_CONFIDENCE_THRESHOLD:
            logger.debug("No face (best score=%.3f)", scores[best])
            return None

        # Decode bounding box: [cx, cy, bw, bh] in 128×128 pixel space
        cx = regressors[best, 0] / BLAZE_INPUT_SIZE
        cy = regressors[best, 1] / BLAZE_INPUT_SIZE
        bw = regressors[best, 2] / BLAZE_INPUT_SIZE
        bh = regressors[best, 3] / BLAZE_INPUT_SIZE

        x1 = max(0, int((cx - bw / 2) * w))
        y1 = max(0, int((cy - bh / 2) * h))
        x2 = min(w, int((cx + bw / 2) * w))
        y2 = min(h, int((cy + bh / 2) * h))

        if x2 <= x1 or y2 <= y1:
            return self._centre_crop(image)

        crop = image[y1:y2, x1:x2]
        logger.debug("Face detected (%.2f) at (%d,%d)→(%d,%d)", scores[best], x1, y1, x2, y2)
        return cv2.resize(crop, (FACE_INPUT_SIZE, FACE_INPUT_SIZE))

    # ── Fallback ──────────────────────────────────────────────────────────────

    @staticmethod
    def _centre_crop(image: np.ndarray) -> np.ndarray:
        """Crop the central 80 % of the image and resize to 96×96."""
        h, w = image.shape[:2]
        mx, my = int(w * 0.10), int(h * 0.10)
        crop = image[my:h - my, mx:w - mx]
        return cv2.resize(crop, (FACE_INPUT_SIZE, FACE_INPUT_SIZE))

    @staticmethod
    def _sigmoid(x: np.ndarray) -> np.ndarray:
        return 1.0 / (1.0 + np.exp(-x))
