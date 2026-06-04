# VisionX — Native Module & Backend Infrastructure

> **Branch:** `feat/m3-backend`
> **Owner:** Member 3 — Native Module / SQLite / AWS Sync

---

## Overview

This directory contains the platform-specific native modules, encrypted database layer, background sync services, and AWS backend infrastructure for the VisionX face-authentication attendance system.

The architecture follows an **offline-first** pattern: attendance records are captured and stored in a local SQLCipher-encrypted database, then synced to AWS when connectivity is available.

```
┌──────────────────────────────────────────────────┐
│  React Native (TypeScript)                       │
│  ┌────────────────────────────────────────────┐  │
│  │        NativeModule.ts (JS Bridge)         │  │
│  └──────────────┬─────────────────────────────┘  │
│                 │ NativeModules.FaceAuthModule    │
├─────────────────┼────────────────────────────────┤
│  Android (Kotlin)          │   iOS (Swift)        │
│  FaceAuthModule.kt         │   FaceAuthModule.swift│
│  FaceAuthPackage.kt        │   FaceAuthModule.m   │
│  DatabaseManager.kt        │   DatabaseManager.swift│
│  SyncService.kt            │   SyncService.swift  │
│  (WorkManager)             │   (BGTaskScheduler)  │
├─────────────────┬──────────┴─────────────────────┤
│           SQLCipher (AES-256)                     │
│  ┌─────────────────┐  ┌──────────────────────┐   │
│  │ enrolled_faces   │  │  attendance_log      │   │
│  └─────────────────┘  └──────────┬───────────┘   │
│                                  │ sync           │
├──────────────────────────────────┼────────────────┤
│  AWS API Gateway + Lambda        │                │
│  POST /attendance ───────────────┘                │
│  ┌───────────────────────────┐                    │
│  │  DynamoDB: attendance_records │                │
│  └───────────────────────────┘                    │
└──────────────────────────────────────────────────┘
```

---

## Directory Structure

```
native-module/
├── NativeModule.ts                  # TypeScript type declarations for JS bridge
├── README.md                        # This file
├── android/
│   ├── FaceAuthModule.kt            # Android native module (RN bridge)
│   ├── FaceAuthPackage.kt           # ReactPackage registration
│   ├── DatabaseManager.kt           # SQLCipher database manager (Kotlin)
│   └── SyncService.kt               # WorkManager-based background sync
├── ios/
│   ├── FaceAuthModule.swift          # iOS native module (Swift)
│   ├── FaceAuthModule.m              # Objective-C bridging header
│   ├── DatabaseManager.swift         # SQLCipher database manager (Swift)
│   └── SyncService.swift             # BGTaskScheduler-based background sync
├── database/
│   └── schema.sql                    # SQLCipher database schema + indexes
└── api-contract/
    ├── API_SPEC.md                   # REST API specification for POST /attendance
    └── lambda_handler.py             # AWS Lambda function (Python)
```

---

## JS API Surface

The TypeScript bridge (`NativeModule.ts`) exposes a unified API that works identically on both Android and iOS:

```typescript
import FaceAuthModule from './NativeModule';

// 1. Initialize — load ML models
await FaceAuthModule.initialize('/path/to/models');

// 2. Enroll a face
const { success, id } = await FaceAuthModule.enrollFace('John Doe', [
  '/path/to/photo1.jpg',
  '/path/to/photo2.jpg',
]);

// 3. Authenticate a camera frame
const result = await FaceAuthModule.authenticate(base64Frame);
// → { matched: true, name: 'John Doe', score: 0.92, livenessPass: true }

// 4. Liveness challenge
await FaceAuthModule.startLivenessChallenge();
const state = await FaceAuthModule.getLivenessChallengeState();
// → { step: 'blink' | 'turn' | 'done' | 'failed', progress: 0–100 }
```

### Method Reference

| Method | Parameters | Returns | Description |
|---|---|---|---|
| `initialize` | `modelPath: string` | `Promise<void>` | Loads TFLite/CoreML models into memory |
| `enrollFace` | `name: string, imagePaths: string[]` | `Promise<EnrollResult>` | Enrolls a face, stores embedding in SQLite |
| `authenticate` | `frameBase64: string` | `Promise<AuthResult>` | Full pipeline: detect → liveness → recognize |
| `startLivenessChallenge` | — | `Promise<void>` | Starts blink+turn challenge sequence |
| `getLivenessChallengeState` | — | `Promise<LivenessState>` | Polls current challenge state |

---

## Encrypted Database (SQLCipher)

### Encryption

- **Algorithm:** AES-256 (SQLCipher v4)
- **KDF:** PBKDF2 with 256,000 iterations
- **Page size:** 4096 bytes
- **Journal mode:** WAL (Write-Ahead Logging)

### Key Derivation

The encryption key is **never hardcoded**. It is derived per-device:

| Platform | Device ID Source | Secret Source | Derivation |
|---|---|---|---|
| Android | `Settings.Secure.ANDROID_ID` | `BuildConfig.DB_SECRET` | SHA-256(`deviceId:secret`) |
| iOS | `UIDevice.identifierForVendor` | `Info.plist → DB_SECRET` | SHA-256(`deviceId:secret`) |

### Schema

#### `enrolled_faces`

| Column | Type | Description |
|---|---|---|
| `id` | TEXT (PK) | UUID v4 |
| `name` | TEXT | Display name |
| `embedding` | BLOB | 128 × float32 = 512 bytes |
| `enrolled_at` | INTEGER | Unix epoch (seconds) |
| `synced` | INTEGER | 0 = pending, 1 = synced |

#### `attendance_log`

| Column | Type | Description |
|---|---|---|
| `id` | TEXT (PK) | UUID v4 |
| `face_id` | TEXT (FK) | References `enrolled_faces.id` |
| `timestamp` | INTEGER | Unix epoch (seconds) |
| `lat` | REAL | Latitude (-90 to 90) |
| `lng` | REAL | Longitude (-180 to 180) |
| `liveness_score` | REAL | 0.0 to 1.0 |
| `auth_score` | REAL | 0.0 to 1.0 |
| `synced` | INTEGER | 0 = pending, 1 = synced |
| `aws_ack` | INTEGER | 0 = not ack'd, 1 = ack'd |

---

## Sync & Purge Service

### Flow

```
┌──────────────┐      ┌──────────────┐      ┌──────────────┐
│  Check Net   │─YES─→│ Fetch Pending│─────→│ Build JSON   │
│  Available?  │      │ Records      │      │ Batch        │
└──────┬───────┘      └──────────────┘      └──────┬───────┘
       │ NO                                        │
       ▼                                           ▼
  ┌────────┐                               ┌──────────────┐
  │ Retry  │                               │  POST to AWS │
  │ Later  │                               │  /attendance  │
  └────────┘                               └──────┬───────┘
                                                   │
                                          ┌────────┴────────┐
                                          │                 │
                                      HTTP 200          HTTP ≠ 200
                                          │                 │
                                          ▼                 ▼
                                   ┌─────────────┐   ┌────────┐
                                   │ Parse ACK'd │   │ Retry  │
                                   │ IDs & Purge │   │ Later  │
                                   └─────────────┘   └────────┘
```

### Platform Implementation

| Feature | Android | iOS |
|---|---|---|
| Scheduler | WorkManager (`PeriodicWorkRequest`) | `BGTaskScheduler` (`BGProcessingTask`) |
| Interval | 15 minutes | 15 minutes (earliest begin) |
| Network constraint | `NetworkType.CONNECTED` | `requiresNetworkConnectivity = true` |
| Retry | WorkManager exponential back-off | 5-minute retry schedule |
| HTTP client | `HttpURLConnection` | `URLSession` |

### Authentication

All sync requests include the `x-api-key` header:

| Platform | Key source |
|---|---|
| Android | `BuildConfig.AWS_API_KEY` |
| iOS | `Info.plist → AWS_API_KEY` |

---

## AWS Backend

### Endpoint

```
POST https://api.visionx.example.com/v1/attendance
```

### Lambda Function

`api-contract/lambda_handler.py` handles:

1. **Parse** the JSON body
2. **Validate** each record (required fields, types, ranges)
3. **Write** valid records to DynamoDB (`attendance_records` table)
4. **Return** acknowledged and failed IDs

See [`API_SPEC.md`](api-contract/API_SPEC.md) for the full request/response schema.

---

## Configuration

### Android (`build.gradle` / `BuildConfig`)

```groovy
android {
    defaultConfig {
        buildConfigField "String", "DB_SECRET", "\"${project.findProperty('DB_SECRET')}\""
        buildConfigField "String", "AWS_SYNC_ENDPOINT", "\"https://api.visionx.example.com/v1/attendance\""
        buildConfigField "String", "AWS_API_KEY", "\"${project.findProperty('AWS_API_KEY')}\""
    }
}
```

### iOS (`Info.plist` / xcconfig)

```xml
<key>DB_SECRET</key>
<string>$(DB_SECRET)</string>
<key>AWS_API_KEY</key>
<string>$(AWS_API_KEY)</string>
```

### iOS Background Task Registration (`AppDelegate.swift`)

```swift
func application(
    _ application: UIApplication,
    didFinishLaunchingWithOptions launchOptions: [UIApplication.LaunchOptionsKey: Any]?
) -> Bool {
    SyncService.register()
    // ...
    return true
}
```

Add to `Info.plist`:

```xml
<key>BGTaskSchedulerPermittedIdentifiers</key>
<array>
    <string>com.datalakefaceauth.sync</string>
</array>
```

### Android Sync Scheduling

```kotlin
// In your Application.onCreate() or after FaceAuthModule.initialize()
SyncService.schedulePeriodic(applicationContext)
```

### React Native Package Registration (`MainApplication.kt`)

```kotlin
override fun getPackages(): List<ReactPackage> =
    PackageList(this).packages.apply {
        add(FaceAuthPackage())
    }
```

---

## Dependencies

### Android

| Dependency | Purpose |
|---|---|
| `net.zetetic:android-database-sqlcipher` | SQLCipher encryption |
| `androidx.work:work-runtime-ktx` | WorkManager for background sync |
| `org.jetbrains.kotlinx:kotlinx-coroutines-android` | Coroutine support |

### iOS

| Dependency | Purpose |
|---|---|
| SQLCipher (CocoaPods / SPM) | SQLCipher encryption |
| `BackgroundTasks.framework` | BGTaskScheduler for background sync |

---

## Security Considerations

- **Database encryption:** All local data is encrypted at rest with AES-256 via SQLCipher.
- **Key derivation:** Encryption keys are derived from device-specific hardware IDs and build-time secrets — never stored in plain text.
- **API authentication:** All cloud sync requests require a device-scoped `x-api-key`.
- **Embedding storage:** Face embeddings are stored as compact BLOBs (512 bytes per face) and are not human-readable.
- **Purge after sync:** Acknowledged attendance records are deleted locally after successful cloud sync to minimize data retention.
