package dev.wander.android.opentagviewer;

import static androidx.test.espresso.Espresso.onView;
import static androidx.test.espresso.action.ViewActions.click;
import static androidx.test.espresso.action.ViewActions.replaceText;
import static androidx.test.espresso.assertion.ViewAssertions.matches;
import static androidx.test.espresso.matcher.ViewMatchers.isDisplayed;
import static androidx.test.espresso.matcher.ViewMatchers.isEnabled;
import static androidx.test.espresso.matcher.ViewMatchers.withId;
import static androidx.test.espresso.matcher.ViewMatchers.withText;
import static androidx.test.platform.app.InstrumentationRegistry.getInstrumentation;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.app.Activity;
import android.app.Instrumentation.ActivityResult;
import android.os.SystemClock;

import androidx.test.core.app.ActivityScenario;
import androidx.test.espresso.intent.Intents;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.filters.LargeTest;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.List;

import dev.wander.android.opentagviewer.anisette.FakeAnisetteSource;
import dev.wander.android.opentagviewer.db.datastore.UserAuthDataStore;
import dev.wander.android.opentagviewer.db.repo.UserAuthRepository;
import dev.wander.android.opentagviewer.python.AppDependencies;
import dev.wander.android.opentagviewer.python.FakeAppleAuthService;
import dev.wander.android.opentagviewer.python.TermsDocument;
import dev.wander.android.opentagviewer.util.android.AppCryptographyUtil;

import static androidx.test.espresso.intent.Intents.intending;
import static androidx.test.espresso.intent.matcher.IntentMatchers.hasComponent;

/**
 * Agreeing to Apple's terms, which is a thing no test could reach before.
 *
 * <p>The condition needs an Apple account that Apple happens to be waiting on terms for - not
 * something anybody can produce on demand, and not something worth producing on a real account
 * even if they could, since agreeing is a legal act with no undo. So the whole path shipped in
 * the desktop CLI with tests and in the app with none.
 *
 * <p><b>The failure modes here are not "it crashed".</b> They are a screen that shows part of a
 * contract, a button that records agreement to a document the user was not looking at, and a
 * session stored in a state that fails every later fetch. Those are what these assert.
 */
@LargeTest
@RunWith(AndroidJUnit4.class)
public class AcceptingTermsFlowTest {

    private static final String EMAIL = "someone@example.com";
    private static final String PASSWORD = "hunter2";

    /** Long enough that a screen quietly truncating it would show. */
    private static final String ICLOUD_TEXT =
            "ICLOUD TERMS OF SERVICE\n-----------------------\n\n"
                    + "This agreement covers your use of the service, and continues for several"
                    + " paragraphs in the way that these documents do.\n\n"
                    + "  - You must be of legal age.\n"
                    + "  - THE VERY LAST LINE, which is how truncation shows up.";

    private static final String MEDIA_TEXT =
            "MEDIA SERVICES TERMS\n--------------------\n\nA second document, quite different.";

    private static final TermsDocument ICLOUD =
            new TermsDocument("iCloud", ICLOUD_TEXT, true);
    private static final TermsDocument MEDIA =
            new TermsDocument("iCloudTerms2", MEDIA_TEXT, true);

    private FakeAppleAuthService apple;
    private ActivityScenario<AppleLoginActivity> scenario;
    private DeviceStateGuard deviceState;

    @Before
    public void replaceApple() {
        this.deviceState = DeviceStateGuard.capture(getInstrumentation().getTargetContext());
        signEverybodyOut();

        AppDependencies.replaceAnisette(settings -> FakeAnisetteSource.ready());

        Intents.init();
        intending(hasComponent(MapsActivity.class.getName()))
                .respondWith(new ActivityResult(Activity.RESULT_OK, null));
    }

    @After
    public void putTheRealOnesBack() {
        if (this.scenario != null) {
            this.scenario.close();
        }
        Intents.release();
        AppDependencies.reset();
        getInstrumentation().waitForIdleSync();
        signEverybodyOut();
        this.deviceState.restore();
    }

    /** Sign in with the given fake Apple, reaching whatever it is set up to do. */
    private void signIn(final FakeAppleAuthService withApple) {
        this.apple = withApple;
        AppDependencies.replaceAuthService(this.apple);

        this.scenario = ActivityScenario.launch(AppleLoginActivity.class);

        Eventually.check(() -> onView(withId(R.id.email_or_phone_input_field))
                .check(matches(isDisplayed())));

        // One action per perform: the sign-in completes on the button press, and a second
        // action in the same call would run against a screen that has already moved on.
        TestPace.afterAStep();

        onView(withId(R.id.email_or_phone_input_field)).perform(replaceText(EMAIL));
        TestPace.afterAStep();
        onView(withId(R.id.password_input_field)).perform(replaceText(PASSWORD));
        TestPace.afterAStep();

        Eventually.perform("the sign in button", () -> this.apple.timesCalled("login") > 0,
                () -> onView(withId(R.id.login_button_main)).perform(click()));

        // The terms arriving is the thing worth watching, and at full speed it is a flicker.
        TestPace.afterAStep();
    }

    private void agree() {
        // Long enough for somebody watching to read some of the document before it is agreed to,
        // which is the whole point of showing this flow to a person.
        TestPace.afterAStep();

        final long before = this.apple.timesCalled("acceptTerms");
        Eventually.perform("the agree button",
                () -> this.apple.timesCalled("acceptTerms") > before,
                () -> onView(withId(R.id.login_terms_agree_button)).perform(click()));

        TestPace.afterAStep();
    }

    @Test
    public void thetermsAreShownWhenAppleIsWaitingOnThem() {
        this.signIn(FakeAppleAuthService.wantsTermsAccepted(ICLOUD));

        Eventually.check(() -> onView(withId(R.id.login_terms_container))
                .check(matches(isDisplayed())));
        Eventually.check(() -> onView(withId(R.id.login_terms_text))
                .check(matches(withText(containsString("ICLOUD TERMS OF SERVICE")))));
    }

    /**
     * The whole document, not the first screenful of it.
     *
     * <p>What is displayed is what gets agreed to, so a screen that silently drops the end of a
     * contract is the worst bug available here - and it would look completely fine.
     */
    @Test
    public void thedocumentIsNotTruncated() {
        this.signIn(FakeAppleAuthService.wantsTermsAccepted(ICLOUD));

        Eventually.check(() -> onView(withId(R.id.login_terms_text))
                .check(matches(withText(containsString("THE VERY LAST LINE")))));
        Eventually.check(() -> onView(withId(R.id.login_terms_text))
                .check(matches(withText(ICLOUD_TEXT))));
    }

    @Test
    public void agreeingToTheOnlyDocumentSignsIn() {
        this.signIn(FakeAppleAuthService.wantsTermsAccepted(ICLOUD));
        Eventually.check(() -> onView(withId(R.id.login_terms_container))
                .check(matches(isDisplayed())));

        this.agree();

        Eventually.check(() -> assertEquals(List.of("iCloud"), this.apple.acceptedPageIds()));
        // Storing the session is the last step of signing in, and it is the fake's
        // retrieveAuthData that produces it.
        Eventually.check(() -> assertTrue("the session was never stored, so signing in did not"
                + " actually complete", this.apple.timesCalled("retrieveAuthData") > 0));
    }

    /**
     * Two documents are two screens, agreed to in order.
     *
     * <p>The bug this rules out is a screen that sends agreement for the first document twice,
     * or skips to signing in after one - both of which record agreement to something the user
     * never saw.
     */
    @Test
    public void everyDocumentIsShownAndAgreedInTurn() {
        this.signIn(FakeAppleAuthService.wantsTermsAccepted(ICLOUD, MEDIA));

        Eventually.check(() -> onView(withId(R.id.login_terms_text))
                .check(matches(withText(containsString("ICLOUD TERMS OF SERVICE")))));

        this.agree();

        Eventually.check(() -> onView(withId(R.id.login_terms_text))
                .check(matches(withText(containsString("MEDIA SERVICES TERMS")))));
        // Not signed in yet - there was another document to read.
        assertEquals(0, this.apple.timesCalled("retrieveAuthData"));

        this.agree();

        Eventually.check(() -> assertEquals(
                List.of("iCloud", "iCloudTerms2"), this.apple.acceptedPageIds()));
        Eventually.check(() -> assertTrue(this.apple.timesCalled("retrieveAuthData") > 0));
    }

    /**
     * Leaving without agreeing.
     *
     * <p>Asserted in the real activity, where the button is inflated and laid out the way it
     * actually ships - a layout test cannot tell you that, because a hand-laid-out button
     * outside a window does not finish placing its icon.
     */
    @Test
    public void thebackButtonReturnsToSigningInWithoutAgreeing() {
        this.signIn(FakeAppleAuthService.wantsTermsAccepted(ICLOUD));
        Eventually.check(() -> onView(withId(R.id.login_terms_container))
                .check(matches(isDisplayed())));

        Eventually.check(() -> onView(withId(R.id.login_terms_back_button))
                .check(matches(isDisplayed())));

        // Paced so somebody being shown this can see the terms screen before it leaves.
        TestPace.afterAStep();

        Eventually.perform("the back button",
                () -> !isShown(R.id.login_terms_container),
                () -> onView(withId(R.id.login_terms_back_button)).perform(click()));

        TestPace.afterAStep();

        Eventually.check(() -> onView(withId(R.id.email_or_phone_input_field))
                .check(matches(isDisplayed())));

        TestPace.afterAStep();
        assertEquals("nothing may be sent by walking away",
                0, this.apple.acceptedPageIds().size());
    }

    /**
     * The system back button, which is not the one in the layout.
     *
     * <p>The whole sign-in screen swallows back deliberately - {@code handleOnBackPressed} does
     * nothing at all - so this asserts that the terms step inherits that rather than doing
     * something of its own. The three ways it could go wrong all matter: crashing, quietly
     * recording agreement on the way out, or skipping to the next document as though the first
     * had been agreed to.
     */
    @Test
    public void thesystemBackButtonNeitherCrashesNorAgrees() {
        this.signIn(FakeAppleAuthService.wantsTermsAccepted(ICLOUD, MEDIA));
        Eventually.check(() -> onView(withId(R.id.login_terms_container))
                .check(matches(isDisplayed())));

        androidx.test.espresso.Espresso.pressBack();

        // Still here, still on the first document, and nothing sent.
        Eventually.check(() -> onView(withId(R.id.login_terms_container))
                .check(matches(isDisplayed())));
        Eventually.check(() -> onView(withId(R.id.login_terms_text))
                .check(matches(withText(containsString("ICLOUD TERMS OF SERVICE")))));

        assertEquals("back must never record agreement", 0, this.apple.acceptedPageIds().size());
        assertEquals("back must not move on to the next document",
                0, this.apple.timesCalled("acceptTerms"));
        assertEquals("back must not finish signing in",
                0, this.apple.timesCalled("retrieveAuthData"));
    }

    /**
     * The document can actually be read to the end.
     *
     * <p>A fixed-height box with a contract in it is only acceptable if it scrolls. Asserting the
     * parent is a {@code ScrollView} proves nothing - it would pass just as happily with the text
     * clipped and unreachable - so this uses a document longer than the box and checks both that
     * there is more of it than fits and that swiping moves.
     */
    @Test
    public void thedocumentScrollsWhenItIsLongerThanTheBox() {
        this.signIn(FakeAppleAuthService.wantsTermsAccepted(
                new TermsDocument("iCloud", aVeryLongDocument(), true)));
        Eventually.check(() -> onView(withId(R.id.login_terms_container))
                .check(matches(isDisplayed())));

        Eventually.check(() -> assertTrue(
                "the document fits inside the box, so scrolling it proves nothing - this test"
                        + " needs a document longer than the box to mean anything",
                contentHeight() > viewportHeight()));

        assertEquals("it should start at the top of the document", 0, scrollY());
        Eventually.check(() -> assertTrue(
                "there should be more document below the fold to begin with", canScrollDown()));

        // Swipe until it stops moving, rather than once. **"It scrolled a bit" is not the
        // property that matters** - a contract you can nudge but not finish is as unreadable as
        // one that will not move at all, and the first version of this test asserted only that
        // scrollY had gone above zero.
        int previous = -1;
        for (int attempt = 0; attempt < 30 && scrollY() != previous; attempt++) {
            previous = scrollY();
            onView(withId(R.id.login_terms_scroll))
                    .perform(androidx.test.espresso.action.ViewActions.swipeUp());
            // Paced so this is watchable when somebody is being shown the flow. Off by default,
            // so a normal run and CI are unaffected.
            TestPace.afterAStep();
        }

        assertTrue("the box moved but never reached the end, so the last clauses of the"
                + " contract cannot be read", scrollY() > 0);
        Eventually.check(() -> assertFalse(
                "there is still document below and no way to get to it", canScrollDown()));
    }

    /** Whether the terms box has anything left below the fold. */
    private boolean canScrollDown() {
        final boolean[] value = {false};
        this.scenario.onActivity(activity ->
                value[0] = activity.findViewById(R.id.login_terms_scroll)
                        .canScrollVertically(1));
        return value[0];
    }

    private static String aVeryLongDocument() {
        final StringBuilder document = new StringBuilder("ICLOUD TERMS OF SERVICE\n\n");
        for (int i = 0; i < 60; i++) {
            document.append("Clause ").append(i)
                    .append(": this agreement covers your use of the service, and goes on at the")
                    .append(" length these documents go on at.\n\n");
        }
        return document.toString();
    }

    private int scrollY() {
        final int[] value = {-1};
        this.scenario.onActivity(activity ->
                value[0] = ((android.widget.ScrollView)
                        activity.findViewById(R.id.login_terms_scroll)).getScrollY());
        return value[0];
    }

    private int viewportHeight() {
        final int[] value = {-1};
        this.scenario.onActivity(activity ->
                value[0] = activity.findViewById(R.id.login_terms_scroll).getHeight());
        return value[0];
    }

    private int contentHeight() {
        final int[] value = {-1};
        this.scenario.onActivity(activity ->
                value[0] = activity.findViewById(R.id.login_terms_text).getHeight());
        return value[0];
    }

    private boolean isShown(final int id) {
        final boolean[] shown = {false};
        this.scenario.onActivity(activity ->
                shown[0] = activity.findViewById(id).getVisibility() == android.view.View.VISIBLE);
        return shown[0];
    }

    /** With one document there is no "Document 1 of 1" to clutter the screen. */
    @Test
    public void thecounterOnlyAppearsWhenThereIsMoreThanOne() {
        this.signIn(FakeAppleAuthService.wantsTermsAccepted(ICLOUD));

        Eventually.check(() -> onView(withId(R.id.login_terms_counter))
                .check(matches(not(isDisplayed()))));
    }

    @Test
    public void thecounterSaysWhichDocumentThisIs() {
        this.signIn(FakeAppleAuthService.wantsTermsAccepted(ICLOUD, MEDIA));

        Eventually.check(() -> onView(withId(R.id.login_terms_counter))
                .check(matches(isDisplayed())));
        Eventually.check(() -> onView(withId(R.id.login_terms_counter))
                .check(matches(withText(containsString("1")))));
    }

    /**
     * A document Apple gave no way to agree to says so, rather than offering a button that fails.
     */
    @Test
    public void adocumentThatCannotBeAcceptedSaysSo() {
        this.signIn(FakeAppleAuthService.wantsTermsAccepted(
                new TermsDocument("iCloud", ICLOUD_TEXT, false)));

        Eventually.check(() -> onView(withId(R.id.login_terms_cannot_accept))
                .check(matches(isDisplayed())));
        Eventually.check(() -> onView(withId(R.id.login_terms_agree_button))
                .check(matches(not(isEnabled()))));
    }

    /**
     * Reported as terms, turned out to be nothing - so it was something else.
     *
     * <p>Which failure value means "terms pending" is not established, so the app offers rather
     * than asserts. An empty fetch must go back to the sign-in step with a message, not sit on
     * an empty terms screen.
     */
    @Test
    public void nothingToAgreeToGoesBackToSigningInWithAMessage() {
        this.signIn(FakeAppleAuthService.reportsTermsButHasNone());

        Eventually.check(() -> onView(withId(R.id.login_error_container))
                .check(matches(isDisplayed())));
        Eventually.check(() -> onView(withId(R.id.login_terms_container))
                .check(matches(not(isDisplayed()))));
        Eventually.check(() -> onView(withId(R.id.login_error_message_text))
                .check(matches(withText(containsString("terms of service")))));
    }

    @Test
    public void afailureFetchingTheTermsIsReportedRatherThanBlank() {
        this.signIn(FakeAppleAuthService.wantsTermsAccepted(ICLOUD)
                .whereFetchingTermsFails(new RuntimeException("Apple said no")));

        Eventually.check(() -> onView(withId(R.id.login_error_container))
                .check(matches(isDisplayed())));
        Eventually.check(() -> onView(withId(R.id.login_terms_container))
                .check(matches(not(isDisplayed()))));
    }

    /**
     * <b>The one that matters most, and the one nothing else covers.</b>
     *
     * <p>Every document agreed to and signing in still did not reach LOGGED_IN. Storing that is
     * issues #43 and #119: an account in another state fails FindMy.py's own check before a
     * request is made, so the map never updates and signing out does not repair it - the user is
     * stuck with no way back and no explanation.
     */
    @Test
    public void asessionThatDidNotReachLoggedInIsNotStored() {
        this.signIn(FakeAppleAuthService.wantsTermsAccepted(ICLOUD).endingAt("REQUIRE_2FA"));
        Eventually.check(() -> onView(withId(R.id.login_terms_container))
                .check(matches(isDisplayed())));

        this.agree();

        Eventually.check(() -> onView(withId(R.id.login_error_container))
                .check(matches(isDisplayed())));
        assertEquals("a session that is not LOGGED_IN must never be stored",
                0, this.apple.timesCalled("retrieveAuthData"));
    }

    private static void signEverybodyOut() {
        final UserAuthRepository auth = new UserAuthRepository(
                UserAuthDataStore.getInstance(getInstrumentation().getTargetContext()),
                new AppCryptographyUtil());

        int consecutivelyEmpty = 0;
        for (int attempt = 0; attempt < 40 && consecutivelyEmpty < 3; attempt++) {
            if (auth.getUserAuth().blockingFirst().isEmpty()) {
                consecutivelyEmpty++;
            } else {
                consecutivelyEmpty = 0;
                auth.clearUser().blockingAwait();
            }
            SystemClock.sleep(50);
        }

        if (consecutivelyEmpty < 3) {
            throw new IllegalStateException("a stored session kept coming back");
        }
    }
}
