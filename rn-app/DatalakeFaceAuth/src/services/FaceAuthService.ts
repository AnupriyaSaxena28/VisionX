/**
 * FaceAuthService — JS service layer bridging screens to the native module.
 *
 * ┌─ CameraScreen / EnrollmentScreen / HistoryScreen
 * │         (import FaceAuthService)
 * └─ FaceAuthService
 *         ↓ calls
 *    FaceAuthModule (NativeModule.ts)
 *         ↓ JNI bridge
 *    FaceAuthModule.kt / FaceAuthPackage.kt
 *         ↓ calls
 *    FaceDetector / FaceEmbedder / LivenessDetector  (inference/*.kt)
 *         +  DatabaseManager.kt  (SQLCipher)
 *
 * If the native module is unavailable (e.g., running in Expo Go or Jest),
 * the service falls back to lightweight mocks so the UI renders correctly.
 */

import { Platform } from 'react-native';
import FaceAuthModule, { AuthResult, EnrollResult } from '../NativeModule';

// ── Type re-exports used by screens ──────────────────────────────────────────

export interface AttendanceLog {
  id: string;
  name: string;
  timestamp: string;     // ISO 8601
  score: number;         // auth_score 0–1
  livenessScore: number;
  synced: boolean;
}

// ── Helper: is the native module available? ───────────────────────────────────

const isNativeAvailable = (): boolean => {
  return !!FaceAuthModule && typeof FaceAuthModule.authenticate === 'function';
};

// ── Initialisation ─────────────────────────────────────────────────────────────

let _initialized = false;

/**
 * Call this once from App.tsx (useEffect on mount).
 * Resolves the model directory inside the APK assets folder.
 */
export async function initializeFaceAuth(): Promise<void> {
  console.log('[FaceAuthService] initializeFaceAuth called');
  if (_initialized) return;
  if (!isNativeAvailable()) {
    console.warn('[FaceAuthService] Native module unavailable — running in mock mode');
    _initialized = true;
    return;
  }
  try {
    console.log('[FaceAuthService] Calling FaceAuthModule.initialize...');
    // On Android, TFLite models are bundled under assets/models/
    // The native module reads them from the app's files directory.
    // In production, copy assets to filesDir on first launch.
    const modelPath = Platform.OS === 'android'
      ? '/data/data/com.datalakefaceauth/files/models'
      : `${(process as any).env.HOME}/Library/models`; // iOS placeholder

    await FaceAuthModule.initialize(modelPath);
    _initialized = true;
    console.log('[FaceAuthService] Native module initialized');
  } catch (err) {
    console.error('[FaceAuthService] Initialization failed:', err);
    // Don't throw — fall back to mock mode so UI still works
    _initialized = true;
  }
}

// ── Authentication ─────────────────────────────────────────────────────────────

/**
 * Authenticate a photo taken by VisionCamera.
 * Uses authenticateFromPath to avoid JS-side file I/O.
 *
 * @param photoPath Absolute file path returned by camera.takePhoto()
 */
export async function authenticatePhoto(photoPath: string): Promise<AuthResult> {
  if (!isNativeAvailable()) {
    return mockAuthenticate();
  }
  const result = await FaceAuthModule.authenticateFromPath(photoPath);

  return result;
}

// ── Enrollment ─────────────────────────────────────────────────────────────────

/**
 * Enroll a new face.
 *
 * @param name       Display name for the person.
 * @param imagePaths Array of local file paths (5 captures recommended).
 */
export async function enrollFace(
  name: string,
  imagePaths: string[]
): Promise<{ success: boolean; id: string }> {
  if (!isNativeAvailable()) {
    await delay(1500);
    return { success: true, id: `mock-${Date.now()}` };
  }
  const result: EnrollResult = await FaceAuthModule.enrollFace(name, imagePaths);
  return result;
}

// ── Liveness challenge ─────────────────────────────────────────────────────────

export async function startLivenessChallenge(): Promise<void> {
  if (!isNativeAvailable()) return;
  return FaceAuthModule.startLivenessChallenge();
}

export async function getLivenessChallengeState() {
  if (!isNativeAvailable()) {
    return { step: 'blink' as const, progress: 0 };
  }
  return FaceAuthModule.getLivenessChallengeState();
}

// ── Attendance history (read from native DB via new helper methods) ─────────────

/**
 * Returns recent attendance records.
 * In a future iteration, a dedicated NativeModule method will expose the DB
 * read directly. For now we reconstruct from the pending count + sync time.
 */
export async function getAttendanceLog(): Promise<AttendanceLog[]> {
  // TODO (M3 v2): expose DatabaseManager.getAllAttendanceRecords() via NativeModule
  // and replace mock data below with the real DB query.
  return MOCK_ATTENDANCE;
}

export async function getPendingRecordsCount(): Promise<number> {
  // TODO (M3 v2): expose DatabaseManager.getPendingCount() via NativeModule
  return 0;
}

export async function getLastSyncTime(): Promise<string> {
  // TODO (M3 v2): expose DatabaseManager.getLastSyncTimestamp() via NativeModule
  return new Date(Date.now() - 15 * 60 * 1000).toISOString();
}

export async function clearGallery(): Promise<void> {
  if (!isNativeAvailable()) return;
  await FaceAuthModule.clearGallery();
}

/**
 * Returns the number of enrolled faces in the gallery.
 * If 0, verification will never match — user must enroll first.
 */
export async function getEnrolledCount(): Promise<number> {
  if (!isNativeAvailable()) return 0;
  return FaceAuthModule.getEnrolledCount();
}

// ── Mocks (fallback when native module unavailable) ────────────────────────────

let _mockCallCount = 0;

async function mockAuthenticate(): Promise<AuthResult> {
  await delay(200);
  _mockCallCount++;
  if (_mockCallCount <= 3) return { matched: false, name: '', score: 0, livenessPass: false };
  if (_mockCallCount <= 6) return { matched: false, name: '', score: 0.3, livenessPass: false };
  _mockCallCount = 0;
  return { matched: true, name: 'Aarav Patel', score: 0.94, livenessPass: true };
}

const MOCK_ATTENDANCE: AttendanceLog[] = [
  {
    id: '1', name: 'Aarav Patel',
    timestamp: new Date(Date.now() - 3_600_000).toISOString(),
    score: 0.98, livenessScore: 0.97, synced: true,
  },
  {
    id: '2', name: 'Priya Sharma',
    timestamp: new Date(Date.now() - 1_800_000).toISOString(),
    score: 0.95, livenessScore: 0.96, synced: false,
  },
  {
    id: '3', name: 'Rohan Kumar',
    timestamp: new Date().toISOString(),
    score: 0.91, livenessScore: 0.93, synced: false,
  },
];

const delay = (ms: number) => new Promise(r => setTimeout(() => r(undefined), ms));

// ── Default export (legacy compat) ────────────────────────────────────────────

const FaceAuthService = {
  initializeFaceAuth,
  authenticatePhoto,
  enrollFace,
  startLivenessChallenge,
  getLivenessChallengeState,
  getAttendanceLog,
  getPendingRecordsCount,
  getLastSyncTime,
  clearGallery,
  getEnrolledCount,
};

export default FaceAuthService;
