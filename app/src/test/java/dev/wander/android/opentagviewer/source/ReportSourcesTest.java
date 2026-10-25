package dev.wander.android.opentagviewer.source;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
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
import java.io.IOException;

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
        final List<ExtraSourcesApi.NamedPlace> places = new ArrayList<>();
        int locates;
        boolean failing;

        @Override
        public void locate(final String googleId, final int waitS) {
            this.locates++;
        }

        /** Like the service: what was recorded at or after {@code sinceMs}. */
        @Override
        public ExtraSourcesApi.GoogleReports reports(final String googleId, final long sinceMs) throws Exception {
            if (this.failing) {
                throw new IOException("service down");
            }
            final List<BeaconLocationReport> positions = new ArrayList<>();
            for (final BeaconLocationReport r : this.reports) {
                if (r.getTimestamp() >= sinceMs) positions.add(r);
            }
            ExtraSourcesApi.NamedPlace newest = null;
            for (final ExtraSourcesApi.NamedPlace p : this.places) {
                if (p.timestampMs >= sinceMs && (newest == null || p.timestampMs > newest.timestampMs)) {
                    newest = p;
                }
            }
            return new ExtraSourcesApi.GoogleReports(positions, newest);
        }
    }

    private final FakeGoogle google = new FakeGoogle();
    private final AtomicLong clock = new AtomicLong(NOW);
    private final Map<String, Long> firstLocates = new HashMap<>();
    private final Map<String, ExtraSourcesApi.NamedPlace> places = new HashMap<>();

    private ReportSources sources(final boolean configured, final Map<String, String> links) {
        return new ReportSources(() -> configured, links::get, this.google, this.clock::get, this.firstLocates,
                this.places);
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

    // --- a tracker Google has not seen for a while -----------------------------------------------------

    /**
     * Only the last week is read, so a tracker nobody has passed for longer used to come back with
     * nothing and had no position on the map. The newest older one is still where it was last seen.
     */
    @Test
    public void aTrackerQuietForOverAWeekStillHasItsLastKnownPosition() {
        this.google.reports.add(report(NOW - 9 * 24 * HOUR, LocationReport.PROVENANCE_GOOGLE));
        this.google.reports.add(report(NOW - 8 * 24 * HOUR, LocationReport.PROVENANCE_GOOGLE));

        final FetchResult result = this.fetch(this.sources(true, Map.of()), "google-g1",
                ExternalAccessory.newGoogle("g1", "Keys"), false, null);

        final List<BeaconLocationReport> reports = result.getReports().get("google-g1");
        assertEquals("only the newest of the old ones", 1, reports.size());
        assertEquals(NOW - 8 * 24 * HOUR, reports.get(0).getTimestamp());
    }

    /** It was located once, so it is not the never-located case that asks Google by itself. */
    @Test
    public void aQuietTrackerIsNotAskedForLikeANeverLocatedOne() {
        this.google.reports.add(report(NOW - 8 * 24 * HOUR, LocationReport.PROVENANCE_GOOGLE));

        this.fetch(this.sources(true, Map.of()), "google-g1", ExternalAccessory.newGoogle("g1", "Keys"), false, null);

        assertEquals(0, this.google.locates);
    }

    @Test
    public void withReportsInTheWindowNothingOlderIsAdded() {
        this.google.reports.add(report(NOW - 8 * 24 * HOUR, LocationReport.PROVENANCE_GOOGLE));
        this.google.reports.add(report(NOW - 60_000, LocationReport.PROVENANCE_GOOGLE));

        final FetchResult result = this.fetch(this.sources(true, Map.of()), "google-g1",
                ExternalAccessory.newGoogle("g1", "Keys"), false, null);

        assertEquals(1, result.getReports().get("google-g1").size());
        assertEquals(NOW - 60_000, result.getReports().get("google-g1").get(0).getTimestamp());
    }

    // --- places Google names instead of giving a position ------------------------------------------

    /**
     * When the owner's own phone sees the tracker, Google answers "Home" with no coordinates. It is
     * not a position, but the card should say Google answered.
     */
    @Test
    public void aNamedPlaceNewerThanTheShownPositionIsOffered() {
        this.google.places.add(new ExtraSourcesApi.NamedPlace("Home", NOW - 60_000));
        final ReportSources sources = this.sources(true, Map.of("tag", "g1"));

        final FetchResult result = this.fetch(sources, "tag", APPLE_TAG, true,
                Observable.just(appleResult("tag", report(NOW - HOUR, LocationReport.PROVENANCE_APPLE))));

        assertEquals("a name is not a position", 1, result.getReports().get("tag").size());
        final ExtraSourcesApi.NamedPlace place = sources.newerNamedPlace("tag", NOW - HOUR);
        assertEquals("Home", place == null ? null : place.name);
    }

    @Test
    public void aNamedPlaceOlderThanTheShownPositionIsNot() {
        this.google.places.add(new ExtraSourcesApi.NamedPlace("Home", NOW - 2 * HOUR));
        final ReportSources sources = this.sources(true, Map.of("tag", "g1"));

        this.fetch(sources, "tag", APPLE_TAG, false,
                Observable.just(appleResult("tag", report(NOW - HOUR, LocationReport.PROVENANCE_APPLE))));

        assertNull(sources.newerNamedPlace("tag", NOW - HOUR));
    }

    /** A history fetch for an earlier day must not replace a newer place with an older one. */
    @Test
    public void theNewestNamedPlaceIsKept() {
        final ReportSources sources = this.sources(true, Map.of());
        final String json = ExternalAccessory.newGoogle("g1", "Keys");
        this.google.reports.add(report(NOW - 60_000, LocationReport.PROVENANCE_GOOGLE));
        this.google.places.add(new ExtraSourcesApi.NamedPlace("Home", NOW - 30_000));
        this.fetch(sources, "google-g1", json, false, null);

        this.google.places.clear();
        this.google.places.add(new ExtraSourcesApi.NamedPlace("Work", NOW - 3 * HOUR));
        this.fetch(sources, "google-g1", json, false, null);

        final ExtraSourcesApi.NamedPlace place = sources.newerNamedPlace("google-g1", 0);
        assertEquals("Home", place == null ? null : place.name);
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
