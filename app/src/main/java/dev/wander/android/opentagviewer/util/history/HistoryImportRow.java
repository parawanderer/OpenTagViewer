package dev.wander.android.opentagviewer.util.history;

import androidx.annotation.NonNull;

import dev.wander.android.opentagviewer.data.model.BeaconLocationReport;
import lombok.AllArgsConstructor;
import lombok.Getter;

/** A valid CSV row, before the database decides whether its beacon exists. */
@Getter
@AllArgsConstructor
public final class HistoryImportRow {
    @NonNull private final String beaconId;
    @NonNull private final BeaconLocationReport report;
}
