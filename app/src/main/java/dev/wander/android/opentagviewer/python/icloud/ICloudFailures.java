package dev.wander.android.opentagviewer.python.icloud;

import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;

/**
 * What a failure from the account means for the screen that hit it.
 *
 * <p><b>One place decides, because three screens can hit the same wall.</b> Reading the account
 * in the background, opening the iCloud device list, and renaming a tag all go through the same
 * bridge - so a failure classified in Python and then interpreted three times gets interpreted
 * three different ways. The one that turned up in practice was rejected credentials landing on
 * the "worth trying again later" screen, which is false for a session that can never work again.
 */
public final class ICloudFailures {

    private ICloudFailures() {}

    /**
     * Whether the only remedy is a fresh sign-in.
     *
     * <p><b>True for exactly one failure today, and phrased as a question rather than a
     * comparison so it stays true when there are two.</b> Apple refusing the stored password is
     * permanent: the account state carries the password, the app re-sends it, and Apple declines
     * - so every retry is the same rejected exchange. Opening a keychain session needs a fresh
     * token rather than the one already held, which is why nothing can paper over it.
     *
     * <p>Everything else here is either recoverable in place ({@code PASSCODE_REJECTED}), an
     * honest end state ({@code NOTHING_TO_RECOVER_FROM}), or genuinely worth retrying
     * ({@code SERVICE_UNSURE}) - and sending somebody to sign in again for one of those would
     * cost them a sign-in for nothing.
     *
     * <p>Unwraps the cause chain: these travel out through RxJava, which hands the subscriber its
     * own exception rather than the thrown one.
     */
    public static boolean meansSignInAgain(final Throwable error) {
        return failureOf(error) == ICloudFailure.CREDENTIALS_REJECTED;
    }

    /**
     * The failure behind a throwable, wherever it is in the chain, or {@code UNKNOWN}.
     *
     * <p><b>Guarded with a set rather than a self-comparison.</b> Java refuses
     * {@code initCause(this)}, so the obvious guard - stop when a cause is itself - looks
     * sufficient and does nothing: that shape cannot be built. A two-element cycle can, and the
     * first version of this walked one forever, on whichever thread happened to be reporting an
     * error at the time.
     */
    public static ICloudFailure failureOf(final Throwable error) {
        final Set<Throwable> seen = Collections.newSetFromMap(new IdentityHashMap<>());

        for (Throwable cause = error; cause != null && seen.add(cause); cause = cause.getCause()) {
            if (cause instanceof ICloudException) {
                return ((ICloudException) cause).getFailure();
            }
        }
        return ICloudFailure.UNKNOWN;
    }
}
