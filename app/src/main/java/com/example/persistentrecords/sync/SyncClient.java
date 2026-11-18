package com.example.persistentrecords.sync;

import android.util.Log;

import com.example.persistentrecords.dao.ReadingDao;
import com.example.persistentrecords.model.ReadingRecord;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

/**
 * Sync client implementing the state machine for record synchronization.
 * Processes records in PENDING or FAILED state.
 * Runs on background thread via Executor.
 */
public class SyncClient {

    private static final String TAG = "HM8";

    private final ReadingDao dao;
    private final MockSyncService mockSyncService;
    private final Executor executor = Executors.newSingleThreadExecutor();
    private final SyncCallback callback;

    public interface SyncCallback {
        void onSyncComplete(int processed, int succeeded, int failed);
        void onRecordSynced(ReadingRecord record);
        void onRecordFailed(ReadingRecord record, String error);
        void onRecordConflict(ReadingRecord record);
    }

    public SyncClient(ReadingDao dao, MockSyncService mockSyncService, SyncCallback callback) {
        this.dao = dao;
        this.mockSyncService = mockSyncService;
        this.callback = callback;
    }

    /**
     * Starts syncing all records in PENDING or FAILED state.
     * Runs on background thread.
     */
    public void syncPending() {
        executor.execute(() -> {
            List<String> pendingStates = new ArrayList<>();
            pendingStates.add(ReadingRecord.STATE_PENDING);
            pendingStates.add(ReadingRecord.STATE_FAILED);

            List<ReadingRecord> records = dao.getRecordsBySyncStates(pendingStates);
            Log.i(TAG, String.format("SYNC_ATTEMPT: Found %d records to sync", records.size()));

            int processed = 0;
            int succeeded = 0;
            int failed = 0;

            for (ReadingRecord record : records) {
                processed++;
                syncSingleRecord(record);
                if (record.getSyncState().equals(ReadingRecord.STATE_ACKNOWLEDGED)) {
                    succeeded++;
                } else {
                    failed++;
                }
            }

            // Run callback on main thread (assuming callback handles UI updates)
            if (callback != null) {
                callback.onSyncComplete(processed, succeeded, failed);
            }
        });
    }

    /**
     * Syncs a single record according to the state machine.
     */
    private void syncSingleRecord(ReadingRecord record) {
        String operationId = record.getOperationId();
        int attempt = record.getAttemptCount();

        try {
            MockSyncService.AckResponse ack = mockSyncService.syncRecord(record);

            // Check if it's a conflict response (valid HTTP response but status=CONFLICT)
            if ("CONFLICT".equals(ack.status)) {
                handleConflict(record, ack);
                return;
            }

            // Validate the ack
            if (!mockSyncService.validateAck(ack, operationId)) {
                handleMalformedAck(record);
                return;
            }

            // Valid ACK - transition to ACKNOWLEDGED
            handleSuccess(record, ack);

        } catch (MockSyncService.SyncException e) {
            if (e.type == MockSyncService.SyncException.Type.TRANSIENT) {
                handleTransientFailure(record, e.getMessage());
            } else {
                handlePermanentFailure(record, e.getMessage());
            }
        } catch (Exception e) {
            // Unexpected error - treat as transient
            Log.e(TAG, "SYNC_TRANSIENT_FAILURE: Unexpected error", e);
            handleTransientFailure(record, "Unexpected error: " + e.getMessage());
        }
    }

    private void handleSuccess(ReadingRecord record, MockSyncService.AckResponse ack) {
        record.setSyncState(ReadingRecord.STATE_ACKNOWLEDGED);
        record.setServerVersion(ack.serverVersion);
        record.setLastError(null);
        // operationId stays the same (it was the successful one)
        // attemptCount stays as-is (successful attempt)

        dao.update(record);

        Log.i(TAG, String.format("SYNC_ACK: operationId=%s recordId=%s serverVersion=%d",
                record.getOperationId(), record.getId(), ack.serverVersion));

        if (callback != null) {
            callback.onRecordSynced(record);
        }
    }

    private void handleTransientFailure(ReadingRecord record, String errorMessage) {
        record.setSyncState(ReadingRecord.STATE_FAILED);
        record.setAttemptCount(record.getAttemptCount() + 1);
        record.setLastError(errorMessage);
        // operationId REUSED for retry
        // localVersion unchanged

        dao.update(record);

        Log.i(TAG, String.format("SYNC_TRANSIENT_FAILURE: operationId=%s recordId=%s attempt=%d error=%s",
                record.getOperationId(), record.getId(), record.getAttemptCount(), errorMessage));

        if (callback != null) {
            callback.onRecordFailed(record, errorMessage);
        }
    }

    private void handlePermanentFailure(ReadingRecord record, String errorMessage) {
        record.setSyncState(ReadingRecord.STATE_FAILED);
        record.setAttemptCount(record.getAttemptCount() + 1);
        record.setLastError(errorMessage);

        dao.update(record);

        Log.i(TAG, String.format("SYNC_PERMANENT_FAILURE: operationId=%s recordId=%s error=%s",
                record.getOperationId(), record.getId(), errorMessage));

        if (callback != null) {
            callback.onRecordFailed(record, errorMessage);
        }
    }

    private void handleConflict(ReadingRecord record, MockSyncService.AckResponse ack) {
        record.setSyncState(ReadingRecord.STATE_CONFLICT);
        record.setServerVersion(ack.serverVersion);
        record.setLastError(String.format("Conflict: server version %d > local version %d",
                ack.serverVersion, record.getServerVersion()));
        // operationId stays (it's the one that got conflict)
        // attemptCount stays

        dao.update(record);

        Log.i(TAG, String.format("SYNC_CONFLICT: operationId=%s recordId=%s serverVersion=%d localVersion=%d",
                record.getOperationId(), record.getId(), ack.serverVersion, record.getServerVersion()));

        if (callback != null) {
            callback.onRecordConflict(record);
        }
    }

    private void handleMalformedAck(ReadingRecord record) {
        record.setSyncState(ReadingRecord.STATE_FAILED);
        record.setAttemptCount(record.getAttemptCount() + 1);
        record.setLastError("Malformed acknowledgement");
        // operationId REUSED (client will retry with same operationId)
        // But since mock stores the malformed ack, retry will get same malformed ack
        // This is intentional - shows malformed ack is not success

        dao.update(record);

        Log.i(TAG, String.format("SYNC_MALFORMED_ACK: operationId=%s recordId=%s",
                record.getOperationId(), record.getId()));

        if (callback != null) {
            callback.onRecordFailed(record, "Malformed acknowledgement");
        }
    }

    /**
     * Resolves a conflict by "Keep my version" - creates NEW operationId and re-queues as PENDING.
     * This is a NEW logical operation, not a retry.
     */
    public void resolveConflictKeepLocal(ReadingRecord record) {
        executor.execute(() -> {
            MockSyncService.AckResponse ack = mockSyncService.resolveConflictKeepLocal(record);
            if (mockSyncService.validateAck(ack, record.getOperationId())) {
                handleSuccess(record, ack);
            } else {
                handleMalformedAck(record);
            }
        });
    }
}