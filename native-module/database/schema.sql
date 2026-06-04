-- ==========================================================================
-- VisionX — Local SQLCipher Database Schema
-- ==========================================================================
-- This schema defines the on-device tables used by the React Native
-- application to persist enrolled face data and attendance logs.  The
-- database is encrypted with SQLCipher; the PRAGMAs below configure the
-- cipher settings and must be executed immediately after opening the
-- database connection.
-- ==========================================================================

-- --------------------------------------------------------------------------
-- SQLCipher configuration
-- --------------------------------------------------------------------------
PRAGMA cipher_compatibility = 4;          -- Use SQLCipher v4 defaults
PRAGMA kdf_iter          = 256000;        -- PBKDF2 iterations
PRAGMA cipher_page_size  = 4096;          -- Page size in bytes
PRAGMA journal_mode      = WAL;           -- Write-Ahead Logging for concurrency
PRAGMA foreign_keys      = ON;            -- Enforce FK constraints

-- --------------------------------------------------------------------------
-- Table: enrolled_faces
-- --------------------------------------------------------------------------
-- Stores the face embeddings captured during enrollment.  Each row
-- represents a single enrolled individual.
-- --------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS enrolled_faces (
    id          TEXT    PRIMARY KEY,
    name        TEXT    NOT NULL,
    embedding   BLOB    NOT NULL,
    enrolled_at INTEGER NOT NULL DEFAULT (strftime('%s', 'now')),
    synced      INTEGER NOT NULL DEFAULT 0
        CHECK (synced IN (0, 1))
);

-- --------------------------------------------------------------------------
-- Table: attendance_log
-- --------------------------------------------------------------------------
-- Records every authentication / attendance event.  References the
-- enrolled face that was matched.
-- --------------------------------------------------------------------------
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

-- --------------------------------------------------------------------------
-- Indexes
-- --------------------------------------------------------------------------
-- Speed up queries that filter by sync state, face reference, or time range.
-- --------------------------------------------------------------------------
CREATE INDEX IF NOT EXISTS idx_enrolled_faces_synced
    ON enrolled_faces (synced);

CREATE INDEX IF NOT EXISTS idx_attendance_log_synced
    ON attendance_log (synced);

CREATE INDEX IF NOT EXISTS idx_attendance_log_face_id
    ON attendance_log (face_id);

CREATE INDEX IF NOT EXISTS idx_attendance_log_timestamp
    ON attendance_log (timestamp);
