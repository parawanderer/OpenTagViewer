package dev.wander.android.opentagviewer.db.repo;

import android.content.Context;

import androidx.annotation.NonNull;

import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;

import dev.wander.android.opentagviewer.db.repo.model.UserSettings;
import dev.wander.android.opentagviewer.db.room.OpenTagViewerDatabase;
import io.reactivex.rxjava3.core.Single;
import io.reactivex.rxjava3.schedulers.Schedulers;

/**
 * Deletes location reports older than the period chosen in Settings, keeping each tag's newest.
 *
 * <p>Runs when the app starts and when a shorter period is chosen, and compacts the file
 * afterwards so the deleted reports are not left in it (see {@link DatabaseCompaction}). Nothing
 * else deletes history on a timer, because the app has no background work outside the optional
 * scan.
 *
 * <p><b>Keeping everything opens nothing.</b> With the default, {@link #prune} returns without
 * touching the database, so an install that never chose a period behaves exactly as it did
 * before this existed.
 *
 * <p>What is left alone:
 * <ul>
 *   <li>each tag's newest report, which is its last known position rather than history (see
 *       {@code LocationReportDao.deleteOlderThanExceptNewest});</li>
 *   <li>{@code DailyHistoryFetchRecord}, the per-day "already fetched" markers. They hold no
 *       position, and removing one makes the History screen ask Apple for that day again. The
 *       shortest period is a month, far beyond what Apple keeps, so that request could only
 *       come back empty.</li>
 * </ul>
 */
public final class LocationHistoryRetention implements HistoryPruner {

    /** Returned by {@link #cutoffFor} when nothing is to be deleted. */
    public static final long NO_CUTOFF = Long.MIN_VALUE;

    private final OpenTagViewerDatabase db;
    private final LongSupplier clock;

    public LocationHistoryRetention(
            @NonNull final OpenTagViewerDatabase db, @NonNull final LongSupplier clock) {
        this.db = db;
        this.clock = clock;
    }

    public static LocationHistoryRetention of(@NonNull final Context context) {
        return new LocationHistoryRetention(
                OpenTagViewerDatabase.getInstance(context.getApplicationContext()),
                System::currentTimeMillis);
    }

    /**
     * For a period just chosen in Settings: deletes, then compacts the file if anything went, so
     * what was deleted is not still readable in it. See {@link DatabaseCompaction#now}.
     */
    @Override
    public Single<Integer> prune(final int retentionDays) {
        return Single.fromCallable(() -> {
            final int deleted = this.delete(retentionDays);
            if (deleted > 0) {
                DatabaseCompaction.now(this.db, "deleting older location history");
            }
            return deleted;
        }).subscribeOn(Schedulers.io());
    }

    /**
     * For the delete at app start, which runs every time the app does and removes little each
     * time. So it checkpoints rather than rewriting the whole file: that is what clears the
     * deleted rows out of the files, and it costs only the pages that changed. See
     * {@link DatabaseCompaction#checkpoint}.
     */
    public Single<Integer> pruneAtStart(final int retentionDays) {
        return Single.fromCallable(() -> {
            final int deleted = this.delete(retentionDays);
            if (deleted > 0) {
                DatabaseCompaction.checkpoint(this.db, "deleting older location history");
            }
            return deleted;
        }).subscribeOn(Schedulers.io());
    }

    private int delete(final int retentionDays) {
        final long cutoff = cutoffFor(retentionDays, this.clock.getAsLong());
        if (cutoff == NO_CUTOFF) {
            return 0;
        }
        return this.db.locationReportDao().deleteOlderThanExceptNewest(cutoff);
    }

    /**
     * The oldest report time to keep, in Unix milliseconds, or {@link #NO_CUTOFF}.
     *
     * <p>The period goes through {@link UserSettings#resolveHistoryRetentionDays()} first, so a
     * value that is not one of the choices keeps at least as much as asked, never less.
     */
    public static long cutoffFor(final int retentionDays, final long nowUnixMS) {
        final int days = UserSettings.builder()
                .historyRetentionDays(retentionDays)
                .build()
                .resolveHistoryRetentionDays();

        if (days == UserSettings.HISTORY_RETENTION_FOREVER) {
            return NO_CUTOFF;
        }
        return nowUnixMS - TimeUnit.DAYS.toMillis(days);
    }
}
