package dev.wander.android.opentagviewer.db.repo;

import android.content.Context;

import dev.wander.android.opentagviewer.db.datastore.UserAuthDataStore;
import dev.wander.android.opentagviewer.db.datastore.UserCacheDataStore;
import dev.wander.android.opentagviewer.db.room.OpenTagViewerDatabase;
import dev.wander.android.opentagviewer.util.android.AppCryptographyUtil;
import dev.wander.android.opentagviewer.util.rx.RxFlows;
import io.reactivex.rxjava3.core.Completable;

/**
 * The real {@link LocalDataEraser}: the Room database, the iCloud link and the map position.
 *
 * <p><b>The iCloud link is forgotten first</b>, so that nothing reading the account between the
 * two steps can write account tags back into a database that has just been emptied.
 *
 * <p>Forgetting the link does not leave the keychain circle; nothing here can. The peer stays on
 * the account, which the confirmation says. It is still the right thing for "erase all data":
 * those are a circle member's private keys, the most sensitive thing this app holds, and keeping
 * them after the user asked for everything to be erased would be the opposite of what they asked.
 */
public final class ThisPhonesTagData implements LocalDataEraser {

    private final BeaconRepository beacons;
    private final KeychainMembershipRepository memberships;
    private final UserDataRepository userData;

    public ThisPhonesTagData(
            final BeaconRepository beacons,
            final KeychainMembershipRepository memberships,
            final UserDataRepository userData) {
        this.beacons = beacons;
        this.memberships = memberships;
        this.userData = userData;
    }

    public static ThisPhonesTagData of(final Context context) {
        final Context app = context.getApplicationContext();
        return new ThisPhonesTagData(
                new BeaconRepository(OpenTagViewerDatabase.getInstance(app)),
                new KeychainMembershipRepository(
                        UserAuthDataStore.getInstance(app), new AppCryptographyUtil()),
                new UserDataRepository(UserCacheDataStore.getInstance(app)));
    }

    /**
     * Each step starts only once the one before it has finished, through
     * {@link RxFlows#onlyAfter}. The DataStore writes start the moment their method is called, so
     * chaining the calls directly would start all of them at once, and start them even if nobody
     * ever subscribed. The first is deferred for the same reason.
     */
    @Override
    public Completable eraseEverything() {
        final Completable link = Completable.defer(this.memberships::forget);
        final Completable mapPosition =
                RxFlows.onlyAfter(link, this.userData::forgetLastCameraPosition);
        return RxFlows.onlyAfter(mapPosition, this.beacons::eraseEverything);
    }
}
