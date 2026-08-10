package dev.wander.android.opentagviewer.anisette;

/**
 * An {@link AnisetteSource} that is whatever a test needs it to be.
 *
 * <p>The real one downloads Apple's libraries, verifies them against recorded hashes, and
 * provisions a machine identity with Apple. That makes most of the states the UI has to render
 * impossible to reach on demand - and one of them, "Apple published a new build whose hashes no
 * longer match", impossible to reach at all. It is also the state that matters most, because it
 * is the only one that reveals the supply-your-own-APK controls.
 *
 * <p>Use the named constructors rather than setting fields: they are the states the UI actually
 * distinguishes, and naming them keeps a test honest about which one it means.
 */
public final class FakeAnisetteSource implements AnisetteSource {

    private final boolean ready;
    private final String unavailableReason;
    private final boolean changingMachineIdentity;

    private Boolean recordedProvenance;
    private int ensureReadyCalls;

    private FakeAnisetteSource(boolean ready, String unavailableReason,
                               boolean changingMachineIdentity) {
        this.ready = ready;
        this.unavailableReason = unavailableReason;
        this.changingMachineIdentity = changingMachineIdentity;
    }

    /** Working: libraries loaded, machine provisioned, headers available. */
    public static FakeAnisetteSource ready() {
        return new FakeAnisetteSource(true, null, false);
    }

    /** Failed for an ordinary reason - no network being the usual one. */
    public static FakeAnisetteSource unavailable(String reason) {
        return new FakeAnisetteSource(false, reason, false);
    }

    /**
     * Failed because Apple replaced the APK the libraries come from.
     *
     * <p>Cannot be produced any other way: it depends on Apple shipping a build, and the last
     * time they did was April 2025.
     */
    public static FakeAnisetteSource appleChangedTheLibraries(String expectedVersion) {
        return new FakeAnisetteSource(false,
                "libstoreservicescore.so does not match the manifest - Apple has probably "
                        + "shipped a new Apple Music build (this manifest is from "
                        + expectedVersion + ")",
                false);
    }

    /**
     * Unavailable, for a session that was established locally - the case where continuing
     * against a server presents Apple with a different machine.
     */
    public static FakeAnisetteSource unavailableAfterLocalSession(String reason) {
        return new FakeAnisetteSource(false, reason, true);
    }

    @Override
    public boolean ensureReady() {
        this.ensureReadyCalls++;
        return this.ready;
    }

    @Override
    public String unavailableReason() {
        return this.unavailableReason;
    }

    @Override
    public boolean isChangingMachineIdentity() {
        return this.changingMachineIdentity;
    }

    @Override
    public String otp() {
        return this.ready ? "AAAABQAAABBSUIpSHa7cBJnWyxeDD4UkAAAABA==" : "";
    }

    @Override
    public String machine() {
        return this.ready ? "fxB7CSVYqLXLJ+vWLeubVOG64YOcI1H4+Eav8164u10VcaJjBQhxIpKV" : "";
    }

    @Override
    public void recordSessionProvenance(boolean establishedLocally) {
        this.recordedProvenance = establishedLocally;
    }

    @Override
    public String describe() {
        return this.ready ? "fake, ready" : "fake, unavailable: " + this.unavailableReason;
    }

    /** What was recorded, or null if nothing was. */
    public Boolean recordedProvenance() {
        return this.recordedProvenance;
    }

    /** How many times readiness was asked for - enough to catch work repeated per call. */
    public int ensureReadyCalls() {
        return this.ensureReadyCalls;
    }
}
