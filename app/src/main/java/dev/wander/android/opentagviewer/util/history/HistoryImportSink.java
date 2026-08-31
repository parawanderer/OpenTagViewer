package dev.wander.android.opentagviewer.util.history;

import java.util.List;

/** Internal seam: Room in production, an in-memory capture in the JVM parser tests. */
@FunctionalInterface
interface HistoryImportSink {
    HistoryImportResult merge(
            List<HistoryImportRow> rows,
            int rowsRead,
            int malformedRows,
            long now);
}
