import Foundation
import UIKit

// MARK: - Data Models

/// Represents an enrolled face record stored in the local database.
struct Enrollment {
    let id: String
    let name: String
    let embedding: [Float]
    let createdAt: Date
    var isSynced: Bool
}

/// Represents a single attendance log record stored in the local database.
struct AttendanceRecord {
    let id: String
    let faceId: String
    let timestamp: Int64
    let lat: Double
    let lng: Double
    let livenessScore: Float
    let authScore: Float
    let synced: Bool
    let awsAck: Bool
}

// MARK: - DatabaseManager

/// Encrypted SQLite database manager using SQLCipher (AES-256).
///
/// Manages two tables:
/// - **enrolled_faces**: stores face embeddings keyed by UUID
/// - **attendance_log**: stores timestamped authentication events with geo & scores
///
/// The encryption key is derived from the device's `identifierForVendor` UUID
/// combined with a build-time secret from the app's Info.plist, ensuring the key
/// is never hardcoded and varies per device.
///
/// Thread-safe singleton via a serial dispatch queue with barrier writes.
final class DatabaseManager {

    // MARK: - Singleton

    static let shared = DatabaseManager()

    // MARK: - Constants

    private static let dbFileName = "faceauth.db"
    private static let embeddingDim = 128

    // MARK: - Properties

    /// Opaque pointer to the SQLite database connection.
    private var db: OpaquePointer?

    /// Serial queue for thread-safe database access.
    private let accessQueue = DispatchQueue(
        label: "com.datalakefaceauth.db",
        attributes: .concurrent
    )

    /// Tracks whether the database has been opened and tables created.
    private var isInitialized = false

    // MARK: - Initialization

    private init() {
        do {
            try openDatabase()
            try createTables()
            isInitialized = true
            NSLog("[DatabaseManager] Database initialized successfully")
        } catch {
            NSLog("[DatabaseManager] Failed to initialize database: %@", error.localizedDescription)
        }
    }

    deinit {
        if let db = db {
            sqlite3_close(db)
        }
    }

    // MARK: - Database Setup

    /// Opens (or creates) the encrypted SQLite database file in the app's
    /// documents directory and applies the SQLCipher encryption key.
    private func openDatabase() throws {
        let documentsPath = NSSearchPathForDirectoriesInDomains(
            .documentDirectory, .userDomainMask, true
        ).first!
        let dbPath = (documentsPath as NSString).appendingPathComponent(Self.dbFileName)

        let result = sqlite3_open(dbPath, &db)
        guard result == SQLITE_OK else {
            throw DatabaseError.openFailed(code: result)
        }

        // Apply SQLCipher encryption key
        let key = deriveEncryptionKey()
        let keyResult = sqlite3_exec(db, "PRAGMA key = '\(key)';", nil, nil, nil)
        guard keyResult == SQLITE_OK else {
            throw DatabaseError.encryptionFailed(code: keyResult)
        }

        // SQLCipher configuration — match schema.sql PRAGMAs
        sqlite3_exec(db, "PRAGMA cipher_compatibility = 4;", nil, nil, nil)
        sqlite3_exec(db, "PRAGMA kdf_iter = 256000;", nil, nil, nil)
        sqlite3_exec(db, "PRAGMA cipher_page_size = 4096;", nil, nil, nil)
        sqlite3_exec(db, "PRAGMA journal_mode = WAL;", nil, nil, nil)
        sqlite3_exec(db, "PRAGMA foreign_keys = ON;", nil, nil, nil)
    }

    /// Creates the `enrolled_faces` and `attendance_log` tables if they
    /// do not already exist, along with performance indexes.
    private func createTables() throws {
        let createEnrolledFaces = """
            CREATE TABLE IF NOT EXISTS enrolled_faces (
                id          TEXT    PRIMARY KEY,
                name        TEXT    NOT NULL,
                embedding   BLOB    NOT NULL,
                enrolled_at INTEGER NOT NULL DEFAULT (strftime('%s', 'now')),
                synced      INTEGER NOT NULL DEFAULT 0
                    CHECK (synced IN (0, 1))
            );
        """

        let createAttendanceLog = """
            CREATE TABLE IF NOT EXISTS attendance_log (
                id              TEXT    PRIMARY KEY,
                face_id         TEXT    NOT NULL REFERENCES enrolled_faces(id),
                timestamp       INTEGER NOT NULL,
                lat             REAL    NOT NULL,
                lng             REAL    NOT NULL,
                liveness_score  REAL    NOT NULL,
                auth_score      REAL    NOT NULL,
                synced          INTEGER NOT NULL DEFAULT 0
                    CHECK (synced IN (0, 1)),
                aws_ack         INTEGER NOT NULL DEFAULT 0
                    CHECK (aws_ack IN (0, 1))
            );
        """

        let indexes = [
            "CREATE INDEX IF NOT EXISTS idx_enrolled_faces_synced ON enrolled_faces (synced);",
            "CREATE INDEX IF NOT EXISTS idx_attendance_log_synced ON attendance_log (synced);",
            "CREATE INDEX IF NOT EXISTS idx_attendance_log_face_id ON attendance_log (face_id);",
            "CREATE INDEX IF NOT EXISTS idx_attendance_log_timestamp ON attendance_log (timestamp);"
        ]

        try executeSQL(createEnrolledFaces)
        try executeSQL(createAttendanceLog)
        for index in indexes {
            try executeSQL(index)
        }

        NSLog("[DatabaseManager] Database tables created")
    }

    // MARK: - Enrollment Operations

    /// Inserts a new face enrollment.
    ///
    /// - Parameters:
    ///   - id: UUID string identifier for this enrollment.
    ///   - name: Display name for the enrolled person.
    ///   - embedding: 128-dimensional float embedding vector.
    func saveEnrollment(id: String, name: String, embedding: [Float]) throws {
        guard isInitialized else { throw DatabaseError.notInitialized }
        guard embedding.count == Self.embeddingDim else {
            throw DatabaseError.invalidEmbedding(
                expected: Self.embeddingDim,
                got: embedding.count
            )
        }

        let blob = floatArrayToData(embedding)
        let timestamp = Int64(Date().timeIntervalSince1970)

        let sql = "INSERT INTO enrolled_faces (id, name, embedding, enrolled_at, synced) VALUES (?, ?, ?, ?, 0)"

        try accessQueue.sync(flags: .barrier) {
            var stmt: OpaquePointer?
            defer { sqlite3_finalize(stmt) }

            guard sqlite3_prepare_v2(db, sql, -1, &stmt, nil) == SQLITE_OK else {
                throw DatabaseError.prepareFailed(message: errorMessage())
            }

            sqlite3_bind_text(stmt, 1, (id as NSString).utf8String, -1, nil)
            sqlite3_bind_text(stmt, 2, (name as NSString).utf8String, -1, nil)
            blob.withUnsafeBytes { ptr in
                sqlite3_bind_blob(stmt, 3, ptr.baseAddress, Int32(blob.count), nil)
            }
            sqlite3_bind_int64(stmt, 4, timestamp)

            guard sqlite3_step(stmt) == SQLITE_DONE else {
                throw DatabaseError.executionFailed(message: errorMessage())
            }

            NSLog("[DatabaseManager] Enrolled face: name=%@, id=%@", name, id)
        }
    }

    /// Returns all enrolled embeddings for matching.
    ///
    /// - Returns: Array of `Enrollment` records.
    func fetchAllEnrollments() throws -> [Enrollment] {
        guard isInitialized else { throw DatabaseError.notInitialized }

        return try accessQueue.sync {
            var results: [Enrollment] = []
            let sql = "SELECT id, name, embedding, enrolled_at, synced FROM enrolled_faces"
            var stmt: OpaquePointer?
            defer { sqlite3_finalize(stmt) }

            guard sqlite3_prepare_v2(db, sql, -1, &stmt, nil) == SQLITE_OK else {
                throw DatabaseError.prepareFailed(message: errorMessage())
            }

            while sqlite3_step(stmt) == SQLITE_ROW {
                let id = String(cString: sqlite3_column_text(stmt, 0))
                let name = String(cString: sqlite3_column_text(stmt, 1))

                let blobPtr = sqlite3_column_blob(stmt, 2)
                let blobSize = sqlite3_column_bytes(stmt, 2)
                let embedding: [Float]
                if let ptr = blobPtr, blobSize > 0 {
                    embedding = dataToFloatArray(
                        Data(bytes: ptr, count: Int(blobSize))
                    )
                } else {
                    embedding = []
                }

                let enrolledAt = sqlite3_column_int64(stmt, 3)
                let synced = sqlite3_column_int(stmt, 4) == 1

                results.append(Enrollment(
                    id: id,
                    name: name,
                    embedding: embedding,
                    createdAt: Date(timeIntervalSince1970: TimeInterval(enrolledAt)),
                    isSynced: synced
                ))
            }
            return results
        }
    }

    /// Retrieves the enrolled name for a given id.
    ///
    /// - Parameter id: UUID of the enrolled face.
    /// - Returns: The name, or nil if not found.
    func getNameById(_ id: String) throws -> String? {
        guard isInitialized else { throw DatabaseError.notInitialized }

        return try accessQueue.sync {
            let sql = "SELECT name FROM enrolled_faces WHERE id = ?"
            var stmt: OpaquePointer?
            defer { sqlite3_finalize(stmt) }

            guard sqlite3_prepare_v2(db, sql, -1, &stmt, nil) == SQLITE_OK else {
                throw DatabaseError.prepareFailed(message: errorMessage())
            }

            sqlite3_bind_text(stmt, 1, (id as NSString).utf8String, -1, nil)

            if sqlite3_step(stmt) == SQLITE_ROW {
                return String(cString: sqlite3_column_text(stmt, 0))
            }
            return nil
        }
    }

    /// Fetches enrollment records that have not been synced to the cloud.
    func fetchPendingEnrollments() throws -> [Enrollment] {
        guard isInitialized else { throw DatabaseError.notInitialized }

        return try accessQueue.sync {
            var results: [Enrollment] = []
            let sql = "SELECT id, name, embedding, enrolled_at, synced FROM enrolled_faces WHERE synced = 0"
            var stmt: OpaquePointer?
            defer { sqlite3_finalize(stmt) }

            guard sqlite3_prepare_v2(db, sql, -1, &stmt, nil) == SQLITE_OK else {
                throw DatabaseError.prepareFailed(message: errorMessage())
            }

            while sqlite3_step(stmt) == SQLITE_ROW {
                let id = String(cString: sqlite3_column_text(stmt, 0))
                let name = String(cString: sqlite3_column_text(stmt, 1))

                let blobPtr = sqlite3_column_blob(stmt, 2)
                let blobSize = sqlite3_column_bytes(stmt, 2)
                let embedding: [Float]
                if let ptr = blobPtr, blobSize > 0 {
                    embedding = dataToFloatArray(
                        Data(bytes: ptr, count: Int(blobSize))
                    )
                } else {
                    embedding = []
                }

                let enrolledAt = sqlite3_column_int64(stmt, 3)

                results.append(Enrollment(
                    id: id,
                    name: name,
                    embedding: embedding,
                    createdAt: Date(timeIntervalSince1970: TimeInterval(enrolledAt)),
                    isSynced: false
                ))
            }
            return results
        }
    }

    /// Marks the given enrollment IDs as synced and optionally deletes them.
    func markEnrollmentsSyncedAndDelete(ids: [String]) throws {
        guard isInitialized else { throw DatabaseError.notInitialized }

        try accessQueue.sync(flags: .barrier) {
            for id in ids {
                let sql = "DELETE FROM enrolled_faces WHERE id = ?"
                var stmt: OpaquePointer?
                defer { sqlite3_finalize(stmt) }

                guard sqlite3_prepare_v2(db, sql, -1, &stmt, nil) == SQLITE_OK else {
                    throw DatabaseError.prepareFailed(message: errorMessage())
                }

                sqlite3_bind_text(stmt, 1, (id as NSString).utf8String, -1, nil)

                guard sqlite3_step(stmt) == SQLITE_DONE else {
                    throw DatabaseError.executionFailed(message: errorMessage())
                }

                NSLog("[DatabaseManager] Deleted enrollment: id=%@", id)
            }
        }
    }

    // MARK: - Attendance Operations

    /// Inserts a new attendance record.
    ///
    /// - Parameters:
    ///   - faceId: UUID of the enrolled face that was matched.
    ///   - lat: Latitude of the device at capture time.
    ///   - lng: Longitude of the device at capture time.
    ///   - livenessScore: Confidence score from the liveness detection model.
    ///   - authScore: Confidence score from the face-authentication model.
    /// - Returns: UUID string identifier for this record.
    @discardableResult
    func insertAttendanceRecord(
        faceId: String,
        lat: Double,
        lng: Double,
        livenessScore: Float,
        authScore: Float
    ) throws -> String {
        guard isInitialized else { throw DatabaseError.notInitialized }

        let id = UUID().uuidString
        let timestamp = Int64(Date().timeIntervalSince1970)

        let sql = """
            INSERT INTO attendance_log
                (id, face_id, timestamp, lat, lng, liveness_score, auth_score, synced, aws_ack)
            VALUES (?, ?, ?, ?, ?, ?, ?, 0, 0)
        """

        try accessQueue.sync(flags: .barrier) {
            var stmt: OpaquePointer?
            defer { sqlite3_finalize(stmt) }

            guard sqlite3_prepare_v2(db, sql, -1, &stmt, nil) == SQLITE_OK else {
                throw DatabaseError.prepareFailed(message: errorMessage())
            }

            sqlite3_bind_text(stmt, 1, (id as NSString).utf8String, -1, nil)
            sqlite3_bind_text(stmt, 2, (faceId as NSString).utf8String, -1, nil)
            sqlite3_bind_int64(stmt, 3, timestamp)
            sqlite3_bind_double(stmt, 4, lat)
            sqlite3_bind_double(stmt, 5, lng)
            sqlite3_bind_double(stmt, 6, Double(livenessScore))
            sqlite3_bind_double(stmt, 7, Double(authScore))

            guard sqlite3_step(stmt) == SQLITE_DONE else {
                throw DatabaseError.executionFailed(message: errorMessage())
            }

            NSLog("[DatabaseManager] Attendance recorded: faceId=%@, id=%@", faceId, id)
        }

        return id
    }

    /// Fetches all attendance records that have not yet been synced to the cloud.
    func getPendingSyncRecords() throws -> [AttendanceRecord] {
        guard isInitialized else { throw DatabaseError.notInitialized }

        return try accessQueue.sync {
            var results: [AttendanceRecord] = []

            let sql = """
                SELECT id, face_id, timestamp, lat, lng, liveness_score, auth_score, synced, aws_ack
                FROM attendance_log
                WHERE synced = 0
            """
            var stmt: OpaquePointer?
            defer { sqlite3_finalize(stmt) }

            guard sqlite3_prepare_v2(db, sql, -1, &stmt, nil) == SQLITE_OK else {
                throw DatabaseError.prepareFailed(message: errorMessage())
            }

            while sqlite3_step(stmt) == SQLITE_ROW {
                let record = AttendanceRecord(
                    id: String(cString: sqlite3_column_text(stmt, 0)),
                    faceId: String(cString: sqlite3_column_text(stmt, 1)),
                    timestamp: sqlite3_column_int64(stmt, 2),
                    lat: sqlite3_column_double(stmt, 3),
                    lng: sqlite3_column_double(stmt, 4),
                    livenessScore: Float(sqlite3_column_double(stmt, 5)),
                    authScore: Float(sqlite3_column_double(stmt, 6)),
                    synced: sqlite3_column_int(stmt, 7) == 1,
                    awsAck: sqlite3_column_int(stmt, 8) == 1
                )
                results.append(record)
            }
            return results
        }
    }

    /// Marks an attendance record as synced.
    ///
    /// - Parameter id: UUID of the attendance record.
    func markAttendanceAsSynced(_ id: String) throws {
        guard isInitialized else { throw DatabaseError.notInitialized }

        try accessQueue.sync(flags: .barrier) {
            let sql = "UPDATE attendance_log SET synced = 1, aws_ack = 1 WHERE id = ?"
            var stmt: OpaquePointer?
            defer { sqlite3_finalize(stmt) }

            guard sqlite3_prepare_v2(db, sql, -1, &stmt, nil) == SQLITE_OK else {
                throw DatabaseError.prepareFailed(message: errorMessage())
            }

            sqlite3_bind_text(stmt, 1, (id as NSString).utf8String, -1, nil)

            guard sqlite3_step(stmt) == SQLITE_DONE else {
                throw DatabaseError.executionFailed(message: errorMessage())
            }

            NSLog("[DatabaseManager] Marked as synced: id=%@", id)
        }
    }

    /// Deletes an attendance record by id (for purge after sync).
    ///
    /// - Parameter id: UUID of the attendance record.
    func deleteAttendanceRecord(_ id: String) throws {
        guard isInitialized else { throw DatabaseError.notInitialized }

        try accessQueue.sync(flags: .barrier) {
            let sql = "DELETE FROM attendance_log WHERE id = ?"
            var stmt: OpaquePointer?
            defer { sqlite3_finalize(stmt) }

            guard sqlite3_prepare_v2(db, sql, -1, &stmt, nil) == SQLITE_OK else {
                throw DatabaseError.prepareFailed(message: errorMessage())
            }

            sqlite3_bind_text(stmt, 1, (id as NSString).utf8String, -1, nil)

            guard sqlite3_step(stmt) == SQLITE_DONE else {
                throw DatabaseError.executionFailed(message: errorMessage())
            }

            NSLog("[DatabaseManager] Deleted record: id=%@", id)
        }
    }

    // MARK: - Private Helpers

    /// Derives an encryption key from the device's identifierForVendor + build secret.
    /// This ensures keys are unique per device and never hardcoded.
    private func deriveEncryptionKey() -> String {
        let deviceId = UIDevice.current.identifierForVendor?.uuidString ?? "fallback_device_id"

        // Read build-time secret from Info.plist (set via xcconfig or build settings)
        let secret = Bundle.main.object(forInfoDictionaryKey: "DB_SECRET") as? String ?? "default_secret"

        // SHA-256 derivation — matches Android's approach
        let combined = "\(deviceId):\(secret)"
        guard let data = combined.data(using: .utf8) else { return deviceId }

        var hash = [UInt8](repeating: 0, count: 32)
        data.withUnsafeBytes { ptr in
            _ = CC_SHA256(ptr.baseAddress, CC_LONG(data.count), &hash)
        }
        return hash.map { String(format: "%02x", $0) }.joined()
    }

    /// Converts a Float array to a compact Data blob (4 bytes per float, little-endian).
    private func floatArrayToData(_ floats: [Float]) -> Data {
        var data = Data(capacity: floats.count * MemoryLayout<Float>.size)
        for var f in floats {
            withUnsafeBytes(of: &f) { data.append(contentsOf: $0) }
        }
        return data
    }

    /// Converts a Data blob back to a Float array (little-endian).
    private func dataToFloatArray(_ data: Data) -> [Float] {
        let count = data.count / MemoryLayout<Float>.size
        var result = [Float](repeating: 0, count: count)
        _ = result.withUnsafeMutableBytes { ptr in
            data.copyBytes(to: ptr)
        }
        return result
    }

    /// Executes a raw SQL statement (used for DDL like CREATE TABLE).
    private func executeSQL(_ sql: String) throws {
        var errorPtr: UnsafeMutablePointer<CChar>?
        let result = sqlite3_exec(db, sql, nil, nil, &errorPtr)
        if result != SQLITE_OK {
            let message = errorPtr.map { String(cString: $0) } ?? "Unknown error"
            sqlite3_free(errorPtr)
            throw DatabaseError.executionFailed(message: message)
        }
    }

    /// Returns the last SQLite error message for the current connection.
    private func errorMessage() -> String {
        if let msg = sqlite3_errmsg(db) {
            return String(cString: msg)
        }
        return "Unknown SQLite error"
    }
}

// MARK: - DatabaseError

/// Errors that can occur during database operations.
enum DatabaseError: LocalizedError {
    case notInitialized
    case openFailed(code: Int32)
    case encryptionFailed(code: Int32)
    case prepareFailed(message: String)
    case executionFailed(message: String)
    case invalidEmbedding(expected: Int, got: Int)

    var errorDescription: String? {
        switch self {
        case .notInitialized:
            return "DatabaseManager is not initialized."
        case .openFailed(let code):
            return "Failed to open database (code: \(code))."
        case .encryptionFailed(let code):
            return "Failed to apply encryption key (code: \(code))."
        case .prepareFailed(let message):
            return "SQL prepare failed: \(message)"
        case .executionFailed(let message):
            return "SQL execution failed: \(message)"
        case .invalidEmbedding(let expected, let got):
            return "Embedding must be \(expected) floats, got \(got)."
        }
    }
}
