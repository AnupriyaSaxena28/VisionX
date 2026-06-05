/**
 * VisionX — React Native Bridge for the FaceAuth Native Module
 *
 * M3 → M4 contract.  Member 4 imports this file — do NOT change
 * any method signatures without informing Member 4.
 *
 * Registered name on Android (Kotlin): "FaceAuthModule" (FaceAuthPackage.kt)
 * Registered name on iOS (Swift):      "FaceAuthModule" (FaceAuthModule.m)
 */

import { NativeModules } from 'react-native';

// ---------------------------------------------------------------------------
// Type definitions
// ---------------------------------------------------------------------------

/** Result returned after enrolling a new face. */
export interface EnrollResult {
  /** Whether the enrollment succeeded. */
  success: boolean;
  /** UUID of the newly enrolled face record (matches enrolled_faces.id in SQLite). */
  id: string;
}

/** Result returned after running face authentication on a frame. */
export interface AuthResult {
  /** `true` if the face matched an enrolled identity AND liveness passed. */
  matched: boolean;
  /** Name of the matched individual (empty string if no match). */
  name: string;
  /** Cosine-similarity score between probe and gallery embeddings (0–1). */
  score: number;
  /** Whether the liveness check passed for this frame. */
  livenessPass: boolean;
}

/** Represents the current state of an active liveness challenge. */
export interface LivenessState {
  /** The challenge step the user is currently on. */
  step: 'blink' | 'turn' | 'done' | 'failed';
  /** Progress within the current step, from 0 to 1. */
  progress: number;
}

// ---------------------------------------------------------------------------
// Native module interface — the canonical M3→M4 contract
// ---------------------------------------------------------------------------

export interface FaceAuthModuleInterface {
  /**
   * Load on-device ML models from the given directory path.
   * Must be called once before any other method (called in App.tsx useEffect).
   *
   * @param modelPath - Absolute path to the directory containing:
   *                    blazeface.tflite, mobilefacenet.tflite, face_mesh_lite.tflite
   */
  initialize(modelPath: string): Promise<void>;

  /**
   * Enroll a new face under the given name.
   *
   * @param name       - Human-readable label for the enrolled person.
   * @param imagePaths - Array of local file paths to face images (≥ 1, ≤ 10).
   * @returns { success: true, id: "<UUID>" } on success.
   */
  enrollFace(name: string, imagePaths: string[]): Promise<EnrollResult>;

  /**
   * Authenticate a single camera frame against the enrolled gallery.
   * Use this when you have a base64-encoded image (e.g., from a streaming API).
   *
   * @param frameBase64 - Base64-encoded JPEG/PNG image data (no data-URI prefix).
   * @returns { matched, name, score, livenessPass }
   */
  authenticate(frameBase64: string): Promise<AuthResult>;

  /**
   * Authenticate using a local file path — preferred from VisionCamera's takePhoto().
   * Avoids the JS-side cost of reading the file as base64.
   *
   * @param filePath - Absolute filesystem path to the captured photo.
   * @returns The same { matched, name, score, livenessPass } as authenticate().
   */
  authenticateFromPath(filePath: string): Promise<AuthResult>;

  /**
   * Begin an interactive liveness challenge (blink → turn → done).
   * The challenge runs on the native side. Poll getLivenessChallengeState()
   * every 200–500 ms to track progress.
   */
  startLivenessChallenge(): Promise<void>;

  /**
   * Query the current state of a running liveness challenge.
   * @returns A LivenessState snapshot.
   */
  getLivenessChallengeState(): Promise<LivenessState>;

  /**
   * Clear all enrolled faces and attendance logs from the local database.
   */
  clearGallery(): Promise<void>;

  /**
   * Returns the number of enrolled faces in the gallery.
   * Useful for diagnostics — if 0, verification will never match.
   */
  getEnrolledCount(): Promise<number>;
}

// ---------------------------------------------------------------------------
// Module export
// ---------------------------------------------------------------------------

/**
 * Typed reference to the native FaceAuth module.
 *
 * Usage:
 * ```ts
 * import FaceAuthModule from './NativeModule';
 * await FaceAuthModule.initialize('/path/to/models');
 * const result = await FaceAuthModule.authenticateFromPath(photo.path);
 * ```
 */
const FaceAuthModule =
  NativeModules.FaceAuthModule as FaceAuthModuleInterface;

if (FaceAuthModule) {
  const keys = [];
  for (const key in FaceAuthModule) {
    keys.push(key);
  }
  console.log('[NativeModule] FaceAuthModule keys (all):', keys);
} else {
  console.warn('[NativeModule] FaceAuthModule is null!');
}

export default FaceAuthModule;
