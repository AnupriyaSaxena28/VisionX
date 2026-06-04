# VisionX Attendance Sync API Specification

> **Version:** 1.0.0
> **Last Updated:** 2026-06-04

---

## Overview

The VisionX Attendance Sync API allows the mobile application to push locally captured attendance records to the cloud backend in batches. Each record contains face-authentication metadata, geolocation, and liveness verification scores.

---

## Base URL

```
https://api.visionx.example.com/v1
```

---

## Authentication

All requests **must** include a valid API key in the `x-api-key` header. Keys are provisioned per-device during enrollment.

| Header      | Required | Description                          |
| ----------- | -------- | ------------------------------------ |
| `x-api-key` | Yes      | Device-scoped API key for auth       |

---

## Endpoint

### `POST /attendance`

Submit a batch of attendance records for server-side persistence.

---

### Request Headers

| Header         | Required | Value                |
| -------------- | -------- | -------------------- |
| `Content-Type` | Yes      | `application/json`   |
| `x-api-key`    | Yes      | `<your-api-key>`     |

---

### Request Body

The request body **must** be a JSON object containing a `records` array. Each element represents a single attendance event.

```json
{
  "records": [
    {
      "id": "string (UUID v4)",
      "face_id": "string (UUID v4)",
      "timestamp": 1234567890,
      "lat": 28.6139,
      "lng": 77.2090,
      "liveness_score": 0.95,
      "auth_score": 0.88
    }
  ]
}
```

#### Field Descriptions

| Field            | Type     | Required | Description                                                                 | Constraints                   |
| ---------------- | -------- | -------- | --------------------------------------------------------------------------- | ----------------------------- |
| `id`             | `string` | Yes      | Client-generated UUID v4 uniquely identifying this attendance event.        | Must be a valid UUID string.  |
| `face_id`        | `string` | Yes      | UUID of the enrolled face that was matched.                                 | Must reference a known face.  |
| `timestamp`      | `integer`| Yes      | Unix epoch timestamp (seconds) when attendance was captured.                | Must be a positive integer.   |
| `lat`            | `number` | Yes      | Latitude of the device at capture time.                                     | `-90 ≤ lat ≤ 90`             |
| `lng`            | `number` | Yes      | Longitude of the device at capture time.                                    | `-180 ≤ lng ≤ 180`           |
| `liveness_score` | `number` | Yes      | Confidence score from the liveness detection model.                         | `0 ≤ liveness_score ≤ 1`     |
| `auth_score`     | `number` | Yes      | Confidence score from the face-authentication model.                        | `0 ≤ auth_score ≤ 1`         |

---

### Response Schemas

#### `200 OK` — Batch Processed Successfully

Returned when **all** (or some) records in the batch were accepted.

```json
{
  "statusCode": 200,
  "message": "Batch processed successfully",
  "acknowledged_ids": [
    "550e8400-e29b-41d4-a716-446655440000",
    "6fa459ea-ee8a-3ca4-894e-db77e160355e"
  ],
  "failed_ids": []
}
```

| Field              | Type       | Description                                          |
| ------------------ | ---------- | ---------------------------------------------------- |
| `statusCode`       | `integer`  | HTTP status code echoed in the body.                 |
| `message`          | `string`   | Human-readable result summary.                       |
| `acknowledged_ids` | `string[]` | IDs of records that were persisted successfully.     |
| `failed_ids`       | `array`    | IDs (with error details) that failed validation/persistence. |

> **Note:** If some records succeed and others fail, the response is still `200` but `message` will read `"Batch processed with some failures"` and `failed_ids` will be non-empty.

---

#### `400 Bad Request` — Invalid Schema

Returned when the request body cannot be parsed, is missing the `records` array, exceeds batch limits, or has an incorrect `Content-Type`.

```json
{
  "statusCode": 400,
  "message": "'records' must be a non-empty array",
  "acknowledged_ids": [],
  "failed_ids": []
}
```

**Common 400 causes:**

- Request body is not valid JSON.
- `Content-Type` header is not `application/json`.
- `records` key is missing or is not an array.
- `records` array is empty.
- Batch size exceeds the maximum of **100** records.

---

#### `422 Unprocessable Entity` — All Records Failed

Returned when every record in the batch fails validation or persistence.

```json
{
  "statusCode": 422,
  "message": "All records failed validation or persistence",
  "acknowledged_ids": [],
  "failed_ids": [
    {
      "id": "550e8400-e29b-41d4-a716-446655440000",
      "errors": [
        "'liveness_score' must be between 0 and 1, got 1.5"
      ]
    }
  ]
}
```

---

#### `500 Internal Server Error` — Server Error

Returned when an unexpected error occurs on the server side (e.g., DynamoDB unavailable).

```json
{
  "statusCode": 500,
  "message": "Internal server error",
  "acknowledged_ids": [],
  "failed_ids": []
}
```

---

### Error Codes

| Code  | Meaning                    | Description                                                                 |
| ----- | -------------------------- | --------------------------------------------------------------------------- |
| `400` | Bad Request                | Malformed JSON, missing `records`, wrong Content-Type, or batch too large.  |
| `422` | Unprocessable Entity       | All records in the batch failed validation or DynamoDB writes.              |
| `500` | Internal Server Error      | Unexpected server-side failure (database outage, Lambda timeout, etc.).     |

---

## Example Request

```bash
curl -X POST https://api.visionx.example.com/v1/attendance \
  -H "Content-Type: application/json" \
  -H "x-api-key: vx_dev_abc123def456" \
  -d '{
    "records": [
      {
        "id": "550e8400-e29b-41d4-a716-446655440000",
        "face_id": "7c9e6679-7425-40de-944b-e07fc1f90ae7",
        "timestamp": 1717500000,
        "lat": 28.6139,
        "lng": 77.2090,
        "liveness_score": 0.97,
        "auth_score": 0.91
      },
      {
        "id": "6fa459ea-ee8a-3ca4-894e-db77e160355e",
        "face_id": "7c9e6679-7425-40de-944b-e07fc1f90ae7",
        "timestamp": 1717503600,
        "lat": 28.6145,
        "lng": 77.2085,
        "liveness_score": 0.95,
        "auth_score": 0.88
      }
    ]
  }'
```

### Example Response

```json
{
  "statusCode": 200,
  "message": "Batch processed successfully",
  "acknowledged_ids": [
    "550e8400-e29b-41d4-a716-446655440000",
    "6fa459ea-ee8a-3ca4-894e-db77e160355e"
  ],
  "failed_ids": []
}
```

---

## Rate Limiting

| Limit                | Value                |
| -------------------- | -------------------- |
| Requests per minute  | **100**              |
| Max records per batch| **100**              |

When the rate limit is exceeded, the API returns:

```json
{
  "statusCode": 429,
  "message": "Rate limit exceeded. Try again later."
}
```

The response will include a `Retry-After` header indicating the number of seconds to wait.

---

## Notes

### Idempotency

The `id` field in each record serves as an **idempotency key**. Submitting the same `id` more than once will overwrite the previous record with the new data (DynamoDB `PutItem` semantics). Clients should use deterministic, client-generated UUID v4 values.

### Batch Size

The maximum batch size is **100 records**. Requests exceeding this limit are rejected with a `400` response. Clients should partition larger data sets into multiple requests.

### Offline Sync

The mobile app is designed to work offline-first. Records are captured and stored in a local SQLCipher database, then synced to this API when connectivity is available. The `synced` and `aws_ack` flags in the local schema track sync state.

### Data Types

- **Coordinates** (`lat`, `lng`): Stored as strings in DynamoDB to preserve decimal precision.
- **Scores** (`liveness_score`, `auth_score`): Stored as strings in DynamoDB for the same reason.
- **Timestamps**: Stored as integers (Unix epoch seconds).
