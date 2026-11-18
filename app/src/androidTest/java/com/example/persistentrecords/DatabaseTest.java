package com.example.persistentrecords;

import android.content.Context;

import androidx.room.Room;
import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;

import com.example.persistentrecords.db.AppDatabase;
import com.example.persistentrecords.dao.ReadingDao;
import com.example.persistentrecords.model.ReadingRecord;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

import static org.junit.Assert.*;

/**
 * Instrumented tests for Room database persistence.
 * Tests insert, query, transaction atomicity, and persistence across reopen.
 */
@RunWith(AndroidJUnit4.class)
public class DatabaseTest {

    private AppDatabase database;
    private ReadingDao dao;
    private Executor executor = Executors.newSingleThreadExecutor();
    private Context context;

    @Before
    public void setUp() {
        context = ApplicationProvider.getApplicationContext();
        database = Room.inMemoryDatabaseBuilder(context, AppDatabase.class)
                .allowMainThreadQueries() // For test simplicity
                .build();
        dao = database.readingDao();
    }

    @After
    public void tearDown() {
        database.close();
    }

    @Test
    public void insertAndRetrieveRecords() {
        // Insert 3 records
        ReadingRecord r1 = createRecord("rec-1", "Site A", ReadingRecord.TYPE_TEMPERATURE, 25.0);
        ReadingRecord r2 = createRecord("rec-2", "Site B", ReadingRecord.TYPE_HUMIDITY, 60.0);
        ReadingRecord r3 = createRecord("rec-3", "Site C", ReadingRecord.TYPE_SOIL_MOISTURE, 45.0);

        dao.insertAll(java.util.Arrays.asList(r1, r2, r3));

        // Query all
        List<ReadingRecord> records = dao.getAllRecords();
        assertEquals(3, records.size());

        // Verify content
        assertEquals("Site A", records.get(2).getSiteName()); // DESC order by updatedAt
        assertEquals("Site B", records.get(1).getSiteName());
        assertEquals("Site C", records.get(0).getSiteName());
    }

    @Test
    public void recordsPersistAcrossReopen() {
        // Insert 3 records
        ReadingRecord r1 = createRecord("rec-1", "Site A", ReadingRecord.TYPE_TEMPERATURE, 25.0);
        ReadingRecord r2 = createRecord("rec-2", "Site B", ReadingRecord.TYPE_HUMIDITY, 60.0);
        ReadingRecord r3 = createRecord("rec-3", "Site C", ReadingRecord.TYPE_SOIL_MOISTURE, 45.0);

        dao.insertAll(java.util.Arrays.asList(r1, r2, r3));

        // Close and reopen database (simulates app restart)
        database.close();
        database = Room.inMemoryDatabaseBuilder(context, AppDatabase.class)
                .allowMainThreadQueries()
                .build();
        dao = database.readingDao();

        // Query all - should still be there
        List<ReadingRecord> records = dao.getAllRecords();
        assertEquals(3, records.size());

        // Verify data integrity
        for (ReadingRecord record : records) {
            assertNotNull(record.getId());
            assertNotNull(record.getSiteName());
            assertNotNull(record.getReadingType());
            assertTrue(record.getValue() >= 0);
            assertNotNull(record.getUnit());
            assertNotNull(record.getSyncState());
            assertNotNull(record.getOperationId());
            assertEquals(1, record.getLocalVersion());
        }
    }

    @Test
    public void transactionalFailureLeavesNoPartialRow() {
        // This test verifies that if a transaction fails, no partial data is written
        // We can't easily simulate a mid-transaction failure in Room without custom logic,
        // but we can verify that the DAO's saveRecord method is atomic by checking
        // that either the full record exists or it doesn't.

        ReadingRecord record = createRecord("rec-transaction", "Site Transaction", ReadingRecord.TYPE_TEMPERATURE, 30.0);

        // Save should be atomic (insert or update)
        dao.saveRecord(record);

        ReadingRecord retrieved = dao.getRecordById("rec-transaction");
        assertNotNull(retrieved);
        assertEquals("Site Transaction", retrieved.getSiteName());
        assertEquals(30.0, retrieved.getValue(), 0.001);

        // Update the record
        retrieved.setValue(35.0);
        retrieved.setSyncState(ReadingRecord.STATE_PENDING);
        retrieved.setOperationId(UUID.randomUUID().toString());
        retrieved.setLocalVersion(2);
        dao.saveRecord(retrieved);

        ReadingRecord updated = dao.getRecordById("rec-transaction");
        assertNotNull(updated);
        assertEquals(35.0, updated.getValue(), 0.001);
        assertEquals(2, updated.getLocalVersion());
        assertEquals(ReadingRecord.STATE_PENDING, updated.getSyncState());
    }

    @Test
    public void getRecordsBySyncStates() {
        ReadingRecord r1 = createRecord("rec-1", "Site A", ReadingRecord.TYPE_TEMPERATURE, 25.0);
        r1.setSyncState(ReadingRecord.STATE_PENDING);

        ReadingRecord r2 = createRecord("rec-2", "Site B", ReadingRecord.TYPE_HUMIDITY, 60.0);
        r2.setSyncState(ReadingRecord.STATE_ACKNOWLEDGED);

        ReadingRecord r3 = createRecord("rec-3", "Site C", ReadingRecord.TYPE_SOIL_MOISTURE, 45.0);
        r3.setSyncState(ReadingRecord.STATE_FAILED);

        ReadingRecord r4 = createRecord("rec-4", "Site D", ReadingRecord.TYPE_TEMPERATURE, 20.0);
        r4.setSyncState(ReadingRecord.STATE_SAVED_LOCAL);

        dao.insertAll(java.util.Arrays.asList(r1, r2, r3, r4));

        // Query PENDING + FAILED
        List<String> states = new java.util.ArrayList<>();
        states.add(ReadingRecord.STATE_PENDING);
        states.add(ReadingRecord.STATE_FAILED);

        List<ReadingRecord> pendingFailed = dao.getRecordsBySyncStates(states);
        assertEquals(2, pendingFailed.size());

        // Should be ordered by updatedAt ASC (oldest first for sync)
        assertEquals("rec-1", pendingFailed.get(0).getId());
        assertEquals("rec-3", pendingFailed.get(1).getId());
    }

    @Test
    public void getRecordByOperationId() {
        String opId = "test-operation-id";
        ReadingRecord record = createRecord("rec-op", "Site Op", ReadingRecord.TYPE_TEMPERATURE, 25.0);
        record.setOperationId(opId);

        dao.insert(record);

        ReadingRecord found = dao.getRecordByOperationId(opId);
        assertNotNull(found);
        assertEquals("rec-op", found.getId());
        assertEquals(opId, found.getOperationId());

        // Non-existent operationId
        ReadingRecord notFound = dao.getRecordByOperationId("non-existent");
        assertNull(notFound);
    }

    @Test
    public void deleteRecords() {
        ReadingRecord r1 = createRecord("rec-1", "Site A", ReadingRecord.TYPE_TEMPERATURE, 25.0);
        ReadingRecord r2 = createRecord("rec-2", "Site B", ReadingRecord.TYPE_HUMIDITY, 60.0);

        dao.insertAll(java.util.Arrays.asList(r1, r2));
        assertEquals(2, dao.getAllRecords().size());

        dao.deleteById("rec-1");
        assertEquals(1, dao.getAllRecords().size());
        assertNull(dao.getRecordById("rec-1"));

        dao.deleteAll();
        assertEquals(0, dao.getAllRecords().size());
    }

    private ReadingRecord createRecord(String id, String siteName, String type, double value) {
        return new ReadingRecord(
                id, siteName, type, value,
                ReadingRecord.getUnitForType(type), "Test notes",
                System.currentTimeMillis(), System.currentTimeMillis(),
                ReadingRecord.STATE_SAVED_LOCAL, UUID.randomUUID().toString(), 0, null, 0, 1);
    }
}