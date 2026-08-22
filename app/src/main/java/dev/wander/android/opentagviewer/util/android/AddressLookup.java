package dev.wander.android.opentagviewer.util.android;

import android.location.Address;
import android.location.Geocoder;

import java.io.IOException;
import java.util.List;

/**
 * Turning a coordinate into something a person recognises.
 *
 * <p><b>An interface because {@link Geocoder} is final.</b> The obvious seam - a subclass that
 * answers from a table - does not compile, and the platform offers no other way to substitute
 * one. This is the smallest thing that does: the single method the app actually calls, with the
 * real implementation being a one-line delegation.
 *
 * <p>Worth the indirection because a geocoder that answers nothing does not look broken.
 * {@code Geocoder.getFromLocation} returns an empty list when it has no backend, which is what
 * every instrumented run is in - the {@code aosp-atd} image has none - and the app's response to
 * an empty answer is to print the raw latitude and longitude. That is the right thing to show
 * when an address genuinely cannot be found, and it makes "no geocoder on this device" and "no
 * address for this point" identical on screen. So the rounding, the caching and the fallback
 * were exercised by nothing, and every screenshot of the map or the history list showed numbers
 * where a phone shows a street.
 */
public interface AddressLookup {

    /**
     * @return what is at this point, nearest first, or empty if nothing is known. Never null.
     * @throws IOException if the lookup could not be made at all - which is not the same as
     *         there being nothing there, and callers treat it differently.
     */
    List<Address> getFromLocation(double latitude, double longitude, int maxResults)
            throws IOException;

    /** The real one. */
    static AddressLookup through(final Geocoder geocoder) {
        return geocoder::getFromLocation;
    }
}
