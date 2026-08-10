package dev.wander.android.opentagviewer.anisette;

/**
 * What local Anisette has to say for itself, as a value.
 *
 * <p>Separate from rendering it on purpose. Working it out means
 * {@link AnisetteSource#ensureReady()}, which downloads, hashes and can talk to Apple - so it
 * cannot happen on the main thread. Drawing it is a handful of {@code setVisibility} calls that
 * must happen on the main thread. Keeping a value between the two means each half can be tested
 * without the other: the classification here needs no views, and the rendering needs no Apple.
 */
public final class AnisetteStatus {

    public enum State {
        /** Nothing set up yet, and nothing wrong - it prepares itself when first needed. */
        PENDING,
        /**
         * Being worked out right now.
         *
         * <p>Distinct from PENDING, which says nothing is happening. Finding out can take up
         * to a connection timeout - the worst case being offline, where it waits the full 30
         * seconds before failing - and showing "sets itself up next time you sign in" for that
         * long reads as the screen having hung.
         */
        CHECKING,
        READY,
        /** Failed for an ordinary reason: no network, or a remote server was chosen. */
        UNAVAILABLE,
        /**
         * Apple replaced the libraries, so the recorded hashes no longer match.
         *
         * <p>The only state that offers supplying an APK by hand, because it is the only one
         * where doing so would help. Everything else is either fine or fixed by other means.
         */
        APPLE_CHANGED
    }

    private final State state;
    private final String detail;

    private AnisetteStatus(State state, String detail) {
        this.state = state;
        this.detail = detail;
    }

    public static AnisetteStatus pending() {
        return new AnisetteStatus(State.PENDING, null);
    }

    /** Being worked out. Show this while {@link #of(AnisetteSource)} is running. */
    public static AnisetteStatus checking() {
        return new AnisetteStatus(State.CHECKING, null);
    }

    /**
     * Ask a source how it is doing. <b>Blocks</b> - call from a background thread.
     *
     * @param source may be null, which reads as pending: nothing has been set up, and nothing
     *               is wrong
     */
    public static AnisetteStatus of(final AnisetteSource source) {
        if (source == null) {
            return pending();
        }
        if (source.ensureReady()) {
            return new AnisetteStatus(State.READY, null);
        }

        final String reason = source.unavailableReason();
        return new AnisetteStatus(
                looksLikeAppleChangedTheLibraries(reason)
                        ? State.APPLE_CHANGED : State.UNAVAILABLE,
                reason);
    }

    /**
     * Whether a failure is "Apple shipped a new build" rather than something ordinary.
     *
     * <p>Matched on the message because that is what the source reports, and the alternative -
     * a typed error for every way this can fail - would put knowledge of downloads and hashes
     * into an interface whose whole point is that a fake can implement it. The marker lives in
     * {@link AdiLibraryManifest} so both sides refer to the same string rather than two copies
     * of it.
     */
    private static boolean looksLikeAppleChangedTheLibraries(final String reason) {
        return reason != null && reason.contains(AdiLibraryManifest.MISMATCH_MARKER);
    }

    public State state() {
        return this.state;
    }

    /** The failure, when there is one. Null otherwise. */
    public String detail() {
        return this.detail;
    }

    /** Whether supplying an Apple Music APK by hand would help. */
    public boolean needsOwnApk() {
        return this.state == State.APPLE_CHANGED;
    }

    @Override
    public String toString() {
        return this.detail == null ? this.state.name() : this.state + ": " + this.detail;
    }
}
