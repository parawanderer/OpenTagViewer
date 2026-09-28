package dev.wander.android.opentagviewer.source;

import android.content.Context;
import android.util.Log;

import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import dev.wander.android.opentagviewer.data.model.BeaconLocationReport;
import dev.wander.android.opentagviewer.python.AccessoryRequest;
import dev.wander.android.opentagviewer.python.FetchResult;
import dev.wander.android.opentagviewer.python.PythonAppleService;
import io.reactivex.rxjava3.core.Observable;
import io.reactivex.rxjava3.schedulers.Schedulers;

/**
 * Decides, per device, where its location reports come from, and hands them back in the same
 * {@link FetchResult} the Apple path produces - so storing, drawing, the scan backoff and the
 * history all work unchanged.
 *
 * <ul>
 *   <li>Apple-paired accessories and OpenHaystack keys: the signed-in Apple account, as before.</li>
 *   <li>Google trackers, and any device linked to one: googlefind.</li>
 * </ul>
 */
public final class ReportSources {
    private static final String TAG = ReportSources.class.getSimpleName();
    /** How long a manual refresh waits for Google to answer a fresh locate request. */
    private static final int GOOGLE_LOCATE_WAIT_S = 25;
    /**
     * A tracker with no reports at all has no card, so nothing to press refresh on: it is asked
     * for by itself instead, but not more often than this.
     */
    private static final long GOOGLE_FIRST_LOCATE_EVERY_MS = 30 * 60_000L;
    private static final Map<String, Long> lastFirstLocate = new java.util.concurrent.ConcurrentHashMap<>();

    private final ExtraSourcesSettings settings;
    private final SourceLinks links;
    private final ExtraSourcesApi api;

    public ReportSources(final ExtraSourcesSettings settings, final SourceLinks links, final ExtraSourcesApi api) {
        this.settings = settings;
        this.links = links;
        this.api = api;
    }

    public static ReportSources create(final Context context) {
        final ExtraSourcesSettings settings = new ExtraSourcesSettings(context);
        return new ReportSources(settings, new SourceLinks(context), new ExtraSourcesApi(settings));
    }

    /**
     * Reports of one device.
     *
     * @param sinceMs         oldest report wanted
     * @param untilMs         newest report wanted (history asks for one day)
     * @param askGoogleNow    ask Google for fresh locations first (a manual refresh), rather than
     *                        only reading what the service already has
     * @param appleFetch      the Apple-account fetch for this request
     */
    public Observable<FetchResult> fetch(final AccessoryRequest request,
                                         final long sinceMs, final long untilMs,
                                         final boolean askGoogleNow,
                                         @Nullable final Observable<FetchResult> appleFetch) {
        final String beaconId = request.getBeaconId();
        final String json = request.getAccessoryJson();

        final Observable<FetchResult> apple;
        if (ExternalAccessory.isGoogle(json)) {
            apple = Observable.just(empty(beaconId));
        } else if (appleFetch != null) {
            apple = appleFetch;
        } else {
            apple = Observable.just(empty(beaconId));
        }

        final String googleId = ExternalAccessory.isGoogle(json)
                ? ExternalAccessory.googleId(json) : this.links.googleId(beaconId);
        if (googleId == null || !this.settings.isGoogleConfigured()) {
            return apple;
        }

        final Observable<List<BeaconLocationReport>> google = Observable.fromCallable(() -> {
            if (askGoogleNow) {
                try {
                    this.api.googleLocate(googleId, GOOGLE_LOCATE_WAIT_S);
                } catch (final Exception e) {
                    // The stored reports are still worth showing
                    Log.w(TAG, "Google locate request failed for " + beaconId, e);
                }
            }
            // Google's answer is the last known position, often hours or days old, so a scheduled
            // one-hour window would hide it; the database drops what it already holds
            final long googleSince = Math.min(sinceMs, System.currentTimeMillis() - 7 * 86_400_000L);
            List<BeaconLocationReport> fetched = this.api.googleReports(googleId, googleSince);
            if (!askGoogleNow && fetched.isEmpty() && this.api.googleReports(googleId, 0).isEmpty()) {
                // Never located: ask Google once, rather than waiting for a refresh button that
                // a device without a location does not have
                final long now = System.currentTimeMillis();
                final Long last = lastFirstLocate.get(googleId);
                if (last == null || now - last > GOOGLE_FIRST_LOCATE_EVERY_MS) {
                    lastFirstLocate.put(googleId, now);
                    try {
                        this.api.googleLocate(googleId, GOOGLE_LOCATE_WAIT_S);
                        fetched = this.api.googleReports(googleId, googleSince);
                    } catch (final Exception e) {
                        Log.w(TAG, "First Google locate failed for " + beaconId, e);
                    }
                }
            }
            final List<BeaconLocationReport> reports = new ArrayList<>();
            for (final BeaconLocationReport r : fetched) {
                if (r.getTimestamp() <= untilMs) reports.add(r);
            }
            return reports;
        }).subscribeOn(Schedulers.io());

        // A failing Google service must not cost the device its Apple reports, and the reverse
        if (ExternalAccessory.isGoogle(json)) {
            return google.map(reports -> withReports(beaconId, reports));
        }
        return Observable.zip(
                apple.onErrorReturn(e -> {
                    Log.w(TAG, "Apple reports failed for " + beaconId, e);
                    return empty(beaconId);
                }),
                google.onErrorReturn(e -> {
                    Log.w(TAG, "Google reports failed for " + beaconId, e);
                    return new ArrayList<>();
                }),
                (fromApple, fromGoogle) -> merge(beaconId, fromApple, fromGoogle));
    }

    private static FetchResult empty(final String beaconId) {
        return withReports(beaconId, new ArrayList<>());
    }

    private static FetchResult withReports(final String beaconId, final List<BeaconLocationReport> reports) {
        final Map<String, List<BeaconLocationReport>> map = new HashMap<>();
        map.put(beaconId, reports);
        return new FetchResult(map, new HashMap<>(), new HashSet<>(), new HashSet<>());
    }

    static FetchResult merge(final String beaconId, final FetchResult apple, final List<BeaconLocationReport> google) {
        final Map<String, List<BeaconLocationReport>> map = new HashMap<>(apple.getReports());
        final List<BeaconLocationReport> all = new ArrayList<>(map.getOrDefault(beaconId, new ArrayList<>()));
        all.addAll(google);
        map.put(beaconId, all);
        final Set<String> exhausted = new HashSet<>(apple.getExhaustedWideSearch());
        // Google answering means the device is not silent, whatever Apple found
        if (!google.isEmpty()) exhausted.remove(beaconId);
        return new FetchResult(map, apple.getUpdatedAccessoryJson(), exhausted, apple.getWideSearch());
    }

    /** For callers that only hold an Apple service. */
    @Nullable
    public static Observable<FetchResult> appleFetchOrNull(@Nullable final PythonAppleService service,
                                                           final AccessoryRequest request, final int hours) {
        return service == null ? null : service.getLastReports(List.of(request), hours);
    }
}
