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
