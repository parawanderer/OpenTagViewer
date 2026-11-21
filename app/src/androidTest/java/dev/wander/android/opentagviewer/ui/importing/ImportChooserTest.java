package dev.wander.android.opentagviewer.ui.importing;

import static androidx.test.espresso.Espresso.onView;
import static androidx.test.espresso.action.ViewActions.click;
import static androidx.test.espresso.assertion.ViewAssertions.matches;
import static androidx.test.espresso.matcher.RootMatchers.isDialog;
import static androidx.test.espresso.matcher.ViewMatchers.isDisplayed;
import static androidx.test.espresso.matcher.ViewMatchers.withText;
import static androidx.test.platform.app.InstrumentationRegistry.getInstrumentation;
import static org.junit.Assert.assertEquals;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatDelegate;
import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.ArrayList;
import java.util.List;

import dev.wander.android.opentagviewer.Eventually;
import dev.wander.android.opentagviewer.R;
import dev.wander.android.opentagviewer.Shot;
import dev.wander.android.opentagviewer.ui.TestHostActivity;

/**
 * The one Import entry's chooser, on its own: each choice starts its own import, in light and
 * dark. The map and My Devices both open this, so its routing is tested once, here.
 */
@RunWith(AndroidJUnit4.class)
public class ImportChooserTest {

    private ActivityScenario<TestHostActivity> scenario;
    private final List<String> chosen = new ArrayList<>();
    private int nightModeBefore;

    private final ImportChooser.Choices recording = new ImportChooser.Choices() {
        @Override
        public void exporterZip() {
            chosen.add("zip");
        }

        @Override
        public void openHaystackKeys() {
            chosen.add("keys");
        }

        @Override
        public void googleFindHub() {
            chosen.add("google");
        }
    };

    @Before
    public void setUp() {
        this.nightModeBefore = AppCompatDelegate.getDefaultNightMode();
    }

    @After
    public void tearDown() {
        if (this.scenario != null) {
            this.scenario.close();
        }
        getInstrumentation().runOnMainSync(
                () -> AppCompatDelegate.setDefaultNightMode(this.nightModeBefore));
    }

    private AlertDialog open() {
        this.scenario = ActivityScenario.launch(TestHostActivity.class);
        final AlertDialog[] dialog = {null};
        this.scenario.onActivity(a -> dialog[0] = ImportChooser.show(a, this.recording));
        Eventually.check(() -> onView(withText(R.string.do_import)).inRoot(isDialog())
                .check(matches(isDisplayed())));
        return dialog[0];
    }

    private void pick(final int label, final String expected) {
        this.open();
        onView(withText(label)).inRoot(isDialog()).perform(click());
        Eventually.check(() -> assertEquals(List.of(expected), this.chosen));
    }

    @Test
    public void theExporterZipStartsTheZipImport() {
        this.pick(R.string.import_choice_exporter, "zip");
    }

    @Test
    public void anOpenHaystackKeyStartsTheKeysImport() {
        this.pick(R.string.import_choice_openhaystack, "keys");
    }

    @Test
    public void googleFindHubStartsTheGoogleImport() {
        this.pick(R.string.import_choice_google, "google");
    }

    @Test
    public void cancellingStartsNothing() {
        this.open();
        onView(withText(android.R.string.cancel)).inRoot(isDialog()).perform(click());
        getInstrumentation().waitForIdleSync();
        assertEquals(List.of(), this.chosen);
    }

    @Test
    public void inLightMode() {
        Shot.of(this.open(), "import_chooser_light");
    }

    @Test
    public void inDarkMode() {
        getInstrumentation().runOnMainSync(
                () -> AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_YES));
        Shot.of(this.open(), "import_chooser_dark");
    }
}
