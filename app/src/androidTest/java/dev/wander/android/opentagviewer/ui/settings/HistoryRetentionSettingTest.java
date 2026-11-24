package dev.wander.android.opentagviewer.ui.settings;

import static androidx.test.espresso.Espresso.onView;
import static androidx.test.espresso.action.ViewActions.click;
import static androidx.test.espresso.action.ViewActions.scrollTo;
import static androidx.test.espresso.assertion.ViewAssertions.doesNotExist;
import static androidx.test.espresso.assertion.ViewAssertions.matches;
import static androidx.test.espresso.matcher.RootMatchers.isDialog;
import static androidx.test.espresso.matcher.ViewMatchers.isDescendantOfA;
import static androidx.test.espresso.matcher.ViewMatchers.isDisplayed;
import static androidx.test.espresso.matcher.ViewMatchers.withId;
import static androidx.test.espresso.matcher.ViewMatchers.withText;
import static androidx.test.platform.app.InstrumentationRegistry.getInstrumentation;
import static org.hamcrest.Matchers.allOf;
import static org.junit.Assert.assertEquals;

import android.content.Context;

import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import dev.wander.android.opentagviewer.DeviceStateGuard;
import dev.wander.android.opentagviewer.Eventually;
import dev.wander.android.opentagviewer.R;
import dev.wander.android.opentagviewer.SettingsActivity;
import dev.wander.android.opentagviewer.anisette.FakeAnisetteSource;
import dev.wander.android.opentagviewer.db.datastore.UserSettingsDataStore;
import dev.wander.android.opentagviewer.db.repo.HistoryPruner;
import dev.wander.android.opentagviewer.db.repo.UserSettingsRepository;
import dev.wander.android.opentagviewer.db.repo.model.UserSettings;
import dev.wander.android.opentagviewer.python.AppDependencies;
import io.reactivex.rxjava3.core.Single;

/**
 * Settings -> Keep location history.
 *
 * <p>The pruner is a fake: the suite runs against the real on-device database, and on somebody's
 * own phone a real one would delete their history. {@code PruningOldLocationHistoryTest} checks
 * what the real one deletes. This checks that the screen asks before deleting, asks for the right
 * period, and saves the period before the delete starts.
 */
@RunWith(AndroidJUnit4.class)
public class HistoryRetentionSettingTest {

    private final Context context = getInstrumentation().getTargetContext();
    private final RecordingPruner pruner = new RecordingPruner();
    private DeviceStateGuard deviceState;
    private ActivityScenario<SettingsActivity> scenario;

    @Before
    public void setUp() {
        this.deviceState = DeviceStateGuard.capture(this.context);
        AppDependencies.replaceAnisette(settings -> FakeAnisetteSource.ready());
        AppDependencies.replaceHistoryPruner(this.pruner);
        this.storePeriod(null);
    }

    @After
    public void tearDown() {
        if (this.scenario != null) {
            this.scenario.close();
        }
        AppDependencies.reset();
        this.deviceState.restore();
    }

    @Test
    public void nobodyHasChosenSoItKeepsEverything() {
        this.open();
        this.seeSubtitle(R.string.history_retention_forever);
        assertEquals(List.of(), this.pruner.asked);
    }

    @Test
    public void choosingAMonthAsksBeforeDeleting() {
        this.open();
        this.choose(R.string.history_retention_1_month);

        Eventually.check(() -> onView(withText(R.string.history_retention_confirm_message))
                .inRoot(isDialog()).check(matches(isDisplayed())));
    }

    @Test
    public void cancellingKeepsEverythingAndDeletesNothing() {
        this.open();
        this.choose(R.string.history_retention_1_month);
        Eventually.check(() -> onView(withText(R.string.history_retention_confirm_title))
                .inRoot(isDialog()).check(matches(isDisplayed())));
        onView(withText(R.string.cancel)).inRoot(isDialog()).perform(click());

        Eventually.check(() -> onView(withText(R.string.history_retention_confirm_title))
                .check(doesNotExist()));
        this.seeSubtitle(R.string.history_retention_forever);
        assertEquals(UserSettings.HISTORY_RETENTION_FOREVER, this.storedPeriod());
        assertEquals(List.of(), this.pruner.asked);
    }

    /**
     * Confirming saves the month and then deletes. The fake reads the stored period when it is
     * called, so a delete that started before the save landed would record "forever".
     */
    @Test
    public void confirmingSavesTheMonthThenDeletesOlderHistory() {
        this.open();
        this.choose(R.string.history_retention_1_month);
        Eventually.check(() -> onView(withText(R.string.history_retention_confirm_button))
                .inRoot(isDialog()).check(matches(isDisplayed())));
        onView(withText(R.string.history_retention_confirm_button))
                .inRoot(isDialog()).perform(click());

        Eventually.check(() -> assertEquals(List.of(31), this.pruner.asked));
        assertEquals("the period was not saved before the delete started",
                List.of(31), this.pruner.storedWhenAsked);
        assertEquals(31, this.storedPeriod());
        this.seeSubtitle(R.string.history_retention_1_month);
    }

    @Test
    public void goingBackToForeverDoesNotAskOrDelete() {
        this.storePeriod(31);
        this.open();
        this.seeSubtitle(R.string.history_retention_1_month);

        this.choose(R.string.history_retention_forever);

        Eventually.check(() -> assertEquals(
                UserSettings.HISTORY_RETENTION_FOREVER, this.storedPeriod()));
        this.seeSubtitle(R.string.history_retention_forever);
        onView(withText(R.string.history_retention_confirm_title)).check(doesNotExist());
        assertEquals(List.of(), this.pruner.asked);
    }

    @Test
    public void aLongerPeriodIsSavedWithoutAsking() {
        this.storePeriod(31);
        this.open();

        this.choose(R.string.history_retention_1_year);

        Eventually.check(() -> assertEquals(366, this.storedPeriod()));
        this.seeSubtitle(R.string.history_retention_1_year);
        onView(withText(R.string.history_retention_confirm_title)).check(doesNotExist());
        assertEquals(List.of(), this.pruner.asked);
    }

    @Test
    public void aShorterPeriodAsksAgain() {
        this.storePeriod(366);
        this.open();

        this.choose(R.string.history_retention_3_months);

        Eventually.check(() -> onView(withText(R.string.history_retention_confirm_title))
                .inRoot(isDialog()).check(matches(isDisplayed())));
        onView(withText(R.string.history_retention_confirm_button))
                .inRoot(isDialog()).perform(click());
        Eventually.check(() -> assertEquals(List.of(92), this.pruner.asked));
    }

    /** The period stays chosen when the delete fails, so the next start deletes instead. */
    @Test
    public void aFailedDeleteKeepsTheNewPeriod() {
        this.pruner.fail = true;
        this.open();
        this.choose(R.string.history_retention_1_month);
        Eventually.check(() -> onView(withText(R.string.history_retention_confirm_button))
                .inRoot(isDialog()).check(matches(isDisplayed())));
        onView(withText(R.string.history_retention_confirm_button))
                .inRoot(isDialog()).perform(click());

        Eventually.check(() -> assertEquals(List.of(31), this.pruner.asked));
        assertEquals(31, this.storedPeriod());
        this.seeSubtitle(R.string.history_retention_1_month);
    }

    private void open() {
        this.scenario = ActivityScenario.launch(SettingsActivity.class);
    }

    private void choose(final int label) {
        onView(withId(R.id.settings_history_retention)).perform(scrollTo(), click());
        Eventually.check(() -> onView(withText(label)).inRoot(isDialog())
                .check(matches(isDisplayed())));
        onView(withText(label)).inRoot(isDialog()).perform(click());
        onView(withText(R.string.accept)).inRoot(isDialog()).perform(click());
    }

    private void seeSubtitle(final int label) {
        Eventually.check(() -> onView(allOf(withId(R.id.textView5),
                isDescendantOfA(withId(R.id.settings_history_retention))))
                .check(matches(withText(label))));
    }

    private UserSettingsRepository repository() {
        return new UserSettingsRepository(UserSettingsDataStore.getInstance(this.context));
    }

    private int storedPeriod() {
        return this.repository().getUserSettings().resolveHistoryRetentionDays();
    }

    /** Changes only the period, leaving the rest of whatever is stored as it was. */
    private void storePeriod(final Integer days) {
        final UserSettings settings = this.repository().getUserSettings();
        settings.setHistoryRetentionDays(days);
        this.repository().storeUserSettings(settings).blockingAwait();
    }

    /** Records each call, and the period stored at that moment. */
    private final class RecordingPruner implements HistoryPruner {
        final List<Integer> asked = new CopyOnWriteArrayList<>();
        final List<Integer> storedWhenAsked = new CopyOnWriteArrayList<>();
        volatile boolean fail = false;

        @Override
        public Single<Integer> prune(final int retentionDays) {
            return Single.fromCallable(() -> {
                this.storedWhenAsked.add(storedPeriod());
                this.asked.add(retentionDays);
                if (this.fail) {
                    throw new IllegalStateException("a delete that fails, on purpose");
                }
                return 0;
            });
        }
    }
}
