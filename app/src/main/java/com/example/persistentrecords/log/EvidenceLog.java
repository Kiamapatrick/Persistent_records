package com.example.persistentrecords.log;

import android.util.Log;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * In-memory evidence log for the app.
 * Stores last ~100 log events with evidence IDs for screenshots.
 * All important events are logged here AND to Android Log with tag "HM8".
 */
public class EvidenceLog {

    private static final String TAG = "HM8";
    private static final int MAX_ENTRIES = 100;

    private static volatile EvidenceLog INSTANCE;
    private final List<LogEntry> entries = Collections.synchronizedList(new ArrayList<>());

    private EvidenceLog() {}

    public static EvidenceLog getInstance() {
        if (INSTANCE == null) {
            synchronized (EvidenceLog.class) {
                if (INSTANCE == null) {
                    INSTANCE = new EvidenceLog();
                }
            }
        }
        return INSTANCE;
    }

    public static void resetInstance() {
        INSTANCE = new EvidenceLog();
        LogEntry.resetCounter();
    }

    /**
     * Adds a log entry and also writes to Android Log with HM8 tag.
     */
    public void log(String tag, String message) {
        LogEntry entry = new LogEntry(tag, message);
        addEntry(entry);
        Log.i(TAG, String.format("[%s] %s %s", entry.getEvidenceId(), tag, message));
    }

    /**
     * Adds a log entry with a specific evidence ID.
     */
    public void logWithId(String tag, String message, String evidenceId) {
        LogEntry entry = new LogEntry(tag, message, evidenceId);
        addEntry(entry);
        Log.i(TAG, String.format("[%s] %s %s", evidenceId, tag, message));
    }

    private void addEntry(LogEntry entry) {
        synchronized (entries) {
            entries.add(0, entry); // Newest first
            if (entries.size() > MAX_ENTRIES) {
                entries.remove(entries.size() - 1);
            }
        }
    }

    /**
     * Returns a copy of all log entries (newest first).
     */
    public List<LogEntry> getEntries() {
        synchronized (entries) {
            return new ArrayList<>(entries);
        }
    }

    public void clear() {
        synchronized (entries) {
            entries.clear();
            LogEntry.resetCounter();
        }
    }

    public int size() {
        return entries.size();
    }
}