package dev.wander.android.opentagviewer.anisette;

/**
 * Where Apple sign-in data comes from, as far as the rest of the app is concerned.
 *
 * <p>This exists to be substituted. {@link LocalAnisette} downloads Apple's libraries, talks to
 * Apple's servers and provisions a machine identity, so every state the UI has to render -
 * ready, still setting up, no network, Apple replaced the libraries, the user supplied their
 * own file - is otherwise reachable only by arranging the real world. Several of them cannot be
 * arranged at all: "Apple shipped a new build" is not something you can produce for a test.
 *
 * <p>The surface is deliberately small, and deliberately says nothing about how any of it is
 * obtained. A caller that needed to know would be a caller that could not be given a fake.
 */
public interface AnisetteSource {

    /**
     * Get ready if possible, doing only what is not already done.
     *
     * @return false if sign-in has to fall back to a remote server, with
     *         {@link #unavailableReason()} explaining why
     */
    boolean ensureReady();

    /** Why this cannot be used, or null if it can. */
    String unavailableReason();

    /**
     * Whether a session established with local Anisette is about to continue against something
     * else - the case where Apple sees a different machine and may demand a new sign-in.
     */
    boolean isChangingMachineIdentity();

    /** The one-time password, base64. Empty string when unavailable. */
    String otp();

    /** The machine identifier, base64. Empty string when unavailable. */
    String machine();

    /** Record which kind of Anisette established the current session. */
    void recordSessionProvenance(boolean establishedLocally);

    /** Short human-readable state, for logs and diagnostics. */
    String describe();
}
