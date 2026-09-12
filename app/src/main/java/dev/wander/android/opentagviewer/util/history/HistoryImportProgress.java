package dev.wander.android.opentagviewer.util.history;

/** Progress from the blocking archive reader and database merge. */
@FunctionalInterface
public interface HistoryImportProgress {
    enum Stage {
        READING,
        MERGING,
    }

    HistoryImportProgress NONE = (stage, completed, total) -> {};

    /** Total is unknown while reading and is therefore zero for that stage. */
    void changed(Stage stage, int completed, int total);
}
