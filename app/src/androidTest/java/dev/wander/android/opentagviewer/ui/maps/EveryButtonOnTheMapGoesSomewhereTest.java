package dev.wander.android.opentagviewer.ui.maps;

import static androidx.test.espresso.Espresso.onView;
import static androidx.test.espresso.action.ViewActions.click;
import static androidx.test.espresso.action.ViewActions.scrollTo;
import static androidx.test.espresso.assertion.ViewAssertions.matches;
import static androidx.test.espresso.intent.Intents.intended;
import static androidx.test.espresso.intent.Intents.intending;
import static androidx.test.espresso.intent.matcher.IntentMatchers.hasAction;
import static androidx.test.espresso.intent.matcher.IntentMatchers.hasComponent;
import static androidx.test.espresso.intent.matcher.IntentMatchers.hasDataString;
import static androidx.test.espresso.intent.matcher.IntentMatchers.hasType;
import static androidx.test.espresso.matcher.RootMatchers.isPlatformPopup;
import static androidx.test.espresso.matcher.ViewMatchers.isDisplayed;
import static androidx.test.espresso.matcher.ViewMatchers.withId;
import static androidx.test.espresso.matcher.ViewMatchers.withText;
import static androidx.test.platform.app.InstrumentationRegistry.getInstrumentation;
import static org.hamcrest.Matchers.allOf;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.startsWith;
import static org.junit.Assert.assertTrue;

import android.app.Activity;
import android.app.Instrumentation.ActivityResult;
import android.content.Intent;

import androidx.test.espresso.intent.Intents;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.filters.LargeTest;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import dev.wander.android.opentagviewer.DeviceInfoActivity;
import dev.wander.android.opentagviewer.Eventually;
import dev.wander.android.opentagviewer.HistoryViewActivity;
import dev.wander.android.opentagviewer.InformationActivity;
import dev.wander.android.opentagviewer.MapsActivity;
import dev.wander.android.opentagviewer.MyDevicesListActivity;
import dev.wander.android.opentagviewer.R;
import dev.wander.android.opentagviewer.SettingsActivity;

/**
 * Every control on the map goes where it says it goes.
 *
 * <p><b>The cheapest useful test in this suite, and the one with no equivalent before it.</b>
 * Nothing here checks that a destination screen is correct - each has its own tests. What it
 * checks is that the wire between the control and the destination still exists, which is the
 * thing that breaks silently: a renamed id, a listener attached to the wrong view, an
 * {@code android:onClick} pointing at a method somebody moved. None of those fail to compile,
 * and all of them present as a button that does nothing.
 *
 * <p><b>Intents are stubbed at the door.</b> Each destination is answered rather than started,
 * so this stays a test of the map and does not become a slow tour of the whole app - and the
 * external maps application, which would otherwise really open, never does.
 */
@LargeTest
@RunWith(AndroidJUnit4.class)
public class EveryButtonOnTheMapGoesSomewhereTest {

    private final AMapWithTagsOnIt theMap = new AMapWithTagsOnIt();

    @Before
    public void openTheMapWithTwoTagsOnIt() {
        // Before the activity starts, or the first intents it sends are missed.
        Intents.init();

        // **Everything except the map itself.** `anyIntent()` reads like the obvious way to say
        // "do not really navigate anywhere", and it also stubs the intent
        // `ActivityScenario.launch` uses to start MapsActivity - so the map is answered at the
        // door, never runs, and every test here waits for a screen that was never allowed to
        // exist. It presents as a hang rather than a failure: six minutes per test, with
        // `Stubbing intent ... MapsActivity` as the only clue in the log.
        intending(not(hasComponent(MapsActivity.class.getName())))
                .respondWith(new ActivityResult(Activity.RESULT_OK, null));

        this.theMap.seed("Bike", "Keys").open();

        Eventually.check(() -> assertTrue("the map never became ready",
                this.theMap.map().isReady()));
    }

    @After
    public void putEverythingBack() {
        this.theMap.putItBack();
        Intents.release();
    }

    // ------------------------------------------------------------------ the overflow menu

    @Test
    public void themenuOpensMyDevices() {
        this.pickFromTheOverflow(R.string.my_devices);

        Eventually.check(() -> intended(hasComponent(MyDevicesListActivity.class.getName())));
    }

    @Test
    public void themenuOpensSettings() {
        this.pickFromTheOverflow(R.string.settings);

        Eventually.check(() -> intended(hasComponent(SettingsActivity.class.getName())));
    }

    @Test
    public void themenuOpensTheInformationPage() {
        this.pickFromTheOverflow(R.string.information);

        Eventually.check(() -> intended(hasComponent(InformationActivity.class.getName())));
    }

    /**
     * Import opens the system file picker, not a screen of this app's own.
     *
     * <p>Asserted on the action rather than a component for that reason: which application
     * answers it is the platform's business and differs by device.
     *
     * <p><b>{@code GET_CONTENT}, not {@code OPEN_DOCUMENT}</b>, which is worth stating because
     * the two look interchangeable and are not. {@code GET_CONTENT} asks for a copy of a file
     * and is right here - the zip is read once at import and never again - while
     * {@code OPEN_DOCUMENT} asks for a lasting handle to the original, which this app has no use
     * for and would be asking the user to grant more than it needs. The log export next door
     * uses {@code CREATE_DOCUMENT} for the same sort of reason, in the other direction.
     */
    @Test
    public void themenuOpensTheFilePickerToImportAZip() {
        this.pickFromTheOverflow(R.string.do_import);

        Eventually.check(() -> intended(allOf(
                hasAction(Intent.ACTION_GET_CONTENT),
                hasType("application/zip"))));
    }

    // ------------------------------------------------------------------ the tag card

    @Test
    public void thehistoryButtonOnACardOpensThatTagsHistory() {
        this.waitForTheCards();
        onView(theFirstCard(R.id.device_history_button_container)).perform(scrollTo(), click());

        Eventually.check(() -> intended(hasComponent(HistoryViewActivity.class.getName())));
    }

    @Test
    public void themoreButtonOnACardOpensThatTagsPage() {
        this.waitForTheCards();
        onView(theFirstCard(R.id.device_more_button_container)).perform(scrollTo(), click());

        Eventually.check(() -> intended(hasComponent(DeviceInfoActivity.class.getName())));
    }

    /**
     * <b>Navigate-to hands the tag's position to whatever opens maps.</b>
     *
     * <p>This is the control that was dead on every current device. It named
     * {@code com.google.android.apps.maps} explicitly, and from Android 11 an application cannot
     * see a package it has not declared in {@code <queries>} - so {@code resolveActivity}
     * returned null whether or not Google Maps was installed, and the button logged and did
     * nothing.
     *
     * <p>Two things are asserted, and the second is the point: that an intent goes out at all,
     * and that it names <b>no package</b>. Pinning the absence is what stops the hard-coding
     * coming back - it reads like a reasonable thing to add, and it would send everyone who
     * chose a different maps application into a dead end again.
     */
    @Test
    public void navigateToOpensWhicheverMapsApplicationTheUserHas() {
        // **Waited on for a reason that is not obvious from the button.** It is on screen from
        // the moment the map opens, but onClickNavigateTo returns early when there are no tag
        // cards yet - so pressing it too soon does nothing at all, and the failure is "no intent
        // was sent", which reads as the navigation being broken rather than as being early.
        this.waitForTheCards();

        Eventually.check(() -> onView(withId(R.id.button_navigate_to))
                .check(matches(isDisplayed())));
        onView(withId(R.id.button_navigate_to)).perform(click());

        Eventually.check(() -> intended(allOf(
                hasAction(Intent.ACTION_VIEW),
                hasDataString(startsWith("geo:")))));

        assertTrue("navigate-to named a package again, so every maps application except that"
                        + " one is shut out - and on Android 11 and up package visibility hides"
                        + " the named one too, which makes the button do nothing at all",
                this.theOutgoingMapsIntentNamesNoPackage());
    }

    private boolean theOutgoingMapsIntentNamesNoPackage() {
        return Intents.getIntents().stream()
                .filter(sent -> sent.getData() != null
                        && String.valueOf(sent.getData()).startsWith("geo:"))
                .allMatch(sent -> sent.getPackage() == null);
    }

    // ------------------------------------------------------------------ helpers

    /**
     * Wait until the tag cards exist.
     *
     * <p><b>The overflow button is there the instant the map opens; the cards are not.</b> They
     * are built after the beacons are read, parsed and drawn, so reaching for one straight away
     * races that - and Espresso reports it as "no views in hierarchy matching", which reads as
     * the card being gone rather than as not having arrived yet.
     */
    private void waitForTheCards() {
        Eventually.check(() -> assertTrue("the tag cards were never built",
                this.theMap.cards().size() >= 2));
    }

    /** A control inside the first tag's card - there is more than one card on screen. */
    private static org.hamcrest.Matcher<android.view.View> theFirstCard(final int controlId) {
        return allOf(withId(controlId), androidx.test.espresso.matcher.ViewMatchers
                .isDescendantOfA(allOf(withId(R.id.tag_item_container),
                        androidx.test.espresso.matcher.ViewMatchers
                                .hasDescendant(withText("Bike")))));
    }

    /**
     * Open the overflow and choose an item by its label.
     *
     * <p>Not wrapped in {@code Eventually}: a {@code PopupMenu} is shown on the main thread
     * inside the click handler, so it is already up when the click returns. Asking for a
     * platform-popup root that is not there yet is the <i>expensive</i> question - Espresso's
     * root picker retries internally for seconds before admitting it - so a retry loop here
     * makes the cheap case the slow one.
     */
    private void pickFromTheOverflow(final int label) {
        Eventually.check(() -> onView(withId(R.id.button_more_settings))
                .check(matches(isDisplayed())));
        onView(withId(R.id.button_more_settings)).perform(click());

        onView(withText(label)).inRoot(isPlatformPopup()).perform(click());
    }
}
