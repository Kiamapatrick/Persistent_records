package com.example.persistentrecords.ui;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.example.persistentrecords.R;
import com.example.persistentrecords.model.ReadingRecord;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * RecyclerView adapter for displaying ReadingRecords in the list screen.
 * Shows site, type, value+unit, and colored syncState badge.
 */
public class ReadingAdapter extends RecyclerView.Adapter<ReadingAdapter.ViewHolder> {

    private final List<ReadingRecord> records = new ArrayList<>();
    private final OnRecordClickListener listener;
    private final SimpleDateFormat dateFormat = new SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault());

    public interface OnRecordClickListener {
        void onRecordClick(ReadingRecord record);
    }

    public ReadingAdapter(OnRecordClickListener listener) {
        this.listener = listener;
    }

    public void setRecords(List<ReadingRecord> newRecords) {
        records.clear();
        if (newRecords != null) {
            records.addAll(newRecords);
        }
        notifyDataSetChanged();
    }

    @NonNull
    @Override
    public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_reading, parent, false);
        return new ViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
        ReadingRecord record = records.get(position);
        holder.bind(record);
    }

    @Override
    public int getItemCount() {
        return records.size();
    }

    class ViewHolder extends RecyclerView.ViewHolder {
        private final TextView tvSiteName;
        private final TextView tvReadingType;
        private final TextView tvValueUnit;
        private final TextView tvSyncState;
        private final TextView tvUpdatedAt;
        private final View syncBadge;

        ViewHolder(@NonNull View itemView) {
            super(itemView);
            tvSiteName = itemView.findViewById(R.id.tvSiteName);
            tvReadingType = itemView.findViewById(R.id.tvReadingType);
            tvValueUnit = itemView.findViewById(R.id.tvValueUnit);
            tvSyncState = itemView.findViewById(R.id.tvSyncState);
            tvUpdatedAt = itemView.findViewById(R.id.tvUpdatedAt);
            syncBadge = itemView.findViewById(R.id.syncBadge);

            itemView.setOnClickListener(v -> {
                int pos = getAdapterPosition();
                if (pos != RecyclerView.NO_POSITION && listener != null) {
                    listener.onRecordClick(records.get(pos));
                }
            });
        }

        void bind(ReadingRecord record) {
            tvSiteName.setText(record.getSiteName());
            tvReadingType.setText(record.getReadingType());
            tvValueUnit.setText(String.format(Locale.getDefault(), "%.2f %s", record.getValue(), record.getUnit()));
            tvSyncState.setText(record.getSyncState());
            tvUpdatedAt.setText("Updated: " + dateFormat.format(new Date(record.getUpdatedAt())));

            // Color the sync state badge
            int color = getSyncStateColor(record.getSyncState());
            syncBadge.setBackgroundColor(color);
            tvSyncState.setTextColor(color);
        }

        private int getSyncStateColor(String state) {
            switch (state) {
                case ReadingRecord.STATE_SAVED_LOCAL:
                    return 0xFF9E9E9E; // Grey
                case ReadingRecord.STATE_PENDING:
                    return 0xFFFF9800; // Orange
                case ReadingRecord.STATE_ACKNOWLEDGED:
                    return 0xFF4CAF50; // Green
                case ReadingRecord.STATE_FAILED:
                    return 0xFFF44336; // Red
                case ReadingRecord.STATE_CONFLICT:
                    return 0xFF9C27B0; // Purple
                default:
                    return 0xFF000000; // Black
            }
        }
    }
}