"""
VisionX Inference Engine — CLI Entry Point
==========================================
Benchmarks the full authentication pipeline on a test set,
validates latency targets for the hackathon submission,
and prints the accuracy-vs-threshold table required for the slide deck.

Usage:
    python main.py --model-dir ./models --test-dir ./test_images --threshold 0.6

Directory layout expected under --test-dir:
    test_images/
      enroll/
        Aarav/  img1.jpg img2.jpg img3.jpg img4.jpg img5.jpg
        Priya/  ...
      probe/
        correct/        ← same person (genuine pairs)
          Aarav_probe.jpg
        impostor/       ← different person (impostor pairs)
          Unknown_probe.jpg

Outputs:
    - Console table of TAR / FAR / FRR per threshold
    - inference-engine/benchmarks/latency_log.csv   (appended)
    - inference-engine/benchmarks/accuracy_report.csv (overwritten)
"""

from __future__ import annotations

import argparse
import csv
import logging
import os
import sys
import time
from pathlib import Path

import numpy as np

# Make sure src/ is importable when run as `python main.py`
sys.path.insert(0, str(Path(__file__).parent))

from inference_pipeline import InferencePipeline, AuthResult
from similarity import accuracy_at_threshold
from constants import (
    SIMILARITY_THRESHOLD,
    SIMILARITY_THRESHOLD_LENIENT,
    SIMILARITY_THRESHOLD_STRICT,
)

logging.basicConfig(
    level=logging.INFO,
    format="%(asctime)s [%(levelname)s] %(name)s: %(message)s"
)
logger = logging.getLogger("visionx.main")

BENCHMARKS_DIR = Path(__file__).parent.parent / "benchmarks"
BENCHMARKS_DIR.mkdir(parents=True, exist_ok=True)

LATENCY_CSV  = BENCHMARKS_DIR / "latency_log.csv"
ACCURACY_CSV = BENCHMARKS_DIR / "accuracy_report.csv"


# ── Argument parsing ──────────────────────────────────────────────────────────

def parse_args() -> argparse.Namespace:
    p = argparse.ArgumentParser(
        description="VisionX face-auth benchmark runner",
        formatter_class=argparse.ArgumentDefaultsHelpFormatter,
    )
    p.add_argument("--model-dir",  default="./models",       help="Directory with TFLite models")
    p.add_argument("--test-dir",   default="./test_images",  help="Root of test dataset")
    p.add_argument("--threshold",  type=float, default=SIMILARITY_THRESHOLD,
                   help="Primary decision threshold")
    p.add_argument("--warmup",     type=int, default=3,
                   help="Number of warmup frames before timing")
    p.add_argument("--enroll-dir", default=None,
                   help="Override enroll directory (default: <test-dir>/enroll)")
    p.add_argument("--probe-dir",  default=None,
                   help="Override probe directory (default: <test-dir>/probe)")
    return p.parse_args()


# ── Data loading ──────────────────────────────────────────────────────────────

def load_gallery(pipe: InferencePipeline, enroll_dir: Path) -> int:
    """Enroll all persons found under enroll_dir/<PersonName>/*.jpg"""
    count = 0
    if not enroll_dir.exists():
        logger.warning("Enroll dir not found: %s — running with empty gallery", enroll_dir)
        return 0

    for person_dir in sorted(enroll_dir.iterdir()):
        if not person_dir.is_dir():
            continue
        imgs = [str(p) for p in sorted(person_dir.glob("*.jpg"))] + \
               [str(p) for p in sorted(person_dir.glob("*.png"))]
        if not imgs:
            continue
        try:
            pipe.enroll(person_dir.name, imgs)
            count += 1
        except ValueError as e:
            logger.warning("Skipping %s: %s", person_dir.name, e)
    return count


# ── Benchmarking ──────────────────────────────────────────────────────────────

def run_benchmark(pipe: InferencePipeline, probe_dir: Path) -> list[AuthResult]:
    """Run authentication on all probe images. Returns list of AuthResults."""
    results: list[AuthResult] = []
    probe_paths = list(probe_dir.rglob("*.jpg")) + list(probe_dir.rglob("*.png"))

    for img_path in sorted(probe_paths):
        r = pipe.authenticate(str(img_path))
        results.append(r)
        logger.info("  %s → matched=%s name='%s' score=%.3f live=%s total=%.1fms",
                    img_path.name, r.matched, r.name, r.score,
                    r.liveness_pass, r.latency_ms.get("total_ms", 0))
    return results


def write_latency_csv(results: list[AuthResult]) -> None:
    fieldnames = ["timestamp", "detect_ms", "liveness_ms", "embed_ms", "match_ms", "total_ms"]
    is_new = not LATENCY_CSV.exists()
    with open(LATENCY_CSV, "a", newline="") as f:
        w = csv.DictWriter(f, fieldnames=fieldnames)
        if is_new:
            w.writeheader()
        ts = time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime())
        for r in results:
            row = {"timestamp": ts}
            row.update({k: f"{r.latency_ms.get(k, 0):.2f}" for k in fieldnames[1:]})
            w.writerow(row)
    logger.info("Latency data appended to %s", LATENCY_CSV)


def print_accuracy_table(
    pipe: InferencePipeline,
    probe_dir: Path,
    thresholds: list[float],
) -> None:
    """Build genuine/impostor pairs and print TAR/FAR/FRR table."""
    correct_dir   = probe_dir / "correct"
    impostor_dir  = probe_dir / "impostor"

    if not correct_dir.exists() or not impostor_dir.exists():
        logger.info("Skipping accuracy table: correct/ or impostor/ dirs missing")
        return

    import cv2
    pairs = []

    def emb_from_file(p: Path):
        img = cv2.imread(str(p))
        if img is None: return None
        face = pipe.detector.detect(img)
        return pipe.embedder.extract(face) if face is not None else None

    # Genuine pairs: same person
    for img_a in sorted(correct_dir.glob("*.*")):
        ea = emb_from_file(img_a)
        if ea is None: continue
        # Compare against enrolled gallery embedding of the same identity
        name = img_a.stem.rsplit("_", 1)[0]
        enrolled = next((g for g in pipe.gallery if g.name == name), None)
        if enrolled is not None:
            pairs.append((ea, enrolled.embedding, True))

    # Impostor pairs: compare against random enrolled identity
    all_enrolled = pipe.gallery
    for img_b in sorted(impostor_dir.glob("*.*")):
        eb = emb_from_file(img_b)
        if eb is None or not all_enrolled: continue
        pairs.append((eb, all_enrolled[0].embedding, False))

    if not pairs:
        logger.info("No valid pairs found for accuracy table")
        return

    rows = []
    for t in thresholds:
        metrics = accuracy_at_threshold(pairs, t)
        rows.append({
            "threshold": t,
            "TAR":       f"{metrics['TAR']:.4f}",
            "FAR":       f"{metrics['FAR']:.4f}",
            "FRR":       f"{metrics['FRR']:.4f}",
            "accuracy":  f"{metrics['accuracy']:.4f}",
        })

    # Print table
    print(f"\n{'Threshold':>10} {'TAR':>8} {'FAR':>8} {'FRR':>8} {'Accuracy':>10}")
    print("-" * 50)
    for r in rows:
        print(f"{r['threshold']:>10.2f} {r['TAR']:>8} {r['FAR']:>8} {r['FRR']:>8} {r['accuracy']:>10}")

    # Write CSV
    with open(ACCURACY_CSV, "w", newline="") as f:
        w = csv.DictWriter(f, fieldnames=["threshold", "TAR", "FAR", "FRR", "accuracy"])
        w.writeheader()
        w.writerows(rows)
    logger.info("Accuracy report written to %s", ACCURACY_CSV)


# ── Main ──────────────────────────────────────────────────────────────────────

def main() -> None:
    args   = parse_args()
    test   = Path(args.test_dir)
    enroll = Path(args.enroll_dir) if args.enroll_dir else test / "enroll"
    probe  = Path(args.probe_dir)  if args.probe_dir  else test / "probe"

    logger.info("=== VisionX Inference Benchmark ===")
    logger.info("Model dir : %s", args.model_dir)
    logger.info("Test dir  : %s", test)
    logger.info("Threshold : %.2f", args.threshold)

    pipe = InferencePipeline(model_dir=args.model_dir, threshold=args.threshold)

    # Enroll
    n = load_gallery(pipe, enroll)
    logger.info("Enrolled %d person(s)", n)

    # Warmup
    if probe.exists():
        probe_images = list(probe.rglob("*.jpg"))[:args.warmup]
        for p in probe_images:
            pipe.authenticate(str(p))
        logger.info("Warmup complete (%d frames)", len(probe_images))
    else:
        logger.warning("Probe dir not found: %s", probe)
        return

    # Benchmark
    logger.info("--- Running benchmark ---")
    results = run_benchmark(pipe, probe)

    if results:
        total_ms = [r.latency_ms.get("total_ms", 0) for r in results]
        logger.info(
            "Latency — avg: %.1f ms, p50: %.1f ms, p95: %.1f ms, p99: %.1f ms",
            np.mean(total_ms), np.percentile(total_ms, 50),
            np.percentile(total_ms, 95), np.percentile(total_ms, 99),
        )
        matched = [r for r in results if r.matched]
        logger.info("Match rate: %d/%d (%.1f%%)", len(matched), len(results),
                    100 * len(matched) / len(results))
        write_latency_csv(results)

    # Accuracy table (for slide deck)
    print_accuracy_table(
        pipe, probe,
        thresholds=[SIMILARITY_THRESHOLD_LENIENT, SIMILARITY_THRESHOLD, SIMILARITY_THRESHOLD_STRICT]
    )


if __name__ == "__main__":
    main()
