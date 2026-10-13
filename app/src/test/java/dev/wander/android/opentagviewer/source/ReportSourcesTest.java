package dev.wander.android.opentagviewer.source;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

import dev.wander.android.opentagviewer.data.model.BeaconLocationReport;
import dev.wander.android.opentagviewer.db.room.entity.LocationReport;
import dev.wander.android.opentagviewer.python.AccessoryRequest;
import dev.wander.android.opentagviewer.python.FetchResult;
import io.reactivex.rxjava3.core.Observable;

public class ReportSourcesTest {
    private static final long NOW = 1_800_000_000_000L;
    private static final long HOUR = 3_600_000L;
    private static final String APPLE_TAG = "{\"type\":\"custom_rolling_key_accessory\",\"identifier\":\"tag\"}";

    private static BeaconLocationReport report(final long timestamp, final String provenance) {
        return BeaconLocationReport.builder()
                .timestamp(timestamp)
                .publishedAt(timestamp)
                .latitude(52.4)
                .longitude(16.9)
                .provenance(provenance)
                .build();
    }

    /** The googlefind service, counting what it was asked. */
    private static final class FakeGoogle implements ReportSources.Google {
        final List<BeaconLocationReport> reports = new ArrayList<>();
        int locates;
        boolean failing;

        @Override
        public void locate(final String googleId, final int waitS) {
            this.locates++;
        }

        @Override
        public List<BeaconLocationReport> reports(final String googleId, final long sinceMs) throws Exception {
            if (this.failing) {
                throw new java.io.IOException("service down");
            }
            return new ArrayList<>(this.reports);
        }
    }

    private final FakeGoogle google = new FakeGoogle();
    private final AtomicLong clock = new AtomicLong(NOW);
    private final Map<String, Long> firstLocates = new HashMap<>();

    private ReportSources sources(final boolean configured, final Map<String, String> links) {
        return new ReportSources(() -> configured, links::get, this.google, this.clock::get, this.firstLocates);
    }

    private static FetchResult appleResult(final String beaconId, final BeaconLocationReport... reports) {
        final Map<String, List<BeaconLocationReport>> map = new HashMap<>();
        map.put(beaconId, new ArrayList<>(List.of(reports)));
        return new FetchResult(map, new HashMap<>(), Set.of(), Set.of());
    }

    private FetchResult fetch(final ReportSources sources, final String beaconId, final String json,
                              final boolean askGoogleNow, final Observable<FetchResult> apple) {
        return sources.fetch(new AccessoryRequest(beaconId, json), NOW - HOUR, NOW, askGoogleNow, apple)
                .blockingFirst();
    }

    // --- with the feature off, nothing changes ------------------------------------------------------

    /** The guarantee for everyone who never sets the service up: Apple's fetch, untouched. */
    @Test
    public void withNoServiceTheAppleFetchIsReturnedUntouched() {
        final Observable<FetchResult> apple = Observable.just(appleResult("tag"));

        final ReportSources sources = this.sources(false, Map.of("tag", "g1"));

        assertSame(apple, sources.fetch(new AccessoryRequest("tag", APPLE_TAG), NOW - HOUR, NOW, true, apple));
        assertEquals(0, this.google.locates);
    }

    @Test
    public void aDeviceWithNoGoogleLinkOnlyAsksApple() {
        final Observable<FetchResult> apple = Observable.just(appleResult("tag"));

        final ReportSources sources = this.sources(true, Map.of());

        assertSame(apple, sources.fetch(new AccessoryRequest("tag", APPLE_TAG), NOW - HOUR, NOW, true, apple));
        assertEquals(0, this.google.locates);
    }

    // --- routing ----------------------------------------------------------------------------------

    /** FindMy.py raises on a type it does not know, so a Google tracker must never reach it. */
    @Test
    public void aGoogleTrackerIsNeverHandedToApple() {
        final AtomicBoolean appleAsked = new AtomicBoolean(false);
        final Observable<FetchResult> apple = Observable.<FetchResult>never()
                .doOnSubscribe(d -> appleAsked.set(true));
        this.google.reports.add(report(NOW - 60_000, LocationReport.PROVENANCE_GOOGLE));

        final FetchResult result = this.fetch(this.sources(true, Map.of()), "google-g1",
                ExternalAccessory.newGoogle("g1", "Keys"), false, apple);

        assertFalse(appleAsked.get());
        assertEquals(1, result.getReports().get("google-g1").size());
    }

    @Test
    public void aLinkedDeviceGetsBothNetworks() {
        this.google.reports.add(report(NOW - 60_000, LocationReport.PROVENANCE_GOOGLE));

        final FetchResult result = this.fetch(this.sources(true, Map.of("tag", "g1")), "tag", APPLE_TAG, false,
                Observable.just(appleResult("tag", report(NOW - 120_000, LocationReport.PROVENANCE_APPLE))));

        assertEquals(2, result.getReports().get("tag").size());
    }

    @Test
    public void aFailingServiceDoesNotCostTheAppleReports() {
        this.google.failing = true;

        final FetchResult result = this.fetch(this.sources(true, Map.of("tag", "g1")), "tag", APPLE_TAG, false,
                Observable.just(appleResult("tag", report(NOW - 120_000, LocationReport.PROVENANCE_APPLE))));

        assertEquals(1, result.getReports().get("tag").size());
        assertEquals(LocationReport.PROVENANCE_APPLE, result.getReports().get("tag").get(0).getProvenance());
    }

    @Test
    public void aFailingAppleFetchDoesNotCostTheGoogleReports() {
        this.google.reports.add(report(NOW - 60_000, LocationReport.PROVENANCE_GOOGLE));

        final FetchResult result = this.fetch(this.sources(true, Map.of("tag", "g1")), "tag", APPLE_TAG, false,
                Observable.error(new IllegalStateException("Apple said no")));

        assertEquals(1, result.getReports().get("tag").size());
        assertEquals(LocationReport.PROVENANCE_GOOGLE, result.getReports().get("tag").get(0).getProvenance());
    }

    @Test
    public void reportsNewerThanTheWindowAreLeftOut() {
        this.google.reports.add(report(NOW - 60_000, LocationReport.PROVENANCE_GOOGLE));
        this.google.reports.add(report(NOW + 60_000, LocationReport.PROVENANCE_GOOGLE));

        final FetchResult result = this.fetch(this.sources(true, Map.of()), "google-g1",
                ExternalAccessory.newGoogle("g1", "Keys"), false, null);

        assertEquals(1, result.getReports().get("google-g1").size());
    }

    // --- when Google is asked for a fresh location ----------------------------------------------------

    @Test
    public void aRefreshAsksGoogleForAFreshLocation() {
        this.google.reports.add(report(NOW - 60_000, LocationReport.PROVENANCE_GOOGLE));

        this.fetch(this.sources(true, Map.of()), "google-g1", ExternalAccessory.newGoogle("g1", "Keys"), true, null);

        assertEquals(1, this.google.locates);
    }

    /** A scheduled fetch of a tracker that has reports only reads what the service holds. */
    @Test
    public void aScheduledFetchDoesNotAskGoogle() {
        this.google.reports.add(report(NOW - 60_000, LocationReport.PROVENANCE_GOOGLE));

        this.fetch(this.sources(true, Map.of()), "google-g1", ExternalAccessory.newGoogle("g1", "Keys"), false, null);

        assertEquals(0, this.google.locates);
    }

    /**
     * A tracker that has never been located has no card to refresh, so it is asked for on its
     * own - but at most every 30 minutes, or every scheduled fetch would wake Google for it.
     */
    @Test
    public void aNeverLocatedTrackerIsAskedForAtMostEveryHalfHour() {
        final ReportSources sources = this.sources(true, Map.of());
        final String json = ExternalAccessory.newGoogle("g1", "Keys");

        this.fetch(sources, "google-g1", json, false, null);
        this.clock.set(NOW + 10 * 60_000);
        this.fetch(sources, "google-g1", json, false, null);
        assertEquals("once within the half hour", 1, this.google.locates);

        this.clock.set(NOW + 31 * 60_000);
        this.fetch(sources, "google-g1", json, false, null);
        assertEquals("and again after it", 2, this.google.locates);
    }

    // --- merging ------------------------------------------------------------------------------------

    @Test
    public void mergeKeepsBothNetworksOnOneDevice() {
        final Map<String, List<BeaconLocationReport>> apple = new HashMap<>();
        apple.put("tag", new ArrayList<>(List.of(report(1000, LocationReport.PROVENANCE_APPLE))));
        final FetchResult fromApple = new FetchResult(apple, Map.of("tag", "{\"json\":1}"), Set.of(), Set.of("tag"));

        final FetchResult merged = ReportSources.merge("tag", fromApple,
                List.of(report(2000, LocationReport.PROVENANCE_GOOGLE)));

        assertEquals(2, merged.getReports().get("tag").size());
        assertEquals(LocationReport.PROVENANCE_GOOGLE, merged.getReports().get("tag").get(1).getProvenance());
        // The Apple side's bookkeeping travels unchanged
        assertEquals("{\"json\":1}", merged.getUpdatedAccessoryJson().get("tag"));
        assertTrue(merged.getWideSearch().contains("tag"));
    }

    @Test
    public void googleReportsMeanTheDeviceIsNotSilent() {
        final FetchResult fromApple = new FetchResult(new HashMap<>(), Map.of(), Set.of("tag"), Set.of("tag"));

        final FetchResult withGoogle = ReportSources.merge("tag", fromApple,
                List.of(report(2000, LocationReport.PROVENANCE_GOOGLE)));
        final FetchResult withoutGoogle = ReportSources.merge("tag", fromApple, List.of());

        assertFalse(withGoogle.getExhaustedWideSearch().contains("tag"));
        assertTrue(withoutGoogle.getExhaustedWideSearch().contains("tag"));
    }
}
