package dev.wander.android.opentagviewer.util.history;

import androidx.annotation.NonNull;

import java.io.InputStream;

/** The blocking history-restore operation used by the My Devices screen. */
@FunctionalInterface
public interface HistoryArchiveImporter {
    HistoryImportResult importArchive(
            @NonNull InputStream archive,
            @NonNull HistoryImportProgress progress) throws HistoryImportException;
}
