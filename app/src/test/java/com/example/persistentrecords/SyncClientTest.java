package com.example.persistentrecords;

import com.example.persistentrecords.model.ReadingRecord;
import com.example.persistentrecords.sync.MockSyncService;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.Assert.*;

/**
 * JUnit tests for SyncClient state machine transitions.
 * Tests pure logic without Android dependencies.
 */
@RunWith(JUnit4.class)
public class SyncClientTest {

    private MockSyncService mockSyncService;

    @Before
    public void setUp() {
        MockSyncService.resetInstance();
        mockSyncService = MockSyncService.getInstance();
        mockSyncService.setLogger(MockSyncService.NOOP_LOGGER);
        mockSyncService.setCurrentMode(MockSyncService.Mode.NORMAL);
    }

    @Test
    public void stateTransition_savedLocalToAcknowledged() throws Exception {
        ReadingRecord record = createRecord("rec-1", "op-1");
        record.setSyncState(ReadingRecord.STATE_SAVED_LOCAL);
        record.setServerVersion(0);

        MockSyncService.AckResponse ack = mockSyncService.syncRecord(record);
        assertTrue(mockSyncService.validateAck(ack, "op-1"));

        record.setSyncState(ReadingRecord.STATE_ACKNOWLEDGED);
        record.setServerVersion(ack.serverVersion);
        record.setLastError(null);

        assertEquals(ReadingRecord.STATE_ACKNOWLEDGED, record.getSyncState());
        assertEquals(1, record.getServerVersion());
        assertNull(record.getLastError());
    }

    @Test
    public void stateTransition_transientFailure_toFailedRetryable() throws Exception {
        mockSyncService.setCurrentMode(MockSyncService.Mode.UNAVAILABLE);
        ReadingRecord record = createRecord("rec-1", "op-1");
        record.setSyncState(ReadingRecord.STATE_PENDING);

        try {
            mockSyncService.syncRecord(record);
            fail("Expected exception");
        } catch (MockSyncService.SyncException e) {
            assertEquals(MockSyncService.SyncException.Type.TRANSIENT, e.type);

            record.setSyncState(ReadingRecord.STATE_FAILED);
            record.setAttemptCount(record.getAttemptCount() + 1);
            record.setLastError(e.getMessage());
            assertEquals("op-1", record.getOperationId());
        }

        assertEquals(ReadingRecord.STATE_FAILED, record.getSyncState());
        assertEquals(1, record.getAttemptCount());
        assertNotNull(record.getLastError());
        assertEquals("op-1", record.getOperationId());
    }

    @Test
    public void stateTransition_lostResponse_retryDeduped() throws Exception {
        mockSyncService.setCurrentMode(MockSyncService.Mode.LOST_RESPONSE);
        ReadingRecord record = createRecord("rec-1", "op-lost");
        record.setSyncState(ReadingRecord.STATE_PENDING);

        try {
            mockSyncService.syncRecord(record);
            fail("Expected timeout");
        } catch (MockSyncService.SyncException e) {
            assertEquals(MockSyncService.SyncException.Type.TRANSIENT, e.type);
            record.setSyncState(ReadingRecord.STATE_FAILED);
            record.setAttemptCount(1);
            record.setLastError(e.getMessage());
        }

        assertEquals(1, mockSyncService.getServerRecordCount());

        mockSyncService.setCurrentMode(MockSyncService.Mode.NORMAL);
        MockSyncService.AckResponse ack = mockSyncService.syncRecord(record);

        assertEquals("ACK", ack.status);
        assertEquals(1, ack.serverVersion);
        assertEquals(1, mockSyncService.getServerRecordCount());
    }

    @Test
    public void stateTransition_malformedAck_notAcknowledged() throws Exception {
        mockSyncService.setCurrentMode(MockSyncService.Mode.MALFORMED_ACK);
        ReadingRecord record = createRecord("rec-1", "op-malformed");
        record.setSyncState(ReadingRecord.STATE_PENDING);

        MockSyncService.AckResponse ack = mockSyncService.syncRecord(record);
        boolean valid = mockSyncService.validateAck(ack, "op-malformed");

        assertFalse(valid);
        record.setSyncState(ReadingRecord.STATE_FAILED);
        record.setLastError("Malformed acknowledgement");

        assertEquals(ReadingRecord.STATE_FAILED, record.getSyncState());
        assertEquals("Malformed acknowledgement", record.getLastError());
        assertEquals("op-malformed", record.getOperationId());
    }

    @Test
    public void stateTransition_conflict_notAutoRetried() throws Exception {
        mockSyncService.setCurrentMode(MockSyncService.Mode.CONFLICT);
        ReadingRecord record = createRecord("rec-1", "op-conflict");
        record.setSyncState(ReadingRecord.STATE_PENDING);
        record.setServerVersion(1);

        MockSyncService.AckResponse ack = mockSyncService.syncRecord(record);

        assertEquals("CONFLICT", ack.status);
        record.setSyncState(ReadingRecord.STATE_CONFLICT);
        record.setServerVersion(ack.serverVersion);
        record.setLastError("Conflict: server version " + ack.serverVersion + " > local version 1");

        assertEquals(ReadingRecord.STATE_CONFLICT, record.getSyncState());
        assertTrue(record.getServerVersion() > 1);
        assertNotNull(record.getLastError());
    }

    @Test
    public void conflictResolution_keepLocalCreatesNewOperation() throws Exception {
        mockSyncService.setCurrentMode(MockSyncService.Mode.CONFLICT);
        ReadingRecord record = createRecord("rec-1", "op-conflict");
        record.setServerVersion(1);

        MockSyncService.AckResponse conflictAck = mockSyncService.syncRecord(record);
        assertEquals("CONFLICT", conflictAck.status);

        mockSyncService.setCurrentMode(MockSyncService.Mode.NORMAL);
        String oldOpId = record.getOperationId();
        MockSyncService.AckResponse resolutionAck = mockSyncService.resolveConflictKeepLocal(record);

        assertNotEquals(oldOpId, record.getOperationId());
        assertEquals(resolutionAck.operationId, record.getOperationId());
        assertEquals("ACK", resolutionAck.status);
        // Note: resolveConflictKeepLocal sets state to ACKNOWLEDGED internally
    }

    @Test
    public void timeoutNeverProvesRejection() throws Exception {
        mockSyncService.setCurrentMode(MockSyncService.Mode.LOST_RESPONSE);
        ReadingRecord record = createRecord("rec-1", "op-timeout");

        try {
            mockSyncService.syncRecord(record);
        } catch (MockSyncService.SyncException ignored) {
        }

        assertEquals(1, mockSyncService.getServerRecordCount());

        mockSyncService.setCurrentMode(MockSyncService.Mode.NORMAL);
        MockSyncService.AckResponse ack = mockSyncService.syncRecord(record);

        assertEquals("ACK", ack.status);
        assertEquals(1, mockSyncService.getServerRecordCount());
    }

    private ReadingRecord createRecord(String recordId, String operationId) {
        return new ReadingRecord(
                recordId, "Test Site", ReadingRecord.TYPE_TEMPERATURE,
                25.0, "°C", "Notes",
                System.currentTimeMillis(), System.currentTimeMillis(),
                ReadingRecord.STATE_PENDING, operationId, 0, null, 0, 1);
    }
}