package dev.wander.android.opentagviewer.db.repo.model;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * Who gets asked whether they want to connect an iCloud account.
 *
 * <p><b>A one-time prompt has exactly two ways to be wrong</b>, and both are bad enough to pin
 * every case: shown to somebody it does not apply to, or shown again to somebody who already
 * said no. The second is worse - it is how a prompt becomes something people close without
 * reading, and this one is the only mention they will ever get of the feature that removes the
 * Mac from the story.
 *
 * <p>Pure decision logic, so it lives on the JVM. The dialog itself, and the two real journeys
 * through it, are {@code TheICloudOfferAppearsOnceTest}.
 */
public class OfferingICloudSetupTest {

    private static final boolean LINKED = true;
    private static final boolean NOT_LINKED = false;
    private static final boolean SIGNED_IN = true;

    /**
     * <b>Somebody setting the app up from scratch is asked.</b>
     *
     * <p>Nothing stored at all: no account, no record of being asked. They have just signed in
     * and their only route to tags is exporting a zip on a Mac, which is exactly the situation
     * the offer exists for.
     */
    @Test
    public void anewUserIsAsked() {
        final UserSettings fresh = UserSettings.builder()
                // A new install resolves to local Anisette without being asked, so the Anisette
                // offer is not due and does not stand in front of this one.
                .anisetteMode(UserSettings.ANISETTE_LOCAL)
                .build();

        assertTrue("a new user should be offered the account route",
                fresh.shouldOfferICloud(NOT_LINKED, SIGNED_IN));
    }

    /**
     * <b>Somebody updating from an earlier version is asked too</b> - eventually.
     *
     * <p>They have settings, a session and no account, and crucially no {@code icloudOfferMade}
     * key, because the key did not exist when they last ran the app. Absence is what makes the
     * offer reach them without any migration code.
     */
    @Test
    public void anupgraderIsAskedOnceTheAnisetteQuestionIsSettled() {
        final UserSettings updated = UserSettings.builder()
                .anisetteServerUrl("https://someone-elses-server.example")
                // Already answered, so it is not competing for the screen.
                .anisetteUpgradeOffered(true)
                .build();

        assertTrue("somebody updating should be offered the account route",
                updated.shouldOfferICloud(NOT_LINKED, SIGNED_IN));
    }

    /**
     * <b>But not while the Anisette offer is still due.</b>
     *
     * <p>Somebody updating qualifies for both at the same moment, and two dialogs stacked on the
     * map is how people learn to dismiss dialogs unread. Anisette wins because it is about the
     * session continuing to work at all.
     *
     * <p>The important half is that this is a <i>deferral</i>, not a refusal - nothing marks the
     * offer made, so it comes back on the next launch. The test below pins that.
     */
    @Test
    public void theanisetteOfferGoesFirst() {
        final UserSettings updating = UserSettings.builder()
                .anisetteServerUrl("https://someone-elses-server.example")
                .build();

        assertTrue("this test is meaningless unless the Anisette offer really is due",
                updating.shouldOfferLocalAnisette(SIGNED_IN));
        assertFalse("two one-time offers must not arrive at once",
                updating.shouldOfferICloud(NOT_LINKED, SIGNED_IN));
    }

    /** And once Anisette has been dealt with, the iCloud offer arrives. */
    @Test
    public void deferringForAnisetteDoesNotSpendTheOffer() {
        final UserSettings updating = UserSettings.builder()
                .anisetteServerUrl("https://someone-elses-server.example")
                .build();

        assertFalse(updating.shouldOfferICloud(NOT_LINKED, SIGNED_IN));

        // What the Anisette dialog does when it is shown.
        updating.setAnisetteUpgradeOffered(true);

        assertTrue("the deferred offer has to come back, not be silently spent",
                updating.shouldOfferICloud(NOT_LINKED, SIGNED_IN));
    }

    // --- and nobody else ------------------------------------------------------------------------

    /**
     * <b>Dismissing it once is final.</b>
     *
     * <p>Somebody who is happy importing zips should never be bothered again. The flag is set
     * when the dialog is shown rather than when a button is pressed, so this covers declining,
     * dismissing and having the screen torn down mid-dialog alike.
     */
    @Test
    public void somebodyWhoSaidNoIsNeverAskedAgain() {
        final UserSettings declined = UserSettings.builder()
                .anisetteMode(UserSettings.ANISETTE_LOCAL)
                .icloudOfferMade(true)
                .build();

        assertFalse("a dismissed one-time offer must stay dismissed",
                declined.shouldOfferICloud(NOT_LINKED, SIGNED_IN));
    }

    /**
     * <b>Somebody already reading their account is not asked.</b>
     *
     * <p>Even if the flag was somehow never written - a crash between showing the dialog and
     * saving, say - the question does not apply to them, and asking would read as the app
     * having lost track of what it is already doing.
     */
    @Test
    public void somebodyAlreadyConnectedIsNotAsked() {
        final UserSettings connected = UserSettings.builder()
                .anisetteMode(UserSettings.ANISETTE_LOCAL)
                .build();

        assertFalse("an account is already connected; there is nothing to offer",
                connected.shouldOfferICloud(LINKED, SIGNED_IN));
    }

    /** Both reasons at once is still no, which is the ordinary state after the first run. */
    @Test
    public void connectedAndAlreadyAskedIsStillNo() {
        final UserSettings settled = UserSettings.builder()
                .anisetteMode(UserSettings.ANISETTE_LOCAL)
                .icloudOfferMade(true)
                .build();

        assertFalse(settled.shouldOfferICloud(LINKED, SIGNED_IN));
    }
}
