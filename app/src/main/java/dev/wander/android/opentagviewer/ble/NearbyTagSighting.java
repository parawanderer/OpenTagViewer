package dev.wander.android.opentagviewer.ble;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * One of the user's own tags, seen by this phone's radio just now.
 *
 * <p><b>A sighting is a positive claim only, and the absence of one claims nothing.</b> Seeing a
 * tag proves it was in range at that instant and reports what its own beacon said about its
 * battery. Not seeing it means any of: out of range, with its owner and therefore not
 * advertising at all (measured, see {@link FindMyAdvertisement}), or simply silent during the
 * window. Those are indistinguishable from here, so nothing may present "no sighting" as "out of
 * range" - the honest rendering is to show what was seen and stay quiet otherwise.
 *
 * <p>The battery level is the reason this is worth surfacing at all. The value the app has
 * otherwise comes from the iCloud record, which only Apple devices ever refresh, so for a tag
 * imported from a file it is whatever was true when the export was made - possibly years ago,
 * which is why it sits behind the debug switch. This one comes from the tag itself, in the
 * moment it was heard.
 */
@AllArgsConstructor
@Getter
public final class NearbyTagSighting {

    private final String beaconId;

    /**
     * The key index the matched address was derived at, as a hint for the alignment correction.
     *
     * <p><b>Carried, never acted on here.</b> Only Python can tell whether that index came from
     * a primary or a secondary key, and that is what decides whether it may be trusted - see
     * {@code AccessoryMacResolver#recordSeen}. Passing it along lets the correction verify one
     * index instead of re-deriving a 48-hour window: three key derivations instead of about
     * 1150.
     */
    private final int keyIndex;

    /** Signal strength in dBm. Negative; closer to zero is nearer. */
    private final int rssi;

    private final FindMyAdvertisement.BatteryLevel batteryLevel;

    /**
     * The status byte {@link #batteryLevel} was decoded from.
     *
     * <p>Carried alongside the reading rather than discarded once it has been decoded, because
     * the reading is two bits of it interpreted against a table only partly confirmed outside
     * Apple - see {@link dev.wander.android.opentagviewer.util.parse.LocationReportFields}. It
     * is what gets persisted with a stored reading, so a disputed one can be re-derived from
     * what was actually received.
     */
    private final int statusByte;

    /** Whether the beacon said it was separated from its owner. See {@link FindMyAdvertisement}. */
    private final FindMyAdvertisement.State state;

    /** Wall-clock time of the sighting, so a stale one can be aged out rather than left on screen. */
    private final long seenAtMs;
}
