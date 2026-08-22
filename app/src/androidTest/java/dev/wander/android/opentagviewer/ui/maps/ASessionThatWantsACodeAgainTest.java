package dev.wander.android.opentagviewer.ui.maps;

import static androidx.test.espresso.Espresso.onView;
import static androidx.test.espresso.action.ViewActions.click;
import static androidx.test.espresso.action.ViewActions.replaceText;
import static androidx.test.espresso.assertion.ViewAssertions.matches;
import static androidx.test.espresso.matcher.ViewMatchers.isDisplayed;
import static androidx.test.espresso.matcher.ViewMatchers.withId;
import static androidx.test.platform.app.InstrumentationRegistry.getInstrumentation;
import static org.hamcrest.Matchers.not;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.filters.LargeTest;

import org.junit.After;
import org.junit.Test;
import org.junit.runner.RunWith;

import dev.wander.android.opentagviewer.Eventually;
import dev.wander.android.opentagviewer.R;
import dev.wander.android.opentagviewer.db.datastore.UserAuthDataStore;
import dev.wander.android.opentagviewer.db.repo.UserAuthRepository;
import dev.wander.android.opentagviewer.util.android.AppCryptographyUtil;

/**
 * A working session that Apple decides needs a verification code again.
 *
 * <p><b>This was permanent, and completely silent.</b> An account restores as logged in, works,
 * and at some later point Apple moves it to {@code REQUIRE_2FA}. From then on every fetch fails
 * its state check before a request leaves the phone. The app logged it and waited to retry -
 * under a comment saying the error "just happens every now and then" - which is true of most
 * fetch errors and wrong about this one, because the state does not heal itself. Users saw pins
 * that stopped updating and nothing anywhere explaining why. It is the issue people kept
 * reporting and nobody could reproduce from the description.
 *
 * <p>What is asserted here is the <b>rescue</b>, not the dialog: whether the session is usable
 * again afterwards, and whether the login data is really gone when the app gives up. A test that
 * only checked which views appeared would pass against an overlay that did nothing.
 */
@LargeTest
@RunWith(AndroidJUnit4.class)
public class ASessionThatWantsACodeAgainTest {

    /** What the double accepts - see {@code apple_test_double.THE_RIGHT_CODE}. */
    private static final String RIGHT = "123456";
    private static final String WRONG = "000000";

    private final AMapWithTagsOnIt theMap = new AMapWithTagsOnIt();

    @After
    public void putItBack() {
        this.theMap.putItBack();
    }

    /**
     * <b>A correct code puts the session back, and the map carries on.</b>
     *
     * <p>The whole point: six digits instead of finding an Apple password. Asserted against the
     * account itself rather than against the overlay disappearing, because an overlay that hid
     * itself without rescuing anything would leave exactly the bug this replaces.
     */
    @Test
    public void therightCodeRescuesTheSession() {
        this.theMap.seed("Wallet");
        this.theMap.theSessionGoesStale();
        this.theMap.open();
        this.waitForTheOverlay();

        this.type(RIGHT);

        Eventually.check(() -> assertTrue(
                "the code was accepted, so the session must be usable again",
                this.theMap.theSessionIsUsableAgain()));

        Eventually.check(() -> onView(withId(R.id.two_factor_again_overlay))
                .check(matches(not(isDisplayed()))));
    }

    /**
     * <b>A wrong code is a retry, not a sign-out.</b>
     *
     * <p>The boundary that matters most: somebody who fat-fingers one digit must not lose their
     * session. The overlay stays, the login data survives, and Apple was genuinely asked - so
     * this cannot pass against an overlay that merely re-rendered a label.
     */
    @Test
    public void onewrongCodeJustAsksAgain() {
        this.theMap.seed("Wallet");
        this.theMap.theSessionGoesStale();
        this.theMap.open();
        this.waitForTheOverlay();

        this.type(WRONG);

        Eventually.check(() -> assertEquals(
                "the code should have been sent to Apple and rejected",
                1, this.theMap.codesSubmitted()));

        onView(withId(R.id.two_factor_again_overlay)).check(matches(isDisplayed()));
        assertTrue("one wrong digit must not cost somebody their session", this.stillSignedIn());
    }

    /**
     * <b>Three rejections and the app stops pretending.</b>
     *
     * <p>More typing will not rescue this session, so the login data goes and the user is sent
     * to sign in properly. Asserted on the stored login rather than on the screen, because the
     * screen changing without the data going would leave a session that comes back on the next
     * launch, still broken.
     */
    @Test
    public void threewrongCodesSignsThemOut() {
        this.theMap.seed("Wallet");
        this.theMap.theSessionGoesStale();
        this.theMap.open();
        this.waitForTheOverlay();

        for (int attempt = 1; attempt <= 3; attempt++) {
            final int sent = attempt;
            this.type(WRONG);
            Eventually.check(() -> assertEquals(sent, this.theMap.codesSubmitted()));
        }

        Eventually.check(() -> assertTrue(
                "after the last attempt the stored login must be gone, or the broken session"
                        + " simply returns on the next launch",
                !this.stillSignedIn()));
    }

    /**
     * <b>Signing out is offered, and it really signs out.</b>
     *
     * <p>The other exit. There is deliberately no third one back to the map: with the session in
     * this state nothing can be located at all, so a dismissed overlay would put somebody back
     * on a screen that has quietly stopped working.
     */
    @Test
    public void signingOutFromTheOverlayClearsTheLogin() {
        this.theMap.seed("Wallet");
        this.theMap.theSessionGoesStale();
        this.theMap.open();
        this.waitForTheOverlay();

        onView(withId(R.id.two_factor_again_sign_out)).perform(click());

        Eventually.check(() -> assertTrue("signing out must clear the stored login",
                !this.stillSignedIn()));
        assertEquals("no code should have been sent", 0, this.theMap.codesSubmitted());
    }

    // --- helpers ------------------------------------------------------------------------------

    /**
     * The overlay appears off the back of a failed fetch, which is driven by the refresh policy
     * rather than by anything this test does - so it is waited for rather than triggered.
     */
    private void waitForTheOverlay() {
        Eventually.check(() -> onView(withId(R.id.two_factor_again_overlay))
                .check(matches(isDisplayed())));
    }

    /**
     * Fill the six boxes.
     *
     * <p>{@code replaceText}, not {@code typeText}: the boxes move focus as they fill, and
     * Espresso's per-character typing fails the moment the field it started on stops being
     * focused. Filling the first box with the whole code is also what a paste does, which is how
     * most people enter these.
     */
    private void type(final String code) {
        onView(withId(R.id.twofactorauth_textinput_1)).perform(replaceText(code));
    }

    private boolean stillSignedIn() {
        return new UserAuthRepository(
                UserAuthDataStore.getInstance(getInstrumentation().getTargetContext()),
                new AppCryptographyUtil())
                .getUserAuth()
                .blockingFirst()
                .isPresent();
    }
}
