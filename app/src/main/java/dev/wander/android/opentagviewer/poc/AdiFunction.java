package dev.wander.android.opentagviewer.poc;

/**
 * The ADI entry points exported by Apple's {@code libstoreservicescore.so}, and the obfuscated
 * names they are actually exported under.
 *
 * <p>Apple deliberately scrambles these names, and <em>they differ between APK builds</em>. So
 * this mapping is the one place that has to change when Apple ships a new Apple Music APK, and
 * the one thing that makes a failure legible: "ADIProvisioningStart is missing from this build"
 * is actionable, where "rsegvyrt87 is missing" is not.
 *
 * <p>Names and signatures come from
 * <a href="https://github.com/Dadoum/Provision">Dadoum/Provision</a>,
 * {@code lib/provision/adi.d}. The signatures are recorded here because they cannot be
 * recovered from the binary - the exports are plain C symbols with no type information.
 */
public enum AdiFunction {

    /** {@code int(const char *path)} - directory holding libCoreADI.so, which ADI opens itself. */
    LOAD_LIBRARY_WITH_PATH("ADILoadLibraryWithPath", "kq56gsgHG6"),

    /** {@code int(const char *identifier, uint length)} - the invented device identity. */
    SET_ANDROID_ID("ADISetAndroidID", "Sph98paBcz"),

    /** {@code int(const char *path)} - directory ADI persists its provisioning state into. */
    SET_PROVISIONING_PATH("ADISetProvisioningPath", "nf92ngaK92"),

    /** {@code int(ulong dsId)} - throw away an existing provisioning session. */
    PROVISIONING_ERASE("ADIProvisioningErase", "p435tmhbla"),

    /** {@code int(ulong, ubyte*, uint, ubyte**, uint*, ubyte**, uint*)} */
    SYNCHRONIZE("ADISynchronize", "tn46gtiuhw"),

    /** {@code int(uint session)} */
    PROVISIONING_DESTROY("ADIProvisioningDestroy", "fy34trz2st"),

    /** {@code int(uint session, ubyte *ptm, uint, ubyte *tk, uint)} - second half of the round trip. */
    PROVISIONING_END("ADIProvisioningEnd", "uv5t6nhkui"),

    /** {@code int(ulong, ubyte*, uint, ubyte**, uint*, uint*)} - first half of the round trip. */
    PROVISIONING_START("ADIProvisioningStart", "rsegvyrt87"),

    /** {@code int(ulong dsId)} */
    GET_LOGIN_CODE("ADIGetLoginCode", "aslgmuibau"),

    /** {@code int(void *ptr)} - frees anything ADI allocated on our behalf. */
    DISPOSE("ADIDispose", "jk24uiwqrg"),

    /** {@code int(ulong, ubyte**, uint*, ubyte**, uint*)} - the one-time password per login. */
    OTP_REQUEST("ADIOTPRequest", "qi864985u0");

    private final String appleName;
    private final String symbol;

    AdiFunction(String appleName, String symbol) {
        this.appleName = appleName;
        this.symbol = symbol;
    }

    /** Apple's own name for this function, for error messages and logs. */
    public String appleName() {
        return this.appleName;
    }

    /** The obfuscated name it is actually exported under, for dlsym. */
    public String symbol() {
        return this.symbol;
    }

    @Override
    public String toString() {
        return this.appleName + " (" + this.symbol + ")";
    }
}
