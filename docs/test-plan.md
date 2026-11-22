# Test Plan - Field Readings App (Lab_08_hm8)

## Required Checks (Assignment Mandatory)

Each check includes steps, expected result, and evidence column for screenshots/logs.

---

### Check 1: Offline Persistence
**Description**: Save three records offline; close app (swipe away/force stop) and reopen; records still listed.

| Step | Action | Expected Result | Observed / Evidence ID |
|------|--------|-----------------|------------------------|
| 1.1 | Set Mock Mode = **UNAVAILABLE** (offline simulation) | Mode shows UNAVAILABLE | |
| 1.2 | Fill form: Site="Site A", Type=Temperature, Value=25, Notes="Test 1" | Form validates | |
| 1.3 | Tap **Save Reading** | Toast: "Saved on this device (pending sync)" | |
| 1.4 | Repeat for Site B (Humidity, 60) and Site C (Soil Moisture, 45) | 3 records in list | |
| 1.5 | Verify list shows 3 records with syncState = PENDING/FAILED | 3 cards visible | |
| 1.6 | **Force stop app** (Settings → Apps → Force Stop) or swipe from recents | App terminated | |
| 1.7 | Reopen app from launcher | App starts fresh | |
| 1.8 | Observe record list | **All 3 records still present** with same data | |
| 1.9 | Check Evidence Log for `APP_START` entry | `APP_START: Loaded 3 records from Room` | |

**Type**: Real observation (Room persistence, process restart)

> **Note**: The `MockSyncService` is in-memory, so after a force-stop the Room records survive but the mock server count resets to 0 and its operationId store is empty. This is expected, not a bug. Real observation = Room data; mock outcome = server count.

---

### Check 2: Rejected Input / Failed Write Preserves Draft
**Description**: Validation rejection or DB write failure keeps the draft in the form.

#### 2A: Validation Rejection
| Step | Action | Expected Result | Observed / Evidence ID |
|------|--------|-----------------|------------------------|
| 2A.1 | Clear form, enter Site="Ab" (too short), Value=25 | Site field shows error "at least 3 characters" | |
| 2A.2 | Tap Save | **No success message**, form **unchanged** (draft kept) | |
| 2A.3 | Check Evidence Log | `VALIDATION_REJECTED: Site name must be at least 3 characters` | |

#### 2B: Simulated Write Failure (Test Hook)
| Step | Action | Expected Result | Observed / Evidence ID |
|------|--------|-----------------|------------------------|
| 2B.1 | Enable **Simulate local write failure** switch | Toast: "TEST HOOK ENABLED" | |
| 2B.2 | Fill valid form (Site="Valid Site", Temp=25) | Form validates, Save enabled | |
| 2B.3 | Tap Save | Error toast "Save failed: TEST HOOK...", **draft stays in form** | |
| 2B.4 | Check Evidence Log | `SAVE_FAILED: ... error=TEST HOOK...` | |
| 2B.5 | Disable switch, tap Save again | Success: "Saved on this device (pending sync)" | |

**Type**: Real observation (form state preserved, no data loss)

---

### Check 3: Retry Accepted Operation (LOST_RESPONSE → NORMAL) No Duplicate
**Description**: Server accepts but response lost; retry with same operationId does not create duplicate.

| Step | Action | Expected Result | Observed / Evidence ID |
|------|--------|-----------------|------------------------|
| 3.1 | Set Mock Mode = **LOST_RESPONSE** | Mode shows LOST_RESPONSE | |
| 3.2 | Save a new record (Site="Retry Test", Temperature, 20). Expect "Saved on this device (pending sync)", state PENDING (saving never auto-syncs). | | |
| 3.3 | Tap **Sync Pending**. Expect state FAILED, attemptCount=1, error "Request timeout (response lost)". Evidence log: `SYNC_LOST_RESPONSE` then `SYNC_TRANSIENT_FAILURE`. | | |
| 3.4 | Note Mock server record count = 1 (the server committed even though the client saw a timeout). | | |
| 3.5 | Do NOT edit the record (an edit creates a new operationId and would skip the dedupe). Note the record's operationId. | | |
| 3.6 | Mock Mode = **NORMAL**, tap **Sync Pending**. | | |
| 3.7 | Expect ACKNOWLEDGED, serverVersion=1, same operationId as 3.5, server count still 1. | | |
| 3.8 | Evidence log: `SYNC_LOST_RESPONSE` → `SYNC_TRANSIENT_FAILURE` → `SYNC_RETRY_DEDUPED` → `SYNC_ACK`. (Dedupe is logged by the mock as `[MOCK] SYNC_RETRY_DEDUPED`.) | | |

**Type**: Mock outcome (server behavior verified via count)

---

### Check 4: Malformed Acknowledgement or Conflict Not Shown as Success
**Description**: Invalid ACK or conflict response never transitions to ACKNOWLEDGED.

#### 4A: Malformed ACK
| Step | Action | Expected Result | Observed / Evidence ID |
|------|--------|-----------------|------------------------|
| 4A.1 | Set Mock Mode = **MALFORMED_ACK** | Mode shows MALFORMED_ACK | |
| 4A.2 | Save new record (Site="Malformed Test", Temp=22) | Record saved locally (PENDING) | |
| 4A.3 | Tap **Sync Pending** | Record state = FAILED, error = "Malformed acknowledgement" | |
| 4A.4 | Verify **NO "Synced" / "Acknowledged" message** | Sync status shows failed count | |
| 4A.5 | Check Evidence Log | `SYNC_MALFORMED_ACK` then `SYNC_TRANSIENT_FAILURE` (malformed treated as transient) | |

#### 4B: Conflict
| Step | Action | Expected Result | Observed / Evidence ID |
|------|--------|-----------------|------------------------|
| 4B.1 | Set Mock Mode = **NORMAL**, save record (Site="Conflict Test", Temp=15) | Record ACKNOWLEDGED, serverVersion=1 | |
| 4B.2 | Set Mock Mode = **CONFLICT** | Mode shows CONFLICT | |
| 4B.3 | Edit same record (change value to 16), Save | New operationId, state = PENDING | |
| 4B.4 | Tap **Sync Pending** | Conflict dialog appears: "Server has newer version..." | |
| 4B.5 | Choose **"Keep My Version (New Operation)"** | New sync with NEW operationId, succeeds | |
| 4B.6 | Verify record state = ACKNOWLEDGED, serverVersion=3 | Updated record acknowledged | |
| 4B.7 | Check Evidence Log | `SYNC_CONFLICT` then `CONFLICT_RESOLUTION` then `SYNC_ACK` | |

> **Note**: The expected serverVersion after "Keep My Version" is **3**, not 2.
> Sequence: first ACK = 1, CONFLICT response bumps the mock's server version to 2, the resolved ACK = 3.
> After resolution, `localVersion` is incremented by 1 and the record has a new `operationId`.

**Type**: Mock outcome (server behavior) + Real observation (UI state)

---

## Additional Demo Scenarios

### Normal Acknowledgement
| Step | Action | Expected | Evidence |
|------|--------|----------|----------|
| 1 | Mock Mode = NORMAL | | |
| 2 | Save record | "Saved on this device (pending sync)" | |
| 3 | Sync Pending | Record → ACKNOWLEDGED, green badge | `SYNC_ACK` |
| 4 | Server count = 1 | | |

### Unavailable Service (Transient)
| Step | Action | Expected | Evidence |
|------|--------|----------|----------|
| 1 | Mock Mode = UNAVAILABLE | | |
| 2 | Save record | Local PENDING/FAILED | |
| 3 | Sync Pending | FAILED, attemptCount=1, error=503 | `SYNC_TRANSIENT_FAILURE` |
| 4 | Switch to NORMAL, Sync again | Retry with same operationId → ACK | `SYNC_RETRY_DEDUPED` |

### Lost Response Retry (Idempotency)
| Step | Action | Expected | Evidence |
|------|--------|----------|----------|
| 1 | Mock Mode = LOST_RESPONSE | | |
| 2 | Save record | "Saved on this device (pending sync)", state PENDING | |
| 3 | Tap **Sync Pending** | Timeout, state FAILED | `SYNC_LOST_RESPONSE` → `SYNC_TRANSIENT_FAILURE` |
| 4 | Server count = 1 | | |
| 5 | Switch to NORMAL, tap **Sync Pending** | Retry → stored ACK, no duplicate | `SYNC_RETRY_DEDUPED` → `SYNC_ACK` |
| 6 | Server count = 1 | **No increase** | |

---

## Evidence Collection Guide

| Evidence ID | Screen / Log | Capture Method |
|-------------|--------------|----------------|
| E1, E2, ... | Evidence Log list | Screenshot EvidenceLogActivity |
| HM8 logs | Logcat (filter: HM8) | `adb logcat -s HM8` |
| Record list | MainActivity RecyclerView | Screenshot main screen |
| Form errors | TextInputLayout errors | Screenshot form with errors |
| Sync status | tvSyncStatus TextView | Screenshot after sync |
| Server count | tvServerCount TextView | Screenshot debug card |
| Conflict dialog | AlertDialog | Screenshot conflict dialog |

---

## Test Execution Checklist

- [ ] All local JUnit tests pass (`./gradlew test`)
- [ ] All instrumented tests pass (`./gradlew connectedAndroidTest`)
- [ ] Check 1: Offline persistence verified
- [ ] Check 2A: Validation rejection preserves draft
- [ ] Check 2B: Simulated write failure preserves draft
- [ ] Check 3: LOST_RESPONSE retry no duplicate
- [ ] Check 4A: Malformed ACK not success
- [ ] Check 4B: Conflict not success, resolution works
- [ ] Normal acknowledgement demo
- [ ] Unavailable service demo
- [ ] Lost response retry demo
- [ ] Evidence logs exported / screenshots taken
- [ ] All Evidence IDs filled in Observed column above

---

## Notes

- **Real observations** = On-device Room persistence, process restart, form behavior, UI state
- **Mock outcomes** = Server record count, idempotency, conflict responses (deterministic, in-memory)
- **Do NOT fake observations** - leave blank if not yet captured
- Run on: [Emulator API ___ / Physical device ________________]
- Date: ________________
- Tester: ________________