# Enrollment JSON Schema

This document specifies the exact JSON schema produced by `enroll.py`.
Member 3 must use this schema when parsing and inserting enrolled users into the offline SQLite database on the Android device.

## Schema Definition

```json
{
  "$schema": "http://json-schema.org/draft-07/schema#",
  "type": "object",
  "properties": {
    "name": {
      "type": "string",
      "description": "The unique display name of the enrolled person."
    },
    "embedding": {
      "type": "array",
      "description": "A 512-dimensional L2-normalized float32 vector from MobileFaceNet (InsightFace w600k_mbf).",
      "items": { "type": "number" },
      "minItems": 512,
      "maxItems": 512
    },
    "enrolled_at": {
      "type": "string",
      "format": "date-time",
      "description": "ISO 8601 UTC timestamp of when the enrollment occurred."
    }
  },
  "required": ["name", "embedding", "enrolled_at"]
}
```

## Example Payload

```json
{
  "name": "Virat Kohli",
  "embedding": [
    -0.0345, 0.1239, -0.0984, 0.0412, ...
  ],
  "enrolled_at": "2026-06-03T18:00:00.000000Z"
}
```

## SQLite Integration (Member 3)

### Recommended Table Schema

```sql
CREATE TABLE enrolled_users (
    id          INTEGER PRIMARY KEY AUTOINCREMENT,
    name        TEXT    NOT NULL UNIQUE,
    embedding   BLOB    NOT NULL,           -- 512 x float32 = 2048 bytes
    enrolled_at TEXT    NOT NULL,           -- ISO 8601 UTC
    created_at  TEXT    DEFAULT (datetime('now'))
);
```

### Inserting an Enrollment

```python
import struct, json, sqlite3

with open('Virat_Kohli_enrollment.json') as f:
    data = json.load(f)

embedding_blob = struct.pack(f'{len(data["embedding"])}f', *data['embedding'])

conn = sqlite3.connect('attendance.db')
conn.execute(
    'INSERT INTO enrolled_users (name, embedding, enrolled_at) VALUES (?, ?, ?)',
    (data['name'], embedding_blob, data['enrolled_at'])
)
conn.commit()
```

### Verification (Cosine Similarity)

During live authentication, compute cosine similarity between the live frame's
embedding and all stored embeddings. Since embeddings are L2-normalized, the
dot product equals cosine similarity:

```python
import struct, numpy as np

# Load stored embedding
row = conn.execute('SELECT embedding FROM enrolled_users WHERE name=?', (name,)).fetchone()
stored = np.array(struct.unpack(f'{512}f', row[0]))

# Compare with live embedding
similarity = np.dot(live_embedding, stored)

if similarity >= 0.6:
    print("AUTH GRANTED")
else:
    print("AUTH DENIED")
```

> **Threshold Guideline:**
> - `>= 0.6` → Matched (recommended default)
> - `>= 0.5` → Lenient (fewer false rejections, more false acceptances)
> - `>= 0.7` → Strict (high security, may reject legitimate users in bad lighting)
