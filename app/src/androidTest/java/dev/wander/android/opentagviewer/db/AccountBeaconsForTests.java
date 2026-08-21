package dev.wander.android.opentagviewer.db;

import static androidx.test.platform.app.InstrumentationRegistry.getInstrumentation;

import dev.wander.android.opentagviewer.db.room.OpenTagViewerDatabase;
import dev.wander.android.opentagviewer.db.room.entity.BeaconNamingRecord;
import dev.wander.android.opentagviewer.db.room.entity.OwnedBeacon;

/**
 * Undo what a test's iCloud import wrote to the real database.
 *
 * <p><b>A test that finishes the iCloud flow writes real rows.</b> The activity holds
 * {@code OpenTagViewerDatabase.getInstance}, which is a plain singleton with no test seam, so
 * every fake tag the flow "imports" lands in the same database the app uses - visible in My
 * Devices, on the map, and for as long as that install exists. On the managed device that is one
 * run; on a developer's own device it is a handful of tags they did not import and cannot
 * explain, and {@code allowBackup} is false, so there is nothing to restore from.
 *
 * <p>It also breaks other tests, which is how it was found: {@code RemoveAccountTagTest} seeds
 * three known tags and looks for them by name, and passed alone while failing in the full suite
 * because the leftovers had pushed its rows off the bottom of the list.
 *
 * <p>Scoped to {@code from_account} rows, so a file-imported tag - somebody's real one, if this
 * is ever run against a real install - is never touched. Same reasoning as
 * {@code OwnedBeaconDao#retireAccountBeaconsMissingFrom}, and the same reason
 * {@code clearAllTables} is not used here.
 */
public final class AccountBeaconsForTests {

    private AccountBeaconsForTests() {
    }

    /** Call from {@code @Before} as well as {@code @After} - a crashed test cleans up neither. */
    public static void forgetThemAll() {
        final OpenTagViewerDatabase db =
                OpenTagViewerDatabase.getInstance(getInstrumentation().getTargetContext());

        for (final String id : db.ownedBeaconDao().getAccountBeaconIds()) {
            db.ownedBeaconDao().delete(OwnedBeacon.builder().id(id).build());
            db.beaconNamingRecordDao().delete(BeaconNamingRecord.builder().id(id).build());
        }
    }
}
