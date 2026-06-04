import Foundation
import BackgroundTasks
import UIKit

// MARK: - SyncService

/// Background sync service that periodically uploads pending attendance
/// records to the AWS backend using `BGTaskScheduler`.
///
/// Usage:
///   1. Call `SyncService.register()` in `application(_:didFinishLaunchingWithOptions:)`.
///   2. Add the task identifier to `Info.plist` under `BGTaskSchedulerPermittedIdentifiers`.
///   3. Call `SyncService.scheduleSync()` when there are new records to sync.
final class SyncService {

    // MARK: - Constants

    /// Background task identifier — must match the entry in Info.plist.
    static let taskIdentifier = "com.datalakefaceauth.sync"

    /// AWS API Gateway endpoint for batch attendance sync.
    /// Matches the POST /attendance endpoint documented in API_SPEC.md.
    private static let AWS_ENDPOINT = "https://api.visionx.example.com/v1/attendance"

    /// Device-scoped API key for authentication with the sync endpoint.
    /// Read from Info.plist (set via xcconfig or build settings).
    private static var apiKey: String {
        Bundle.main.object(forInfoDictionaryKey: "AWS_API_KEY") as? String ?? ""
    }

    /// Default timeout for the URLSession data task.
    private static let requestTimeoutSeconds: TimeInterval = 30

    // MARK: - Private

    private init() {} // Static-only API

    // MARK: - Registration

    /// Registers the background processing task with `BGTaskScheduler`.
    /// **Must** be called inside `application(_:didFinishLaunchingWithOptions:)`
    /// before the app finishes launching.
    static func register() {
        BGTaskScheduler.shared.register(
            forTaskWithIdentifier: taskIdentifier,
            using: nil
        ) { task in
            guard let processingTask = task as? BGProcessingTask else { return }
            handleSync(task: processingTask)
        }

        NSLog("[SyncService] Background task registered: %@", taskIdentifier)
    }

    // MARK: - Scheduling

    /// Schedules a `BGProcessingTaskRequest` with a 15-minute earliest begin date
    /// and network connectivity requirement.
    static func scheduleSync() {
        let request = BGProcessingTaskRequest(identifier: taskIdentifier)
        request.earliestBeginDate = Date(timeIntervalSinceNow: 15 * 60) // 15 minutes
        request.requiresNetworkConnectivity = true
        request.requiresExternalPower = false

        do {
            try BGTaskScheduler.shared.submit(request)
            NSLog("[SyncService] Sync scheduled — earliest begin in 15 minutes.")
        } catch {
            NSLog("[SyncService] Failed to schedule sync: %@", error.localizedDescription)
        }
    }

    // MARK: - Task Handler

    /// Handles the background sync task:
    ///   1. Fetches pending (un-synced) attendance records from DatabaseManager.
    ///   2. Builds a JSON batch payload matching the API_SPEC.md contract.
    ///   3. POSTs to the AWS API Gateway endpoint with x-api-key authentication.
    ///   4. On HTTP 200: parses acknowledged IDs, marks synced, and purges locally.
    ///   5. On failure: schedules a retry.
    ///   6. Schedules the next sync cycle.
    static func handleSync(task: BGProcessingTask) {
        // Schedule the next sync so the cycle continues regardless of outcome.
        scheduleSync()

        // Set expiration handler — clean up if the system reclaims time.
        task.expirationHandler = {
            NSLog("[SyncService] Task expired before completion.")
            // Any ongoing URLSession tasks will be cancelled by the system.
        }

        // 1. Fetch pending attendance records
        let pendingRecords: [AttendanceRecord]
        do {
            pendingRecords = try DatabaseManager.shared.getPendingSyncRecords()
        } catch {
            NSLog("[SyncService] Failed to fetch pending records: %@", error.localizedDescription)
            task.setTaskCompleted(success: false)
            return
        }

        // Nothing to sync
        guard !pendingRecords.isEmpty else {
            NSLog("[SyncService] No pending records — sync complete.")
            task.setTaskCompleted(success: true)
            return
        }

        NSLog("[SyncService] Found %d pending record(s)", pendingRecords.count)

        // 2. Build JSON batch payload
        let payload = buildBatchPayload(from: pendingRecords)

        guard let jsonData = try? JSONSerialization.data(withJSONObject: payload, options: []) else {
            NSLog("[SyncService] Failed to serialize JSON payload.")
            task.setTaskCompleted(success: false)
            return
        }

        // 3. POST to AWS endpoint
        guard let url = URL(string: AWS_ENDPOINT) else {
            NSLog("[SyncService] Invalid AWS endpoint URL.")
            task.setTaskCompleted(success: false)
            return
        }

        var request = URLRequest(url: url)
        request.httpMethod = "POST"
        request.setValue("application/json", forHTTPHeaderField: "Content-Type")
        request.setValue(apiKey, forHTTPHeaderField: "x-api-key")
        request.httpBody = jsonData
        request.timeoutInterval = requestTimeoutSeconds

        let session = URLSession(configuration: .default)
        let dataTask = session.dataTask(with: request) { data, response, error in
            defer {
                // Always mark the task as completed so the system knows we are done.
                task.setTaskCompleted(success: error == nil)
            }

            // Handle network error
            if let error = error {
                NSLog("[SyncService] Network error: %@", error.localizedDescription)
                scheduleRetry()
                return
            }

            // Validate HTTP response
            guard let httpResponse = response as? HTTPURLResponse else {
                NSLog("[SyncService] Invalid response type.")
                scheduleRetry()
                return
            }

            guard httpResponse.statusCode == 200 else {
                NSLog("[SyncService] Server returned HTTP %d", httpResponse.statusCode)
                scheduleRetry()
                return
            }

            // 4. Parse acknowledged IDs from response
            // API_SPEC.md returns: { "acknowledged_ids": ["id1", "id2", ...] }
            guard let data = data,
                  let json = try? JSONSerialization.jsonObject(with: data) as? [String: Any],
                  let acknowledgedIds = json["acknowledged_ids"] as? [String] else {
                NSLog("[SyncService] Failed to parse server response.")
                scheduleRetry()
                return
            }

            NSLog("[SyncService] AWS acknowledged %d record(s)", acknowledgedIds.count)

            // 5. Mark synced and delete acknowledged records
            for id in acknowledgedIds {
                do {
                    try DatabaseManager.shared.markAttendanceAsSynced(id)
                    try DatabaseManager.shared.deleteAttendanceRecord(id)
                } catch {
                    NSLog(
                        "[SyncService] Failed to clean up record %@: %@",
                        id, error.localizedDescription
                    )
                }
            }

            NSLog("[SyncService] Sync and purge complete")
        }

        dataTask.resume()

        // Cancel the data task if the background task expires.
        task.expirationHandler = {
            dataTask.cancel()
            NSLog("[SyncService] Task expired — cancelled network request.")
        }
    }

    // MARK: - Helpers

    /// Builds the batch upload payload from an array of attendance records.
    /// Matches the request body schema documented in API_SPEC.md:
    /// ```json
    /// {
    ///   "records": [
    ///     {
    ///       "id": "uuid",
    ///       "face_id": "uuid",
    ///       "timestamp": 1234567890,
    ///       "lat": 28.6139,
    ///       "lng": 77.2090,
    ///       "liveness_score": 0.95,
    ///       "auth_score": 0.88
    ///     }
    ///   ]
    /// }
    /// ```
    private static func buildBatchPayload(from records: [AttendanceRecord]) -> [String: Any] {
        let recordDicts: [[String: Any]] = records.map { record in
            return [
                "id": record.id,
                "face_id": record.faceId,
                "timestamp": record.timestamp,
                "lat": record.lat,
                "lng": record.lng,
                "liveness_score": Double(record.livenessScore),
                "auth_score": Double(record.authScore)
            ]
        }

        return [
            "records": recordDicts
        ]
    }

    /// Schedules an immediate retry by requesting sync with a short delay.
    private static func scheduleRetry() {
        let retryRequest = BGProcessingTaskRequest(identifier: taskIdentifier)
        retryRequest.earliestBeginDate = Date(timeIntervalSinceNow: 5 * 60) // Retry in 5 minutes
        retryRequest.requiresNetworkConnectivity = true

        do {
            try BGTaskScheduler.shared.submit(retryRequest)
            NSLog("[SyncService] Retry scheduled — earliest begin in 5 minutes.")
        } catch {
            NSLog("[SyncService] Failed to schedule retry: %@", error.localizedDescription)
        }
    }
}
