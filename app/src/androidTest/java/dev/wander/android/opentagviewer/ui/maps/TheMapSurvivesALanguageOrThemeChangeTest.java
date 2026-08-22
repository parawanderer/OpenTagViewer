package dev.wander.android.opentagviewer.ui.maps;

import static androidx.test.platform.app.InstrumentationRegistry.getInstrumentation;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import androidx.appcompat.app.AppCompatDelegate;
import androidx.core.os.LocaleListCompat;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.filters.LargeTest;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import dev.wander.android.opentagviewer.Eventually;

/**
 * Changing the language or the theme does not lose the map.
 *
 * <p><b>Both of these have broken this app before, and both break it the same way.</b>
 * {@code AppCompatDelegate.setDefaultNightMode} and {@code setApplicationLocales} do not repaint
 * anything - they <b>relaunch every activity in the process</b>. Whatever the map was holding in
 * fields is gone, {@code onCreate} runs again from nothing, and anything that only ever ran on
 * the first launch does not run.
 *
 * <p>It is a nasty class of failure because the code compiles, the setting works, and the screen
 * comes back looking plausible - just without its pins, or its cards, or with a fetch that never
 * happens again. Nobody exercises it either: a person changes their language once, on a device
 * they have already set up, which is the state hardest to reach deliberately.
 *
 * <p><b>What is asserted is what the user would notice</b>: the tags are still on the map
 * afterwards. Not that the theme changed - that is AppCompat's job and it has its own tests -
 * but that this app is still standing on the other side of it.
 *
 * <p><b>The pins are counted through the provider, not read off the screen.</b> A relaunch
 * builds a new activity against the same substituted provider, so what is being asked is "did
 * the rebuilt screen draw its tags again", which is precisely the thing that stops happening.
 */
@LargeTest
@RunWith(AndroidJUnit4.class)
public class TheMapSurvivesALanguageOrThemeChangeTest {

    private final AMapWithTagsOnIt theMap = new AMapWithTagsOnIt();

    private int nightModeBefore;
    private LocaleListCompat localesBefore;

    @Before
    public void openTheMapWithTwoTagsOnIt() {
        this.nightModeBefore = AppCompatDelegate.getDefaultNightMode();
        this.localesBefore = AppCompatDelegate.getApplicationLocales();

        this.theMap.seed("Bike", "Keys").open();

        Eventually.check(() -> assertTrue("the map never became ready",
                this.theMap.map().isReady()));
        Eventually.check(() -> assertEquals("the tags were never drawn to begin with",
                2, this.theMap.map().markerCount()));
    }

    @After
    public void putEverythingBack() {
        // Restored before the fixture, because both of these relaunch activities and the
        // fixture's teardown wants a settled process to close its scenario in.
        getInstrumentation().runOnMainSync(() -> {
            AppCompatDelegate.setDefaultNightMode(this.nightModeBefore);
            AppCompatDelegate.setApplicationLocales(this.localesBefore);
        });
        getInstrumentation().waitForIdleSync();

        this.theMap.putItBack();
    }

    /** <b>Switching to dark and the tags are still there.</b> */
    @Test
    public void thetagsAreStillOnTheMapAfterSwitchingToDark() {
        this.switchNightModeTo(AppCompatDelegate.MODE_NIGHT_YES);

        Eventually.check(() -> assertEquals(
                "the map came back from a theme change without its pins",
                2, this.theMap.map().markerCount()));
    }

    /**
     * <b>And after switching back, which is a second relaunch on top of the first.</b>
     *
     * <p>Worth its own case: the second relaunch starts from a process that has already been
     * through one, so anything that survived the first by luck - a static left initialised, a
     * flag not reset - has a second chance to be caught.
     */
    @Test
    public void andafterSwitchingBackToLight() {
        this.switchNightModeTo(AppCompatDelegate.MODE_NIGHT_YES);
        Eventually.check(() -> assertEquals(2, this.theMap.map().markerCount()));

        this.switchNightModeTo(AppCompatDelegate.MODE_NIGHT_NO);

        Eventually.check(() -> assertEquals(
                "the map lost its pins on the way back to the light theme",
                2, this.theMap.map().markerCount()));
    }

    /**
     * <b>And changing the language, which relaunches for a different reason.</b>
     *
     * <p>A language change also re-resolves every string and drawable through a new
     * configuration, so it catches things a theme change does not - a resource looked up once
     * and cached in a static, for instance.
     */
    @Test
    public void thetagsAreStillOnTheMapAfterChangingLanguage() {
        this.switchLanguageTo("de");

        Eventually.check(() -> assertEquals(
                "the map came back from a language change without its pins",
                2, this.theMap.map().markerCount()));
    }

    /** And the cards come back too, not only the pins. */
    @Test
    public void thetagCardsAreRebuiltAfterALanguageChange() {
        this.switchLanguageTo("de");

        Eventually.check(() -> assertTrue(
                "the tag cards were not rebuilt after the screen was relaunched",
                this.theMap.cards().size() >= 2));
    }

    // ------------------------------------------------------------------ the two switches

    /**
     * Change the theme the way the app does, and wait for the relaunch to settle.
     *
     * <p>On the main thread: {@code setDefaultNightMode} tears down and rebuilds activities, and
     * doing that from the test thread races the rebuild it starts.
     */
    private void switchNightModeTo(final int mode) {
        getInstrumentation().runOnMainSync(() -> AppCompatDelegate.setDefaultNightMode(mode));
        getInstrumentation().waitForIdleSync();
    }

    private void switchLanguageTo(final String languageTag) {
        getInstrumentation().runOnMainSync(() -> AppCompatDelegate.setApplicationLocales(
                LocaleListCompat.forLanguageTags(languageTag)));
        getInstrumentation().waitForIdleSync();
    }
}
