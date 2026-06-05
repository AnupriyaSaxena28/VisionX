"""
VisionX Inference Engine — Cosine Similarity Utilities
=======================================================
L2 normalisation and cosine similarity helpers used by the
authentication pipeline to compare face embeddings.

All functions operate on numpy float32 arrays of shape (512,).
"""

from __future__ import annotations

import numpy as np

from constants import (
    EMBEDDING_DIM,
    SIMILARITY_THRESHOLD,
    SIMILARITY_THRESHOLD_LENIENT,
    SIMILARITY_THRESHOLD_STRICT,
)


def l2_normalize(embedding: np.ndarray) -> np.ndarray:
    """
    L2-normalise a 1-D float32 embedding vector.

    After normalisation, ``np.dot(a, b)`` equals cosine_similarity(a, b).

    Args:
        embedding: Raw float32 array of length EMBEDDING_DIM (512).

    Returns:
        Unit-norm float32 array.
    """
    assert embedding.shape == (EMBEDDING_DIM,), (
        f"Expected ({EMBEDDING_DIM},) embedding, got {embedding.shape}"
    )
    norm = np.linalg.norm(embedding)
    return embedding / norm if norm > 1e-6 else embedding


def cosine_similarity(a: np.ndarray, b: np.ndarray) -> float:
    """
    Cosine similarity between two L2-normalised embeddings.

    Since both vectors should already be unit-norm, this is simply
    their dot product.  The explicit norm division is kept for safety.

    Returns:
        Float in [−1, 1].  For face matching, > 0 means same identity.
    """
    assert a.shape == b.shape == (EMBEDDING_DIM,), (
        f"Shape mismatch: a={a.shape}, b={b.shape}"
    )
    dot  = float(np.dot(a, b))
    norm = float(np.linalg.norm(a) * np.linalg.norm(b))
    return dot / norm if norm > 1e-6 else 0.0


def find_best_match(
    query: np.ndarray,
    gallery: list[tuple[str, np.ndarray]],
    threshold: float = SIMILARITY_THRESHOLD,
) -> tuple[str | None, float]:
    """
    Compare ``query`` against all enrolled embeddings and return the
    best match above ``threshold``.

    Args:
        query:     L2-normalised probe embedding (512,).
        gallery:   List of (name, embedding) pairs from the enrolled_faces table.
        threshold: Cosine-similarity decision boundary.
                   Default 0.6 per M1 SCHEMA.md.

    Returns:
        (name, score) if a match is found, (None, best_score) otherwise.
    """
    best_name: str | None = None
    best_score: float     = -1.0

    for name, stored_emb in gallery:
        score = cosine_similarity(query, stored_emb)
        if score > best_score:
            best_score = score
            best_name  = name

    if best_score >= threshold:
        return best_name, best_score
    return None, best_score


def accuracy_at_threshold(
    pairs: list[tuple[np.ndarray, np.ndarray, bool]],
    threshold: float,
) -> dict[str, float]:
    """
    Compute TAR, FAR, FRR for a list of embedding pairs at a given threshold.

    Useful for generating the accuracy-vs-threshold table required for the
    hackathon presentation (Member 4 slide deck).

    Args:
        pairs:     List of (emb_a, emb_b, is_same_person) tuples.
        threshold: Decision boundary.

    Returns:
        Dict with keys: TAR, FAR, FRR, accuracy.
    """
    tp = fp = tn = fn = 0
    for a, b, label in pairs:
        score    = cosine_similarity(a, b)
        positive = score >= threshold
        if label and positive:  tp += 1
        elif label and not positive: fn += 1
        elif not label and positive: fp += 1
        else:                        tn += 1

    total  = tp + fp + tn + fn
    tar    = tp / (tp + fn + 1e-9)   # True-Acceptance Rate
    far    = fp / (fp + tn + 1e-9)   # False-Acceptance Rate
    frr    = fn / (fn + tp + 1e-9)   # False-Rejection Rate
    acc    = (tp + tn) / (total + 1e-9)
    return {"TAR": tar, "FAR": far, "FRR": frr, "accuracy": acc}
