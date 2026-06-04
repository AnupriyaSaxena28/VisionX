/**
 * VisionX — React Native Bridge for the FaceAuth Native Module
 *
 * This module exposes the native face-authentication and liveness-detection
 * APIs to the JavaScript / TypeScript layer via React Native's NativeModules
 * bridge.
 */

import { NativeModules } from 'react-native';

// ---------------------------------------------------------------------------
// Type definitions
// ---------------------------------------------------------------------------

/** Result returned after enrolling a new face. */
export interface EnrollResult {
  /** Whether the enrollment succeeded. */
  success: boolean;
  /** UUID of the newly enrolled face record. */
  id: string;
}

/** Result returned after running face authentication on a frame. */
export interface AuthResult {
  /** `true` if the face matched an enrolled identity. */
  matched: boolean;
  /** Name of the matched individual (empty string if no match). */
  name: string;
  /** Cosine-similarity score between the probe and gallery embeddings. */
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
// Native module interface
// ---------------------------------------------------------------------------

/**
 * Contract for the native FaceAuth module exposed to JS.
 *
 * Implementations live in platform-specific code (Swift / Kotlin) and are
 * registered under the name `FaceAuthModule`.
 */
export interface FaceAuthModuleInterface {
  /**
   * Load the on-device ML model from the given path.
   *
   * Must be called once before any other method.
   *
   * @param modelPath - Absolute filesystem path to the TFLite / CoreML model.
   */
  initialize(modelPath: string): Promise<void>;

  /**
   * Enroll a new face under the given name.
   *
   * @param name       - Human-readable label for the enrolled person.
   * @param imagePaths - Array of filesystem paths to face images (≥ 1).
   * @returns An {@link EnrollResult} indicating success and the new face ID.
   */
  enrollFace(name: string, imagePaths: string[]): Promise<EnrollResult>;

  /**
   * Authenticate a single camera frame against the enrolled gallery.
   *
   * @param frameBase64 - Base64-encoded image data from the camera preview.
   * @returns An {@link AuthResult} with match details and liveness status.
   */
  authenticate(frameBase64: string): Promise<AuthResult>;

  /**
   * Begin an interactive liveness challenge (blink → turn → done).
   *
   * The challenge runs asynchronously on the native side.  Poll
   * {@link getLivenessChallengeState} to track progress.
   */
  startLivenessChallenge(): Promise<void>;

  /**
   * Query the current state of a running liveness challenge.
   *
   * @returns A {@link LivenessState} snapshot.
   */
  getLivenessChallengeState(): Promise<LivenessState>;
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
 *
 * await FaceAuthModule.initialize('/path/to/model.tflite');
 * const result = await FaceAuthModule.authenticate(base64Frame);
 * ```
 */
const FaceAuthModule =
  NativeModules.FaceAuthModule as FaceAuthModuleInterface;

export default FaceAuthModule;
