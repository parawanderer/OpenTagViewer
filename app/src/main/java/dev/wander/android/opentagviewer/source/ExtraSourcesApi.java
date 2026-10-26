package dev.wander.android.opentagviewer.source;

import android.util.Base64;

import androidx.annotation.Nullable;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Function;

import dev.wander.android.opentagviewer.data.model.BeaconLocationReport;
import dev.wander.android.opentagviewer.db.room.entity.LocationReport;

/**
 * Blocking HTTP client for the googlefind service (Google Find Hub). Call from a background
 * thread.
 *
 * <p>{@code GET /api/devices}, {@code GET /api/devices/{id}/reports},
 * {@code POST /api/devices/{id}/locate}, {@code GET /api/devices/{id}/ring}; reports arrive
 * decrypted. The service may sit behind HTTP Basic Auth on a reverse proxy, so its token also
 * travels in {@code X-Api-Token} rather than only in {@code Authorization}.
 */
public final class ExtraSourcesApi {
    /** A stored setting by key. Real: {@link ExtraSourcesSettings#get}. */
    private final Function<String, String> setting;
    /** Base64 without line breaks. Real: {@code android.util.Base64}, a stub on the JVM. */
    private final Function<byte[], String> base64;

    public ExtraSourcesApi(final ExtraSourcesSettings settings) {
        this(settings::get, bytes -> Base64.encodeToString(bytes, Base64.NO_WRAP));
    }

    /** For {@code ExtraSourcesApiTest}, which runs on the JVM against a local socket. */
    ExtraSourcesApi(final Function<String, String> setting, final Function<byte[], String> base64) {
        this.setting = setting;
        this.base64 = base64;
    }

    public static final class ApiException extends IOException {
        public ApiException(final String message) {
            super(message);
        }
    }

    /** A tracker of the Google account. */
    public static final class GoogleDevice {
        public final String id;
        public final String name;
        public final boolean custom;

        GoogleDevice(final String id, final String name, final boolean custom) {
            this.id = id;
            this.name = name;
            this.custom = custom;
        }
    }

    private String basic(final String user, final String password) {
        if (user.isEmpty()) {
            return null;
        }
        final String raw = user + ":" + password;
        return "Basic " + this.base64.apply(raw.getBytes(StandardCharsets.UTF_8));
    }

    private static String join(String base, final String path) {
        while (base.endsWith("/")) base = base.substring(0, base.length() - 1);
        return base + path;
    }

    private static String request(final String method, final String url, final String body,
                                  final String authorization, final String token,
                                  final int timeoutMs) throws IOException {
        final HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        try {
            c.setRequestMethod(method);
            c.setConnectTimeout(15000);
            c.setReadTimeout(timeoutMs);
            if (authorization != null) c.setRequestProperty("Authorization", authorization);
            if (token != null && !token.isEmpty()) {
                if (authorization == null) c.setRequestProperty("Authorization", "Bearer " + token);
                c.setRequestProperty("X-Api-Token", token);
            }
            if (body != null) {
                c.setDoOutput(true);
                c.setRequestProperty("Content-Type", "application/json");
                try (OutputStream os = c.getOutputStream()) {
                    os.write(body.getBytes(StandardCharsets.UTF_8));
                }
            }
            final int code = c.getResponseCode();
            final InputStream in = code < 400 ? c.getInputStream() : c.getErrorStream();
            String text = "";
            if (in != null) {
                final ByteArrayOutputStream buf = new ByteArrayOutputStream();
                final byte[] chunk = new byte[8192];
                int n;
                while ((n = in.read(chunk)) > 0) buf.write(chunk, 0, n);
                in.close();
                text = buf.toString("UTF-8");
            }
            if (code >= 400) throw new ApiException("HTTP " + code + " from " + url);
            return text;
        } finally {
            c.disconnect();
        }
    }

    // ---- Google Find Hub, through googlefind ----

    private String google(final String method, final String path, final int timeoutMs) throws IOException {
        return request(method, join(this.setting.apply(ExtraSourcesSettings.GOOGLE_URL), path),
                "POST".equals(method) ? "{}" : null,
                this.basic(this.setting.apply(ExtraSourcesSettings.GOOGLE_USER),
                        this.setting.apply(ExtraSourcesSettings.GOOGLE_PASSWORD)),
                this.setting.apply(ExtraSourcesSettings.GOOGLE_TOKEN), timeoutMs);
    }

    public List<GoogleDevice> googleDevices() throws Exception {
        final JSONArray arr = new JSONArray(this.google("GET", "/api/devices", 30000));
        final List<GoogleDevice> out = new ArrayList<>();
        for (int i = 0; i < arr.length(); i++) {
            final JSONObject d = arr.getJSONObject(i);
            out.add(new GoogleDevice(d.getString("id"), d.getString("name"), d.optBoolean("custom")));
        }
        return out;
    }

    /** What is needed to ring a Google tracker nearby: its ring key and current EIDs. */
    public static final class RingMaterial {
        /** 8-byte ring key: rings the tracker, cannot read its locations. */
        public final byte[] ringKey;
        /** EIDs it may be advertising around now, lowercase hex (20 bytes each). */
        public final List<String> eids;

        RingMaterial(final byte[] ringKey, final List<String> eids) {
            this.ringKey = ringKey;
            this.eids = eids;
        }
    }

    public RingMaterial ringMaterial(final String googleId) throws Exception {
        final JSONObject o = new JSONObject(this.google("GET", "/api/devices/" + googleId + "/ring", 30000));
        final String keyHex = o.getString("ring_key");
        final byte[] key = new byte[keyHex.length() / 2];
        for (int i = 0; i < key.length; i++) key[i] = (byte) Integer.parseInt(keyHex.substring(2 * i, 2 * i + 2), 16);
        final JSONArray arr = o.getJSONArray("eids");
        final List<String> eids = new ArrayList<>();
        for (int i = 0; i < arr.length(); i++) eids.add(arr.getString(i).toLowerCase(Locale.ROOT));
        return new RingMaterial(key, eids);
    }

    /** Asks Google for fresh locations and waits up to {@code waitS} for the answer. */
    public boolean googleLocate(final String googleId, final int waitS) throws Exception {
        final String text = this.google("POST", "/api/devices/" + googleId + "/locate?wait=" + waitS,
                (waitS + 30) * 1000);
        return new JSONObject(text).optBoolean("answered");
    }

    /**
     * A place Google named instead of giving a position - "Home", typically, when the owner's
     * own phone saw the tracker there. Nothing to draw, but it does say the tracker was seen.
     */
    public static final class NamedPlace {
        public final String name;
        public final long timestampMs;

        public NamedPlace(final String name, final long timestampMs) {
            this.name = name;
            this.timestampMs = timestampMs;
        }
    }

    /** What the service holds for one tracker. */
    public static final class GoogleReports {
        /** Reports with a position. */
        public final List<BeaconLocationReport> positions;
        /** The newest named place, if Google sent any. */
        @Nullable
        public final NamedPlace newestPlace;

        public GoogleReports(final List<BeaconLocationReport> positions, @Nullable final NamedPlace newestPlace) {
            this.positions = positions;
            this.newestPlace = newestPlace;
        }
    }

    /** Reports recorded at or after {@code sinceMs} (the service keeps what Google sent it). */
    public GoogleReports googleReports(final String googleId, final long sinceMs) throws Exception {
        final JSONArray arr = new JSONArray(this.google("GET",
                "/api/devices/" + googleId + "/reports?limit=5000&since=" + (sinceMs / 1000), 30000));
        final List<BeaconLocationReport> out = new ArrayList<>();
        NamedPlace newestPlace = null;
        for (int i = 0; i < arr.length(); i++) {
            final JSONObject r = arr.getJSONObject(i);
            final long timeMs = r.getLong("time") * 1000L;
            if (r.isNull("latitude") || r.isNull("longitude")) {
                // A semantic report ("Home") has no position to draw, only a name
                final String name = r.optString("semantic", "");
                if (!r.isNull("semantic") && !name.isEmpty()
                        && (newestPlace == null || timeMs > newestPlace.timestampMs)) {
                    newestPlace = new NamedPlace(name, timeMs);
                }
                continue;
            }
            out.add(BeaconLocationReport.builder()
                    .publishedAt(r.optLong("received", r.getLong("time")) * 1000L)
                    .description(r.optBoolean("own") ? "Google Find Hub (own device)" : "Google Find Hub")
                    .timestamp(timeMs)
                    .confidence(0)
                    .latitude(r.getDouble("latitude"))
                    .longitude(r.getDouble("longitude"))
                    .horizontalAccuracy(Math.round(r.optDouble("accuracy", 0)))
                    .status(r.optLong("status"))
                    .provenance(LocationReport.PROVENANCE_GOOGLE)
                    .build());
        }
        return new GoogleReports(out, newestPlace);
    }
}
