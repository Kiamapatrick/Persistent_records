# Data Dictionary - Field Readings App

Table: `readings` (Room entity: `ReadingRecord`)

| Field | Type | Unit | Validation | Required | Example | Description |
|-------|------|------|------------|----------|---------|-------------|
| `id` | String (UUID) | - | Valid UUID v4 | Yes | `550e8400-e29b-41d4-a716-446655440000` | Client-generated primary key. Never changes for a record. |
| `siteName` | String | - | 3-40 chars, non-empty | Yes | `"Makongeni Farm"` | Human-readable site/location name. |
| `readingType` | String (enum) | - | One of: `Temperature`, `Humidity`, `Soil Moisture` | Yes | `"Temperature"` | Type of sensor reading. Determines unit and valid range. |
| `value` | double | Per type | Numeric, within type range | Yes | `25.5` | Measured value. Validated against type-specific range. |
| `unit` | String | - | Derived from type | Yes | `"°C"` | Auto-set: Temperature→°C, Humidity→%RH, Soil Moisture→%VWC |
| `notes` | String | - | Max 120 chars | No | `"Morning reading"` | Optional free-text notes. |
| `recordedAt` | long (epoch ms) | ms | Set on first save, immutable | Yes | `1704067200000` | Timestamp when record was first created. |
| `updatedAt` | long (epoch ms) | ms | Updated on every edit | Yes | `1704067500000` | Timestamp of last local modification. |
| `syncState` | String (enum) | - | One of: `SAVED_LOCAL`, `PENDING`, `ACKNOWLEDGED`, `FAILED`, `CONFLICT` | Yes | `"PENDING"` | Synchronization state machine state. |
| `operationId` | String (UUID) | - | Valid UUID v4 | Yes | `a1b2c3d4-e5f6-7890-abcd-ef1234567890` | Generated ONCE per logical save/edit. REUSED on retries. |
| `attemptCount` | int | - | >= 0, incremented on retry | Yes | `2` | Number of sync attempts for current operation. |
| `lastError` | String (nullable) | - | Error message from last failure | No | `"Service unavailable (HTTP 503)"` | Last sync error. Null on success. |
| `serverVersion` | int | - | >= 0, 0 until acknowledged | Yes | `3` | Server-side version. Updated from valid ACK. |
| `localVersion` | int | - | >= 1, incremented on each local edit | Yes | `2` | Client-side edit counter. Starts at 1. |

## Validation Ranges by Reading Type

| Reading Type | Unit | Min | Max | Example Valid | Example Invalid |
|--------------|------|-----|-----|---------------|-----------------|
| Temperature | °C | -10 | 60 | 25.0, -5.5, 60.0 | -11, 61, "abc" |
| Humidity | %RH | 0 | 100 | 50, 0, 100, 99.9 | -1, 101, "high" |
| Soil Moisture | %VWC | 0 | 100 | 45, 0, 100, 33.3 | -5, 105, "wet" |

## Sync State Definitions

| State | Meaning | Next States | Trigger |
|-------|---------|-------------|---------|
| `SAVED_LOCAL` | Written to Room, not yet queued for sync | `PENDING` (on explicit sync) | Initial save with sync disabled |
| `PENDING` | Queued for sync, awaiting server response | `ACKNOWLEDGED`, `FAILED`, `CONFLICT` | Sync attempt started |
| `ACKNOWLEDGED` | Server confirmed with valid ACK | (terminal) | Valid ACK received |
| `FAILED` | Transient failure (retryable) | `PENDING` (retry), `ACKNOWLEDGED` | Timeout, 503, malformed ACK |
| `CONFLICT` | Server has newer version (permanent) | `PENDING` (via "Keep my version") | HTTP 409 response |

## OperationId Lifecycle

1. **New record**: Generate `operationId` → save with `syncState=PENDING`
2. **Edit record**: Generate **NEW** `operationId` → increment `localVersion` → `syncState=PENDING`
3. **Transient failure**: **REUSE** same `operationId` → increment `attemptCount` → `syncState=FAILED`
4. **Retry**: Use same `operationId` → server returns stored ACK (idempotent)
5. **Conflict resolution ("Keep my version")**: Generate **NEW** `operationId` → `syncState=PENDING`
6. **Success**: `operationId` stays, `syncState=ACKNOWLEDGED`, `serverVersion` updated

## Notes

- All timestamps are UTC epoch milliseconds (`System.currentTimeMillis()`)
- UUIDs are generated via `java.util.UUID.randomUUID()`
- `unit` is **derived**, never user-entered
- `localVersion` distinguishes local edits; `serverVersion` tracks server state
- `attemptCount` resets to 0 on new operation (new record or conflict resolution)