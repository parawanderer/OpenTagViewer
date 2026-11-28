package dev.wander.android.opentagviewer.ui.settings;

import static dev.wander.android.opentagviewer.db.repo.model.UserSettings.HISTORY_RETENTION_CHOICES_DAYS;
import static dev.wander.android.opentagviewer.db.repo.model.UserSettings.HISTORY_RETENTION_FOREVER;

import android.content.Context;
import android.util.Log;
import android.widget.AdapterView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.StringRes;
import androidx.appcompat.app.AlertDialog;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

import dev.wander.android.opentagviewer.R;
import dev.wander.android.opentagviewer.db.repo.UserSettingsRepository;
import dev.wander.android.opentagviewer.db.repo.model.UserSettings;
import dev.wander.android.opentagviewer.python.AppDependencies;
import dev.wander.android.opentagviewer.util.rx.RxFlows;
import io.reactivex.rxjava3.android.schedulers.AndroidSchedulers;
import io.reactivex.rxjava3.core.Completable;

/**
 * Settings -> Keep location history: forever (the default), or a year, six months, three months
 * or a month.
 *
 * <p>Choosing a shorter period asks first, then saves it and deletes what is now too old. A
 * longer one, or forever, is only saved: there is nothing new to delete. After that the app
 * deletes older history each time it starts (see {@code OpenAirTagApplication}).
 *
 * <p>Shares the screen's {@link UserSettings} object rather than reading its own, because the
 * screen saves the whole object when anything else changes, and a second copy would write the old
 * period back.
 */
public final class HistoryRetentionRow {
    private static final String TAG = HistoryRetentionRow.class.getSimpleName();

    /** Labels for {@link UserSettings#HISTORY_RETENTION_CHOICES_DAYS}, in the same order. */
    private static final List<Integer> LABELS = List.of(
            R.string.history_retention_forever,
            R.string.history_retention_1_year,
            R.string.history_retention_6_months,
            R.string.history_retention_3_months,
            R.string.history_retention_1_month);

    private final Context context;
    private final UserSettings settings;
    private final UserSettingsRepository repository;
    private final Consumer<String> showSubtitle;

    public HistoryRetentionRow(
            @NonNull final Context context,
            @NonNull final UserSettings settings,
            @NonNull final UserSettingsRepository repository,
            @NonNull final Consumer<String> showSubtitle) {
        this.context = context;
        this.settings = settings;
        this.repository = repository;
        this.showSubtitle = showSubtitle;
    }

    public void refresh() {
        this.showSubtitle.accept(this.context.getString(
                labelFor(this.settings.resolveHistoryRetentionDays())));
    }

    public void onClick() {
        final int current = this.settings.resolveHistoryRetentionDays();
        final CharSequence[] labels = new CharSequence[LABELS.size()];
        for (int i = 0; i < labels.length; i++) {
            labels[i] = this.context.getString(LABELS.get(i));
        }

        new MaterialAlertDialogBuilder(this.context)
                .setTitle(R.string.history_retention_title)
                .setSingleChoiceItems(labels, HISTORY_RETENTION_CHOICES_DAYS.indexOf(current), null)
                .setNegativeButton(R.string.cancel, null)
                .setPositiveButton(R.string.accept, (dialog, which) -> {
                    final int checked =
                            ((AlertDialog) dialog).getListView().getCheckedItemPosition();
                    if (checked != AdapterView.INVALID_POSITION) {
                        this.choose(current, HISTORY_RETENTION_CHOICES_DAYS.get(checked));
                    }
                })
                .show();
    }

    private void choose(final int current, final int chosen) {
        if (chosen == current) {
            return;
        }

        if (!deletesMore(current, chosen)) {
            this.apply(current, chosen, false);
            return;
        }

        new MaterialAlertDialogBuilder(this.context)
                .setTitle(R.string.history_retention_confirm_title)
                .setMessage(R.string.history_retention_confirm_message)
                .setNegativeButton(R.string.cancel, null)
                .setPositiveButton(R.string.history_retention_confirm_button,
                        (dialog, which) -> this.apply(current, chosen, true))
                .show();
    }

    /**
     * Saves the period, then, only once that has worked, deletes what is older.
     *
     * <p><b>Saved first, through {@link RxFlows#onlyAfter}</b>, because the save is a DataStore
     * write and those start the moment they are called. Built as {@code store.andThen(prune)}
     * the delete would be created while the chain was, and would run even when the save failed,
     * leaving history deleted under a setting that still says forever.
     */
    private void apply(final int previous, final int chosen, final boolean deleteNow) {
        this.settings.setHistoryRetentionDays(chosen);
        this.refresh();

        final Context app = this.context.getApplicationContext();
        final AtomicBoolean saved = new AtomicBoolean(false);
        final Completable save = this.repository.storeUserSettings(this.settings)
                .doOnComplete(() -> saved.set(true));
        final Completable work = deleteNow
                ? RxFlows.onlyAfter(save, () -> AppDependencies.historyPruner(app)
                        .prune(chosen)
                        .doOnSuccess(deleted -> Log.i(TAG, "Deleted " + deleted
                                + " reports older than " + chosen + " days"))
                        .ignoreElement())
                : save;

        // Not disposed when the screen goes: a delete already under way should finish, and
        // nothing here touches more than the subtitle.
        final var unused = work
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(() -> Log.i(TAG, "Keeping location history for "
                                + (chosen == HISTORY_RETENTION_FOREVER ? "ever" : chosen + " days")),
                        error -> {
                            Log.e(TAG, "Could not apply the history period " + chosen, error);
                            if (!saved.get()) {
                                // Nothing was deleted, and the old period still stands.
                                this.settings.setHistoryRetentionDays(previous);
                                this.refresh();
                                Toast.makeText(app, R.string.error_updating_settings,
                                        Toast.LENGTH_LONG).show();
                            } else {
                                Toast.makeText(app, R.string.history_retention_delete_failed,
                                        Toast.LENGTH_LONG).show();
                            }
                        });
    }

    /**
     * Whether going from {@code current} to {@code chosen} deletes anything that would have been
     * kept, which is what needs asking first.
     */
    static boolean deletesMore(final int current, final int chosen) {
        if (chosen == HISTORY_RETENTION_FOREVER) {
            return false;
        }
        return current == HISTORY_RETENTION_FOREVER || chosen < current;
    }

    @StringRes
    static int labelFor(final int days) {
        final int index = HISTORY_RETENTION_CHOICES_DAYS.indexOf(days);
        return LABELS.get(Math.max(index, 0));
    }
}
