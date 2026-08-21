package dev.wander.android.opentagviewer.python.icloud;

import static androidx.test.platform.app.InstrumentationRegistry.getInstrumentation;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import androidx.room.Room;
import androidx.test.ext.junit.runners.AndroidJUnit4;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.List;

import dev.wander.android.opentagviewer.db.datastore.UserAuthDataStore;
import dev.wander.android.opentagviewer.db.repo.BeaconRepository;
import dev.wander.android.opentagviewer.db.repo.KeychainMembershipRepository;
import dev.wander.android.opentagviewer.db.room.OpenTagViewerDatabase;
import dev.wander.android.opentagviewer.db.room.entity.OwnedBeacon;
import dev.wander.android.opentagviewer.python.AppDependencies;
import dev.wander.android.opentagviewer.util.android.AppCryptographyUtil;

/**
 * Re-reading the Apple account with nobody watching.
 *
 * <p><b>This is what joining the keychain was for.</b> Membership buys reading without a device
 * passcode, and until now the only thing that spent it was somebody opening a screen and asking -
 * so a tag added in Find My, renamed there, or removed did not reach the app until the user went
 * hunting for a button.
 *
 * <p>Everything here is silent in production: it succeeds by the device list quietly being right
 * later on, and fails by logging. That is exactly why it needs tests - there is no screen to
 * notice on, and the two failure modes matter in opposite directions. A read that gives up too
 * easily leaves the app stale forever; one that never gives up retries dead keys on every
 * interval and never says why.
 */
@RunWith(AndroidJUnit4.class)
public class AccountRefresherTest {

    private OpenTagViewerDatabase db;
    private BeaconRepository beacons;
    private KeychainMembershipRepository memberships;
    private FakeICloudService icloud;

    @Before
    public void openEverything() {
        this.db = Room.inMemoryDatabaseBuilder(
                        getInstrumentation().getTargetContext(), OpenTagViewerDatabase.class)
                .allowMainThreadQueries()
                .build();

        // The real converter needs a running Python runtime; nothing here is about conversion.
        this.beacons = new BeaconRepository(this.db, (plist, alignment) -> "{\"type\":\"a\"}");

        this.memberships = new KeychainMembershipRepository(
                UserAuthDataStore.getInstance(getInstrumentation().getTargetContext()),
                new AppCryptographyUtil());
        this.memberships.forget().blockingAwait();

        this.icloud = FakeICloudService.withTags();
        AppDependencies.replaceICloud(() -> this.icloud);
    }

    @After
    public void closeEverything() {
        AppDependencies.reset();
        this.memberships.forget().blockingAwait();
        this.db.close();
    }

    private void givenTheAccountIsLinked() {
        this.memberships.store(new KeychainMembership(
                "{\"peer_id\":\"peer-ours\"}", "ZW50cm9weQ==", "a-passcode", "a-label", 2))
                .blockingAwait();
    }

    private AccountRefresher aRefresher() {
        return new AccountRefresher(this.memberships, this.beacons);
    }

    private List<String> liveBeaconIds() {
        return this.db.ownedBeaconDao().getAll().stream().map(b -> b.id).collect(
                java.util.stream.Collectors.toList());
    }

    /** <b>A linked account is read and the tags land in the database.</b> */
    @Test
    public void alinkedAccountIsReadWithoutAnybodyAsking() {
        this.givenTheAccountIsLinked();

        final List<String> held = this.aRefresher().refresh().blockingFirst();

        assertFalse("nothing was read from the account", held.isEmpty());
        assertEquals("what was read should be what is stored",
                held.size(), this.liveBeaconIds().size());
    }

    /**
     * <b>And it never asks for a device passcode.</b>
     *
     * <p>The whole point. A background read that could ask for one would be a background read
     * that stalls forever, because there is nobody there to answer.
     */
    @Test
    public void itreadsAsTheMemberItAlreadyIsRatherThanUnlocking() {
        this.givenTheAccountIsLinked();

        this.aRefresher().refresh().blockingFirst();

        assertEquals("a background read must never unlock", 0, this.icloud.timesCalled("unlock"));
        assertEquals("it should resume as the stored member", 1, this.icloud.timesCalled("resume"));
    }

    /** An account nobody linked is not an error, and not worth a log line every interval. */
    @Test
    public void anaccountThatWasNeverLinkedIsSkippedQuietly() {
        final List<String> held = this.aRefresher().refresh().blockingFirst();

        assertTrue(held.isEmpty());
        assertEquals("nothing should have been opened", 0, this.icloud.timesCalled("open"));
    }

    /** The session is closed even when the read worked - these hold sockets. */
    @Test
    public void thesessionIsClosedAfterwards() {
        this.givenTheAccountIsLinked();

        this.aRefresher().refresh().blockingFirst();

        assertEquals(1, this.icloud.timesCalled("close"));
    }

    /**
     * <b>A membership the account no longer honours is forgotten.</b>
     *
     * <p>Removing this app's peer is how somebody revokes it, and that is a state a real user
     * creates deliberately. Keeping the dead membership means retrying keys that cannot work on
     * every interval, forever, with nothing on screen ever explaining why the tags stopped
     * changing. Forgetting it costs one device passcode and puts the app back where the screens
     * can explain themselves.
     */
    @Test
    public void adeadMembershipIsForgottenRatherThanRetriedForever() {
        this.givenTheAccountIsLinked();
        this.icloud = FakeICloudService.withTags().whereTheMembershipNoLongerWorks();
        AppDependencies.replaceICloud(() -> this.icloud);

        this.aRefresher().refresh().blockingFirst();

        assertTrue("the unusable membership was kept",
                this.memberships.get().blockingFirst().isEmpty());
    }

    /**
     * Any other failure leaves everything alone.
     *
     * <p>A read that could not reach Apple says nothing and changes nothing - the stored tags are
     * still the best answer available, and throwing them away because the network was down would
     * be losing data to a transient.
     */
    @Test
    public void afailedReadLeavesTheStoredTagsAlone() {
        this.givenTheAccountIsLinked();
        this.db.ownedBeaconDao().insertAll(OwnedBeacon.builder()
                .id("a-stored-tag").content("<plist/>").version("account")
                .fromAccount(true).isRemoved(false).build());

        // **Failing at the fetch, not at the recovery options.** The first version of this used
        // whereTheServiceIsUnsure(), which breaks a call this path never makes - so the read
        // succeeded and the test asserted nothing. A background read never asks what could
        // unlock the keychain; it resumes and reads.
        this.icloud = FakeICloudService.withTags().whereFetchingFails(new ICloudException(
                ICloudFailure.UNKNOWN, "the fake was told the account could not be reached"));
        AppDependencies.replaceICloud(() -> this.icloud);

        final List<String> held = this.aRefresher().refresh().blockingFirst();

        assertTrue("a failed read must report nothing rather than throwing", held.isEmpty());
        assertEquals("the stored tags should be untouched",
                List.of("a-stored-tag"), this.liveBeaconIds());
        assertTrue("a transient failure must not forget the membership",
                this.memberships.get().blockingFirst().isPresent());
    }
}
