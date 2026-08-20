package dev.wander.android.opentagviewer.python.icloud;

import androidx.annotation.NonNull;

import java.util.Optional;

import dev.wander.android.opentagviewer.db.repo.KeychainMembershipRepository;
import dev.wander.android.opentagviewer.python.AppDependencies;
import io.reactivex.rxjava3.core.Completable;
import io.reactivex.rxjava3.schedulers.Schedulers;

/**
 * One rename, from a screen that holds no iCloud session of its own.
 *
 * <p>The tag page is reached from the device list and knows nothing about Apple. A rename that
 * has to reach the account therefore opens a session, uses it once and closes it - which is
 * affordable precisely because renaming is rare, and is the reason this is not a session the
 * screen keeps alive for as long as somebody is looking at a tag.
 *
 * <p><b>It resumes as the member the app already is, and will not ask for a passcode.</b> Being a
 * member is what the join bought; if the stored membership has gone or stopped working there is
 * nothing to fall back on here, because the fallback is a device passcode and a whole flow with
 * screens in it. The rename fails, the caller says so, and the user's next visit to the account
 * screen puts it right.
 */
public final class AccessoryRenamer {

    private final KeychainMembershipRepository memberships;

    public AccessoryRenamer(@NonNull final KeychainMembershipRepository memberships) {
        this.memberships = memberships;
    }

    /**
     * Change the accessory's name and emoji in the user's Apple account.
     *
     * <p>Fails with {@link ICloudFailure#MEMBERSHIP_UNUSABLE} when this app is not a member, which
     * is the honest answer: nothing is wrong with the tag or the name, the app simply cannot
     * write to that account right now.
     *
     * @param name  the new name, or empty to leave it alone.
     * @param emoji the new emoji, or empty to leave it alone.
     */
    public Completable rename(@NonNull final String beaconId, @NonNull final String plistXml,
                              final String name, final String emoji) {
        return this.memberships.get()
                .firstOrError()
                .flatMapCompletable(held -> writeWith(held, beaconId, plistXml, name, emoji))
                .subscribeOn(Schedulers.io());
    }

    private static Completable writeWith(final Optional<KeychainMembership> held,
                                         final String beaconId, final String plistXml,
                                         final String name, final String emoji) {
        if (held.isEmpty()) {
            return Completable.error(new ICloudException(
                    ICloudFailure.MEMBERSHIP_UNUSABLE,
                    "This app is not a member of the account's keychain, so it cannot write"
                            + " to it."));
        }

        final ICloudService icloud = AppDependencies.icloud();

        // `close` in doFinally rather than in the happy path: two of these steps hold sockets,
        // and a rename that fails half way through would otherwise leak them for the life of
        // the process. It never throws.
        return icloud.open()
                .andThen(icloud.resume(held.get().getPeerJson()))
                .andThen(icloud.rename(beaconId, plistXml, name, emoji))
                .doFinally(icloud::close);
    }
}
