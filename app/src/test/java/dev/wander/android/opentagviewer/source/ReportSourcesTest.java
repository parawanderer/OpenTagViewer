package dev.wander.android.opentagviewer.source;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import dev.wander.android.opentagviewer.data.model.BeaconLocationReport;
import dev.wander.android.opentagviewer.db.room.entity.LocationReport;
import dev.wander.android.opentagviewer.python.FetchResult;

public class ReportSourcesTest {
    private static BeaconLocationReport report(final long timestamp, final String provenance) {
        return BeaconLocationReport.builder()
                .timestamp(timestamp)
                .publishedAt(timestamp)
                .latitude(52.4)
                .longitude(16.9)
                .provenance(provenance)
                .build();
    }

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
