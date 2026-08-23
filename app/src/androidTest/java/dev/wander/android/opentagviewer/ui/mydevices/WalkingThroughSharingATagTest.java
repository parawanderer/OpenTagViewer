package dev.wander.android.opentagviewer.ui.mydevices;

import static androidx.test.espresso.Espresso.onView;
import static androidx.test.espresso.action.ViewActions.click;
import static androidx.test.espresso.action.ViewActions.longClick;
import static androidx.test.espresso.assertion.ViewAssertions.matches;
import static androidx.test.espresso.intent.Intents.intended;
import static androidx.test.espresso.intent.Intents.intending;
import static androidx.test.espresso.intent.matcher.IntentMatchers.hasAction;
import static androidx.test.espresso.intent.matcher.IntentMatchers.hasComponent;
import static androidx.test.espresso.matcher.RootMatchers.isDialog;
import static androidx.test.espresso.matcher.ViewMatchers.isDisplayed;
import static androidx.test.espresso.matcher.ViewMatchers.withId;
import static androidx.test.espresso.matcher.ViewMatchers.withText;
import static androidx.test.platform.app.InstrumentationRegistry.getInstrumentation;
import static org.hamcrest.Matchers.anyOf;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.junit.Assert.assertTrue;

import android.app.Activity;
import android.app.Instrumentation.ActivityResult;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;

import androidx.test.core.app.ActivityScenario;
import androidx.test.espresso.intent.Intents;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.filters.LargeTest;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;
import java.util.LinkedHashMap;
import java.util.Map;

import dev.wander.android.opentagviewer.Eventually;
import dev.wander.android.opentagviewer.MyDevicesListActivity;
import dev.wander.android.opentagviewer.R;
import dev.wander.android.opentagviewer.Shot;
import dev.wander.android.opentagviewer.TestPace;
import dev.wander.android.opentagviewer.db.room.OpenTagViewerDatabase;
import dev.wander.android.opentagviewer.db.room.entity.BeaconNamingRecord;
import dev.wander.android.opentagviewer.db.room.entity.Import;
import dev.wander.android.opentagviewer.db.room.entity.OwnedBeacon;
import dev.wander.android.opentagviewer.python.AppDependencies;
import dev.wander.android.opentagviewer.python.BundleBuilder;
import dev.wander.android.opentagviewer.ui.error.ErrorReportActivity;

/**
 * Giving somebody a tag, at a pace a person can follow.
 *
 * <p><b>Its siblings assert; this one is for watching.</b> Pick a tag, choose Export Tags, save it
 * somewhere, and read the code off the screen - which is the whole feature, and takes about two
 * seconds at machine speed.
 *
 * <p>It still asserts as it goes, because a demo that can pass while showing the wrong screen is
 * decoration. The assertions are the ones a viewer is looking at anyway.
 *
 * <p>See {@code AGENTS.md} under "Showing a UI test to a person" for how to run it on a device
 * with a window.
 */
@LargeTest
@RunWith(AndroidJUnit4.class)
public class WalkingThroughSharingATagTest {

    private static final String A_TAG = "share-walkthrough-tag";
    private static final String A_NAME = "Bike Keys";
    private static final String A_TEST_USER = "sharewalkthrough@example.invalid";

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

    private static final String A_NAMING_RECORD =
            "<?xml version=\"1.0\" encoding=\"UTF-8\"?><plist version=\"1.0\"><dict>"
                    + "<key>identifier</key><string>" + A_TAG + "</string>"
                    + "<key>name</key><string>" + A_NAME + "</string></dict></plist>";

    private OpenTagViewerDatabase db;
    private ActivityScenario<MyDevicesListActivity> scenario;
    private File written;

    @Before
    public void seedATagWorthSharing() {
        final Context context = getInstrumentation().getTargetContext();
        this.db = OpenTagViewerDatabase.getInstance(context);
        this.forgetIt();

        final long importId = this.db.importDao().insert(Import.builder()
                .version("0.0.2").importedAt(1_700_000_000_000L).exportedAt(1_699_000_000_000L)
                .sourceUser(A_TEST_USER).exportedVia("OpenTagViewer.wizard:test").build());

        this.db.ownedBeaconDao().insertAll(OwnedBeacon.builder()
                .id(A_TAG).importId(importId).content(A_PLIST)
                .version("0.0.2").fromAccount(false).isRemoved(false).build());

        this.db.beaconNamingRecordDao().insertAll(BeaconNamingRecord.builder()
                .id(A_TAG).importId(importId).content(A_NAMING_RECORD)
                .version("0.0.2").isRemoved(false).build());

        this.written = new File(context.getCacheDir(), "share-walkthrough.zip");

        Intents.init();
        intending(anyOf(
                hasAction(Intent.ACTION_CREATE_DOCUMENT),
                hasComponent(ErrorReportActivity.class.getName())))
                .respondWith(new ActivityResult(
                        Activity.RESULT_OK, new Intent().setData(Uri.fromFile(this.written))));

        // A real bundle would take a Python call; the point here is the screens, and a fake keeps
        // the pacing honest rather than showing somebody an interpreter warming up.
        AppDependencies.replaceBundleBuilder((accessories, via, user, at) -> {
            final Map<String, byte[]> entries = new LinkedHashMap<>();
            entries.put("OPENTAGVIEWER.yml", ("via: " + via + "\n").getBytes());
            entries.put("OwnedBeacons/" + A_TAG + ".plist", A_PLIST.getBytes());
            return new BundleBuilder.Built(entries, null);
        });
    }

    @After
    public void putEverythingBack() {
        if (this.scenario != null) {
            this.scenario.close();
        }
        Intents.release();
        AppDependencies.reset();
        this.forgetIt();
        if (this.written != null) {
            this.written.delete();
        }
    }

    private void forgetIt() {
        this.db.ownedBeaconDao().delete(OwnedBeacon.builder().id(A_TAG).build());
        this.db.beaconNamingRecordDao().delete(BeaconNamingRecord.builder().id(A_TAG).build());
        for (final Import stale : this.db.importDao().getImportsFromUser(A_TEST_USER)) {
            this.db.importDao().delete(stale);
        }
    }

    /**
     * <b>The whole thing, in the order somebody does it.</b>
     *
     * <p>Find the tag, pick it, choose Export Tags, save the file, and read the code that opens
     * it - then copy the code, because the file and the code have to travel separately and the
     * app should not make transcribing twelve characters a person's problem.
     */
    @Test
    public void givingSomebodyATagIsFourTapsAndACode() {
        this.scenario = ActivityScenario.launch(MyDevicesListActivity.class);

        // 1. The tag, in the list it lives in.
        Eventually.check(() -> onView(withText(A_NAME)).check(matches(isDisplayed())));
        Shot.ofTheScreen("sharing_a_tag-the_list");
        TestPace.afterAStep();

        // 2. Long press picks it. Sharing is per tag, deliberately: handing over a household's
        //    whole set and lending one tag are different acts, and exported keys cannot be taken
        //    back afterwards.
        onView(withText(A_NAME)).perform(longClick());
        Eventually.check(() -> onView(withId(R.id.selection_menu_button))
                .check(matches(isDisplayed())));
        Shot.ofTheScreen("sharing_a_tag-one_selected");
        TestPace.afterAStep();

        // 3. The menu, where Export Tags sat listed and disabled for two releases.
        onView(withId(R.id.selection_menu_button)).perform(click());
        Eventually.check(() -> onView(withText(R.string.export_tags))
                .check(matches(isDisplayed())));
        Shot.ofTheScreen("sharing_a_tag-the_menu");
        TestPace.afterAStep();

        // 4. Which asks where to put the file. Stubbed here; on a phone this is the document
        //    picker, so the file lands somewhere the sender can find and attach.
        onView(withText(R.string.export_tags)).perform(click());
        intended(hasAction(Intent.ACTION_CREATE_DOCUMENT));
        TestPace.afterAStep();

        // 5. And then the code, which is the part that matters. It exists here and nowhere else:
        //    the zip keeps only what AES needs to check it, and the app forgets it on dismissal.
        Eventually.check(() -> onView(withId(R.id.exported_bundle_code))
                .check(matches(isDisplayed())));
        onView(withId(R.id.exported_bundle_code)).inRoot(isDialog())
                .check(matches(withText(containsString("-"))));
        Shot.ofTheScreen("sharing_a_tag-the_code");
        TestPace.afterAStep();

        // 6. It says the two things that decide whether this is safe: the code cannot be shown
        //    again, and it must not travel with the file.
        onView(withId(R.id.exported_bundle_not_recoverable)).inRoot(isDialog())
                .check(matches(isDisplayed()));
        onView(withId(R.id.exported_bundle_body)).inRoot(isDialog())
                .check(matches(withText(containsString("separately"))));
        TestPace.afterAStep();

        // 7. Copy, so nobody has to read twelve characters aloud.
        onView(withText(R.string.exported_tags_copy_code)).inRoot(isDialog()).perform(click());
        assertTrue("the code did not reach the clipboard", theClipboardHasACode());
        Shot.ofTheScreen("sharing_a_tag-copied");
        TestPace.afterAStep();

        // 8. And the dialog stays up after copying, rather than taking the code off screen at the
        //    moment somebody is checking they got it.
        onView(withId(R.id.exported_bundle_code)).inRoot(isDialog())
                .check(matches(isDisplayed()));
        TestPace.afterAStep();
    }

    /** Whether what was copied looks like one of ours: grouped, and from the right alphabet. */
    private static boolean theClipboardHasACode() {
        final CharSequence[] held = new CharSequence[1];
        getInstrumentation().runOnMainSync(() -> {
            final ClipboardManager clipboard = getInstrumentation().getTargetContext()
                    .getSystemService(ClipboardManager.class);
            final android.content.ClipData clip =
                    clipboard == null ? null : clipboard.getPrimaryClip();
            held[0] = clip == null || clip.getItemCount() == 0
                    ? null : clip.getItemAt(0).getText();
        });

        return held[0] != null && held[0].toString().matches("[0-9A-HJKMNP-TV-Z]{4}(-[0-9A-HJKMNP-TV-Z]{4}){2}");
    }

    /** Guards the demo against quietly showing nothing: an empty dialog would still "pass" above. */
    @Test
    public void zthedialogIsNotEmpty() {
        this.scenario = ActivityScenario.launch(MyDevicesListActivity.class);

        Eventually.check(() -> onView(withText(A_NAME)).check(matches(isDisplayed())));
        onView(withText(A_NAME)).perform(longClick());
        Eventually.check(() -> onView(withId(R.id.selection_menu_button))
                .check(matches(isDisplayed())));
        onView(withId(R.id.selection_menu_button)).perform(click());
        Eventually.check(() -> onView(withText(R.string.export_tags))
                .check(matches(isDisplayed())));
        onView(withText(R.string.export_tags)).perform(click());

        Eventually.check(() -> onView(withId(R.id.exported_bundle_code))
                .check(matches(not(withText("")))));
    }
}
