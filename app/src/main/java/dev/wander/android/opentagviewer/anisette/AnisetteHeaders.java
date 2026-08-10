package dev.wander.android.opentagviewer.anisette;

import android.util.Base64;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Map.Entry;
import java.util.TimeZone;

/**
 * The Anisette headers a login needs, produced on this device.
 *
 * <p>With remote Anisette servers (as in most implementations) these come from a public Anisette server:
 * the app asks a stranger's machine to compute them, which means every login is visible to
 * that operator and stops working when their server does. With ADI provisioned locally, the
 * same headers come from {@link AdiLibrary#requestOtp}, offline and instantly.
 *
 * <p>The shape is dictated by what Apple accepts, and matches what
 * <a href="https://github.com/Dadoum/anisette-v3-server">anisette-v3-server</a> returns -
 * which is what <a href="https://github.com/malmeloo/FindMy.py">FindMy.py</a> is already written to consume.
 */
public final class AnisetteHeaders {

    private final AdiLibrary adi;
    private final AdiDeviceIdentity identity;

    public AnisetteHeaders(AdiLibrary adi, AdiDeviceIdentity identity) {
        this.adi = adi;
        this.identity = identity;
    }

    /**
     * Routing information. anisette-v3-server returns this constant rather than the value
     * Apple hands back during provisioning, and it is what every client in use today sends, so
     * it is reproduced rather than improved upon.
     */
    private static final String ROUTING_INFO = "17106176";

    /**
     * Produce a set of headers for a login.
     *
     * <p>(This is not a "one-time" password. The password is not generated <i>per call</i>: repeated requests
     * return the same value for a while, and then it rotates. Measured on a device it changed
     * within 16 seconds, sampling every two - enough to establish that it rotates on a timer
     * of that order, not enough to claim an exact period.)
     *
     * <p>So: reusing a set within a single login is fine, and they should be produced fresh
     * per login rather than stored.
     *
     * <p>The device clock is worth being aware of. ADI has a dedicated "time error" code
     * ({@code -45036}), which suggests the value is time-derived and that a badly wrong clock
     * is a failure mode Apple expected - it would present as a rejected login with nothing
     * visibly wrong.
     */
    public Map<String, String> generate(long dsId) throws AdiLibrary.AdiUnavailableException {
        final byte[][] otp = this.adi.requestOtp(dsId);
        final byte[] machineIdentifier = otp[0];
        final byte[] oneTimePassword = otp[1];

        // Insertion-ordered so logs and tests read consistently.
        final Map<String, String> headers = new LinkedHashMap<>();
        headers.put("X-Apple-I-MD", Base64.encodeToString(oneTimePassword, Base64.NO_WRAP));
        headers.put("X-Apple-I-MD-M", Base64.encodeToString(machineIdentifier, Base64.NO_WRAP));
        headers.put("X-Apple-I-MD-RINFO", ROUTING_INFO);
        headers.put("X-Apple-I-MD-LU", this.identity.localUserUuid());
        headers.put("X-Apple-I-SRL-NO", "0");
        headers.put("X-Apple-I-Client-Time", clientTime());
        headers.put("X-Apple-I-TimeZone", TimeZone.getDefault().getID());
        headers.put("X-Apple-Locale", Locale.getDefault().toString());
        headers.put("X-Mme-Device-Id", this.identity.uniqueDeviceIdentifier());
        headers.put("X-MMe-Client-Info", AdiDeviceIdentity.CLIENT_INFO);
        return headers;
    }

    /** ISO 8601 to whole seconds. Apple rejects anything carrying milliseconds. */
    private static String clientTime() {
        final SimpleDateFormat format =
                new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.ROOT);
        format.setTimeZone(TimeZone.getTimeZone("UTC"));
        return format.format(new Date());
    }

    /**
     * The same headers as JSON, in the shape anisette-v3-server's {@code /v3/get_headers}
     * returns them.
     *
     * <p>Deliberately identical to the remote server's response: it means the Python side can
     * treat a local and a remote provider as the same thing, and that falling back costs
     * nothing but a different source of the same string.
     */
    public static String toJson(Map<String, String> headers) {
        final StringBuilder json = new StringBuilder("{");
        for (final Entry<String, String> header : headers.entrySet()) {
            if (json.length() > 1) {
                json.append(',');
            }
            json.append('"').append(header.getKey()).append("\":\"")
                    .append(escape(header.getValue())).append('"');
        }
        return json.append('}').toString();
    }

    /** Enough JSON escaping for header values, which are base64, identifiers and timestamps. */
    private static String escape(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
