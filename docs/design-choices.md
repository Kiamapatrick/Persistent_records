# Design Choices - Field Readings App

## Why Room/SQLite over SharedPreferences or Plain Files?

### Room/SQLite (Chosen)

| Criterion | Room/SQLite | Why It Matters |
|-----------|-------------|----------------|
| **Structured Records** | ✅ Tables with typed columns | Each reading has 14 fields with specific types (String, double, long, int) |
| **Queries** | ✅ SQL + DAO queries | `getRecordsBySyncStates()`, `getRecordByOperationId()`, ordered lists |
| **Transactions** | ✅ `@Transaction` atomicity | Save = insert OR update atomically; no partial writes |
| **Schema & Migration** | ✅ Compile-time checked | Entity = schema; migrations explicit; exportSchema for review |
| **Concurrency** | ✅ WAL mode, thread-safe | Background executor + main thread reads |
| **Type Safety** | ✅ Compile-time | No stringly-typed keys, no manual serialization |
| **Observability** | ✅ LiveData/Flow support | Reactive UI updates (not used here but available) |

### SharedPreferences (Rejected)

| Limitation | Impact |
|------------|--------|
| Key-value only | No structured records; would need JSON serialization per record |
| No queries | Cannot "get all PENDING records" efficiently |
| No transactions | Partial writes possible; no atomic insert-or-update |
| No schema | Runtime errors for typos; no compile-time validation |
| Size limit | Not designed for hundreds of records |
| No relationships | Cannot link operationId → record naturally |

**Still appropriate for**: Simple app settings (theme, last selected mock mode, UI preferences) — not for structured entity data.

### Plain Files (JSON/CSV) (Rejected)

| Limitation | Impact |
|------------|--------|
| Manual parsing | Error-prone, boilerplate, no type safety |
| No atomic writes | File corruption on crash mid-write |
| No concurrent access | Locking complexity |
| No queries | Full read + filter in memory |
| No schema evolution | Migration = manual code |
| No indexing | O(n) lookups by operationId |

**Still appropriate for**: Export/import, backup, log files — not for primary storage.

## Alternatives Considered

| Alternative | Pros | Cons | Verdict |
|-------------|------|------|---------|
| **Realm** | Fast, reactive, objects | Extra dependency, not Google-maintained | Room is standard |
| **ObjectBox** | Fast, no SQL | Proprietary, less community | Room is standard |
| **DataStore (Preferences/Proto)** | Modern, coroutines | Still key-value; Proto = schema but no SQL queries | Not for entities |
| **Custom SQLiteOpenHelper** | Full control | Boilerplate, no compile-time verification | Room wraps this |

## Transaction Design

**Requirement**: "Save to Room inside ONE @Transaction in the DAO. Show 'Saved' message ONLY after transaction committed."

**Implementation**: `ReadingDao.saveRecord()` uses `@Transaction` default method:

```java
@Transaction
default void saveRecord(ReadingRecord record) {
    ReadingRecord existing = getRecordById(record.getId());
    if (existing != null) {
        record.setLocalVersion(existing.getLocalVersion() + 1);
        record.setRecordedAt(existing.getRecordedAt());
        update(record);
    } else {
        insert(record);
    }
}
```

**Why this works**:
- Single transaction: either both `getRecordById` + `insert/update` succeed, or neither
- No partial row: Room rolls back on any exception
- "Saved" message shown only in `runOnUiThread` after `dao.saveRecord()` returns without exception

## Mock Sync Service Design

**Deterministic, in-memory, no network**:
- `Map<operationId, AckResponse>` for idempotency
- `Map<recordId, Integer>` for serverVersion tracking
- `Map<recordId, ReadingRecord>` for server record count
- Mode selector changes behavior globally

**Why not a real HTTP mock (MockWebServer)?**
- Assignment requires "clearly labelled MOCK everywhere"
- Deterministic modes (LOST_RESPONSE, MALFORMED_ACK) are harder to express with HTTP
- In-memory = fast tests, no flakiness, no port conflicts

## State Machine Design

**Explicit states, no implicit transitions**:
- `SyncClient.syncSingleRecord()` handles one record
- Each outcome (ACK, transient, conflict, malformed) → explicit state update
- Callbacks for UI updates (onRecordSynced, onRecordFailed, onRecordConflict)

**Why not WorkManager?**
- Assignment: "Do NOT auto-sync on save; sync is manual so demos are controllable"
- WorkManager = background, automatic, hard to control for demos
- Simple `Executor` + manual button = predictable, demonstrable

## Evidence Logging

**In-memory ring buffer (max 100 entries)**:
- Each entry: timestamp, tag, message, evidence ID (E1, E2...)
- Also logged to Android Log with `HM8` tag
- EvidenceLogActivity shows all entries for screenshots

**Why not persistent log table?**
- Requirement: "kept in memory, or in a small log table"
- In-memory = simpler, survives app process but not needed for demo screenshots
- Can be extended to Room table if needed

## Threading

**Single background Executor**:
```java
private final Executor executor = Executors.newSingleThreadExecutor();
```
- All Room writes: `executor.execute(() -> { dao...; runOnUiThread(...) })`
- Sync: `SyncClient` uses same pattern
- Reads: `allowMainThreadQueries()` in tests; production could use LiveData

**Why not Coroutines/RxJava?**
- Java-only requirement (no Kotlin)
- Executor is sufficient for this scope
- Keeps dependencies minimal

## Validation

**Centralized in `ValidationUtil` (pure Java, no Android deps)**:
- Unit-testable without Robolectric
- Returns `ValidationResult` objects (not exceptions)
- Form validation aggregates all field results

## Conflict Resolution

**"Keep my version" = New Operation**:
- Generates NEW `operationId`
- Resets `attemptCount = 0`
- Sets `syncState = PENDING`
- Increments `localVersion`
- This is a **new logical operation**, not a retry

**Why not "Force Push" (same operationId)?**
- Would violate idempotency contract
- Server would reject (operationId already ACKed)
- New operationId = clean slate, server treats as new record version

## Where SharedPreferences Are Still Appropriate

| Use Case | Example |
|----------|---------|
| User preferences | Last selected mock mode, theme |
| UI state | Scroll position, expanded cards |
| Feature flags | Test hook enabled/disabled (persisted) |
| Onboarding | "First run" flag |

**Not used in this app** but would be the right choice for the above.

## Summary

| Layer | Technology | Rationale |
|-------|------------|-----------|
| Persistence | Room/SQLite | Structured entities, queries, transactions, type safety |
| Sync | In-memory MockSyncService | Deterministic modes, idempotency, testable |
| State Machine | SyncClient + explicit states | Controllable, demonstrable, no hidden behavior |
| Threading | Executor + runOnUiThread | Java-only, simple, sufficient |
| Validation | Pure Java ValidationUtil | Unit-testable, reusable |
| Logging | In-memory EvidenceLog | Screenshot-ready, evidence IDs |
| Tests | JUnit (local) + AndroidJUnit (instrumented) | Fast logic tests + real Room verification |