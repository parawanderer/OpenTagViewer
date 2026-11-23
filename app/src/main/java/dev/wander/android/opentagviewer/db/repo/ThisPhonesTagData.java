package dev.wander.android.opentagviewer.db.repo;

import android.content.Context;

import java.util.Set;

import dev.wander.android.opentagviewer.ble.DerivedAddressStore;
import dev.wander.android.opentagviewer.db.datastore.UserAuthDataStore;
import dev.wander.android.opentagviewer.db.datastore.UserCacheDataStore;
import dev.wander.android.opentagviewer.db.room.OpenTagViewerDatabase;
import dev.wander.android.opentagviewer.service.NearbyScanService;
import dev.wander.android.opentagviewer.util.android.AppCryptographyUtil;
import dev.wander.android.opentagviewer.util.rx.RxFlows;
import io.reactivex.rxjava3.core.Completable;
import io.reactivex.rxjava3.functions.Action;
import io.reactivex.rxjava3.functions.Supplier;

/**
 * The real {@link LocalDataEraser}: the iCloud link, the map position, the Room database, and what
 * the background scan holds.
 *
 * <p><b>The iCloud link is forgotten first</b>, so that nothing reading the account between the
 * steps can write account tags back into a database that has just been emptied. The cost is that
 * a failure after it leaves the link gone and the tags still there, which the error says.
 *
 * <p>Forgetting the link does not leave the keychain circle; nothing here can. The peer stays on
 * the account, which the confirmation says. It is still the right thing for "erase all data":
 * those are a circle member's private keys, the most sensitive thing this app holds, and keeping
 * them after the user asked for everything to be erased would be the opposite of what they asked.
 *
 * <p>The steps are passed in rather than the repositories, so that the order can be tested on the
 * JVM: two of them are DataStore writes, which start when called (AGENTS.md rule 23).
 */
public final class ThisPhonesTagData implements LocalDataEraser {

    private final Supplier<Completable> forgetTheICloudLink;
    private final Supplier<Completable> forgetTheMapPosition;
    private final Supplier<Completable> eraseTheDatabase;
    private final Action forgetWhatTheScanHolds;

    public ThisPhonesTagData(
            final Supplier<Completable> forgetTheICloudLink,
            final Supplier<Completable> forgetTheMapPosition,
            final Supplier<Completable> eraseTheDatabase,
            final Action forgetWhatTheScanHolds) {
        this.forgetTheICloudLink = forgetTheICloudLink;
        this.forgetTheMapPosition = forgetTheMapPosition;
        this.eraseTheDatabase = eraseTheDatabase;
        this.forgetWhatTheScanHolds = forgetWhatTheScanHolds;
    }

    public static ThisPhonesTagData of(final Context context) {
        final Context app = context.getApplicationContext();
        final BeaconRepository beacons =
                new BeaconRepository(OpenTagViewerDatabase.getInstance(app));
        final KeychainMembershipRepository memberships = new KeychainMembershipRepository(
                UserAuthDataStore.getInstance(app), new AppCryptographyUtil());
        final UserDataRepository userData =
                new UserDataRepository(UserCacheDataStore.getInstance(app));

        return new ThisPhonesTagData(
                memberships::forget,
                userData::forgetLastCameraPosition,
                beacons::eraseEverything,
                () -> {
                    // The addresses derived from each tag's keys, kept on disk so they are not
                    // derived again. The running scan retires them too when it restarts, but only
                    // if it has Bluetooth permission, and it may not be running at all.
                    new DerivedAddressStore(app.getFilesDir()).forgetAllExcept(Set.of());
                    NearbyScanService.restartIfRunning(app);
                });
    }

    /**
     * Each step starts only once the one before it has finished, through
     * {@link RxFlows#onlyAfter}. The DataStore writes start the moment their method is called, so
     * chaining the calls directly would start all of them at once, and start them even if nobody
     * ever subscribed. The first is deferred for the same reason.
     *
     * <p>The background scan is restarted last, and only once the database is empty: it read the
     * tags when it started and keeps their keys until it stops, so before this it went on
     * listening for the erased tags, and alerting on them, until the process died.
     */
    @Override
    public Completable eraseEverything() {
        final Completable link = Completable.defer(this.forgetTheICloudLink);
        final Completable mapPosition = RxFlows.onlyAfter(link, this.forgetTheMapPosition);
        final Completable database = RxFlows.onlyAfter(mapPosition, this.eraseTheDatabase);
        return database.andThen(Completable.fromAction(this.forgetWhatTheScanHolds));
    }
}
