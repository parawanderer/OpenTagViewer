package dev.wander.android.opentagviewer.db.repo.model;

import dev.wander.android.opentagviewer.ble.FindMyAdvertisement;
import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * What a tag last told this phone directly, and when it said it.
 *
 * <p>Always older than now, and possibly much older - a tag left in a coat pocket says nothing
 * for as long as it is out of range, and this is the last thing it managed to say before that.
 * Anything showing any of it must show the age with it, which is why the timestamp is not
 * optional here.
 *
 * <p>The battery level is all a sighting carries today. See {@code LastBleSighting} for why this
 * is named for the sighting rather than for that one field.
 */
@AllArgsConstructor
@Getter
public final class LastSightingData {

    /** When the advertisement was heard. */
    private final long heardAtMs;

    private final FindMyAdvertisement.BatteryLevel batteryLevel;

    /** The status byte the level came out of, kept for bug reports. See the entity. */
    private final int statusByte;
}
