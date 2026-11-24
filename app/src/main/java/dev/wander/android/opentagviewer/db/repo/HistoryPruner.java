package dev.wander.android.opentagviewer.db.repo;

import io.reactivex.rxjava3.core.Single;

/**
 * Deletes location history older than the period chosen in Settings.
 *
 * <p>An interface so the Settings test can choose a period without deleting anything from the
 * real on-device database, which on somebody's own phone holds their history. What the real one
 * deletes is tested against an in-memory database instead. See {@link LocationHistoryRetention}.
 */
public interface HistoryPruner {

    /**
     * Deletes reports older than {@code retentionDays}, keeping each tag's newest.
     *
     * @param retentionDays one of {@code UserSettings.HISTORY_RETENTION_CHOICES_DAYS};
     *                      {@code HISTORY_RETENTION_FOREVER} deletes nothing
     * @return how many reports were deleted
     */
    Single<Integer> prune(int retentionDays);
}
