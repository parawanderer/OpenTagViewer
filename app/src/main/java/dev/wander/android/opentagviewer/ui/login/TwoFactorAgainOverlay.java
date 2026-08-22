package dev.wander.android.opentagviewer.ui.login;

import static android.view.View.GONE;
import static android.view.View.VISIBLE;

import android.util.Log;
import android.view.View;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;

import java.util.List;

import dev.wander.android.opentagviewer.R;
import dev.wander.android.opentagviewer.python.PythonAuthService;
import dev.wander.android.opentagviewer.python.PythonAuthService.AuthMethod;
import io.reactivex.rxjava3.android.schedulers.AndroidSchedulers;
import io.reactivex.rxjava3.schedulers.Schedulers;

/**
 * Rescuing a session Apple has decided needs a verification code again.
 *
 * <p><b>The failure this replaces was permanent and silent.</b> An account restores as logged
 * in, works, and at some later point Apple moves it to {@code REQUIRE_2FA}. From then on every
 * fetch fails its state check before a request leaves the phone. The app logged it and waited to
 * retry, under a comment saying the error "just happens every now and then" - which is true of
 * most fetch errors and completely wrong about this one, because the state does not heal itself.
 * People saw pins that stopped updating, and nothing anywhere said why.
 *
 * <p><b>Not dismissible into the map, deliberately.</b> With the session in this state the app
 * cannot locate anything at all - not account tags and not imported ones, because both need an
 * authenticated session. So there is no working screen behind this to return to, and offering a
 * way back to one would recreate the bug with an extra tap in front of it. The two exits are
 * entering the code and signing out.
 *
 * <p><b>Giving up deletes the login.</b> After {@link TwoFactorAttempts#ALLOWED} codes Apple has
 * rejected, the session is not going to be rescued by more typing, so the app stops pretending
 * and sends the user to sign in properly.
 */
public final class TwoFactorAgainOverlay {

    private static final String TAG = "TwoFactorAgain";

    private final AppCompatActivity activity;
    private final View overlay;
    private final Apple2FACodeInputManager codeEntry;
    private final TwoFactorAttempts attempts = new TwoFactorAttempts();

    private final Runnable onRescued;
    private final Runnable onGivenUp;

    /** The method a code is being sent by. Chosen for the user - see {@link #show}. */
    private AuthMethod method;

    /**
     * @param overlay   the inflated {@code two_factor_again_overlay}, already in the view tree.
     * @param onRescued the session is usable again - the caller should carry on as though
     *                  nothing happened, which for the map means refreshing.
     * @param onGivenUp the session is being abandoned. The caller deletes the stored login and
     *                  sends the user to sign in.
     */
    public TwoFactorAgainOverlay(
            final AppCompatActivity activity,
            final View overlay,
            final Runnable onRescued,
            final Runnable onGivenUp) {

        this.activity = activity;
        this.overlay = overlay;
        this.onRescued = onRescued;
        this.onGivenUp = onGivenUp;
        this.codeEntry = new Apple2FACodeInputManager(overlay, this::onCodeTyped);
    }

    /**
     * Put it on screen and ask Apple to send a code.
     *
     * <p><b>The method is chosen rather than offered.</b> The login screen lets somebody pick
     * between a trusted device and each of their phone numbers, and that is right there - it is
     * a considered sign-in. This is an interruption to something else, and the overwhelmingly
     * common case is a single trusted-device prompt; putting a chooser in the way of somebody
     * who just wants their map back is a screen for a decision they do not have.
     *
     * @param methods what Python said would work, never empty - the caller checks.
     */
    public void show(final List<AuthMethod> methods) {
        this.method = methods.get(0);
        this.overlay.setVisibility(VISIBLE);
        this.codeEntry.init();

        this.overlay.findViewById(R.id.two_factor_again_sign_out)
                .setOnClickListener(v -> {
                    Log.i(TAG, "signing out rather than entering a code");
                    this.giveUp();
                });

        this.setBusy(true);

        var async = PythonAuthService.requestCode(this.method)
                .subscribeOn(Schedulers.io())
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(() -> this.setBusy(false), error -> {
                    // Not a strike: Apple never saw a code. But there is nothing to type
                    // either, so the honest move is to stop rather than show an empty box.
                    Log.e(TAG, "Could not ask Apple to send a code", error);
                    this.showError(this.activity.getString(R.string.two_factor_again_giving_up));
                    this.giveUp();
                });
    }

    public boolean isShowing() {
        return this.overlay.getVisibility() == VISIBLE;
    }

    /**
     * Somebody filled in all six boxes.
     *
     * <p>Only a code Apple <b>rejects</b> costs an attempt. A failure to reach Apple at all is
     * not the user being wrong, and counting it would let a bad connection spend somebody's
     * session - so those are reported and left uncounted.
     */
    private void onCodeTyped(final String code) {
        this.setBusy(true);
        this.hideError();

        var async = PythonAuthService.submitCode(this.method, code)
                .subscribeOn(Schedulers.io())
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(() -> {
                    Log.i(TAG, "the session is usable again");
                    this.setBusy(false);
                    this.overlay.setVisibility(GONE);
                    this.onRescued.run();
                }, error -> {
                    Log.w(TAG, "Apple rejected the code", error);
                    this.setBusy(false);

                    // **clear(), not init().** init() re-binds the six boxes and empties them
                    // but leaves the manager's cursor at six, so it never decides the code is
                    // "fully filled" again - the user can type a second code and nothing is
                    // ever submitted. Silent, and only on the second attempt. clear() is the
                    // one that puts the cursor back, which is why the login screen calls it.
                    this.codeEntry.clear();

                    final int left = this.attempts.rejectedOne();

                    if (this.attempts.isExhausted()) {
                        this.showError(this.activity.getString(
                                R.string.two_factor_again_giving_up));
                        this.giveUp();
                        return;
                    }

                    this.showError(this.activity.getString(
                            R.string.two_factor_again_wrong_code, left));
                });
    }

    private void giveUp() {
        this.overlay.setVisibility(GONE);
        this.onGivenUp.run();
    }

    private void setBusy(final boolean busy) {
        this.overlay.findViewById(R.id.two_factor_again_progress)
                .setVisibility(busy ? VISIBLE : GONE);
    }

    private void showError(final String message) {
        final TextView error = this.overlay.findViewById(R.id.two_factor_again_error);
        error.setText(message);
        error.setVisibility(VISIBLE);
    }

    private void hideError() {
        this.overlay.findViewById(R.id.two_factor_again_error).setVisibility(GONE);
    }
}
