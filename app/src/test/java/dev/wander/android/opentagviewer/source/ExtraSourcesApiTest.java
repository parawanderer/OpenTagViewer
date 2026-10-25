package dev.wander.android.opentagviewer.source;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.IOException;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import dev.wander.android.opentagviewer.data.model.BeaconLocationReport;
import dev.wander.android.opentagviewer.db.room.entity.LocationReport;

/**
 * The HTTP client for the googlefind service, against a fake service on 127.0.0.1.
 *
 * <p>The headers are the part worth pinning: the service sits behind a reverse proxy that may
 * take {@code Authorization} for its own Basic Auth, so the token has to travel in
 * {@code X-Api-Token} too, and a mistake there presents as "the service rejects my token" with
 * nothing in the app to say why.
 */
public class ExtraSourcesApiTest {

    private FakeGooglefindServer server;
    private final Map<String, String> settings = new HashMap<>();

    @Before
    public void startTheService() throws IOException {
        this.server = new FakeGooglefindServer();
        this.settings.put(ExtraSourcesSettings.GOOGLE_URL, this.server.url());
        this.settings.put(ExtraSourcesSettings.GOOGLE_TOKEN, "s3cret");
    }

    @After
    public void stopTheService() throws IOException {
        this.server.close();
    }

    private ExtraSourcesApi api() {
        return new ExtraSourcesApi(key -> this.settings.getOrDefault(key, ""),
                bytes -> Base64.getEncoder().encodeToString(bytes));
    }

    // --- authentication -------------------------------------------------------------------------

    @Test
    public void theTokenIsSentAsABearerAndInItsOwnHeader() throws Exception {
        this.server.answer("/api/devices", 200, "[]");

        this.api().googleDevices();

        final FakeGooglefindServer.Request request = this.server.lastRequest();
        assertEquals("Bearer s3cret", request.headers.get("authorization"));
        assertEquals("s3cret", request.headers.get("x-api-token"));
    }

    /** Basic Auth takes Authorization for the proxy, so the token must still reach the service. */
    @Test
    public void withBasicAuthTheTokenTravelsOnlyInItsOwnHeader() throws Exception {
        this.server.answer("/api/devices", 200, "[]");
        this.settings.put(ExtraSourcesSettings.GOOGLE_USER, "me");
        this.settings.put(ExtraSourcesSettings.GOOGLE_PASSWORD, "pw");

        this.api().googleDevices();

        final FakeGooglefindServer.Request request = this.server.lastRequest();
        assertEquals("Basic bWU6cHc=", request.headers.get("authorization"));
        assertEquals("s3cret", request.headers.get("x-api-token"));
    }

    @Test
    public void noTokenSendsNoCredentials() throws Exception {
        this.server.answer("/api/devices", 200, "[]");
        this.settings.put(ExtraSourcesSettings.GOOGLE_TOKEN, "");

        this.api().googleDevices();

        final FakeGooglefindServer.Request request = this.server.lastRequest();
        assertNull(request.headers.get("authorization"));
        assertNull(request.headers.get("x-api-token"));
    }

    @Test
    public void aRefusalIsAnErrorThatSaysWhichStatus() {
        this.server.answer("/api/devices", 401, "{\"detail\":\"bad token\"}");

        final ExtraSourcesApi.ApiException error =
                assertThrows(ExtraSourcesApi.ApiException.class, () -> this.api().googleDevices());
        assertTrue(error.getMessage(), error.getMessage().contains("HTTP 401"));
    }

    @Test
    public void aTrailingSlashOnTheAddressDoesNotDoubleThePath() throws Exception {
        this.server.answer("/api/devices", 200, "[]");
        this.settings.put(ExtraSourcesSettings.GOOGLE_URL, this.server.url() + "//");

        this.api().googleDevices();

        assertEquals("/api/devices", this.server.lastRequest().target);
    }

    // --- what comes back ------------------------------------------------------------------------

    @Test
    public void devicesAreRead() throws Exception {
        this.server.answer("/api/devices", 200,
                "[{\"id\":\"abc\",\"name\":\"Keys\",\"custom\":true},{\"id\":\"def\",\"name\":\"Bag\"}]");

        final List<ExtraSourcesApi.GoogleDevice> devices = this.api().googleDevices();

        assertEquals(2, devices.size());
        assertEquals("abc", devices.get(0).id);
        assertEquals("Keys", devices.get(0).name);
        assertTrue(devices.get(0).custom);
        assertFalse(devices.get(1).custom);
    }

    /**
     * A report with no coordinates ("Home", a semantic location) is not a position, since there is
     * nowhere to draw it. Times arrive in seconds and are stored in milliseconds.
     */
    @Test
    public void reportsAreConvertedAndTheOnesWithoutAPositionLeftOut() throws Exception {
        this.server.answer("/api/devices/abc/reports", 200, "["
                + "{\"time\":1700000000,\"received\":1700000060,\"latitude\":52.4,\"longitude\":16.9,"
                + "\"accuracy\":12.6,\"status\":3},"
                + "{\"time\":1700000100,\"latitude\":null,\"longitude\":null}"
                + "]");

        final ExtraSourcesApi.GoogleReports answer = this.api().googleReports("abc", 1_699_999_000_123L);

        assertEquals("abc's reports since that second, capped",
                "/api/devices/abc/reports?limit=5000&since=1699999000", this.server.lastRequest().target);
        final List<BeaconLocationReport> reports = answer.positions;
        assertEquals(1, reports.size());
        assertNull("no name, so no place either", answer.newestPlace);
        final BeaconLocationReport report = reports.get(0);
        assertEquals(1_700_000_000_000L, report.getTimestamp());
        assertEquals(1_700_000_060_000L, report.getPublishedAt());
        assertEquals(52.4, report.getLatitude(), 0);
        assertEquals(16.9, report.getLongitude(), 0);
        assertEquals(13, report.getHorizontalAccuracy());
        assertEquals(LocationReport.PROVENANCE_GOOGLE, report.getProvenance());
    }

    /** Google answers "Home" when the owner's phone saw the tracker; the newest such name is kept. */
    @Test
    public void theNewestNamedPlaceIsRead() throws Exception {
        this.server.answer("/api/devices/abc/reports", 200, "["
                + "{\"time\":1700000300,\"latitude\":null,\"longitude\":null,\"semantic\":\"Home\",\"status\":0},"
                + "{\"time\":1700000100,\"latitude\":null,\"longitude\":null,\"semantic\":\"Work\",\"status\":0},"
                + "{\"time\":1700000200,\"latitude\":null,\"longitude\":null,\"semantic\":null}"
                + "]");

        final ExtraSourcesApi.GoogleReports answer = this.api().googleReports("abc", 0);

        assertTrue(answer.positions.isEmpty());
        assertEquals("Home", answer.newestPlace.name);
        assertEquals(1_700_000_300_000L, answer.newestPlace.timestampMs);
    }

    @Test
    public void ringMaterialIsParsedAndTheEidsLowercased() throws Exception {
        this.server.answer("/api/devices/abc/ring", 200,
                "{\"ring_key\":\"0102030405060708\",\"eids\":[\"ABCDEF\",\"012345\"]}");

        final ExtraSourcesApi.RingMaterial material = this.api().ringMaterial("abc");

        assertArrayEquals(new byte[]{1, 2, 3, 4, 5, 6, 7, 8}, material.ringKey);
        assertEquals(List.of("abcdef", "012345"), material.eids);
    }

    @Test
    public void locateIsAPostThatSaysHowLongToWait() throws Exception {
        this.server.answer("/api/devices/abc/locate", 200, "{\"answered\":true}");

        assertTrue(this.api().googleLocate("abc", 25));

        final FakeGooglefindServer.Request request = this.server.lastRequest();
        assertEquals("POST", request.method);
        assertEquals("/api/devices/abc/locate?wait=25", request.target);
        assertEquals("{}", request.body);
    }
}
