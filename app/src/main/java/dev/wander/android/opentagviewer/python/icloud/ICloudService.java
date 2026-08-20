package dev.wander.android.opentagviewer.python.icloud;

import java.util.List;

import io.reactivex.rxjava3.core.Completable;
import io.reactivex.rxjava3.core.Observable;

/**
 * Reading the tags in the user's own Apple account.
 *
 * <p>Four steps, in order, because a person answers something between them:
 *
 * <pre>
 *   open() -&gt; recoveryOptions() -&gt; unlock(serial, passcode) -&gt; fetch() -&gt; records(ids)
 * </pre>
 *
 * <p><b>An interface so the screens can be tested without an Apple account.</b> Every failure
 * worth showing a user - an account with nothing to recover from, a service having a bad day, a
 * rejected passcode - is unreachable from a test that needs real credentials, which means those
 * are exactly the paths that would never be covered. {@code FakeICloudService} drives them all.
 *
 * <p><b>Failures arrive as {@link ICloudException} in {@code onError}</b>, carrying an
 * {@link ICloudFailure} to branch on so the wording stays in {@code strings.xml}.
 *
 * <p>Every call takes the shared Python lock for its own duration and gives it back, so the
 * app's periodic location refresh is delayed by a step but never by the user's thinking time.
 * Implementations must not hold it across a dialog.
 */
public interface ICloudService {

    /**
     * Open the Find My client: a keychain session and a CloudKit client.
     *
     * <p>Nothing is decrypted yet - that needs keys, and keys need {@link #unlock}.
     */
    Completable open();

    /**
     * What this account could unlock its keychain from.
     *
     * <p>Never empty on success: the two ways of being empty are
     * {@link ICloudFailure#NOTHING_TO_RECOVER_FROM} and {@link ICloudFailure#SERVICE_UNSURE},
     * and they are errors precisely so a screen cannot accidentally treat them alike.
     */
    Observable<List<RecoverableDevice>> recoveryOptions();

    /**
     * Recover the keychain keys with one device's screen-lock passcode.
     *
     * <p><b>One attempt per call.</b> The retry lives with the dialog that spends it, and so does
     * the cap - see {@link #MAX_UNLOCK_ATTEMPTS}, which has to be respected: attempts are
     * probably a limited resource on Apple's end, and what this service allows is not
     * established.
     */
    Completable unlock(String serial, String passcode);

    /**
     * How many times a passcode may be offered before the flow gives up.
     *
     * <p>A bound rather than a free retry, mirroring {@code exporter.icloud.MAX_UNLOCK_ATTEMPTS}.
     * FindMy.py says Apple's escrow services generally cap attempts and that what this one allows
     * is not established - which is a good reason not to find out on somebody's real account.
     */
    int MAX_UNLOCK_ATTEMPTS = 3;

    /**
     * Become a member of the account's keychain in this app's own right. <b>This one writes.</b>
     *
     * <p>Everything else here reads. This enrols an escrow record and adds a peer, and it is
     * worth that for one reason: a non-member reads with view keys it holds a share of, and those
     * keep working right up until the keys <b>roll</b> - expected whenever the circle's
     * membership changes. Only a current member is given shares of the new ones, so a non-member
     * goes quietly stale, still holding keys and decrypting nothing new. Here that is a map that
     * stopped updating for no reason, which is the failure nobody can diagnose for themselves.
     *
     * <p><b>Never call this twice for one intent.</b> A response that will not decode is not a
     * call that failed, and a timeout does not establish that nothing was sent. Treat any failure
     * as "it may have happened" and recover by looking at the account, not by trying again.
     *
     * <p>Must follow a successful {@link #unlock} in the same session - the peer that unlock
     * recovered is what sponsors the join.
     *
     * @param escrowPasscode the passcode <i>this app's own</i> record will be recoverable under,
     *                       from {@link EscrowPasscode}. Not the user's, and never shown.
     * @return the membership to store <b>before anything else can go wrong</b>: its keys are the
     *         only copy in existence.
     */
    Observable<KeychainMembership> join(String escrowPasscode);

    /**
     * Read the keychain as the member this app already is - no passcode, nothing borrowed.
     *
     * <p>What the join bought, and the call that replaces asking on every refresh.
     *
     * <p>Fails with {@link ICloudFailure#MEMBERSHIP_UNUSABLE} when the stored keys no longer
     * work, which is not a retry: the peer may have been removed from the account, and the way
     * forward is a passcode and a fresh join.
     */
    Completable resume(String peerJson);

    /** Read and decrypt the account's accessories, described but without their key material. */
    Observable<ICloudFetch> fetch();

    /** The chosen accessories, as the plists the importer already reads. */
    Observable<List<AccessoryRecords>> records(List<String> beaconIds);

    /**
     * Close the client.
     *
     * <p>Call it from a {@code finally}: two of the steps hold sockets, and an abandoned session
     * leaks them for the life of the process. Never throws.
     */
    void close();
}
