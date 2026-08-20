package dev.wander.android.opentagviewer;

import static androidx.test.espresso.Espresso.onView;
import static androidx.test.espresso.action.ViewActions.swipeUp;
import static androidx.test.espresso.assertion.ViewAssertions.matches;
import static androidx.test.espresso.matcher.ViewMatchers.isDisplayed;
import static androidx.test.espresso.matcher.ViewMatchers.withId;
import static androidx.test.platform.app.InstrumentationRegistry.getInstrumentation;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.view.View;
import android.widget.ScrollView;

import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.filters.LargeTest;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import dev.wander.android.opentagviewer.db.datastore.UserAuthDataStore;
import dev.wander.android.opentagviewer.db.repo.KeychainMembershipRepository;
import dev.wander.android.opentagviewer.python.AppDependencies;
import dev.wander.android.opentagviewer.python.icloud.FakeICloudService;
import dev.wander.android.opentagviewer.util.android.AppCryptographyUtil;

/**
 * A long list of devices, with the button pinned under it.
 *
 * <p><b>Pinning the button is what makes this worth testing.</b> While it sat at the bottom of
 * the content, a long list simply pushed it further down and scrolling reached it. Now it does
 * not move - so if the list above it did not scroll, the devices past the fold would be
 * unreachable, and the screen would be broken for precisely the people most likely to use it:
 * somebody with years of Apple hardware has an escrow record for every piece of it, and those
 * records outlive the devices that made them.
 */
@LargeTest
@RunWith(AndroidJUnit4.class)
public class FetchFromICloudScrollTest {

    private static final int MANY = 25;

    private ActivityScenario<FetchFromICloudActivity> scenario;
    private KeychainMembershipRepository memberships;

    @Before
    public void forgetAnyStoredMembership() {
        this.memberships = new KeychainMembershipRepository(
                UserAuthDataStore.getInstance(getInstrumentation().getTargetContext()),
                new AppCryptographyUtil());
        this.memberships.forget().blockingAwait();
    }

    @After
    public void putTheRealOneBack() {
        if (this.scenario != null) {
            this.scenario.close();
        }
        AppDependencies.reset();
        this.memberships.forget().blockingAwait();
    }

    private void openWithManyDevices() {
        AppDependencies.replaceICloud(() -> FakeICloudService.withManyDevices(MANY));
        this.scenario = ActivityScenario.launch(FetchFromICloudActivity.class);

        Eventually.check(() -> onView(withId(R.id.icloud_device_container))
                .check(matches(isDisplayed())));
    }

    private <T> T fromActivity(final java.util.function.Function<FetchFromICloudActivity, T> read,
                               final T fallback) {
        final Object[] value = {fallback};
        this.scenario.onActivity(activity -> value[0] = read.apply(activity));

        @SuppressWarnings("unchecked")
        final T typed = (T) value[0];
        return typed;
    }

    private boolean canScrollDown() {
        return this.fromActivity(
                a -> a.findViewById(R.id.icloud_scroll).canScrollVertically(1), false);
    }

    private int scrollY() {
        return this.fromActivity(
                a -> ((ScrollView) a.findViewById(R.id.icloud_scroll)).getScrollY(), 0);
    }

    /** With this many devices there is genuinely more list than screen. */
    @Test
    public void alongListOverflowsTheScreen() {
        this.openWithManyDevices();

        Eventually.check(() -> assertTrue(
                "the list fits, so this test proves nothing about scrolling", canScrollDown()));
    }

    /** And it can be scrolled all the way, so the last device is reachable. */
    @Test
    public void thelistScrollsToTheEnd() {
        this.openWithManyDevices();

        int previous = -1;
        for (int attempt = 0; attempt < 40 && scrollY() != previous; attempt++) {
            previous = scrollY();
            onView(withId(R.id.icloud_scroll)).perform(swipeUp());
        }

        assertTrue("the list never moved", scrollY() > 0);
        Eventually.check(() -> assertFalse(
                "there are devices below the fold that cannot be reached", canScrollDown()));
    }

    /**
     * <b>The button stays put while the list moves under it.</b>
     *
     * <p>The whole reason it was pinned. If it scrolled away with the content, a long list would
     * hide it - and if it moved at all, the screen would appear to jump between steps.
     */
    @Test
    public void thebuttonBarDoesNotMoveWhenTheListScrolls() {
        this.openWithManyDevices();

        final int before = this.fromActivity(
                a -> a.findViewById(R.id.icloud_button_bar).getTop(), -1);

        onView(withId(R.id.icloud_scroll)).perform(swipeUp());
        onView(withId(R.id.icloud_scroll)).perform(swipeUp());

        Eventually.check(() -> assertTrue("the list did not scroll at all", scrollY() > 0));

        final int after = this.fromActivity(
                a -> a.findViewById(R.id.icloud_button_bar).getTop(), -2);

        assertTrue("the button bar moved with the content", before == after);
    }

    /** And it is on screen from the start, rather than below the fold with the last device. */
    @Test
    public void thebuttonBarIsVisibleBeforeAnyScrolling() {
        this.openWithManyDevices();

        Eventually.check(() -> onView(withId(R.id.icloud_button_bar))
                .check(matches(isDisplayed())));
    }
}
