package dev.wander.android.opentagviewer.anisette;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The three requests that register the emulated Mac with Apple, as they go on the wire.
 *
 * <p>Served by {@link FakeGrandSlam} on {@code 127.0.0.1}, so what is checked is what was sent -
 * every header os-findmy-e's {@code gsa.py} was seen to be accepted with, both bodies, and that
 * Apple's answers reach the library intact (AGENTS.md rule 13). Apple allows one provisioning per
 * device id, so this is the only way to look at these requests more than once.
 */
public class MacProvisioningTest {

    private FakeGrandSlam apple;
    private MacIdentity identity;
    private FakeMacAdi adi;

    @Before
    public void startApple() throws Exception {
        this.apple = new FakeGrandSlam();
        this.identity = MacIdentity.generate();
        this.adi = new FakeMacAdi();
    }

    @After
    public void stopApple() throws Exception {
        this.apple.close();
    }

    private static String b64(final String s) {
        return Base64.getEncoder().encodeToString(FakeMacAdi.bytes(s));
    }

    private void appleAnswersEverything() {
        final Map<String, Object> urls = new LinkedHashMap<>();
        urls.put("midStartProvisioning", this.apple.url("/start"));
        urls.put("midFinishProvisioning", this.apple.url("/finish"));
        this.apple.answer("/lookup", 200, TestPlists.dict(Map.of("urls", urls)));
        this.apple.answer("/start", 200, TestPlists.dict(Map.of("Response",
                Map.of("spim", b64("the spim")))));
        this.apple.answer("/finish", 200, TestPlists.dict(Map.of("Response", Map.of(
                "ptm", b64("the ptm"),
                "tk", b64("the tk"),
                "X-Apple-I-MD-RINFO", "17106176"))));
    }

    private long provision() throws Exception {
        return new MacProvisioning(this.identity, this.adi, this.apple.url("/lookup"),
                TestPlists.BASE64, TestPlists::parse).provision();
    }

    @Test
    public void itAsksForTheBagThenStartsThenFinishes() throws Exception {
        appleAnswersEverything();

        assertEquals(17106176L, provision());

        final List<FakeGrandSlam.Request> sent = this.apple.requests();
        assertEquals(3, sent.size());
        assertEquals("GET /lookup", sent.get(0).method + " " + sent.get(0).path);
        assertEquals("POST /start", sent.get(1).method + " " + sent.get(1).path);
        assertEquals("POST /finish", sent.get(2).method + " " + sent.get(2).path);
    }

    @Test
    public void everyRequestPresentsTheSameMac() throws Exception {
        appleAnswersEverything();
        provision();

        for (final FakeGrandSlam.Request request : this.apple.requests()) {
            final Map<String, String> h = request.headers;
            assertEquals(request.path, "akd/1.0 CFNetwork/1404.0.5 Darwin/22.3.0", h.get("user-agent"));
            assertEquals(request.path, MacIdentity.CLIENT_INFO, h.get("x-mme-client-info"));
            assertEquals(request.path, this.identity.deviceId(), h.get("x-mme-device-id"));
            // Raw here; FindMy.py base64-encodes it at login. See MacIdentity#localUserUuid.
            assertEquals(request.path, this.identity.localUserUuid(), h.get("x-apple-i-md-lu"));
            assertEquals(request.path, this.identity.serial(), h.get("x-apple-i-srl-no"));
            assertEquals(request.path, "Setup", h.get("x-apple-client-app-name"));
            assertEquals(request.path, "application/x-www-form-urlencoded", h.get("content-type"));
        }
    }

    @Test
    public void onlyThePostsCarryAClientTimeAndItHasNoMilliseconds() throws Exception {
        appleAnswersEverything();
        provision();

        final List<FakeGrandSlam.Request> sent = this.apple.requests();
        assertNull(sent.get(0).headers.get("x-apple-i-client-time"));
        for (final FakeGrandSlam.Request post : sent.subList(1, 3)) {
            final String time = post.headers.get("x-apple-i-client-time");
            assertTrue(post.path + ": " + time,
                    time.matches("\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}Z"));
        }
    }

    @Test
    public void startSendsAnEmptyRequestAndFinishSendsTheLibrarysCpim() throws Exception {
        appleAnswersEverything();
        provision();

        final List<FakeGrandSlam.Request> sent = this.apple.requests();
        assertTrue(sent.get(1).body, sent.get(1).body.endsWith(
                "<plist version=\"1.0\"><dict><key>Header</key><dict/><key>Request</key><dict/>"
                        + "</dict></plist>"));
        assertTrue(sent.get(2).body, sent.get(2).body.contains(
                "<key>Request</key><dict><key>cpim</key><string>" + b64("cpim for the spim")
                        + "</string></dict>"));
    }

    @Test
    public void applesAnswersReachTheLibraryInOneSession() throws Exception {
        appleAnswersEverything();
        provision();

        assertEquals(List.of("provisioningBegin the spim",
                "provisioningFinish 42 the ptm the tk 17106176"), this.adi.calls);
    }

    @Test
    public void aRefusalSaysWhatAppleSaid() {
        appleAnswersEverything();
        this.apple.answer("/finish", 433, "-29002");

        final AdiProvisioning.ProvisioningException refused =
                assertThrows(AdiProvisioning.ProvisioningException.class, this::provision);

        assertTrue(refused.getMessage(), refused.getMessage().contains("HTTP 433"));
        assertTrue(refused.getMessage(), refused.getMessage().contains("-29002"));
        assertFalse("the library must not be told it finished",
                this.adi.calls.stream().anyMatch(c -> c.startsWith("provisioningFinish")));
    }

    @Test
    public void aBagWithoutTheEndpointsStopsBeforeAnythingIsSent() {
        this.apple.answer("/lookup", 200, TestPlists.dict(Map.of("urls",
                Map.of("somethingElse", this.apple.url("/x")))));

        final AdiProvisioning.ProvisioningException refused =
                assertThrows(AdiProvisioning.ProvisioningException.class, this::provision);

        assertTrue(refused.getMessage(), refused.getMessage().contains("no provisioning endpoints"));
        assertEquals(1, this.apple.requests().size());
        assertTrue(this.adi.calls.isEmpty());
    }

    @Test
    public void aFinishWithoutATrustKeyIsRefusedAndNotHalfApplied() {
        appleAnswersEverything();
        this.apple.answer("/finish", 200, TestPlists.dict(Map.of("Response",
                Map.of("ptm", b64("the ptm")))));

        final AdiProvisioning.ProvisioningException refused =
                assertThrows(AdiProvisioning.ProvisioningException.class, this::provision);

        assertTrue(refused.getMessage(), refused.getMessage().contains("Response/tk"));
        assertEquals(List.of("provisioningBegin the spim"), this.adi.calls);
    }

    @Test
    public void aRoutingNumberThatIsNotANumberIsRefused() {
        appleAnswersEverything();
        this.apple.answer("/finish", 200, TestPlists.dict(Map.of("Response", Map.of(
                "ptm", b64("the ptm"), "tk", b64("the tk"), "X-Apple-I-MD-RINFO", "seventeen"))));

        assertThrows(AdiProvisioning.ProvisioningException.class, this::provision);
    }
}
