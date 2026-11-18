package com.example.persistentrecords.ui;

import android.os.Bundle;
import android.view.MenuItem;

import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;

import com.example.persistentrecords.R;
import com.example.persistentrecords.log.EvidenceLog;
import com.example.persistentrecords.databinding.ActivityEvidenceLogBinding;

/**
 * Evidence Log screen - displays the last ~100 log events with evidence IDs for screenshots.
 */
public class EvidenceLogActivity extends AppCompatActivity {

    private ActivityEvidenceLogBinding binding;
    private EvidenceLogAdapter adapter;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        binding = ActivityEvidenceLogBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());

        setSupportActionBar(binding.toolbar);
        if (getSupportActionBar() != null) {
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
        }

        adapter = new EvidenceLogAdapter();
        binding.rvEvidenceLog.setLayoutManager(new LinearLayoutManager(this));
        binding.rvEvidenceLog.setAdapter(adapter);

        // Load initial log entries
        refreshLog();

        binding.fabClearLog.setOnClickListener(v -> {
            EvidenceLog.getInstance().clear();
            refreshLog();
        });
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshLog();
    }

    private void refreshLog() {
        adapter.setEntries(EvidenceLog.getInstance().getEntries());
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        if (item.getItemId() == android.R.id.home) {
            finish();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }
}