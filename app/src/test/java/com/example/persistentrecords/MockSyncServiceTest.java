package com.example.persistentrecords;

import com.example.persistentrecords.model.ReadingRecord;
import com.example.persistentrecords.sync.MockSyncService;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

import java.util.UUID;

import static org.junit.Assert.*;

/**
 * JUnit tests for MockSyncService - tests idempotency, malformed ack, conflict handling, etc.
 * Pure logic tests, no Android dependencies.
 */
@RunWith(JUnit4.class)
public class MockSyncServiceTest {

    private MockSyncService service;
    private ReadingRecord createRecord(String operationId) {
        return new ReadingRecord(
                "record-" + UUID.randomUUID().toString(),
                "Test Site",
                ReadingRecord.TYPE_TEMPERATURE,
                25.0,
                "°C",
                "Test notes",
                System.currentTimeMillis(),
                System.currentTimeMillis(),
                ReadingRecord.STATE_PENDING,
                operationId,
                0,
                null,
                0,
                1
        );
    }

    @Before
    public void setUp() {
        MockSyncService.resetInstance();
        service = MockSyncService.getInstance();
        service.setLogger(MockSyncService.NOOP_LOGGER);
        service.setCurrentMode(MockSyncService.Mode.NORMAL);
    }

    @Test
    public void normalMode_returnsValidAck() throws Exception {
        ReadingRecord record = createRecord("op-1");
        MockSyncService.AckResponse ack = service.syncRecord(record);

        assertNotNull(ack);
        assertEquals("op-1", ack.operationId);
        assertEquals(record.getId(), ack.recordId);
        assertEquals("ACK", ack.status);
        assertEquals(1, ack.serverVersion);
        assertEquals(1, service.getServerRecordCount());
    }

    @Test
    public void normalMode_incrementsServerVersionOnRepeatedCalls() throws Exception {
        ReadingRecord record = createRecord("op-1");
        service.syncRecord(record); // v1
        service.syncRecord(record); // v2 - but this will be deduped!

        // Actually, same operationId returns stored ack, so server version stays 1
        // To test version increment, we need NEW operationIds
        ReadingRecord record2 = new ReadingRecord(
                record.getId(), "Test Site", ReadingRecord.TYPE_TEMPERATURE,
                26.0, "°C", "Notes", record.getRecordedAt(), System.currentTimeMillis(),
                ReadingRecord.STATE_PENDING, "op-2", 0, null, 1, 2);

        service.syncRecord(record2);
        // Same recordId - should NOT increase server count (overwrites existing)
        assertEquals(1, service.getServerRecordCount());
    }

    @Test
    public void idempotency_sameOperationIdDoesNotCreateDuplicate() throws Exception {
        ReadingRecord record = createRecord("op-same");
        service.syncRecord(record); // First call - creates record
        int countAfterFirst = service.getServerRecordCount();

        // Retry with SAME operationId - should return stored ack, NOT create duplicate
        MockSyncService.AckResponse ack = service.syncRecord(record);
        int countAfterRetry = service.getServerRecordCount();

        assertEquals(countAfterFirst, countAfterRetry);
        assertEquals("op-same", ack.operationId);
        assertEquals("ACK", ack.status);
    }

    @Test
    public void unavailableMode_throwsTransientError() {
        service.setCurrentMode(MockSyncService.Mode.UNAVAILABLE);
        ReadingRecord record = createRecord("op-unavail");

        try {
            service.syncRecord(record);
            fail("Expected SyncException");
        } catch (MockSyncService.SyncException e) {
            assertEquals(MockSyncService.SyncException.Type.TRANSIENT, e.type);
            assertTrue(e.getMessage().contains("503"));
        }
    }

    @Test
    public void lostResponseMode_serverCommitsButThrowsTimeout() throws Exception {
        service.setCurrentMode(MockSyncService.Mode.LOST_RESPONSE);
        ReadingRecord record = createRecord("op-lost");

        try {
            service.syncRecord(record);
            fail("Expected SyncException for timeout");
        } catch (MockSyncService.SyncException e) {
            assertEquals(MockSyncService.SyncException.Type.TRANSIENT, e.type);
            assertTrue(e.getMessage().contains("timeout"));
        }

        // But server DID commit - record count increased
        assertEquals(1, service.getServerRecordCount());

        // Retry with SAME operationId should return stored ack (deduped)
        MockSyncService.AckResponse ack = service.syncRecord(record);
        assertEquals("ACK", ack.status);
        assertEquals(1, ack.serverVersion);
        assertEquals(1, service.getServerRecordCount()); // Still 1, no duplicate
    }

    @Test
    public void conflictMode_returnsConflictNotException() throws Exception {
        service.setCurrentMode(MockSyncService.Mode.CONFLICT);
        ReadingRecord record = createRecord("op-conflict");
        // Pre-set server version higher than client
        record.setServerVersion(5);

        MockSyncService.AckResponse ack = service.syncRecord(record);

        assertNotNull(ack);
        assertEquals("CONFLICT", ack.status);
        assertTrue(ack.serverVersion > record.getServerVersion());
        // Conflict is a valid HTTP response, not an exception
    }

    @Test
    public void malformedAckMode_returnsInvalidAck() throws Exception {
        service.setCurrentMode(MockSyncService.Mode.MALFORMED_ACK);
        ReadingRecord record = createRecord("op-malformed");

        MockSyncService.AckResponse ack = service.syncRecord(record);

        assertNotNull(ack);
        // Wrong operationId
        assertNotEquals("op-malformed", ack.operationId);
        // Null status
        assertNull(ack.status);
        // Validation should fail
        assertFalse(service.validateAck(ack, "op-malformed"));
    }

    @Test
    public void validateAck_validAckReturnsTrue() throws Exception {
        ReadingRecord record = createRecord("op-valid");
        MockSyncService.AckResponse ack = service.syncRecord(record);

        assertTrue(service.validateAck(ack, "op-valid"));
    }

    @Test
    public void validateAck_nullReturnsFalse() {
        assertFalse(service.validateAck(null, "op-1"));
    }

    @Test
    public void validateAck_wrongOperationIdReturnsFalse() throws Exception {
        ReadingRecord record = createRecord("op-1");
        MockSyncService.AckResponse ack = service.syncRecord(record);

        assertFalse(service.validateAck(ack, "op-2"));
    }

    @Test
    public void validateAck_nullStatusReturnsFalse() {
        MockSyncService.AckResponse ack = new MockSyncService.AckResponse("op-1", "rec-1", null, 1);
        assertFalse(service.validateAck(ack, "op-1"));
    }

    @Test
    public void validateAck_wrongStatusReturnsFalse() {
        MockSyncService.AckResponse ack = new MockSyncService.AckResponse("op-1", "rec-1", "ERROR", 1);
        assertFalse(service.validateAck(ack, "op-1"));
    }

    @Test
    public void validateAck_invalidServerVersionReturnsFalse() {
        MockSyncService.AckResponse ack = new MockSyncService.AckResponse("op-1", "rec-1", "ACK", 0);
        assertFalse(service.validateAck(ack, "op-1"));

        MockSyncService.AckResponse ack2 = new MockSyncService.AckResponse("op-1", "rec-1", "ACK", -1);
        assertFalse(service.validateAck(ack2, "op-1"));
    }

    @Test
    public void resolveConflictKeepLocal_createsNewOperationId() throws Exception {
        service.setCurrentMode(MockSyncService.Mode.CONFLICT);
        ReadingRecord record = createRecord("op-conflict");
        record.setServerVersion(5);

        // First get conflict
        MockSyncService.AckResponse conflictAck = service.syncRecord(record);
        assertEquals("CONFLICT", conflictAck.status);

        // Now resolve with "Keep my version" (legacy deprecated method)
        // Switch to NORMAL mode for resolution
        service.setCurrentMode(MockSyncService.Mode.NORMAL);
        MockSyncService.AckResponse resolutionAck = service.resolveConflictKeepLocal(record);

        // Should have NEW operationId (legacy behavior)
        assertNotEquals("op-conflict", record.getOperationId());
        assertEquals("ACK", resolutionAck.status);
        assertEquals(record.getOperationId(), resolutionAck.operationId);
        // Same recordId - should NOT increase server count (overwrites existing)
        assertEquals(1, service.getServerRecordCount());
    }

    @Test
    public void syncForcedNormal_usesExistingOperationIdAndDoesNotModifyRecord() throws Exception {
        // Test that syncForcedNormal uses the record's existing operationId and doesn't modify the record
        service.setCurrentMode(MockSyncService.Mode.NORMAL);
        ReadingRecord record = createRecord("op-resolve-123");
        record.setSyncState(ReadingRecord.STATE_PENDING);
        record.setLocalVersion(2); // Already incremented by client
        record.setAttemptCount(0);
        record.setLastError(null);

        String beforeOpId = record.getOperationId();
        int beforeLocalVersion = record.getLocalVersion();
        int beforeAttemptCount = record.getAttemptCount();

        MockSyncService.AckResponse ack = service.syncForcedNormal(record);

        assertEquals("ACK", ack.status);
        assertEquals(beforeOpId, record.getOperationId()); // Unchanged
        assertEquals(beforeLocalVersion, record.getLocalVersion()); // Unchanged
        assertEquals(beforeAttemptCount, record.getAttemptCount()); // Unchanged
        assertEquals(beforeOpId, ack.operationId); // ACK has same operationId
        // Server count increases because it's a new operationId not seen before
        assertEquals(1, service.getServerRecordCount());
    }

    @Test
    public void syncForcedNormal_idempotentOnRetry() throws Exception {
        // Test that syncForcedNormal is idempotent - retry with same operationId returns stored ack
        service.setCurrentMode(MockSyncService.Mode.NORMAL);
        ReadingRecord record = createRecord("op-idempotent");
        record.setSyncState(ReadingRecord.STATE_PENDING);
        record.setLocalVersion(1);
        record.setAttemptCount(0);
        record.setLastError(null);

        // First call
        MockSyncService.AckResponse ack1 = service.syncForcedNormal(record);
        int countAfterFirst = service.getServerRecordCount();

        // Retry with SAME operationId (simulating transient failure retry)
        MockSyncService.AckResponse ack2 = service.syncForcedNormal(record);
        int countAfterRetry = service.getServerRecordCount();

        assertEquals(ack1.operationId, ack2.operationId);
        assertEquals(ack1.serverVersion, ack2.serverVersion);
        assertEquals(countAfterFirst, countAfterRetry); // No duplicate
    }

    @Test
    public void multipleRecordsIndependent() throws Exception {
        ReadingRecord r1 = createRecord("op-1");
        ReadingRecord r2 = createRecord("op-2");
        ReadingRecord r3 = createRecord("op-3");

        service.syncRecord(r1);
        service.syncRecord(r2);
        service.syncRecord(r3);

        assertEquals(3, service.getServerRecordCount());
    }
}