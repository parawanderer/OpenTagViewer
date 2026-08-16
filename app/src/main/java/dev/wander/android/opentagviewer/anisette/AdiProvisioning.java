package dev.wander.android.opentagviewer.anisette;

import android.util.Base64;
import android.util.Log;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.Map;
import java.util.TimeZone;

/**
 * The provisioning round trip: teaching Apple about this "machine" so it can produce Anisette
 * data locally, instead of every login going through somebody else's Anisette server.
 *
 * <p>It runs once. Apple's side of it is three requests:
 *
 * <ol>
 *   <li>GET the URL bag, because the provisioning endpoints are not fixed addresses</li>
 *   <li>POST to {@code midStartProvisioning}, which returns the server's intermediate
 *       metadata (spim)</li>
 *   <li>POST to {@code midFinishProvisioning} with what ADI made of it (cpim), which returns
 *       a persistent token and a trust key</li>
 * </ol>
 *
 * <p>ADI is called in between: it turns the spim into a cpim, and then swallows the token and
 * trust key. After that the machine is provisioned and ADI can produce one-time passwords
 * offline.
 *
 * <p>Modelled on <a href="https://github.com/Dadoum/Provision">Dadoum/Provision</a>
 * {@code lib/provision/adi.d}, which is the reference every public Anisette server runs.
 */
public final class AdiProvisioning {
    private static final String TAG = "AdiProvisioning";

    private static final String URL_BAG = "https://gsa.apple.com/grandslam/GsService2/lookup";

    /** Anonymous provisioning - no Apple account is involved in this exchange. */
    public static final long ANONYMOUS_DS_ID = -2L;

    private static final String EMPTY_REQUEST =
            "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
            + "<!DOCTYPE plist PUBLIC \"-//Apple//DTD PLIST 1.0//EN\" "
            + "\"http://www.apple.com/DTDs/PropertyList-1.0.dtd\">\n"
            + "<plist version=\"1.0\"><dict>"
            + "<key>Header</key><dict/><key>Request</key><dict/>"
            + "</dict></plist>";

    private static final String CPIM_REQUEST =
            "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
            + "<!DOCTYPE plist PUBLIC \"-//Apple//DTD PLIST 1.0//EN\" "
            + "\"http://www.apple.com/DTDs/PropertyList-1.0.dtd\">\n"
            + "<plist version=\"1.0\"><dict>"
            + "<key>Header</key><dict/>"
            + "<key>Request</key><dict><key>cpim</key><string>%s</string></dict>"
            + "</dict></plist>";

    private final AdiDeviceIdentity identity;
    private final AdiLibrary adi;

    public AdiProvisioning(AdiDeviceIdentity identity, AdiLibrary adi) {
        this.identity = identity;
        this.adi = adi;
    }

    /** Thrown when Apple or ADI refuses, with enough context to tell which. */
    public static final class ProvisioningException extends Exception {
        public ProvisioningException(String message) {
            super(message);
        }

        public ProvisioningException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    /**
     * Provision this machine. Safe to call when already provisioned - it checks first.
     *
     * @return true if provisioning ran, false if it was already done
     */
    public boolean provisionIfNeeded(long dsId) throws ProvisioningException {
        final int loginCode = this.adi.getLoginCode(dsId);
        if (loginCode == 0) {
            Log.i(TAG, "already provisioned");
            return false;
        }
        if (loginCode != AdiError.NOT_PROVISIONED) {
            throw new ProvisioningException(
                    AdiFunction.GET_LOGIN_CODE.appleName() + " failed: "
                    + AdiError.describe(loginCode));
        }

        provision(dsId);
        return true;
    }

    private void provision(long dsId) throws ProvisioningException {
        final Map<String, String> urls = loadUrlBag();

        final String startUrl = urls.get("midStartProvisioning");
        final String finishUrl = urls.get("midFinishProvisioning");
        if (startUrl == null || finishUrl == null) {
            throw new ProvisioningException(
                    "the URL bag has no provisioning endpoints - Apple has changed its shape. "
                    + "Got: " + urls.keySet());
        }

        final byte[] spim = decode(post(startUrl, EMPTY_REQUEST), "spim");

        final int[] out = new int[2];
        final byte[] cpim = this.adi.provisioningStart(dsId, spim, out);
        if (cpim == null) {
            throw new ProvisioningException(AdiFunction.PROVISIONING_START.appleName()
                    + " failed: " + AdiError.describe(out[1]));
        }
        final int session = out[0];

        try {
            final String response = post(finishUrl, String.format(
                    CPIM_REQUEST, Base64.encodeToString(cpim, Base64.NO_WRAP)));

            final byte[] persistentToken = decode(response, "ptm");
            final byte[] trustKey = decode(response, "tk");

            final int result = this.adi.provisioningEnd(session, persistentToken, trustKey);
            if (result != 0) {
                throw new ProvisioningException(AdiFunction.PROVISIONING_END.appleName()
                        + " failed: " + AdiError.describe(result));
            }
            Log.i(TAG, "provisioned: session " + session + " completed");
        } catch (final ProvisioningException e) {
            // An abandoned session is not automatically cleaned up, and leaving one behind
            // makes the next attempt harder to reason about.
            this.adi.provisioningDestroy(session);
            throw e;
        }
    }

    /** The provisioning endpoints are not fixed addresses - Apple hands out a bag of them. */
    private Map<String, String> loadUrlBag() throws ProvisioningException {
        final String body = get(URL_BAG);
        try {
            final Object plist = SimplePlist.parse(body);
            @SuppressWarnings("unchecked")
            final Map<String, Object> bag =
                    (Map<String, Object>) ((Map<String, Object>) plist).get("urls");

            if (bag == null || bag.isEmpty()) {
                throw new ProvisioningException("the URL bag came back empty");
            }

            final Map<String, String> out = new java.util.HashMap<>();
            for (final Map.Entry<String, Object> entry : bag.entrySet()) {
                if (entry.getValue() instanceof String) {
                    out.put(entry.getKey(), (String) entry.getValue());
                }
            }
            return out;
        } catch (final ProvisioningException e) {
            throw e;
        } catch (final Exception e) {
            throw new ProvisioningException("could not read the URL bag", e);
        }
    }

    private static byte[] decode(String responseBody, String key) throws ProvisioningException {
        try {
            final String value = SimplePlist.string(
                    SimplePlist.parse(responseBody), "Response", key);
            if (value == null) {
                throw new ProvisioningException(
                        "Apple's response has no Response/" + key + " - the provisioning "
                        + "protocol has changed, or the request was rejected");
            }
            return Base64.decode(value, Base64.DEFAULT);
        } catch (final ProvisioningException e) {
            throw e;
        } catch (final Exception e) {
            throw new ProvisioningException("could not read " + key + " from Apple's response", e);
        }
    }

    private String get(String url) throws ProvisioningException {
        return request(url, null);
    }

    private String post(String url, String body) throws ProvisioningException {
        return request(url, body);
    }

    private String request(String url, String body) throws ProvisioningException {
        HttpURLConnection connection = null;
        try {
            connection = (HttpURLConnection) new URL(url).openConnection();
            applyHeaders(connection);
            connection.setConnectTimeout(30_000);
            connection.setReadTimeout(30_000);

            if (body != null) {
                connection.setRequestMethod("POST");
                connection.setDoOutput(true);
                connection.getOutputStream().write(body.getBytes(StandardCharsets.UTF_8));
            }

            final int status = connection.getResponseCode();
            final InputStream stream = status >= 400
                    ? connection.getErrorStream() : connection.getInputStream();
            final String response = readAll(stream);

            if (status >= 400) {
                throw new ProvisioningException(
                        "Apple returned HTTP " + status + " for " + url + ": " + response);
            }
            return response;
        } catch (final ProvisioningException e) {
            throw e;
        } catch (final IOException e) {
            // The cause is named inline rather than left to the stack trace: an
            // UnknownHostException and an SSLHandshakeException here mean very different
            // things, and instrumented test output does not always carry the cause chain.
            throw new ProvisioningException("could not reach " + url + ": "
                    + e.getClass().getSimpleName() + ": " + e.getMessage(), e);
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
    }

    /**
     * The headers Apple's provisioning endpoints expect.
     *
     * <p>Note X-Mme-Device-Id against X-MMe-Client-Info: the capitalisation genuinely differs
     * between the two, which should not matter under the HTTP spec but is copied faithfully
     * from the reference implementation rather than tidied up.
     */
    private void applyHeaders(HttpURLConnection connection) {
        connection.setRequestProperty("User-Agent", this.identity.hardware().userAgent());
        connection.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");
        connection.setRequestProperty("Connection", "keep-alive");

        connection.setRequestProperty("X-Mme-Device-Id", this.identity.uniqueDeviceIdentifier());
        connection.setRequestProperty("X-MMe-Client-Info", this.identity.hardware().clientInfo());
        connection.setRequestProperty("X-Apple-I-MD-LU", this.identity.localUserUuid());
        connection.setRequestProperty("X-Apple-Client-App-Name", "Setup");
        connection.setRequestProperty("X-Apple-I-Client-Time", now());
    }

    /** ISO 8601, whole seconds. Apple rejects anything with milliseconds on it. */
    private static String now() {
        final SimpleDateFormat format =
                new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.ROOT);
        format.setTimeZone(TimeZone.getTimeZone("UTC"));
        return format.format(new Date());
    }

    private static String readAll(InputStream stream) throws IOException {
        if (stream == null) {
            return "";
        }
        final ByteArrayOutputStream out = new ByteArrayOutputStream();
        final byte[] buffer = new byte[8192];
        int read;
        while ((read = stream.read(buffer)) != -1) {
            out.write(buffer, 0, read);
        }
        return out.toString("UTF-8");
    }
}
