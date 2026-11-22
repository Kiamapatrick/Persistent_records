# Persistent Records - JKUAT ICS 2300 Lab_08_hm8

A Field Readings form app demonstrating persistent local storage with Room, mock API synchronization with offline recovery, and comprehensive evidence logging.

## Overview

This app implements a "Field Readings" form for synthetic sensor data (Temperature, Humidity, Soil Moisture) with:
- **Local persistence** via Room/SQLite with full transaction support
- **Mock sync service** with deterministic behaviors (NORMAL, UNAVAILABLE, LOST_RESPONSE, CONFLICT, MALFORMED_ACK)
- **State machine sync client** handling retries, idempotency, conflicts, and malformed acknowledgements
- **Evidence log** for capturing test evidence (E1, E2, ...)

All data is **synthetic**. The server is a **deterministic MOCK** - no real network calls.

## Build & Run

### Requirements
- Android Studio (tested with Ladybug 2024.2+)
- JDK 11+
- Android SDK 24+ (minSdk 24, targetSdk 37)

### Steps
```bash
# Clone
git clone https://github.com/Kiamapatrick/Persistent_records.git
cd Persistent_records

# Build debug APK
./gradlew assembleDebug

# Run tests
./gradlew test              # Local JUnit tests
./gradlew connectedAndroidTest  # Instrumented tests (requires emulator/device)
```

### Run on Device/Emulator
1. Open in Android Studio
2. Select target device/emulator
3. Click Run (Shift+F10)

## Using the App

### Main Screen (Form + List)
- **Form**: Enter site name (3-40 chars), select reading type, enter value (validated per type), optional notes (max 120 chars)
- **Save**: Writes to Room in a transaction. Shows "Saved on this device (pending sync)" ONLY after commit.
- **List**: Shows all records with colored sync state badges. Tap to edit.

### Mock Server Mode (Debug Card)
Select server behavior:
| Mode | Behavior |
|------|----------|
| **NORMAL** | Accepts record, returns valid ack `{operationId, recordId, status:"ACK", serverVersion}` |
| **UNAVAILABLE** | Throws transient error (simulates HTTP 503/no connection) |
| **LOST_RESPONSE** | Server commits record but drops response (timeout). Retry with same `operationId` returns stored ack - **no duplicate** |
| **CONFLICT** | Returns HTTP 409 - server has newer `serverVersion`. Requires manual resolution ("Keep my version") |
| **MALFORMED_ACK** | Returns invalid ack (missing fields/wrong operationId). Client validates and rejects |

### Test Hooks
- **Simulate local write failure**: Switch to make DB writes throw. Demonstrates "failed write preserves the draft".
- **Mock server record count**: Shows server-side record count to verify no duplicates on retry.

### Sync
- **Sync Pending** button: Processes all records in `PENDING` or `FAILED` state.
- **Offline** = UNAVAILABLE mode or airplane mode.
- **No auto-sync** - manual only for controllable demos.

### Evidence Log
- FAB (floating action button) or Menu → Evidence Log
- Shows last ~100 HM8-tagged log events with evidence IDs (E1, E2...)
- Clear button to reset

## Running Tests

### Local Unit Tests (JUnit)
```bash
./gradlew test
```
Tests:
- `ValidationUtilTest` - All validation rules (site name, reading type, value ranges, notes)
- `MockSyncServiceTest` - Idempotency, malformed ack, conflict, lost response deduping
- `SyncClientTest` - State machine transitions

### Instrumented Tests (AndroidJUnit)
```bash
./gradlew connectedAndroidTest
```
Tests:
- `DatabaseTest` - Insert 3 records, reopen database, assert persistence; transactional atomicity

## Environment
| Component | Version |
|-----------|---------|
| Android Studio | [Fill in] |
| AGP | 9.5.1 |
| Gradle | 9.6.0 |
| minSdk | 24 |
| targetSdk | 37 |
| compileSdk | 37 |
| Java | 11 |
| Emulator/Device | [Fill in] |

## Key Implementation Details

### "Saved" Means...
**Exact wording**: "Saved on this device (pending sync)"
- Only shown AFTER Room transaction commits
- Record state = `PENDING` (queued for sync)
- `operationId` generated for this logical operation

### Timeout ≠ Rejection
In `LOST_RESPONSE` mode, the server **does** commit but the response is lost.
- Saving a record is **local only** (state = `PENDING`, no auto-sync)
- User taps **Sync Pending** → client sees timeout (transient failure)
- Record state = `FAILED`, `attemptCount` incremented, `operationId` **reused**
- Retry with same `operationId` (Sync Pending again in NORMAL mode) returns stored ack - **server count unchanged**

### Data Surviving Process Restart
All `ReadingRecord` fields in Room survive:
- `id`, `siteName`, `readingType`, `value`, `unit`, `notes`
- `recordedAt`, `updatedAt`
- `syncState`, `operationId`, `attemptCount`, `lastError`
- `serverVersion`, `localVersion`

Established via Room persistence verified in `DatabaseTest`.

### Duplicate Prevention
`operationId` idempotency in `MockSyncService`:
- First call with `operationId` → store ack in `operationStore`
- Retry with same `operationId` → return stored ack, **no new server record**

### Conflict vs Transient Failure
| Aspect | Transient (UNAVAILABLE/LOST_RESPONSE) | Conflict (CONFLICT) |
|--------|----------------------------------------|---------------------|
| Retry | Automatic (same `operationId`) | Never auto-retried |
| Resolution | Retry until success | Manual: "Keep my version" → NEW `operationId` |
| Server state | Unchanged or committed | Has newer `serverVersion` |

## Project Structure
```
app/src/main/java/com/example/persistentrecords/
├── MainActivity.java              # Form + List + Debug UI
├── ui/
│   ├── ReadingAdapter.java        # RecyclerView adapter
│   ├── EvidenceLogAdapter.java    # Evidence log adapter
│   └── EvidenceLogActivity.java   # Evidence log screen
├── model/
│   └── ReadingRecord.java         # Room entity
├── dao/
│   └── ReadingDao.java            # Room DAO with @Transaction
├── db/
│   └── AppDatabase.java           # Room database
├── sync/
│   ├── MockSyncService.java       # MOCK server (deterministic)
│   └── SyncClient.java            # State machine sync client
├── log/
│   ├── EvidenceLog.java           # In-memory log store
│   └── LogEntry.java              # Log entry with evidence ID
└── util/
    └── ValidationUtil.java        # Pure validation logic
```

## License
Academic assignment - JKUAT ICS 2300 Lab_08_hm8