package com.example.persistentrecords.dao;

import androidx.room.Dao;
import androidx.room.Insert;
import androidx.room.OnConflictStrategy;
import androidx.room.Query;
import androidx.room.Transaction;
import androidx.room.Update;

import com.example.persistentrecords.model.ReadingRecord;

import java.util.List;

/**
 * DAO for ReadingRecord entity.
 * All write operations are wrapped in @Transaction to ensure atomicity.
 */
@Dao
public interface ReadingDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    void insert(ReadingRecord record);

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    void insertAll(List<ReadingRecord> records);

    @Update
    void update(ReadingRecord record);

    @Update
    void updateAll(List<ReadingRecord> records);

    @Query("SELECT * FROM readings ORDER BY updatedAt DESC")
    List<ReadingRecord> getAllRecords();

    @Query("SELECT * FROM readings WHERE id = :id")
    ReadingRecord getRecordById(String id);

    @Query("SELECT * FROM readings WHERE syncState IN (:states) ORDER BY updatedAt ASC")
    List<ReadingRecord> getRecordsBySyncStates(List<String> states);

    @Query("SELECT * FROM readings WHERE operationId = :operationId")
    ReadingRecord getRecordByOperationId(String operationId);

    @Query("DELETE FROM readings")
    void deleteAll();

    @Query("DELETE FROM readings WHERE id = :id")
    void deleteById(String id);

    /**
     * Atomically saves a new record or updates an existing one.
     * This transaction ensures that either the entire operation succeeds or fails.
     */
    @Transaction
    default void saveRecord(ReadingRecord record) {
        // Check if record exists
        ReadingRecord existing = getRecordById(record.getId());
        if (existing != null) {
            // Update existing record - increment localVersion, generate new operationId for edit
            record.setLocalVersion(existing.getLocalVersion() + 1);
            record.setRecordedAt(existing.getRecordedAt()); // Keep original recordedAt
            update(record);
        } else {
            // Insert new record
            insert(record);
        }
    }

    /**
     * Gets count of records for UI display.
     */
    @Query("SELECT COUNT(*) FROM readings")
    int getRecordCount();
}