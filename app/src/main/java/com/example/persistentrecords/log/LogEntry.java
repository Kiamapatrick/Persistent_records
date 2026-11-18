package com.example.persistentrecords.log;

/**
 * Represents a single log entry for the evidence log screen.
 * Kept in memory for easy screenshot capture.
 */
public class LogEntry {
    private final long timestamp;
    private final String tag;
    private final String message;
    private final String evidenceId; // E1, E2, etc. for screenshot reference

    private static int evidenceCounter = 0;

    public LogEntry(String tag, String message) {
        this.timestamp = System.currentTimeMillis();
        this.tag = tag;
        this.message = message;
        this.evidenceId = "E" + (++evidenceCounter);
    }

    public LogEntry(String tag, String message, String evidenceId) {
        this.timestamp = System.currentTimeMillis();
        this.tag = tag;
        this.message = message;
        this.evidenceId = evidenceId;
    }

    public long getTimestamp() { return timestamp; }
    public String getTag() { return tag; }
    public String getMessage() { return message; }
    public String getEvidenceId() { return evidenceId; }

    public static void resetCounter() { evidenceCounter = 0; }
    public static int getCounter() { return evidenceCounter; }
}