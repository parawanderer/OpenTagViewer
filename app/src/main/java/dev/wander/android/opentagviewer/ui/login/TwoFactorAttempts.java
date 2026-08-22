package dev.wander.android.opentagviewer.ui.login;

/**
 * How many wrong verification codes a stale session gets before the app gives up on it.
 *
 * <p><b>There was no limit before this.</b> The login screen counts failures, but only to change
 * the wording - after three it suggests a different Anisette server - and you can go on typing
 * codes forever. That is fine there, because somebody on the login screen has nothing to lose.
 * It is not fine for a session being rescued, where "give up" means deleting the login data, so
 * the point at which that happens has to be a decision rather than an accident.
 *
 * <p><b>Three, matching the threshold the login screen already uses</b>, so the app does not
 * carry two different notions of how many is too many.
 *
 * <p><b>Only a submitted code counts.</b> Not opening the overlay, not backing out of it, not a
 * network error on the way - a strike has to mean "Apple looked at this code and rejected it",
 * or somebody loses their session to a flaky connection.
 */
public final class TwoFactorAttempts {

    /** Same number as {@code AppleLoginActivity}'s hint threshold, deliberately. */
    public static final int ALLOWED = 3;

    private int rejected = 0;

    /**
     * Record a code Apple rejected.
     *
     * @return how many attempts are left afterwards. Zero means the session is being given up on.
     */
    public int rejectedOne() {
        if (this.rejected < ALLOWED) {
            this.rejected++;
        }
        return this.remaining();
    }

    /** Attempts left, never negative. */
    public int remaining() {
        return Math.max(0, ALLOWED - this.rejected);
    }

    /** Whether the app should stop asking and sign the user out. */
    public boolean isExhausted() {
        return this.remaining() == 0;
    }
}
