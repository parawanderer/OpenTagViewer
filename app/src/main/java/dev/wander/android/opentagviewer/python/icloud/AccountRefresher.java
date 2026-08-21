package dev.wander.android.opentagviewer.python.icloud;

import android.util.Log;

import androidx.annotation.NonNull;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import dev.wander.android.opentagviewer.db.repo.BeaconRepository;
import dev.wander.android.opentagviewer.db.repo.KeychainMembershipRepository;
import dev.wander.android.opentagviewer.python.AppDependencies;
import io.reactivex.rxjava3.core.Observable;
import io.reactivex.rxjava3.schedulers.Schedulers;

/**
 * Re-read the Apple account on the app's own initiative, with nobody watching.
 *
 * <p><b>This is what being a member of the keychain is for.</b> Joining buys the ability to read
 * without a device passcode, and until now the only thing that spent it was somebody opening the
 * account screen and asking. So a tag added in Find My, or renamed there, or removed, did not
 * reach the app until the user went looking for a button - which is the opposite of how a linked
 * account should behave.
 *
 * <p><b>Silent both ways.</b> Nothing here has a screen: it succeeds by the device list quietly
 * being right the next time it is opened, and it fails by logging. The one exception is a
 * membership the account no longer honours - that is forgotten, because leaving it stored means
 * retrying dead keys on every interval forever, and forgetting it puts the app back in the state
 * where the UI offers to link again.
 *
 * <p><b>It does not decide when to run.</b> That is {@link AccountReadPolicy}, kept apart so the
 * timing can be tested without an Apple account and this can be tested without a clock.
 */
public final class AccountRefresher {
    private static final String TAG = AccountRefresher.class.getSimpleName();

    private final KeychainMembershipRepository memberships;
    private final BeaconRepository beacons;

    public AccountRefresher(@NonNull final KeychainMembershipRepository memberships,
                            @NonNull final BeaconRepository beacons) {
        this.memberships = memberships;
        this.beacons = beacons;
    }

    /**
     * Read the account and bring the stored tags into line with it.
     *
     * @return the ids now held for the account, or an empty list when there was nothing to do -
     *         which is the ordinary answer for somebody who has never linked one.
     */
    public Observable<List<String>> refresh() {
        return this.memberships.get()
                .firstOrError()
                .flatMapObservable(this::readWith)
                .subscribeOn(Schedulers.io());
    }

    private Observable<List<String>> readWith(final Optional<KeychainMembership> held) {
        if (held.isEmpty()) {
            // Not linked. Not a failure, and not worth a log line every interval.
            return Observable.just(List.of());
        }

        final ICloudService icloud = AppDependencies.icloud();
        if (icloud == null) {
            Log.i(TAG, "No usable Apple session, so the account cannot be re-read yet");
            return Observable.just(List.of());
        }

        return icloud.open()
                .andThen(icloud.resume(held.get().getPeerJson()))
                .andThen(icloud.fetch())
                .flatMap(fetched -> icloud.records(idsOf(fetched)))
                .flatMap(this.beacons::refreshAccountBeacons)
                .doOnNext(ids -> Log.i(TAG, "Re-read the Apple account: " + ids.size() + " tags"))
                .onErrorResumeNext(error -> this.recoverFrom(error))
                // In doFinally rather than after the last step: two of these hold sockets, and a
                // read that failed half way would otherwise leak them for the life of the
                // process. It never throws.
                .doFinally(icloud::close);
    }

    /**
     * A failed read is not reported to anybody, but a dead membership is acted on.
     *
     * <p>Keeping one that no longer works means retrying keys that cannot succeed on every
     * interval, forever, and never telling the user why their tags stopped changing. Forgetting
     * it costs a device passcode once and puts the app back where the screens can explain
     * themselves.
     */
    private Observable<List<String>> recoverFrom(final Throwable error) {
        final boolean membershipIsDead = error instanceof ICloudException
                && ((ICloudException) error).getFailure() == ICloudFailure.MEMBERSHIP_UNUSABLE;

        if (!membershipIsDead) {
            Log.w(TAG, "Could not re-read the Apple account; leaving the stored tags alone", error);
            return Observable.just(List.of());
        }

        Log.w(TAG, "The stored keychain membership no longer works, so it is being forgotten."
                + " The app will offer to link the account again.", error);

        return this.memberships.forget().andThen(Observable.just(List.of()));
    }

    private static List<String> idsOf(final ICloudFetch fetched) {
        final List<String> wanted = new ArrayList<>();
        for (final ICloudAccessory accessory : fetched.getAccessories()) {
            wanted.add(accessory.getBeaconId());
        }
        return wanted;
    }
}
