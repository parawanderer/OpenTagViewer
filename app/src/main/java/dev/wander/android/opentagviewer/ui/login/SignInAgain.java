package dev.wander.android.opentagviewer.ui.login;

import android.app.Activity;
import android.content.Intent;
import android.util.Log;

import dev.wander.android.opentagviewer.AppleLoginActivity;
import dev.wander.android.opentagviewer.db.datastore.UserAuthDataStore;
import dev.wander.android.opentagviewer.db.repo.UserAuthRepository;
import dev.wander.android.opentagviewer.util.android.AppCryptographyUtil;
import io.reactivex.rxjava3.android.schedulers.AndroidSchedulers;
import io.reactivex.rxjava3.disposables.Disposable;
import io.reactivex.rxjava3.schedulers.Schedulers;

/**
 * Clearing a session Apple will not accept, and sending the user to make a new one.
 *
 * <p><b>Here because three screens reach the same wall.</b> The iCloud device list, a rename that
 * writes to the account, and the silent background read all go through one bridge - and a
 * recovery written separately at each would drift, which is how one of them ends up on a "try
 * again later" screen for a session that can never work again.
 *
 * <p>{@code MapsActivity} keeps its own variant deliberately: it also calls
 * {@code PythonAppleService.forget()} to close the Apple session's sockets, and it answers a
 * different failure - an account blob that will not restore at all, rather than credentials Apple
 * declined. Merging them would fold two causes into one recovery.
 */
public final class SignInAgain {

    private static final String TAG = SignInAgain.class.getSimpleName();

    private SignInAgain() {}

    /**
     * Forget the stored account, then open the sign-in screen saying why.
     *
     * <p><b>The address is read before the account is cleared</b>, because clearing is what
     * destroys it - and a prefilled field is most of what makes signing in again bearable.
     *
     * <p>The activity finishes either way. Leaving somebody on a screen backed by a session that
     * has just been deleted is worse than closing it: nothing on it can work, and the next thing
     * they press would fail for a reason the screen cannot explain.
     *
     * @return the subscription, so a caller that tracks them can dispose of it
     */
    public static Disposable from(final Activity activity) {
        final UserAuthRepository repo = new UserAuthRepository(
                UserAuthDataStore.getInstance(activity.getApplicationContext()),
                new AppCryptographyUtil());

        final String email = addressOrNull(repo);

        return repo.clearUser()
                .subscribeOn(Schedulers.io())
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(
                        () -> open(activity, email),
                        error -> {
                            // At worst they sign out by hand. Not worth stranding them here.
                            Log.e(TAG, "Could not clear the refused session", error);
                            activity.finish();
                        });
    }

    private static void open(final Activity activity, final String email) {
        final Intent intent = new Intent(activity, AppleLoginActivity.class);
        intent.putExtra(AppleLoginActivity.EXTRA_SESSION_EXPIRED, true);
        if (email != null) {
            intent.putExtra(AppleLoginActivity.EXTRA_PREFILL_EMAIL, email);
        }
        intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_NEW_TASK);

        activity.startActivity(intent);
        activity.finish();
    }

    /**
     * The signed-in address, or null if it cannot be read.
     *
     * <p>Deliberately incurious about why it might not be: this runs while recovering from a
     * session that has already failed, and a missing address costs a prefilled field rather than
     * the recovery.
     */
    private static String addressOrNull(final UserAuthRepository repo) {
        try {
            return repo.getUserAuth().blockingFirst()
                    .map(stored -> stored.getUser().getAccount().getInfo().getAccountName())
                    .orElse(null);
        } catch (final Exception e) {
            Log.w(TAG, "Could not read the signed-in address to prefill the login screen", e);
            return null;
        }
    }
}
