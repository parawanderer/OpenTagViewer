package dev.wander.android.opentagviewer.update;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.util.Log;
import android.view.View;
import android.widget.Toast;

import com.google.android.material.snackbar.Snackbar;

import java.util.Optional;

import dev.wander.android.opentagviewer.BuildConfig;
import dev.wander.android.opentagviewer.R;
import io.reactivex.rxjava3.android.schedulers.AndroidSchedulers;
import io.reactivex.rxjava3.core.Single;
import io.reactivex.rxjava3.disposables.Disposable;
import io.reactivex.rxjava3.schedulers.Schedulers;

/**
 * The map's side of update checks: ask GitHub when a check is due, and say so once when there is
 * a newer version. A bar, not a dialog - nothing here is urgent enough to block the map.
 */
public final class UpdateOffer {
    private static final String TAG = UpdateOffer.class.getSimpleName();
    private static final int SHOWN_FOR_MS = 10_000;

    private final Activity activity;
    private final View anchor;
    private final UpdateSettings settings;
    private Disposable checking;

    public UpdateOffer(final Activity activity, final View anchor) {
        this.activity = activity;
        this.anchor = anchor;
        this.settings = new UpdateSettings(activity);
    }

    /**
     * Offer what the last check found, then check again if one is due.
     *
     * <p>Debug builds never check on their own: they are somebody working on the app, and an
     * offer of the published release is noise to them. Settings' "Check now" still works there.
     */
    public void onResume() {
        this.offerIfNewer();
        if (BuildConfig.DEBUG || (this.checking != null && !this.checking.isDisposed())) {
            return;
        }
        if (!UpdatePolicy.checkIsDue(this.settings.enabled(), this.settings.lastCheckAt(),
                System.currentTimeMillis())) {
            return;
        }
        this.checking = checkNow(this.settings)
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(found -> this.offerIfNewer(),
                        error -> Log.i(TAG, "The update check did not reach GitHub", error));
    }

    public void onDestroy() {
        if (this.checking != null) {
            this.checking.dispose();
        }
    }

    /** Ask GitHub now, and record its answer. Shared with Settings' "Check now". */
    public static Single<Optional<UpdateCheck.Release>> checkNow(final UpdateSettings settings) {
        return Single.fromCallable(() -> new UpdateCheck().newest())
                .doOnSuccess(found -> settings.recordCheck(
                        System.currentTimeMillis(), found.orElse(null)))
                .subscribeOn(Schedulers.io());
    }

    private void offerIfNewer() {
        final String known = this.settings.latestVersion();
        final String url = this.settings.latestUrl();
        if (url == null || !UpdatePolicy.offerOnTheMap(this.settings.enabled(), known,
                BuildConfig.VERSION_NAME, this.settings.dismissedVersion())) {
            return;
        }
        // Recorded as soon as it is shown: once per version, whatever the user does with it.
        this.settings.dismiss(known);
        final Snackbar bar = Snackbar.make(this.anchor,
                this.activity.getString(R.string.updates_available, known), SHOWN_FOR_MS);
        bar.setAction(R.string.updates_update, v -> openReleasePage(this.activity, url));
        bar.show();
    }

    /** Open a release page in the browser. Only pages UpdateCheck accepted get here. */
    public static void openReleasePage(final Context context, final String url) {
        try {
            context.startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
        } catch (final ActivityNotFoundException noBrowser) {
            Toast.makeText(context, url, Toast.LENGTH_LONG).show();
        }
    }
}
