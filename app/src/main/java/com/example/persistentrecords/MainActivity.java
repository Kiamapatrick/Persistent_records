package com.example.persistentrecords;

import android.content.Intent;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.util.Log;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.AutoCompleteTextView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.lifecycle.Lifecycle;
import androidx.lifecycle.LifecycleEventObserver;
import androidx.recyclerview.widget.LinearLayoutManager;

import com.example.persistentrecords.databinding.ActivityMainBinding;
import com.example.persistentrecords.db.AppDatabase;
import com.example.persistentrecords.dao.ReadingDao;
import com.example.persistentrecords.log.EvidenceLog;
import com.example.persistentrecords.model.ReadingRecord;
import com.example.persistentrecords.sync.MockSyncService;
import com.example.persistentrecords.sync.SyncClient;
import com.example.persistentrecords.ui.EvidenceLogActivity;
import com.example.persistentrecords.ui.ReadingAdapter;
import com.example.persistentrecords.util.ValidationUtil;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

/**
 * MainActivity - Field Readings Form and List screen.
 * Combines form for creating/editing records and RecyclerView for listing all records.
 * Includes debug/test controls for mock server modes and local write failure simulation.
 */
public class MainActivity extends AppCompatActivity implements
        ReadingAdapter.OnRecordClickListener,
        SyncClient.SyncCallback {

    private static final String TAG = "HM8";

    private ActivityMainBinding binding;
    private AppDatabase database;
    private ReadingDao dao;
    private MockSyncService mockSyncService;
    private SyncClient syncClient;
    private ReadingAdapter adapter;
    private Executor executor = Executors.newSingleThreadExecutor();

    // Current record being edited (null = new record)
    private ReadingRecord currentRecord = null;

    // Mock server mode options
    private static final String[] MOCK_MODES = {
            "NORMAL",
            "UNAVAILABLE",
            "LOST_RESPONSE",
            "CONFLICT",
            "MALFORMED_ACK"
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        binding = ActivityMainBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());

        // Window insets
        WindowCompat.enableEdgeToEdge(getWindow());
        ViewCompat.setOnApplyWindowInsetsListener(binding.main, (v, insets) -> {
            Insets systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom);
            return insets;
        });

        // Toolbar
        setSupportActionBar(binding.toolbar);

        // Initialize database and services
        database = AppDatabase.getInstance(this);
        dao = database.readingDao();
        mockSyncService = MockSyncService.getInstance();
        syncClient = new SyncClient(dao, mockSyncService, this);

        // Setup UI components
        setupReadingTypeSpinner();
        setupMockModeSpinner();
        setupFormValidation();
        setupButtons();
        setupRecyclerView();
        setupFab();

        // Log app start with record count (run on executor before async load)
        executor.execute(() -> {
            int n = dao.getRecordCount();
            EvidenceLog.getInstance().log(TAG, "APP_START: Loaded " + n + " records from Room");
        });

        // Load initial data
        loadRecords();
    }

    private void setupReadingTypeSpinner() {
        String[] types = {ReadingRecord.TYPE_TEMPERATURE, ReadingRecord.TYPE_HUMIDITY, ReadingRecord.TYPE_SOIL_MOISTURE};
        ArrayAdapter<String> adapter = new ArrayAdapter<>(this, android.R.layout.simple_dropdown_item_1line, types);
        binding.actvReadingType.setAdapter(adapter);
        binding.actvReadingType.setText(types[0], false);
    }

    private void setupMockModeSpinner() {
        ArrayAdapter<String> adapter = new ArrayAdapter<>(this, android.R.layout.simple_dropdown_item_1line, MOCK_MODES);
        binding.actvMockMode.setAdapter(adapter);
        binding.actvMockMode.setText(MOCK_MODES[0], false);

        binding.actvMockMode.setOnItemClickListener((parent, view, position, id) -> {
            MockSyncService.Mode mode = MockSyncService.Mode.valueOf(MOCK_MODES[position]);
            mockSyncService.setCurrentMode(mode);
            updateServerCountDisplay();
        });
    }

    private void setupFormValidation() {
        // Real-time validation feedback
        TextWatcher watcher = new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {
                validateFormFields();
            }
            @Override public void afterTextChanged(Editable s) {}
        };

        binding.etSiteName.addTextChangedListener(watcher);
        binding.etValue.addTextChangedListener(watcher);
        binding.etNotes.addTextChangedListener(watcher);
        binding.actvReadingType.setOnItemClickListener((parent, view, position, id) -> validateFormFields());
    }

    private void setupButtons() {
        binding.btnSave.setOnClickListener(v -> saveRecord());

        binding.btnSyncPending.setOnClickListener(v -> {
            binding.tvSyncStatus.setVisibility(View.GONE);
            syncClient.syncPending();
        });

        binding.switchSimulateFailure.setOnCheckedChangeListener((buttonView, isChecked) -> {
            String msg = isChecked ? "TEST HOOK ENABLED: Local writes will fail" : "TEST HOOK DISABLED: Normal writes";
            Toast.makeText(this, msg, Toast.LENGTH_SHORT).show();
            EvidenceLog.getInstance().log(TAG, "TEST_HOOK: Simulate failure = " + isChecked);
        });
    }

    private void setupRecyclerView() {
        adapter = new ReadingAdapter(this);
        binding.rvRecords.setLayoutManager(new LinearLayoutManager(this));
        binding.rvRecords.setAdapter(adapter);
    }

    private void setupFab() {
        binding.fabEvidenceLog.setOnClickListener(v -> {
            startActivity(new Intent(this, EvidenceLogActivity.class));
        });
    }

    private void validateFormFields() {
        String siteName = binding.etSiteName.getText().toString();
        String readingType = binding.actvReadingType.getText().toString();
        String value = binding.etValue.getText().toString();
        String notes = binding.etNotes.getText().toString();

        ValidationUtil.FormValidationResult result = ValidationUtil.validateForm(siteName, readingType, value, notes);

        // Show/hide errors on fields
        showError(binding.tilSiteName, result.siteName);
        showError(binding.tilReadingType, result.readingType);
        showError(binding.tilValue, result.value);
        showError(binding.tilNotes, result.notes);

        // Enable/disable save button
        binding.btnSave.setEnabled(result.isAllValid());
    }

    private void showError(com.google.android.material.textfield.TextInputLayout til, ValidationUtil.ValidationResult result) {
        if (result.isValid) {
            til.setError(null);
        } else {
            til.setError(result.errorMessage);
        }
    }

    private void saveRecord() {
        // Final validation
        String siteName = binding.etSiteName.getText().toString().trim();
        String readingType = binding.actvReadingType.getText().toString();
        String valueStr = binding.etValue.getText().toString().trim();
        String notes = binding.etNotes.getText().toString().trim();

        ValidationUtil.FormValidationResult result = ValidationUtil.validateForm(siteName, readingType, valueStr, notes);
        if (!result.isAllValid()) {
            showError(binding.tilSiteName, result.siteName);
            showError(binding.tilReadingType, result.readingType);
            showError(binding.tilValue, result.value);
            showError(binding.tilNotes, result.notes);
            EvidenceLog.getInstance().log(TAG, "VALIDATION_REJECTED: " + getFirstError(result));
            showFormStatus("Validation failed - check fields", true);
            return;
        }

        double value = Double.parseDouble(valueStr);
        String unit = ReadingRecord.getUnitForType(readingType);
        long now = System.currentTimeMillis();

        // Check if simulating write failure
        boolean simulateFailure = binding.switchSimulateFailure.isChecked();

        executor.execute(() -> {
            try {
                ReadingRecord record;
                if (currentRecord == null) {
                    // New record
                    String id = UUID.randomUUID().toString();
                    String operationId = UUID.randomUUID().toString();
                    record = new ReadingRecord(id, siteName, readingType, value, unit, notes,
                            now, now, ReadingRecord.STATE_PENDING, operationId, 0, null, 0, 1);
                    EvidenceLog.getInstance().log(TAG, "SAVE_COMMIT: New record id=" + id + " operationId=" + operationId);
                } else {
                    // Editing existing record - increment localVersion, NEW operationId, state back to PENDING
                    String newOperationId = UUID.randomUUID().toString();
                    record = new ReadingRecord(
                            currentRecord.getId(), siteName, readingType, value, unit, notes,
                            currentRecord.getRecordedAt(), now,
                            ReadingRecord.STATE_PENDING, newOperationId, 0, null,
                            currentRecord.getServerVersion(), currentRecord.getLocalVersion() + 1);
                    EvidenceLog.getInstance().log(TAG, "SAVE_COMMIT: Edit record id=" + record.getId() +
                            " newOperationId=" + newOperationId + " localVersion=" + record.getLocalVersion());
                }

                if (simulateFailure) {
                    throw new RuntimeException("TEST HOOK: Simulated local write failure");
                }

                // Save in transaction (DAO handles insert vs update)
                dao.saveRecord(record);

                runOnUiThread(() -> {
                    // Only show success AFTER transaction committed
                    clearForm();
                    showFormStatus("Saved on this device (pending sync)", false);
                    Toast.makeText(MainActivity.this, "Saved on this device (pending sync)", Toast.LENGTH_SHORT).show();
                    EvidenceLog.getInstance().log(TAG, "SAVE_COMMIT: Transaction committed for recordId=" + record.getId());
                    loadRecords();
                    updateServerCountDisplay();
                });

            } catch (Exception e) {
                runOnUiThread(() -> {
                    String error = "Save failed: " + e.getMessage();
                    showFormStatus(error, true);
                    EvidenceLog.getInstance().log(TAG, "SAVE_FAILED: recordId=" + (currentRecord != null ? currentRecord.getId() : "new") + " error=" + e.getMessage());
                    // KEEP the draft in the form - do NOT clear it
                    Toast.makeText(MainActivity.this, error, Toast.LENGTH_LONG).show();
                });
            }
        });
    }

    private String getFirstError(ValidationUtil.FormValidationResult result) {
        if (!result.siteName.isValid) return result.siteName.errorMessage;
        if (!result.readingType.isValid) return result.readingType.errorMessage;
        if (!result.value.isValid) return result.value.errorMessage;
        if (!result.notes.isValid) return result.notes.errorMessage;
        return "Unknown validation error";
    }

    private void showFormStatus(String message, boolean isError) {
        binding.tvFormStatus.setText(message);
        binding.tvFormStatus.setTextColor(isError ? 0xFFF44336 : 0xFF4CAF50);
        binding.tvFormStatus.setVisibility(View.VISIBLE);
    }

    private void clearForm() {
        currentRecord = null;
        binding.etSiteName.setText("");
        binding.actvReadingType.setText(ReadingRecord.TYPE_TEMPERATURE, false);
        binding.etValue.setText("");
        binding.etNotes.setText("");
        binding.btnSave.setText("Save Reading");
        binding.tvFormStatus.setVisibility(View.GONE);
        // Clear field errors so a freshly cleared form shows no red errors
        binding.tilSiteName.setError(null);
        binding.tilReadingType.setError(null);
        binding.tilValue.setError(null);
        binding.tilNotes.setError(null);
        validateFormFields();
    }

    private void loadRecords() {
        executor.execute(() -> {
            List<ReadingRecord> records = dao.getAllRecords();
            runOnUiThread(() -> {
                adapter.setRecords(records);
                binding.tvRecordCount.setText(records.size() + " record" + (records.size() != 1 ? "s" : ""));
                binding.tvEmptyState.setVisibility(records.isEmpty() ? View.VISIBLE : View.GONE);
                binding.rvRecords.setVisibility(records.isEmpty() ? View.GONE : View.VISIBLE);
            });
        });
    }

    private void updateServerCountDisplay() {
        int count = mockSyncService.getServerRecordCount();
        binding.tvServerCount.setText("Mock server record count: " + count);
    }

    // --- ReadingAdapter.OnRecordClickListener ---

    @Override
    public void onRecordClick(ReadingRecord record) {
        // Load record into form for editing
        currentRecord = record;
        binding.etSiteName.setText(record.getSiteName());
        binding.actvReadingType.setText(record.getReadingType(), false);
        binding.etValue.setText(String.valueOf(record.getValue()));
        binding.etNotes.setText(record.getNotes() != null ? record.getNotes() : "");
        binding.btnSave.setText("Update Reading");
        binding.tvFormStatus.setVisibility(View.GONE);
        validateFormFields();

        // Scroll to form
        binding.scrollView.post(() -> binding.scrollView.smoothScrollTo(0, 0));

        EvidenceLog.getInstance().log(TAG, "RECORD_OPENED_FOR_EDIT: recordId=" + record.getId() +
                " syncState=" + record.getSyncState() + " localVersion=" + record.getLocalVersion());
    }

    // --- SyncClient.SyncCallback ---

    @Override
    public void onSyncComplete(int processed, int succeeded, int failed) {
        runOnUiThread(() -> {
            String msg = String.format("Sync complete: %d processed, %d synced, %d failed", processed, succeeded, failed);
            binding.tvSyncStatus.setText(msg);
            binding.tvSyncStatus.setTextColor(failed > 0 ? 0xFFF44336 : 0xFF4CAF50);
            binding.tvSyncStatus.setVisibility(View.VISIBLE);
            loadRecords();
            updateServerCountDisplay();
        });
    }

    @Override
    public void onRecordSynced(ReadingRecord record) {
        runOnUiThread(() -> loadRecords());
    }

    @Override
    public void onRecordFailed(ReadingRecord record, String error) {
        runOnUiThread(() -> loadRecords());
    }

    @Override
    public void onRecordConflict(ReadingRecord record) {
        runOnUiThread(() -> {
            loadRecords();
            // Show conflict resolution dialog
            showConflictDialog(record);
        });
    }

    private void showConflictDialog(ReadingRecord record) {
        new androidx.appcompat.app.AlertDialog.Builder(this)
                .setTitle("Sync Conflict")
                .setMessage(record.getLastError() + "\n\nServer record is at v" + record.getServerVersion() + ". Your edit is local #" + record.getLocalVersion() + ".\n\nChoose how to resolve:")
                .setPositiveButton("Keep My Version (New Operation)", (dialog, which) -> {
                    // This creates a NEW operationId and re-queues as PENDING
                    // SyncClient.resolveConflictKeepLocal now logs CONFLICT_RESOLUTION with the new operationId
                    syncClient.resolveConflictKeepLocal(record);
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    // --- Menu ---

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        getMenuInflater().inflate(R.menu.menu_main, menu);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(@NonNull MenuItem item) {
        int id = item.getItemId();
        if (id == R.id.action_clear_all) {
            new androidx.appcompat.app.AlertDialog.Builder(this)
                    .setTitle("Clear All Records")
                    .setMessage("Delete all local records? This cannot be undone.")
                    .setPositiveButton("Delete", (dialog, which) -> {
                        executor.execute(() -> {
                            dao.deleteAll();
                            runOnUiThread(() -> {
                                loadRecords();
                                EvidenceLog.getInstance().log(TAG, "CLEAR_ALL: All local records deleted");
                            });
                        });
                    })
                    .setNegativeButton("Cancel", null)
                    .show();
            return true;
        } else if (id == R.id.action_evidence_log) {
            startActivity(new Intent(this, EvidenceLogActivity.class));
            return true;
        } else if (id == R.id.action_about) {
            showAboutDialog();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    private void showAboutDialog() {
        new androidx.appcompat.app.AlertDialog.Builder(this)
                .setTitle("About Persistent Records")
                .setMessage("JKUAT ICS 2300 - Lab_08_hm8\n\n" +
                        "Field Readings app with Room persistence and mock sync.\n\n" +
                        "All data is SYNTHETIC. Server is a deterministic MOCK.\n\n" +
                        "Evidence log available via FAB or menu.")
                .setPositiveButton("OK", null)
                .show();
    }
}