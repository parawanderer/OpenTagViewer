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

    /**
     * Record which kind of Anisette established the current session.
     *
     * <p>Called from the Python side, which is the only place that knows: it decides at
     * sign-in time whether the local provider was usable and falls back on its own. Anything
     * on the Java side can only guess from a status read earlier, and would be wrong exactly
     * when it mattered - a sign-in that started local and fell back mid-way.
     */
    void recordSessionProvenance(boolean establishedLocally);

    /** What {@link #recordSessionProvenance} last recorded. False if nothing has. */
    boolean wasSessionEstablishedLocally();

    /**
     * The machine this install claims to be, as FindMy.py's {@code DeviceIdentity} mapping.
     *
     * <p><b>Answerable when nothing else here is.</b> Every other method on this interface
     * describes locally produced Anisette, and returns nothing useful when it is unavailable -
     * but a sign-in relayed through a remote server still has to tell Apple which machine it
     * is, and it must be the same answer either way. So this does not consult ADI, does not
     * download anything, and does not care whether {@link #ensureReady} succeeded: the profile
     * is persisted beside the device identity and read straight back.
     *
     * <p>Called from Python at sign-in. See {@code identity.identityForNewSession}.
     */
    String hardwareProfileJson();

    /**
     * The two ids this installation already introduced itself to Apple with, as
     * {@code {"uid": ..., "devid": ...}}.
     *
     * <p>ADI provisioning is its own exchange with Apple, made before FindMy.py exists, and it
     * carries {@code X-Mme-Device-Id} and {@code X-Apple-I-MD-LU}. FindMy.py used to mint its
     * own pair, so <b>one install talked to Apple as two devices</b>; these are what stop that.
     *
     * <p>Both or neither - FindMy.py refuses one of two, and is right to. A client matching one
     * id and inventing the other is a shape no real client produces.
     *
     * <p>Answerable without ADI, for the same reason as {@link #hardwareProfileJson}.
     */
    String deviceIdsJson();

    /** Short human-readable state, for logs and diagnostics. */
    String describe();
}
