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
        // Uses the real marker rather than an invented message, so that a change to how this
        // is detected breaks the test instead of quietly making it test nothing.
        return new FakeAnisetteSource(false,
                "libstoreservicescore.so " + AdiLibraryManifest.MISMATCH_MARKER
                        + ": Apple has probably shipped a new Apple Music build (this manifest "
                        + "is from " + expectedVersion + ")",
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

    private AdiDeviceIdentity.Hardware hardware = AdiDeviceIdentity.Hardware.IPHONE;

    /**
     * Claim a different machine. Not a named constructor because it is orthogonal to the
     * states above - every one of them can be either profile, and the interesting case is an
     * install that is {@link AdiDeviceIdentity.Hardware#LEGACY_MAC} <i>and</i> unavailable.
     */
    public FakeAnisetteSource claiming(AdiDeviceIdentity.Hardware hardware) {
        this.hardware = hardware;
        return this;
    }

    /**
     * The real profile's own JSON, not a hand-written copy.
     *
     * <p>A literal here would keep passing after the real thing changed shape, which is the
     * failure this fake would otherwise introduce - it is substituted into the sign-in path
     * that feeds Python.
     */
    @Override
    public String hardwareProfileJson() {
        return this.hardware.toJson();
    }

    /**
     * Fixed values, so a test can assert that these exact strings reached Apple.
     *
     * <p>A UUID for the device id because FindMy.py parses it as one - CloudKit does
     * {@code uuid.UUID(device_uuid)} - and a real installation's is a UUID too.
     */
    public static final String UID = "9E1D0C4B-77A2-4E3F-8D51-2B6A0F9C3D74";
    public static final String DEVID = "1A2B3C4D-5E6F-4071-8293-A4B5C6D7E8F9";

    @Override
    public String deviceIdsJson() {
        return "{\"uid\":\"" + UID + "\",\"devid\":\"" + DEVID + "\"}";
    }

    @Override
    public String describe() {
        return this.ready ? "fake, ready" : "fake, unavailable: " + this.unavailableReason;
    }

    @Override
    public boolean wasSessionEstablishedLocally() {
        return Boolean.TRUE.equals(this.recordedProvenance);
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
