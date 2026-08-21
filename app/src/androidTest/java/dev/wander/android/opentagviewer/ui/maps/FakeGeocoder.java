package dev.wander.android.opentagviewer.ui.maps;

import android.location.Address;

import dev.wander.android.opentagviewer.util.android.AddressLookup;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Street names for coordinates, invented and stable.
 *
 * <p><b>The managed device has no geocoder at all.</b> {@code aosp-atd} carries no backend, so
 * the platform's {@code getFromLocation} returns an empty list for every point on earth. Nothing
 * fails: the tag card and the history rows both fall back to printing the raw latitude and
 * longitude, which is exactly what they are supposed to do when an address genuinely cannot be
 * found. So the absence looks like correct behaviour, and every screenshot of either screen
 * shows numbers where a real phone shows a place.
 *
 * <p><b>Which means the geocoding path was covered by nothing.</b> There is real behaviour in
 * it - coordinates are rounded before lookup so that a tag jittering by metres does not
 * re-geocode, and results are cached per rounded pair across screens - and none of it could be
 * observed, because the answer was empty either way.
 *
 * <p><b>Deliberately not plausible.</b> The addresses are invented and say so, because a
 * screenshot of a test is a thing people read later, and one carrying a real-looking address
 * next to a real-looking coordinate invites somebody to believe a location was actually looked
 * up. Anything not explicitly registered gets a name derived from its coordinates rather than
 * nothing, so an unexpected point is visible as an unexpected point.
 */
public final class FakeGeocoder implements AddressLookup {

    /** Registered points, in insertion order, keyed as they will be compared. */
    private final Map<String, String> byRoundedPosition = new LinkedHashMap<>();

    /**
     * How close a lookup has to be to a registered point to get its name.
     *
     * <p>Roughly a hundred metres. The app rounds coordinates before asking - to four decimal
     * places, as {@code HistoryItemsAdapter}'s own log line shows - so a test that registers the
     * exact coordinates it reported cannot rely on getting the exact coordinates back.
     */
    private static final double NEAR_ENOUGH = 0.001;

    private final List<double[]> asked = new ArrayList<>();

    /** Give a point a name. Anything within about a hundred metres of it answers with this. */
    public FakeGeocoder saying(final double latitude, final double longitude, final String name) {
        this.byRoundedPosition.put(key(latitude, longitude), name);
        return this;
    }

    /** Every coordinate pair this was asked about, so a test can assert it was asked at all. */
    public List<double[]> asked() {
        return new ArrayList<>(this.asked);
    }

    @Override
    public List<Address> getFromLocation(
            final double latitude, final double longitude, final int maxResults) {

        this.asked.add(new double[] {latitude, longitude});

        final Address address = new Address(Locale.UK);
        address.setLatitude(latitude);
        address.setLongitude(longitude);
        address.setAddressLine(0, this.nameFor(latitude, longitude));

        return List.of(address);
    }

    private String nameFor(final double latitude, final double longitude) {
        for (final Map.Entry<String, String> registered : this.byRoundedPosition.entrySet()) {
            final String[] parts = registered.getKey().split(",");
            if (Math.abs(Double.parseDouble(parts[0]) - latitude) < NEAR_ENOUGH
                    && Math.abs(Double.parseDouble(parts[1]) - longitude) < NEAR_ENOUGH) {
                return registered.getValue();
            }
        }

        // Not registered, and named rather than dropped - an empty answer would be
        // indistinguishable from the device having no geocoder, which is the whole problem this
        // exists to remove.
        return String.format(Locale.ROOT, "Nowhere in particular (%.4f, %.4f)",
                latitude, longitude);
    }

    private static String key(final double latitude, final double longitude) {
        return latitude + "," + longitude;
    }
}
