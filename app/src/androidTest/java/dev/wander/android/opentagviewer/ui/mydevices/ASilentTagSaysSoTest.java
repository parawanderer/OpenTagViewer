package dev.wander.android.opentagviewer.ui.mydevices;

import static androidx.test.espresso.Espresso.onView;
import static androidx.test.espresso.action.ViewActions.click;
import static androidx.test.espresso.assertion.ViewAssertions.matches;
import static androidx.test.espresso.matcher.ViewMatchers.isDisplayed;
import static androidx.test.espresso.matcher.ViewMatchers.withId;
import static androidx.test.espresso.matcher.ViewMatchers.withText;
import static androidx.test.platform.app.InstrumentationRegistry.getInstrumentation;
import static androidx.test.espresso.matcher.ViewMatchers.hasDescendant;
import static org.hamcrest.Matchers.allOf;
import static org.hamcrest.Matchers.not;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;

import androidx.test.core.app.ActivityScenario;
import androidx.test.espresso.Espresso;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.filters.LargeTest;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import dev.wander.android.opentagviewer.DeviceInfoActivity;
import dev.wander.android.opentagviewer.Eventually;
import dev.wander.android.opentagviewer.MyDevicesListActivity;
import dev.wander.android.opentagviewer.R;
import dev.wander.android.opentagviewer.db.room.OpenTagViewerDatabase;
import dev.wander.android.opentagviewer.db.room.entity.BeaconNamingRecord;
import dev.wander.android.opentagviewer.db.room.entity.Import;
import dev.wander.android.opentagviewer.db.room.entity.OwnedBeacon;

/**
 * What a tag the app has given up on looks like, and what can be done about it.
 *
 * <p><b>The whole feature is invisible without this.</b> Underneath, a silent tag is skipped by
 * the scheduled fetches - which is right, because each one costs a full-history search that will
 * not repay it - but skipping something quietly is indistinguishable from failing to look. A user
 * sees a tag that never updates and an app that appears to have stopped trying, with no
 * explanation and nothing to press.
 *
 * <p>So the two things asserted here are the two things that make the silence honest: the device
 * list says why, and the tag page offers to look again.
 */
@LargeTest
@RunWith(AndroidJUnit4.class)
public class ASilentTagSaysSoTest {

    private static final String A_SILENT_TAG = "test-silent-tag";
    private static final String A_HEALTHY_TAG = "test-healthy-tag";
    private static final String SILENT_NAME = "Long Lost Backpack";
    private static final String HEALTHY_NAME = "Everyday Keys";
    private static final String A_TEST_USER = "silenttagtest@example.invalid";

    private static final String A_PLIST = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
            + "<plist version=\"1.0\"><dict>"
            + "<key>batteryLevel</key><integer>1</integer>"
            + "<key>model</key><string></string>"
            + "<key>pairingDate</key><date>2025-02-27T20:03:32Z</date>"
            + "<key>privateKey</key><dict><key>key</key><dict>"
            + "<key>data</key><data>bm90LWEtcmVhbC1rZXk=</data></dict></dict>"
            + "<key>productId</key><integer>21760</integer>"
            + "<key>stableIdentifier</key><array><string>2001~#0~#A0</string></array>"
            + "<key>systemVersion</key><string>2.0.73</string>"
            + "<key>vendorId</key><integer>76</integer>"
            + "</dict></plist>";

    private OpenTagViewerDatabase db;
    private ActivityScenario<?> scenario;
    private long importId;

    @Before
    public void seedOneSilentTagAndOneHealthyOne() {
        final Context context = getInstrumentation().getTargetContext();
        this.db = OpenTagViewerDatabase.getInstance(context);
        this.forgetThem();

        this.importId = this.db.importDao().insert(Import.builder()
                .version("0.0.2").importedAt(1_700_000_000_000L).exportedAt(1_699_000_000_000L)
                .sourceUser(A_TEST_USER).exportedVia("OpenTagViewer.wizard:test").build());

        this.insert(A_SILENT_TAG, SILENT_NAME, 1_700_000_000_000L);
        this.insert(A_HEALTHY_TAG, HEALTHY_NAME, null);
    }

    @After
    public void putEverythingBack() {
        if (this.scenario != null) {
            this.scenario.close();
        }
        this.forgetThem();
    }

    private void insert(final String id, final String name, final Long ignoredAt) {
        this.db.ownedBeaconDao().insertAll(OwnedBeacon.builder()
                .id(id).importId(this.importId).content(A_PLIST).version("0.0.2")
                .fromAccount(false).isRemoved(false)
                .ignoredAt(ignoredAt)
                .fruitlessScans(ignoredAt == null ? 0 : 7)
                .lastScanAt(ignoredAt)
                .build());

        this.db.beaconNamingRecordDao().insertAll(BeaconNamingRecord.builder()
                .id(id).importId(this.importId).version("0.0.2").isRemoved(false)
                .content("<?xml version=\"1.0\" encoding=\"UTF-8\"?><plist version=\"1.0\"><dict>"
                        + "<key>identifier</key><string>" + id + "</string>"
                        + "<key>name</key><string>" + name + "</string>"
                        + "</dict></plist>")
                .build());
    }

    private void forgetThem() {
        for (final String id : new String[] {A_SILENT_TAG, A_HEALTHY_TAG}) {
            this.db.ownedBeaconDao().delete(OwnedBeacon.builder().id(id).build());
            this.db.beaconNamingRecordDao().delete(BeaconNamingRecord.builder().id(id).build());
        }
        for (final Import stale : this.db.importDao().getImportsFromUser(A_TEST_USER)) {
            this.db.importDao().delete(stale);
        }
    }

    /**
     * Assert that the row for one named tag carries one particular subtitle.
     *
     * <p><b>Matched by row, not by text on the screen.</b> A bare
     * {@code withText(no_last_location_known)} passes only while exactly one row happens to say
     * it - so it would go green with the two rows' subtitles swapped, and it fails with an
     * ambiguous matcher rather than a disagreement the moment two rows agree. The name and the
     * subtitle are not siblings either: the name sits a level deeper, beside the warning icon.
     * So this finds the row containing the name and asks what else that row contains.
     */
    private void assertRowFor(final String name, final int expectedSubtitle) {
        final String expected =
                getInstrumentation().getTargetContext().getString(expectedSubtitle);

        onView(allOf(withId(R.id.device_item_container), hasDescendant(withText(name))))
                .check(matches(hasDescendant(allOf(
                        withId(R.id.list_item_last_update), withText(expected)))));
    }

    private void openTheTagPage(final String beaconId) {
        final Intent intent = new Intent(
                getInstrumentation().getTargetContext(), DeviceInfoActivity.class);
        intent.putExtra("beaconId", beaconId);
        this.scenario = ActivityScenario.launchActivityForResult(intent);
    }

    /**
     * <b>The device list says why, rather than the generic line.</b>
     *
     * <p>"No last location known" and "we have given up looking" are the same sentence to a
     * reader and completely different situations: one resolves itself the next time somebody
     * walks past the tag, the other never will unless they act.
     */
    @Test
    public void thedeviceListExplainsASilentTagRatherThanJustSayingNothingIsKnown() {
        this.scenario = ActivityScenario.launch(MyDevicesListActivity.class);

        Eventually.check(() -> onView(withText(SILENT_NAME)).check(matches(isDisplayed())));
        onView(withText(R.string.tag_ignored_summary)).check(matches(isDisplayed()));
    }

    /**
     * And a healthy tag with no location still gets the ordinary line.
     *
     * <p><b>Scoped to that row's own subtitle</b>, not to the text anywhere on screen. The first
     * version matched {@code withText(no_last_location_known)} globally, which passes only
     * because exactly one row happens to say it - so breaking the feature made this fail with an
     * ambiguous matcher rather than with a disagreement, and it would equally have passed if the
     * healthy row had said the wrong thing while some other row said the right one.
     */
    @Test
    public void ahealthyTagWithNoLocationIsNotDescribedAsGivenUpOn() {
        this.scenario = ActivityScenario.launch(MyDevicesListActivity.class);

        Eventually.check(() -> onView(withText(HEALTHY_NAME)).check(matches(isDisplayed())));

        this.assertRowFor(HEALTHY_NAME, R.string.no_last_location_known);
    }

    /** And the silent one's line belongs to the silent one, not merely to the screen. */
    @Test
    public void thesilentTagsOwnRowIsTheOneThatExplainsItself() {
        this.scenario = ActivityScenario.launch(MyDevicesListActivity.class);

        Eventually.check(() -> onView(withText(SILENT_NAME)).check(matches(isDisplayed())));

        this.assertRowFor(SILENT_NAME, R.string.tag_ignored_summary);
    }

    /** The tag page explains it and offers the one action that can change it. */
    @Test
    public void thetagPageOffersToLookAgain() {
        this.openTheTagPage(A_SILENT_TAG);

        Eventually.check(() -> onView(withId(R.id.device_ignored_notice))
                .check(matches(isDisplayed())));
        onView(withId(R.id.device_ignored_retry)).check(matches(isDisplayed()));
    }

    /** A healthy tag gets no notice at all - it has nothing to explain. */
    @Test
    public void ahealthyTagPageHasNoSuchNotice() {
        this.openTheTagPage(A_HEALTHY_TAG);

        Eventually.check(() -> onView(withId(R.id.device_settings_name))
                .check(matches(isDisplayed())));
        onView(withId(R.id.device_ignored_notice)).check(matches(not(isDisplayed())));
    }

    /**
     * <b>And pressing it asks for that tag by name.</b>
     *
     * <p>The tag page cannot fetch - the Python service and the card that shows the answer both
     * live on the map - so it hands the request back, which is also what puts the retry on the
     * manual path the backoff cannot touch. What is asserted is the handover: the screen finishes
     * naming the tag it wants looked for.
     */
    @Test
    public void pressingItAsksWhoeverOpenedTheScreenToLookForThatTag() {
        this.openTheTagPage(A_SILENT_TAG);

        Eventually.check(() -> onView(withId(R.id.device_ignored_retry))
                .check(matches(isDisplayed())));
        onView(withId(R.id.device_ignored_retry)).perform(click());

        Eventually.check(() -> assertEquals(
                Activity.RESULT_OK, this.scenario.getResult().getResultCode()));

        final Intent handedBack = this.scenario.getResult().getResultData();
        assertNotNull("nothing was handed back, so nothing will look for the tag", handedBack);
        assertEquals("the wrong tag was asked about", A_SILENT_TAG,
                handedBack.getStringExtra(DeviceInfoActivity.RETRY_IGNORED_BEACON));
    }

    /** Leaving the page any other way must not ask for a search nobody requested. */
    @Test
    public void leavingWithoutPressingItAsksForNothing() {
        this.openTheTagPage(A_SILENT_TAG);

        Eventually.check(() -> onView(withId(R.id.device_ignored_notice))
                .check(matches(isDisplayed())));
        Espresso.pressBackUnconditionally();

        Eventually.check(() -> assertNotNull(this.scenario.getResult()));

        final Intent handedBack = this.scenario.getResult().getResultData();
        final String asked = handedBack == null
                ? null : handedBack.getStringExtra(DeviceInfoActivity.RETRY_IGNORED_BEACON);

        assertEquals("backing out must not trigger an expensive search", null, asked);
    }
}
