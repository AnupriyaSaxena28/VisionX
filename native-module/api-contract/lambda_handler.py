"""
VisionX Attendance Sync — AWS Lambda Handler

Receives batch JSON of attendance records from the mobile app via API Gateway,
validates each record, and writes valid entries to DynamoDB.
"""

import json
import logging
import os
import uuid
from typing import Any

import boto3
from botocore.exceptions import ClientError

# ---------------------------------------------------------------------------
# Configuration
# ---------------------------------------------------------------------------
TABLE_NAME = os.environ.get("DYNAMODB_TABLE", "attendance_records")
MAX_BATCH_SIZE = 100

logger = logging.getLogger(__name__)
logger.setLevel(logging.INFO)

dynamodb = boto3.resource("dynamodb")
table = dynamodb.Table(TABLE_NAME)

# ---------------------------------------------------------------------------
# Validation helpers
# ---------------------------------------------------------------------------
REQUIRED_FIELDS: list[str] = [
    "id",
    "face_id",
    "timestamp",
    "lat",
    "lng",
    "liveness_score",
    "auth_score",
]


def validate_record(record: dict[str, Any]) -> list[str]:
    """Validate a single attendance record.

    Returns a list of human-readable error strings.  An empty list means the
    record is valid.
    """
    errors: list[str] = []

    # --- required fields ---------------------------------------------------
    for field in REQUIRED_FIELDS:
        if field not in record:
            errors.append(f"Missing required field: '{field}'")

    # If any required field is missing we cannot do type / range checks.
    if errors:
        return errors

    # --- type checks -------------------------------------------------------
    if not isinstance(record["id"], str):
        errors.append("'id' must be a string (UUID)")
    if not isinstance(record["face_id"], str):
        errors.append("'face_id' must be a string (UUID)")
    if not isinstance(record["timestamp"], (int, float)):
        errors.append("'timestamp' must be a number (epoch seconds)")
    if not isinstance(record["lat"], (int, float)):
        errors.append("'lat' must be a number")
    if not isinstance(record["lng"], (int, float)):
        errors.append("'lng' must be a number")
    if not isinstance(record["liveness_score"], (int, float)):
        errors.append("'liveness_score' must be a number")
    if not isinstance(record["auth_score"], (int, float)):
        errors.append("'auth_score' must be a number")

    # If types are wrong, skip range checks.
    if errors:
        return errors

    # --- range checks ------------------------------------------------------
    if not (-90 <= record["lat"] <= 90):
        errors.append(f"'lat' must be between -90 and 90, got {record['lat']}")
    if not (-180 <= record["lng"] <= 180):
        errors.append(f"'lng' must be between -180 and 180, got {record['lng']}")
    if not (0 <= record["liveness_score"] <= 1):
        errors.append(
            f"'liveness_score' must be between 0 and 1, got {record['liveness_score']}"
        )
    if not (0 <= record["auth_score"] <= 1):
        errors.append(
            f"'auth_score' must be between 0 and 1, got {record['auth_score']}"
        )

    return errors


# ---------------------------------------------------------------------------
# Helpers
# ---------------------------------------------------------------------------

def _build_response(status_code: int, body: dict[str, Any]) -> dict[str, Any]:
    """Return an API-Gateway-compatible response dict."""
    return {
        "statusCode": status_code,
        "headers": {
            "Content-Type": "application/json",
            "Access-Control-Allow-Origin": "*",
        },
        "body": json.dumps(body),
    }


def _write_to_dynamo(record: dict[str, Any]) -> None:
    """Put a single validated record into DynamoDB, converting floats to Decimal-safe strings."""
    table.put_item(
        Item={
            "id": record["id"],
            "face_id": record["face_id"],
            "timestamp": int(record["timestamp"]),
            "lat": str(record["lat"]),
            "lng": str(record["lng"]),
            "liveness_score": str(record["liveness_score"]),
            "auth_score": str(record["auth_score"]),
        }
    )


# ---------------------------------------------------------------------------
# Lambda entry point
# ---------------------------------------------------------------------------

def lambda_handler(event: dict[str, Any], context: Any) -> dict[str, Any]:
    """Process a batch of attendance records from the mobile app.

    Parameters
    ----------
    event : dict
        API Gateway proxy integration event.
    context : Any
        Lambda runtime context (unused but required by the signature).

    Returns
    -------
    dict
        API Gateway proxy response with statusCode, headers, and body.
    """
    logger.info("Received event: %s", json.dumps(event, default=str))

    # --- Parse body --------------------------------------------------------
    try:
        body = event.get("body")
        if isinstance(body, str):
            body = json.loads(body)
        if body is None:
            body = event  # Direct invocation (non-proxy integration)
    except (json.JSONDecodeError, TypeError) as exc:
        logger.error("Failed to parse request body: %s", exc)
        return _build_response(400, {
            "statusCode": 400,
            "message": "Invalid JSON in request body",
            "acknowledged_ids": [],
            "failed_ids": [],
        })

    # --- Content-Type check (when coming through API Gateway) --------------
    headers = event.get("headers") or {}
    content_type = headers.get("Content-Type") or headers.get("content-type") or ""
    if headers and "application/json" not in content_type:
        logger.warning("Unsupported Content-Type: %s", content_type)
        return _build_response(400, {
            "statusCode": 400,
            "message": f"Unsupported Content-Type: '{content_type}'. Expected 'application/json'.",
            "acknowledged_ids": [],
            "failed_ids": [],
        })

    # --- Extract records list ----------------------------------------------
    records = body.get("records")
    if not isinstance(records, list):
        return _build_response(400, {
            "statusCode": 400,
            "message": "'records' must be a non-empty array",
            "acknowledged_ids": [],
            "failed_ids": [],
        })

    if len(records) == 0:
        return _build_response(400, {
            "statusCode": 400,
            "message": "'records' array must not be empty",
            "acknowledged_ids": [],
            "failed_ids": [],
        })

    if len(records) > MAX_BATCH_SIZE:
        return _build_response(400, {
            "statusCode": 400,
            "message": f"Batch size exceeds maximum of {MAX_BATCH_SIZE} records",
            "acknowledged_ids": [],
            "failed_ids": [],
        })

    # --- Validate & persist ------------------------------------------------
    acknowledged_ids: list[str] = []
    failed_ids: list[dict[str, Any]] = []

    for idx, record in enumerate(records):
        errors = validate_record(record)
        if errors:
            record_id = record.get("id", f"<unknown at index {idx}>")
            logger.warning("Validation failed for record %s: %s", record_id, errors)
            failed_ids.append({"id": record_id, "errors": errors})
            continue

        try:
            _write_to_dynamo(record)
            acknowledged_ids.append(record["id"])
            logger.info("Successfully wrote record %s", record["id"])
        except ClientError as exc:
            logger.error("DynamoDB error for record %s: %s", record["id"], exc)
            failed_ids.append({
                "id": record["id"],
                "errors": [f"DynamoDB write failed: {exc.response['Error']['Message']}"],
            })

    # --- Build response ----------------------------------------------------
    if failed_ids and not acknowledged_ids:
        return _build_response(422, {
            "statusCode": 422,
            "message": "All records failed validation or persistence",
            "acknowledged_ids": [],
            "failed_ids": failed_ids,
        })

    status = 200
    message = "Batch processed successfully"
    if failed_ids:
        message = "Batch processed with some failures"

    return _build_response(status, {
        "statusCode": status,
        "message": message,
        "acknowledged_ids": acknowledged_ids,
        "failed_ids": failed_ids,
    })
