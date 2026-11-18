package com.example.persistentrecords.db;

import android.content.Context;

import androidx.annotation.NonNull;
import androidx.room.Database;
import androidx.room.Room;
import androidx.room.RoomDatabase;
import androidx.sqlite.db.SupportSQLiteDatabase;

import com.example.persistentrecords.dao.ReadingDao;
import com.example.persistentrecords.model.ReadingRecord;

/**
 * Room database for the Field Readings app.
 * Single instance pattern with callback for initialization logging.
 */
@Database(entities = {ReadingRecord.class}, version = 1, exportSchema = true)
public abstract class AppDatabase extends RoomDatabase {

    private static volatile AppDatabase INSTANCE;
    private static final String DATABASE_NAME = "field_readings_db";

    public abstract ReadingDao readingDao();

    /**
     * Gets the singleton database instance.
     */
    public static AppDatabase getInstance(@NonNull Context context) {
        if (INSTANCE == null) {
            synchronized (AppDatabase.class) {
                if (INSTANCE == null) {
                    INSTANCE = Room.databaseBuilder(
                                    context.getApplicationContext(),
                                    AppDatabase.class,
                                    DATABASE_NAME)
                            .addCallback(new Callback() {
                                @Override
                                public void onOpen(@NonNull SupportSQLiteDatabase db) {
                                    super.onOpen(db);
                                    android.util.Log.i("HM8", "APP_START: Database opened, loading records...");
                                }
                            })
                            .build();
                }
            }
        }
        return INSTANCE;
    }

    /**
     * For testing: allows replacing the instance.
     */
    public static void setInstanceForTesting(AppDatabase instance) {
        INSTANCE = instance;
    }
}