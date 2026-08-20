package dev.wander.android.opentagviewer.python;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * What happened when one terms document was agreed to.
 *
 * <p>Signing in is only finished once every fetched document is done, so most acceptances say
 * "one fewer to go" and the last one says how signing in ended.
 */
@Getter
@AllArgsConstructor
public class TermsAcceptance {
    /** How many documents are still waiting. Zero means signing in was completed. */
    private final int remaining;

    /**
     * How signing in ended, once nothing is left to agree to. Null while documents remain.
     *
     * <p><b>The caller must refuse to store the account unless this is {@code LOGGED_IN}.</b> A
     * blob written in any other state fails every later fetch inside FindMy.py's own state
     * check, before a request reaches Apple - which is issue #43 and issue #119, and presents to
     * the user as a map that never updates and a session that cannot be repaired by signing out.
     */
    private final String loginState;

    /** Whether this was the last document and signing in reached a state worth storing. */
    public boolean isSignedIn() {
        return this.remaining == 0 && "LOGGED_IN".equals(this.loginState);
    }

    /** Whether anything is still waiting to be agreed to. */
    public boolean hasMore() {
        return this.remaining > 0;
    }
}
