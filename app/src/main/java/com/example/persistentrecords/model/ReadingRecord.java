package com.example.persistentrecords.model;

import androidx.annotation.NonNull;
import androidx.room.Entity;
import androidx.room.PrimaryKey;
import androidx.room.Ignore;

/**
 * Room entity representing a single field/sensor reading record.
 * Table name: readings
 */
@Entity(tableName = "readings")
public class ReadingRecord {

    // Sync state constants
    public static final String STATE_SAVED_LOCAL = "SAVED_LOCAL";
    public static final String STATE_PENDING = "PENDING";
    public static final String STATE_ACKNOWLEDGED = "ACKNOWLEDGED";
    public static final String STATE_FAILED = "FAILED";
    public static final String STATE_CONFLICT = "CONFLICT";

    // Reading type constants
    public static final String TYPE_TEMPERATURE = "Temperature";
    public static final String TYPE_HUMIDITY = "Humidity";
    public static final String TYPE_SOIL_MOISTURE = "Soil Moisture";

    @PrimaryKey
    @NonNull
    private String id; // client-generated UUID

    @NonNull
    private String siteName; // required, 3-40 chars

    @NonNull
    private String readingType; // one of: Temperature, Humidity, Soil Moisture

    private double value; // required, must be in valid range per type

    @NonNull
    private String unit; // derived from type: °C, %RH, %VWC

    private String notes; // optional, max 120 chars

    private long recordedAt; // epoch millis, set when first saved
    private long updatedAt; // epoch millis, updated on each edit

    @NonNull
    private String syncState; // SAVED_LOCAL, PENDING, ACKNOWLEDGED, FAILED, CONFLICT

    @NonNull
    private String operationId; // UUID generated ONCE per logical save/edit operation

    private int attemptCount; // incremented on each retry

    private String lastError; // nullable, last error message

    private int serverVersion; // 0 until acknowledged
    private int localVersion; // incremented on each local edit

    // Default constructor for Room
    public ReadingRecord() {
        this.id = "";
        this.siteName = "";
        this.readingType = "";
        this.value = 0.0;
        this.unit = "";
        this.notes = "";
        this.recordedAt = 0L;
        this.updatedAt = 0L;
        this.syncState = STATE_SAVED_LOCAL;
        this.operationId = "";
        this.attemptCount = 0;
        this.lastError = null;
        this.serverVersion = 0;
        this.localVersion = 1;
    }

    // Full constructor for creating new records
    @Ignore
    public ReadingRecord(@NonNull String id, @NonNull String siteName, @NonNull String readingType,
                         double value, @NonNull String unit, String notes,
                         long recordedAt, long updatedAt, @NonNull String syncState,
                         @NonNull String operationId, int attemptCount, String lastError,
                         int serverVersion, int localVersion) {
        this.id = id;
        this.siteName = siteName;
        this.readingType = readingType;
        this.value = value;
        this.unit = unit;
        this.notes = notes;
        this.recordedAt = recordedAt;
        this.updatedAt = updatedAt;
        this.syncState = syncState;
        this.operationId = operationId;
        this.attemptCount = attemptCount;
        this.lastError = lastError;
        this.serverVersion = serverVersion;
        this.localVersion = localVersion;
    }

    // Getters and Setters
    @NonNull
    public String getId() { return id; }
    public void setId(@NonNull String id) { this.id = id; }

    @NonNull
    public String getSiteName() { return siteName; }
    public void setSiteName(@NonNull String siteName) { this.siteName = siteName; }

    @NonNull
    public String getReadingType() { return readingType; }
    public void setReadingType(@NonNull String readingType) { this.readingType = readingType; }

    public double getValue() { return value; }
    public void setValue(double value) { this.value = value; }

    @NonNull
    public String getUnit() { return unit; }
    public void setUnit(@NonNull String unit) { this.unit = unit; }

    public String getNotes() { return notes; }
    public void setNotes(String notes) { this.notes = notes; }

    public long getRecordedAt() { return recordedAt; }
    public void setRecordedAt(long recordedAt) { this.recordedAt = recordedAt; }

    public long getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(long updatedAt) { this.updatedAt = updatedAt; }

    @NonNull
    public String getSyncState() { return syncState; }
    public void setSyncState(@NonNull String syncState) { this.syncState = syncState; }

    @NonNull
    public String getOperationId() { return operationId; }
    public void setOperationId(@NonNull String operationId) { this.operationId = operationId; }

    public int getAttemptCount() { return attemptCount; }
    public void setAttemptCount(int attemptCount) { this.attemptCount = attemptCount; }

    public String getLastError() { return lastError; }
    public void setLastError(String lastError) { this.lastError = lastError; }

    public int getServerVersion() { return serverVersion; }
    public void setServerVersion(int serverVersion) { this.serverVersion = serverVersion; }

    public int getLocalVersion() { return localVersion; }
    public void setLocalVersion(int localVersion) { this.localVersion = localVersion; }

    /**
     * Returns the unit string for a given reading type.
     */
    public static String getUnitForType(@NonNull String type) {
        switch (type) {
            case TYPE_TEMPERATURE:
                return "°C";
            case TYPE_HUMIDITY:
                return "%RH";
            case TYPE_SOIL_MOISTURE:
                return "%VWC";
            default:
                return "";
        }
    }

    /**
     * Returns the validation range for a given reading type.
     * @return int array [min, max] or null if type unknown
     */
    public static int[] getRangeForType(@NonNull String type) {
        switch (type) {
            case TYPE_TEMPERATURE:
                return new int[]{-10, 60};
            case TYPE_HUMIDITY:
                return new int[]{0, 100};
            case TYPE_SOIL_MOISTURE:
                return new int[]{0, 100};
            default:
                return null;
        }
    }
}