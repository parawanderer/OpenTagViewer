package dev.wander.android.opentagviewer.anisette;

import java.security.SecureRandom;
import java.util.Locale;
import java.util.UUID;

/**
 * The identity this device presents to Apple when provisioning Anisette.
 *
 * <p>It is entirely invented. There is no hardware attestation to defeat and nothing to
 * extract from a real Apple device - every public Anisette server in use today runs on values
 * generated exactly like these, which is the working proof that Apple does not check them
 * against anything real.
 *
 * <p>The lengths, however, are not free. ADI rejects an identifier of the wrong size with
 * -45001 (invalid parameters), so these match the reference implementation exactly: 8 random
 * bytes as 16 lowercase hex characters for ADI, and 32 bytes as 64 uppercase hex characters
 * for the local user UUID.
 *
 * <p><b>The one rule that matters is that this is generated once and then kept.</b> Regenerating
 * it per login would make every session look like a brand new machine to Apple, which is
 * precisely the pattern two-factor authentication exists to notice. So this is persisted, and
 * it must survive anything short of the user deliberately resetting Anisette.
 */
public final class AdiDeviceIdentity {

    /**
     * What we claim to be.
     *
     * <p>The value matches anisette-v3-server, which was originally chosen because it is the most
     * common string Apple sees and therefore the least remarkable thing to send. <b>That reasoning
     * no longer applies.</b> Rule 11 has this app registering under a name and a serial that exist
     * to be recognised - a user looking at their Apple device list is meant to know which entry is
     * this - so blending in stopped being achievable the moment being identifiable became the
     * point. The string stays because it works, not because it hides.
     *
     * <p><b>The parts describe one real release and have to move together.</b> Model, OS version,
     * build, CFNetwork and Darwin are a set; claiming a Mac here and an iPhone elsewhere is a
     * contradiction Apple's own clients never produce. See rule 11 and
     * {@code docs/findmy-export/01-authentication.md} section 2.2, which carries a worked iPhone
     * set if this ever changes to one.
     */
    public static final String CLIENT_INFO =
            "<MacBookPro13,2> <macOS;13.1;22C65> <com.apple.AuthKit/1 (com.apple.dt.Xcode/3594.4.19)>";

    private final String uniqueDeviceIdentifier;
    private final String adiIdentifier;
    private final String localUserUuid;

    public AdiDeviceIdentity(String uniqueDeviceIdentifier, String adiIdentifier,
                             String localUserUuid) {
        this.uniqueDeviceIdentifier = uniqueDeviceIdentifier;
        this.adiIdentifier = adiIdentifier;
        this.localUserUuid = localUserUuid;
    }

    /** A fresh identity. Call this once, persist the result, and never call it again. */
    public static AdiDeviceIdentity generate() {
        final SecureRandom random = new SecureRandom();

        return new AdiDeviceIdentity(
                UUID.randomUUID().toString().toUpperCase(Locale.ROOT),
                hex(random, 8).toLowerCase(Locale.ROOT),
                hex(random, 32).toUpperCase(Locale.ROOT));
    }

    private static String hex(SecureRandom random, int bytes) {
        final byte[] buffer = new byte[bytes];
        random.nextBytes(buffer);

        final StringBuilder out = new StringBuilder(bytes * 2);
        for (final byte b : buffer) {
            out.append(String.format("%02X", b));
        }
        return out.toString();
    }

    /** Sent as X-Mme-Device-Id. */
    public String uniqueDeviceIdentifier() {
        return this.uniqueDeviceIdentifier;
    }

    /** Handed to ADISetAndroidID. 16 lowercase hex characters; other lengths are rejected. */
    public String adiIdentifier() {
        return this.adiIdentifier;
    }

    /** Sent as X-Apple-I-MD-LU. */
    public String localUserUuid() {
        return this.localUserUuid;
    }
}
