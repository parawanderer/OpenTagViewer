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
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.TimeZone;

/**
 * Registering the emulated Mac with Apple: the same three requests as {@link AdiProvisioning},
 * presented the way a Mac's {@code akd} presents them.
 *
 * <ol>
 *   <li>GET the URL bag</li>
 *   <li>POST {@code midStartProvisioning}, which answers the server's metadata (spim)</li>
 *   <li>POST {@code midFinishProvisioning} with what the library made of it (cpim), which
 *       answers a persistent token, a trust key and the machine's routing number</li>
 * </ol>
 *
 * <p>The headers are copied from sacca97/os-findmy-e {@code tools/machoadi/gsa.py}, the
 * configuration Apple has been seen to accept, and not tidied.
 *
 * <p><b>Apple allows one provisioning per device id.</b> A second answers HTTP 433 / -29002
 * whatever else changes, so a failure after {@code midFinishProvisioning} succeeded cannot be
 * retried with the same identity. That is why the caller saves the store and the identity the
 * moment this returns.
 */
final class MacProvisioning {
    private static final String TAG = "MacProvisioning";

    private static final String URL_BAG = "https://gsa.apple.com/grandslam/GsService2/lookup";

    private static final String START_REQUEST =
            "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
            + "<!DOCTYPE plist PUBLIC \"-//Apple//DTD PLIST 1.0//EN\" "
            + "\"http://www.apple.com/DTDs/PropertyList-1.0.dtd\">\n"
            + "<plist version=\"1.0\"><dict>"
            + "<key>Header</key><dict/><key>Request</key><dict/>"
            + "</dict></plist>";

    private static final String FINISH_REQUEST =
            "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
            + "<!DOCTYPE plist PUBLIC \"-//Apple//DTD PLIST 1.0//EN\" "
            + "\"http://www.apple.com/DTDs/PropertyList-1.0.dtd\">\n"
            + "<plist version=\"1.0\"><dict>"
            + "<key>Header</key><dict/>"
            + "<key>Request</key><dict><key>cpim</key><string>%s</string></dict>"
            + "</dict></plist>";

    private final MacIdentity identity;
    private final MacCoreAdi adi;

    MacProvisioning(MacIdentity identity, MacCoreAdi adi) {
        this.identity = identity;
        this.adi = adi;
    }

    /** @return the routing number Apple assigned the machine */
    long provision() throws AdiProvisioning.ProvisioningException {
        final Map<String, String> urls = loadUrlBag();
        final String startUrl = urls.get("midStartProvisioning");
        final String finishUrl = urls.get("midFinishProvisioning");
        if (startUrl == null || finishUrl == null) {
            throw new AdiProvisioning.ProvisioningException(
                    "the URL bag has no provisioning endpoints. Got: " + urls.keySet());
        }

        final Object started = parse(request(startUrl, START_REQUEST), "midStartProvisioning");
        final byte[] spim = decode(started, "spim");

        final int[] session = new int[1];
        final byte[] cpim;
        try {
            cpim = this.adi.provisioningBegin(MacCoreAdi.ANONYMOUS_DS_ID, spim, session);
        } catch (final IllegalStateException e) {
            throw new AdiProvisioning.ProvisioningException(
                    "the emulated Mac could not start provisioning: " + e.getMessage(), e);
        }

        final Object finished = parse(request(finishUrl, String.format(
                FINISH_REQUEST, Base64.encodeToString(cpim, Base64.NO_WRAP))),
                "midFinishProvisioning");
        final byte[] persistentToken = decode(finished, "ptm");
        final byte[] trustKey = decode(finished, "tk");
        final String routingText = SimplePlist.string(finished, "Response", "X-Apple-I-MD-RINFO");
        long routing = 0;
        if (routingText != null && !routingText.isEmpty()) {
            try {
                routing = Long.parseLong(routingText.trim());
            } catch (final NumberFormatException e) {
                throw new AdiProvisioning.ProvisioningException(
                        "Apple's routing number is not a number: " + routingText);
            }
        }

        try {
            this.adi.provisioningFinish(MacCoreAdi.ANONYMOUS_DS_ID, session[0], persistentToken,
                    trustKey, routing);
        } catch (final IllegalStateException e) {
            throw new AdiProvisioning.ProvisioningException(
                    "the emulated Mac could not finish provisioning: " + e.getMessage(), e);
        }
        Log.i(TAG, "the emulated Mac is provisioned, routing " + routing);
        return routing;
    }

    private Map<String, String> loadUrlBag() throws AdiProvisioning.ProvisioningException {
        final Object plist = parse(request(URL_BAG, null), "the URL bag");
        if (!(plist instanceof Map)) {
            throw new AdiProvisioning.ProvisioningException("the URL bag is not a dictionary");
        }
        final Object urls = ((Map<?, ?>) plist).get("urls");
        if (!(urls instanceof Map) || ((Map<?, ?>) urls).isEmpty()) {
            throw new AdiProvisioning.ProvisioningException("the URL bag came back empty");
        }
        final Map<String, String> out = new HashMap<>();
        for (final Map.Entry<?, ?> entry : ((Map<?, ?>) urls).entrySet()) {
            if (entry.getValue() instanceof String) {
                out.put(String.valueOf(entry.getKey()), (String) entry.getValue());
            }
        }
        return out;
    }

    private static Object parse(String body, String what)
            throws AdiProvisioning.ProvisioningException {
        try {
            return SimplePlist.parse(body);
        } catch (final Exception e) {
            throw new AdiProvisioning.ProvisioningException("could not read " + what, e);
        }
    }

    private static byte[] decode(Object plist, String key)
            throws AdiProvisioning.ProvisioningException {
        final String value = SimplePlist.string(plist, "Response", key);
        if (value == null) {
            throw new AdiProvisioning.ProvisioningException("Apple's response has no Response/"
                    + key + " - the request was refused, or the protocol has changed");
        }
        return Base64.decode(value, Base64.DEFAULT);
    }

    private String request(String url, String body) throws AdiProvisioning.ProvisioningException {
        HttpURLConnection connection = null;
        try {
            connection = (HttpURLConnection) new URL(url).openConnection();
            connection.setConnectTimeout(30_000);
            connection.setReadTimeout(30_000);
            connection.setRequestProperty("User-Agent", MacIdentity.PROVISIONING_USER_AGENT);
            // AuthKit really does label an XML plist body as a form.
            connection.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");
            connection.setRequestProperty("Connection", "keep-alive");
            connection.setRequestProperty("Accept", "*/*");
            connection.setRequestProperty("X-Mme-Device-Id", this.identity.deviceId());
            connection.setRequestProperty("X-MMe-Client-Info", MacIdentity.CLIENT_INFO);
            connection.setRequestProperty("X-Apple-I-MD-LU", this.identity.localUserUuid());
            connection.setRequestProperty("X-Apple-Client-App-Name", "Setup");
            connection.setRequestProperty("X-Apple-I-SRL-NO", this.identity.serial());

            if (body != null) {
                connection.setRequestProperty("X-Apple-I-Client-Time", now());
                connection.setRequestMethod("POST");
                connection.setDoOutput(true);
                connection.getOutputStream().write(body.getBytes(StandardCharsets.UTF_8));
            }

            final int status = connection.getResponseCode();
            final String response = readAll(status >= 400
                    ? connection.getErrorStream() : connection.getInputStream());
            if (status >= 400) {
                throw new AdiProvisioning.ProvisioningException(
                        "Apple returned HTTP " + status + " for " + url + ": " + response);
            }
            return response;
        } catch (final AdiProvisioning.ProvisioningException e) {
            throw e;
        } catch (final IOException e) {
            throw new AdiProvisioning.ProvisioningException("could not reach " + url + ": "
                    + e.getClass().getSimpleName() + ": " + e.getMessage(), e);
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
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
