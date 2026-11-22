package com.example.persistentrecords.sync;

import android.util.Log;

import com.example.persistentrecords.model.ReadingRecord;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * MOCK sync service - deterministic in-memory implementation.
 * NO REAL NETWORK CALLS. Clearly labelled MOCK everywhere.
 * 
 * Implements idempotency via operationId map and serverVersion tracking.
 * Modes: NORMAL, UNAVAILABLE, LOST_RESPONSE, CONFLICT, MALFORMED_ACK
 */
public class MockSyncService {

    private static final String TAG = "HM8";

    // Logger interface for testability
    public interface Logger {
        void i(String tag, String message);
        void w(String tag, String message);
        void e(String tag, String message, Throwable t);
    }

    // Default Android logger
    private static final Logger DEFAULT_LOGGER = new Logger() {
        @Override public void i(String tag, String message) { Log.i(tag, message); }
        @Override public void w(String tag, String message) { Log.w(tag, message); }
        @Override public void e(String tag, String message, Throwable t) { Log.e(tag, message, t); }
    };

    // No-op logger for unit tests
    public static final Logger NOOP_LOGGER = new Logger() {
        @Override public void i(String tag, String message) {}
        @Override public void w(String tag, String message) {}
        @Override public void e(String tag, String message, Throwable t) {}
    };

    // Mock server modes
    public enum Mode {
        NORMAL,           // Accepts record, returns valid ack
        UNAVAILABLE,      // Throws transient error (simulates 503/no connection)
        LOST_RESPONSE,    // Server commits but response dropped (timeout)
        CONFLICT,         // Returns 409 conflict - server has newer version
        MALFORMED_ACK     // Returns invalid ack (missing fields/wrong operationId)
    }

    // In-memory "server" storage
    private final Map<String, AckResponse> operationStore = new HashMap<>(); // operationId -> ack
    private final Map<String, Integer> serverVersions = new HashMap<>();     // recordId -> serverVersion
    private final Map<String, ReadingRecord> serverRecords = new HashMap<>(); // recordId -> record (for count)
    private final AtomicInteger recordCounter = new AtomicInteger(0);

    private Mode currentMode = Mode.NORMAL;
    private boolean shouldThrowOnNextCall = false; // For UNAVAILABLE mode

    // Logger instance (can be swapped for testing)
    private Logger logger = DEFAULT_LOGGER;

    // Singleton
    private static volatile MockSyncService INSTANCE;

    private MockSyncService() {}

    public static MockSyncService getInstance() {
        if (INSTANCE == null) {
            synchronized (MockSyncService.class) {
                if (INSTANCE == null) {
                    INSTANCE = new MockSyncService();
                }
            }
        }
        return INSTANCE;
    }

    public static void resetInstance() {
        INSTANCE = new MockSyncService();
    }

    public Mode getCurrentMode() {
        return currentMode;
    }

    public void setCurrentMode(Mode mode) {
        this.currentMode = mode;
        logger.i(TAG, "[MOCK] Mode changed to: " + mode.name());
    }

    /**
     * Sets the logger instance (for testing).
     */
    public void setLogger(Logger logger) {
        this.logger = logger != null ? logger : NOOP_LOGGER;
    }

    public int getServerRecordCount() {
        return serverRecords.size();
    }

    /**
     * Syncs a single record. Returns AckResponse on success, throws on failure.
     * Implements idempotency: same operationId returns stored ack without creating duplicate.
     */
    public AckResponse syncRecord(ReadingRecord record) throws SyncException {
        String operationId = record.getOperationId();
        String recordId = record.getId();

        logger.i(TAG, String.format("[MOCK] SYNC_ATTEMPT operationId=%s attempt=%d mode=%s recordId=%s",
                operationId, record.getAttemptCount(), currentMode.name(), recordId));

        // IDEMPOTENCY CHECK: If we've seen this operationId before, return stored ack
        if (operationStore.containsKey(operationId)) {
            AckResponse storedAck = operationStore.get(operationId);
            logger.i(TAG, String.format("[MOCK] SYNC_RETRY_DEDUPED operationId=%s recordId=%s serverCount=%d",
                    operationId, recordId, serverRecords.size()));
            return storedAck;
        }

        switch (currentMode) {
            case NORMAL:
                return handleNormalMode(record);

            case UNAVAILABLE:
                throw new SyncException("Service unavailable (HTTP 503)", SyncException.Type.TRANSIENT);

            case LOST_RESPONSE:
                return handleLostResponse(record);

            case CONFLICT:
                return handleConflict(record);

            case MALFORMED_ACK:
                return handleMalformedAck(record);

            default:
                throw new SyncException("Unknown mock mode", SyncException.Type.PERMANENT);
        }
    }

    private AckResponse handleNormalMode(ReadingRecord record) {
        String operationId = record.getOperationId();
        String recordId = record.getId();

        // Increment server version
        int newServerVersion = serverVersions.getOrDefault(recordId, 0) + 1;
        serverVersions.put(recordId, newServerVersion);

        // Store record on "server"
        serverRecords.put(recordId, record);
        recordCounter.incrementAndGet();

        // Create and store ack
        AckResponse ack = new AckResponse(operationId, recordId, "ACK", newServerVersion);
        operationStore.put(operationId, ack);

        logger.i(TAG, String.format("[MOCK] SYNC_ACK operationId=%s recordId=%s serverVersion=%d serverCount=%d",
                operationId, recordId, newServerVersion, serverRecords.size()));

        return ack;
    }

    private AckResponse handleLostResponse(ReadingRecord record) throws SyncException {
        String operationId = record.getOperationId();
        String recordId = record.getId();

        // Server DOES commit the record
        int newServerVersion = serverVersions.getOrDefault(recordId, 0) + 1;
        serverVersions.put(recordId, newServerVersion);
        serverRecords.put(recordId, record);
        recordCounter.incrementAndGet();

        // Create ack but DON'T return it (simulates dropped response)
        AckResponse ack = new AckResponse(operationId, recordId, "ACK", newServerVersion);
        operationStore.put(operationId, ack); // Store for idempotency on retry

        logger.i(TAG, String.format("[MOCK] SYNC_LOST_RESPONSE operationId=%s recordId=%s serverVersion=%d serverCount=%d - " +
                "Timeout must NEVER be treated as proof of rejection",
                operationId, recordId, newServerVersion, serverRecords.size()));

        // Throw timeout to simulate lost response
        throw new SyncException("Request timeout (response lost)", SyncException.Type.TRANSIENT);
    }

    private AckResponse handleConflict(ReadingRecord record) {
        String operationId = record.getOperationId();
        String recordId = record.getId();

        // Server has a newer version - return conflict with server's version
        int serverVer = serverVersions.getOrDefault(recordId, 1); // At least 1
        if (serverVer <= record.getServerVersion()) {
            serverVer = record.getServerVersion() + 1;
            serverVersions.put(recordId, serverVer);
        }

        logger.i(TAG, String.format("[MOCK] SYNC_CONFLICT operationId=%s recordId=%s clientVersion=%d serverVersion=%d",
                operationId, recordId, record.getServerVersion(), serverVer));

        // Return conflict response (not an exception - it's a valid HTTP 409 response)
        return new AckResponse(operationId, recordId, "CONFLICT", serverVer);
    }

    private AckResponse handleMalformedAck(ReadingRecord record) {
        String operationId = record.getOperationId();
        String recordId = record.getId();

        logger.i(TAG, String.format("[MOCK] SYNC_MALFORMED_ACK operationId=%s recordId=%s", operationId, recordId));

        // Return malformed ack - missing status field, wrong operationId
        return new AckResponse("wrong-operation-id", recordId, null, 1);
    }

    /**
     * Validates an acknowledgement response.
     * Returns true only if ack is well-formed and matches the operationId.
     */
    public boolean validateAck(AckResponse ack, String expectedOperationId) {
        if (ack == null) {
            logger.w(TAG, "[MOCK] Malformed ack: null response");
            return false;
        }
        if (ack.operationId == null || !ack.operationId.equals(expectedOperationId)) {
            logger.w(TAG, String.format("[MOCK] Malformed ack: operationId mismatch. Expected=%s Got=%s",
                    expectedOperationId, ack.operationId));
            return false;
        }
        if (ack.status == null || !ack.status.equals("ACK")) {
            logger.w(TAG, String.format("[MOCK] Malformed ack: invalid status=%s", ack.status));
            return false;
        }
        if (ack.serverVersion <= 0) {
            logger.w(TAG, String.format("[MOCK] Malformed ack: invalid serverVersion=%d", ack.serverVersion));
            return false;
        }
        return true;
    }

    /**
     * Syncs a record in NORMAL mode WITHOUT modifying the record (no new operationId, no state changes).
     * Used for conflict resolution where the client has already prepared the record with a new operationId
     * and incremented localVersion. The mock only processes the sync and returns an ACK.
     * Implements idempotency: same operationId returns stored ack without creating duplicate.
     */
    public AckResponse syncForcedNormal(ReadingRecord record) throws SyncException {
        String operationId = record.getOperationId();
        String recordId = record.getId();

        logger.i(TAG, String.format("[MOCK] SYNC_FORCED_NORMAL operationId=%s recordId=%s",
                operationId, recordId));

        // IDEMPOTENCY CHECK: If we've seen this operationId before, return stored ack
        if (operationStore.containsKey(operationId)) {
            AckResponse storedAck = operationStore.get(operationId);
            logger.i(TAG, String.format("[MOCK] SYNC_RETRY_DEDUPED operationId=%s recordId=%s serverCount=%d",
                    operationId, recordId, serverRecords.size()));
            return storedAck;
        }

        // Use NORMAL mode logic directly without touching record fields
        return handleNormalMode(record);
    }

    /**
     * For CONFLICT resolution: "Keep my version" - client must prepare record first.
     * This method is DEPRECATED - use syncForcedNormal after client sets new operationId, localVersion+1, etc.
     * Kept for backward compatibility with tests.
     */
    @Deprecated
    public AckResponse resolveConflictKeepLocal(ReadingRecord record) {
        // Generate NEW operationId for this resolution (legacy behavior)
        String newOperationId = UUID.randomUUID().toString();
        record.setOperationId(newOperationId);
        record.setAttemptCount(0);
        record.setLastError(null);
        record.setSyncState(ReadingRecord.STATE_PENDING);
        // Note: legacy behavior does NOT increment localVersion

        // Now sync with NORMAL mode logic (force normal for resolution)
        Mode oldMode = currentMode;
        currentMode = Mode.NORMAL;
        try {
            return syncRecord(record);
        } catch (SyncException e) {
            // Should not happen in NORMAL mode, but handle gracefully
            Log.e(TAG, "Unexpected error in conflict resolution", e);
            throw new RuntimeException(e);
        } finally {
            currentMode = oldMode;
        }
    }

    // --- Data classes ---

    public static class AckResponse {
        public final String operationId;
        public final String recordId;
        public final String status; // "ACK", "CONFLICT", or null for malformed
        public final int serverVersion;

        public AckResponse(String operationId, String recordId, String status, int serverVersion) {
            this.operationId = operationId;
            this.recordId = recordId;
            this.status = status;
            this.serverVersion = serverVersion;
        }
    }

    public static class SyncException extends Exception {
        public enum Type { TRANSIENT, PERMANENT }

        public final Type type;

        public SyncException(String message, Type type) {
            super(message);
            this.type = type;
        }
    }
}