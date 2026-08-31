package dev.wander.android.opentagviewer.util.history;

import lombok.Getter;

/** Mutually-exclusive outcomes for every data row encountered in a history archive. */
@Getter
public final class HistoryImportResult {
    private final int rowsRead;
    private final int rowsAdded;
    private final int rowsAlreadyPresent;
    private final int rowsMalformed;
    private final int rowsSkippedUnknownBeacon;

    public HistoryImportResult(
            final int rowsRead,
            final int rowsAdded,
            final int rowsAlreadyPresent,
            final int rowsMalformed,
            final int rowsSkippedUnknownBeacon) {

        if (rowsRead < 0 || rowsAdded < 0 || rowsAlreadyPresent < 0
                || rowsMalformed < 0 || rowsSkippedUnknownBeacon < 0) {
            throw new IllegalArgumentException("history import counts cannot be negative");
        }
        if (rowsRead != rowsAdded + rowsAlreadyPresent
                + rowsMalformed + rowsSkippedUnknownBeacon) {
            throw new IllegalArgumentException("every history row must have exactly one outcome");
        }

        this.rowsRead = rowsRead;
        this.rowsAdded = rowsAdded;
        this.rowsAlreadyPresent = rowsAlreadyPresent;
        this.rowsMalformed = rowsMalformed;
        this.rowsSkippedUnknownBeacon = rowsSkippedUnknownBeacon;
    }
}
