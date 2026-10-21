package dev.wander.android.opentagviewer.anisette;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Locale;

/**
 * Who the emulated Mac says it is - every value Apple sees about the machine, in one place.
 *
 * <p><b>Rule 11 applies with extra force.</b> The library hashes {@link #hostUuid()} into the
 * machine id it mints, and Apple binds the provisioning to the device id, local user id and serial
 * presented with it. So these are generated once, stored beside the library's own state
 * ({@code identity.json} next to {@code store/}), and never regenerated: a second set is a second
 * Mac on the user's Apple account, and the session established with the first stops working.
 *
 * <p><b>The shapes are a real Mac's, and the values are the ones that have been seen to
 * work.</b> They follow sacca97/os-findmy-e's {@code mac_anisette.py} and {@code gsa.py}, which
 * is the configuration Apple has been observed to issue a Find My token to. Where that source is
 * inconsistent with itself - provisioning sends Darwin 22.3.0 and the login 22.2.0 - it is copied
 * rather than corrected, because the consistent version is the untested one.
 *
 * <p>The serial is this app's own {@code 0PENTAGV} serial, so the device-list entry stays
 * recognisable (rule 11). Apple has not been seen to check serials at all.
 */
public final class MacIdentity {

    static final String MODEL = "MacBookPro13,2";
    static final String OS_NAME = "macOS";
    static final String OS_VERSION = "13.1";
    static final String OS_BUILD = "22C65";
    static final String CFNETWORK = "1404.0.5";

    /** What the sign-in reports, through FindMy.py's {@code DeviceIdentity}. */
    static final String LOGIN_DARWIN = "22.2.0";

    /** {@code X-MMe-Client-Info} for provisioning. FindMy.py composes the same string at login. */
    static final String CLIENT_INFO = "<" + MODEL + "> <" + OS_NAME + ";" + OS_VERSION + ";"
            + OS_BUILD + "> <com.apple.AuthKit/1 (com.apple.akd/1.0)>";

    /** The provisioning requests' User-Agent - Darwin 22.3.0 here, as in the reference. */
    static final String PROVISIONING_USER_AGENT = "akd/1.0 CFNetwork/" + CFNETWORK
            + " Darwin/22.3.0";

    /**
     * A Mac's {@code X-Mme-Device-Id} is its provisioning UDID - eight hex, a dash, sixteen hex -
     * and not its hardware UUID.
     */
    private static final String UDID_PREFIX = "00006000";

    private static final String FILE = "identity.json";

    private final String deviceId;
    private final String localUserUuid;
    private final String hostUuid;
    private final String serial;
    private final long routing;
    private final long provisionedAtMillis;

    private MacIdentity(String deviceId, String localUserUuid, String hostUuid, String serial,
                        long routing, long provisionedAtMillis) {
        this.deviceId = deviceId;
        this.localUserUuid = localUserUuid;
        this.hostUuid = hostUuid;
        this.serial = serial;
        this.routing = routing;
        this.provisionedAtMillis = provisionedAtMillis;
    }

    /** A fresh, unprovisioned machine. Call once, {@link #save}, never call again. */
    static MacIdentity generate() {
        final SecureRandom random = new SecureRandom();
        return new MacIdentity(
                UDID_PREFIX + "-" + hex(random, 8).toUpperCase(Locale.ROOT),
                hex(random, 16).toUpperCase(Locale.ROOT),
                hex(random, 16),
                AdiDeviceIdentity.generateSerial(random),
                0,
                0);
    }

    /** The stored identity, or null if this install has never had one. */
    static MacIdentity load(File directory) throws IOException {
        final File file = new File(directory, FILE);
        if (!file.isFile()) {
            return null;
        }
        try {
            final JSONObject json = new JSONObject(
                    new String(MacCoreAdiFetcher.readFile(file), StandardCharsets.UTF_8));
            return new MacIdentity(
                    json.getString("deviceId"),
                    json.getString("localUserUuid"),
                    json.getString("hostUuid"),
                    json.getString("serial"),
                    json.optLong("routing", 0),
                    json.optLong("provisionedAtMillis", 0));
        } catch (final JSONException e) {
            throw new IOException("the emulated Mac's identity is unreadable", e);
        }
    }

    void save(File directory) throws IOException {
        if (!directory.isDirectory() && !directory.mkdirs()) {
            throw new IOException("could not create " + directory);
        }
        final String text;
        try {
            text = new JSONObject()
                    .put("deviceId", this.deviceId)
                    .put("localUserUuid", this.localUserUuid)
                    .put("hostUuid", this.hostUuid)
                    .put("serial", this.serial)
                    .put("routing", this.routing)
                    .put("provisionedAtMillis", this.provisionedAtMillis)
                    .toString(2);
        } catch (final JSONException e) {
            throw new IOException("could not describe the emulated Mac", e);
        }
        final File partial = new File(directory, FILE + ".partial");
        try (FileOutputStream out = new FileOutputStream(partial)) {
            out.write(text.getBytes(StandardCharsets.UTF_8));
            out.getFD().sync();
        }
        if (!partial.renameTo(new File(directory, FILE))) {
            throw new IOException("could not store the emulated Mac's identity");
        }
    }

    /** This identity, provisioned, with the routing Apple assigned it. */
    MacIdentity provisioned(long assignedRouting, long atMillis) {
        return new MacIdentity(this.deviceId, this.localUserUuid, this.hostUuid, this.serial,
                assignedRouting, atMillis);
    }

    /** {@code X-Mme-Device-Id}. */
    public String deviceId() {
        return this.deviceId;
    }

    /**
     * The local user id. Sent raw at provisioning and as {@code base64} of this at login, by
     * FindMy.py - which is what the reference does, and what Apple accepted.
     */
    public String localUserUuid() {
        return this.localUserUuid;
    }

    /** What {@code gethostuuid} returns inside the emulator, as 32 hex characters. */
    public String hostUuid() {
        return this.hostUuid;
    }

    byte[] hostUuidBytes() {
        final byte[] out = new byte[16];
        for (int i = 0; i < 16; i++) {
            out[i] = (byte) Integer.parseInt(this.hostUuid.substring(i * 2, i * 2 + 2), 16);
        }
        return out;
    }

    public String serial() {
        return this.serial;
    }

    /** {@code X-Apple-I-MD-RINFO}, as Apple assigned it at provisioning. 0 before that. */
    public long routing() {
        return this.routing;
    }

    public long provisionedAtMillis() {
        return this.provisionedAtMillis;
    }

    /** The six values FindMy.py's {@code DeviceIdentity.from_json} takes. See rule 11. */
    static String hardwareJson() {
        try {
            return new JSONObject()
                    .put("model", MODEL)
                    .put("os_name", OS_NAME)
                    .put("os_version", OS_VERSION)
                    .put("os_build", OS_BUILD)
                    .put("cfnetwork", CFNETWORK)
                    .put("darwin", LOGIN_DARWIN)
                    .toString();
        } catch (final JSONException e) {
            throw new IllegalStateException("could not describe the emulated Mac", e);
        }
    }

    private static String hex(SecureRandom random, int bytes) {
        final byte[] buffer = new byte[bytes];
        random.nextBytes(buffer);
        final StringBuilder out = new StringBuilder(bytes * 2);
        for (final byte b : buffer) {
            out.append(String.format("%02x", b));
        }
        return out.toString();
    }
}
