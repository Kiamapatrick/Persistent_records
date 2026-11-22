# State Transitions - Field Readings Sync

## States

```
SAVED_LOCAL  →  PENDING  →  ACKNOWLEDGED
                    ↓
                 FAILED  ←── (retry with same operationId)
                    ↓
                 CONFLICT  →  PENDING (new operationId via "Keep my version")
```

## Allowed Transitions

| From State | To State | Trigger | OperationId | AttemptCount | localVersion | serverVersion |
|------------|----------|---------|-------------|--------------|--------------|---------------|
| (new) | SAVED_LOCAL | Local save (no sync) | New UUID | 0 | 1 | 0 |
| (new) | PENDING | Local save + queued | New UUID | 0 | 1 | 0 |
| SAVED_LOCAL | PENDING | User taps "Sync Pending" | Same | 0 | Same | Same |
| PENDING | ACKNOWLEDGED | Valid ACK received | Same | Same | Same | From ACK |
| PENDING | FAILED | Transient error (503, timeout) | **REUSED** | +1 | Same | Same |
| PENDING | CONFLICT | HTTP 409 (serverVersion > local) | Same | Same | Same | From conflict response |
| FAILED | PENDING | Retry (same operationId) | **REUSED** | Same | Same | Same |
| FAILED | ACKNOWLEDGED | Retry succeeds (valid ACK) | Same | Same | Same | From ACK |
| CONFLICT | PENDING | "Keep my version" action | **NEW UUID** | 0 | +1 | Same |

## Version numbers in the CONFLICT scenario

| Step | Description | serverVersion (mock) | client serverVersion | client localVersion | operationId |
|------|-------------|---------------------|---------------------|---------------------|-------------|
| 1 | First save + sync (NORMAL) | 1 | 1 | 1 | op-1 |
| 2 | Edit record (new operationId) | 1 | 1 | 2 | op-2 |
| 3 | Sync in CONFLICT mode | 2 (bumped by mock) | 2 (from conflict) | 2 | op-2 |
| 4 | Resolve: "Keep my version" | 2 | 2 | 3 (incremented) | op-3 (new) |
| 5 | Sync resolution (forced NORMAL) | 3 | 3 | 3 | op-3 |

The CONFLICT response itself increments the mock's internal server version counter (to 2). The resolved ACK then increments it again (to 3).

## Text Diagram

```
┌─────────────┐
│  NEW RECORD │
└──────┬──────┘
       │ save (transaction)
       ▼
┌─────────────────┐
│  SAVED_LOCAL    │ ◄──┐
│  (or PENDING)   │    │
└────────┬────────┘    │
         │ sync       │
         ▼            │
┌─────────────────┐   │
│    PENDING      │   │
└────────┬────────┘   │
         │            │
    ┌────┴────┐       │
    ▼         ▼       │
┌───────┐ ┌───────┐   │
│  ACK  │ │ FAIL  │   │
└───┬───┘ └───┬───┘   │
    │         │       │
    │    ┌────┴────┐  │
    │    ▼         ▼  │
    │ ┌─────┐   ┌──────┐
    │ │ACK  │   │CONFLICT│
    │ └─────┘   └────┬──┘
    │               │
    │      "Keep my │
    │      version" │
    │               ▼
    │         ┌──────────┐
    └────────►│  PENDING │ (new operationId)
              └──────────┘
```

## Answers to Required Questions

### (a) What exactly does "Saved" mean in the interface?

**Exact wording**: "Saved on this device (pending sync)"

This message is shown **only after** the Room `@Transaction` has successfully committed. It means:
- The record is durably stored in SQLite (survives process restart)
- `syncState` = `PENDING` (queued for background sync)
- An `operationId` has been generated for this logical operation
- `localVersion` = 1 (new) or incremented (edit)
- The draft is **cleared** from the form (only on success)

If the write fails (including test hook simulation), the message is **never shown**, the draft is **kept**, and an error is displayed.

---

### (b) Can a timeout prove the server rejected a record?

**No.** A timeout (LOST_RESPONSE mode) means the client did not receive a response. The server **may have**:
- Processed and committed the record (server count increases)
- Not processed it at all (server count unchanged)

**Reasoning**: The client cannot distinguish between "server didn't get request" and "server processed but response lost". Treating timeout as rejection would cause data loss (not retrying a record the server actually has).

**Implementation**: In `LOST_RESPONSE` mode, the mock server **does commit** and stores the ACK keyed by `operationId`. The client sees a transient failure, sets `FAILED`, reuses `operationId`, and on retry gets the stored ACK — **no duplicate created**.

Log evidence: `[MOCK] SYNC_LOST_RESPONSE ... Timeout must NEVER be treated as proof of rejection`

---

### (c) Which data survives a process restart and how was it established?

**All `ReadingRecord` fields survive** because they are persisted in Room/SQLite:
- Identity: `id`, `siteName`, `readingType`, `value`, `unit`, `notes`
- Timestamps: `recordedAt`, `updatedAt`
- Sync state: `syncState`, `operationId`, `attemptCount`, `lastError`
- Versions: `serverVersion`, `localVersion`

**Established by**: `DatabaseTest.recordsPersistAcrossReopen()` — inserts 3 records, closes database, reopens, queries all 3, asserts field integrity.

---

### (d) How are duplicates prevented?

**`operationId` idempotency** in `MockSyncService`:

1. First sync with `operationId="op-1"` → server processes, stores ACK in `operationStore["op-1"]`, increments server record count
2. Retry with same `operationId="op-1"` → `operationStore` hit → returns stored ACK → **server record count unchanged**
3. New logical operation (edit or conflict resolution) → **new `operationId`** → new server record

**Verified by**: `MockSyncServiceTest.idempotency_sameOperationIdDoesNotCreateDuplicate()`

---

### (e) How does a conflict differ from a transient failure?

| Aspect | Transient Failure (UNAVAILABLE, LOST_RESPONSE) | Conflict (CONFLICT) |
|--------|------------------------------------------------|---------------------|
| **Cause** | Network/server temporary issue | Server has newer `serverVersion` |
| **HTTP-like** | 503 / Timeout | 409 |
| **Retry** | Automatic (same `operationId`) | **Never automatic** |
| **Resolution** | Retry until success | Manual: "Keep my version" → NEW `operationId` |
| **State** | `FAILED` (retryable) | `CONFLICT` (requires user action) |
| **Server record count** | Unchanged (UNAVAILABLE) or +1 then deduped (LOST_RESPONSE) | Unchanged until resolution |
| **Data loss risk** | Low (idempotent retry) | High if auto-merged incorrectly |

**Key distinction**: Transient = "try again later with same identity". Conflict = "your version diverges, choose explicitly".