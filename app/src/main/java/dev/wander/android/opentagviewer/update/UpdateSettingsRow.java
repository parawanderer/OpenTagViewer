package dev.wander.android.opentagviewer.update;

import android.content.Context;
import android.util.Log;
import android.widget.Toast;

import androidx.annotation.NonNull;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import java.util.function.Consumer;

import dev.wander.android.opentagviewer.BuildConfig;
import dev.wander.android.opentagviewer.R;
import io.reactivex.rxjava3.android.schedulers.AndroidSchedulers;
import io.reactivex.rxjava3.disposables.CompositeDisposable;

/**
 * Settings -> App updates: the installed version, whether checks are on, and what they found.
 *
 * <p>Tapping it opens one small dialog whose buttons follow the state: open the release page
 * when there is a newer version, check now or turn off when checks are on, turn on when off.
 */
public final class UpdateSettingsRow {
    private static final String TAG = UpdateSettingsRow.class.getSimpleName();

    private final Context context;
    private final UpdateSettings settings;
    private final Consumer<String> showSubtitle;
    private final CompositeDisposable inFlight = new CompositeDisposable();

    public UpdateSettingsRow(final Context context, final Consumer<String> showSubtitle) {
        this.context = context;
        this.settings = new UpdateSettings(context);
        this.showSubtitle = showSubtitle;
    }

    public void refresh() {
        this.showSubtitle.accept(subtitle(this.context, this.settings));
    }

    @NonNull
    static String subtitle(final Context context, final UpdateSettings settings) {
        final String version = BuildConfig.VERSION_NAME;
        switch (UpdatePolicy.rowState(settings.enabled(), settings.lastCheckAt(),
                settings.latestVersion(), version)) {
            case AVAILABLE:
                return context.getString(R.string.updates_subtitle_available, version,
                        settings.latestVersion());
            case UP_TO_DATE:
                return context.getString(R.string.updates_subtitle_current, version);
            case NOT_CHECKED:
                return context.getString(R.string.updates_subtitle_unchecked, version);
            case OFF:
            default:
                return context.getString(R.string.updates_subtitle_off, version);
        }
    }

    public void onClick() {
        final boolean on = Boolean.TRUE.equals(this.settings.enabled());
        final boolean newer = UpdatePolicy.rowState(this.settings.enabled(),
                this.settings.lastCheckAt(), this.settings.latestVersion(),
                BuildConfig.VERSION_NAME) == UpdatePolicy.RowState.AVAILABLE;

        final MaterialAlertDialogBuilder dialog = new MaterialAlertDialogBuilder(this.context)
                .setTitle(R.string.updates_title)
                .setMessage(subtitle(this.context, this.settings) + "\n\n"
                        + this.context.getString(R.string.updates_explained))
                .setNegativeButton(R.string.cancel, null);

        if (newer && this.settings.latestUrl() != null) {
            dialog.setPositiveButton(R.string.updates_open_release, (d, w) ->
                    UpdateOffer.openReleasePage(this.context, this.settings.latestUrl()));
        } else if (on) {
            dialog.setPositiveButton(R.string.updates_check_now, (d, w) -> this.checkNow());
        } else {
            dialog.setPositiveButton(R.string.updates_turn_on, (d, w) -> {
                this.settings.setEnabled(true);
                this.checkNow();
            });
        }
        if (on) {
            dialog.setNeutralButton(R.string.updates_turn_off, (d, w) -> {
                this.settings.setEnabled(false);
                this.refresh();
            });
        }
        dialog.show();
    }

    private void checkNow() {
        this.showSubtitle.accept(this.context.getString(R.string.updates_checking));
        this.inFlight.add(UpdateOffer.checkNow(this.settings)
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(found -> this.refresh(), error -> {
                    Log.i(TAG, "Checking for an update failed", error);
                    Toast.makeText(this.context, R.string.updates_check_failed,
                            Toast.LENGTH_LONG).show();
                    this.refresh();
                }));
    }

    public void dispose() {
        this.inFlight.dispose();
    }
}
