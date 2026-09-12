package dev.wander.android.opentagviewer.util.export;

import androidx.annotation.NonNull;

import java.util.List;

import dev.wander.android.opentagviewer.data.model.BeaconLocationReport;
import lombok.AllArgsConstructor;
import lombok.Getter;

/** One tag and the reports that belong to it in a history export. */
@Getter
@AllArgsConstructor
public final class HistoryExportEntry {
    @NonNull private final String beaconId;
    @NonNull private final String displayName;
    @NonNull private final List<BeaconLocationReport> reports;
}
