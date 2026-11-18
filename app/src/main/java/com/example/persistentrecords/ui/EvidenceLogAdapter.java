package com.example.persistentrecords.ui;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.example.persistentrecords.R;
import com.example.persistentrecords.log.LogEntry;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * RecyclerView adapter for the Evidence Log screen.
 */
public class EvidenceLogAdapter extends RecyclerView.Adapter<EvidenceLogAdapter.ViewHolder> {

    private final List<LogEntry> entries = new ArrayList<>();
    private static final SimpleDateFormat dateFormat = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault());

    public void setEntries(List<LogEntry> newEntries) {
        entries.clear();
        if (newEntries != null) {
            entries.addAll(newEntries);
        }
        notifyDataSetChanged();
    }

    @NonNull
    @Override
    public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_evidence_log, parent, false);
        return new ViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
        LogEntry entry = entries.get(position);
        holder.bind(entry);
    }

    @Override
    public int getItemCount() {
        return entries.size();
    }

    static class ViewHolder extends RecyclerView.ViewHolder {
        private final TextView tvEvidenceId;
        private final TextView tvTag;
        private final TextView tvTimestamp;
        private final TextView tvMessage;

        ViewHolder(@NonNull View view) {
            super(view);
            tvEvidenceId = view.findViewById(R.id.tvEvidenceId);
            tvTag = view.findViewById(R.id.tvTag);
            tvTimestamp = view.findViewById(R.id.tvTimestamp);
            tvMessage = view.findViewById(R.id.tvMessage);
        }

        void bind(LogEntry entry) {
            tvEvidenceId.setText(entry.getEvidenceId());
            tvTag.setText(entry.getTag());
            tvTimestamp.setText(dateFormat.format(new Date(entry.getTimestamp())));
            tvMessage.setText(entry.getMessage());
        }
    }
}