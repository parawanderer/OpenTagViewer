package dev.wander.android.opentagviewer.ui;

import static androidx.test.espresso.Espresso.onView;
import static androidx.test.espresso.assertion.ViewAssertions.matches;
import static androidx.test.espresso.matcher.ViewMatchers.isDisplayed;
import static androidx.test.espresso.matcher.ViewMatchers.withId;
import static androidx.test.espresso.matcher.ViewMatchers.withText;
import static androidx.test.platform.app.InstrumentationRegistry.getInstrumentation;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.startsWith;

import android.content.Context;

import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.filters.LargeTest;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.List;

import dev.wander.android.opentagviewer.Eventually;
import dev.wander.android.opentagviewer.InformationActivity;
import dev.wander.android.opentagviewer.R;
import dev.wander.android.opentagviewer.Shot;
import dev.wander.android.opentagviewer.db.room.OpenTagViewerDatabase;
import dev.wander.android.opentagviewer.db.room.dao.ImportDao;
import dev.wander.android.opentagviewer.db.room.entity.Import;

/**
 * The screen a bug report sends people to, and whether it answers what the report asks.
 *
 * <p><b>Two questions, and until now it answered one.</b> The app version was here; which
 * exporter produced the bundle was not - and the only place that lives is {@code
 * OPENTAGVIEWER.yml} inside the export zip, a file holding the private keys to somebody's tags
 * and one the issue template tells them in bold not to open. Asking a question whose answer is
 * inside a file you have told people not to open is asking them to ignore you.
 *
 * <p>Both states matter. An install connected straight to an Apple account has no bundle behind
 * it at all, and "nothing" is a real answer rather than a gap - it tells a maintainer the
 * exporter is not involved, which rules out a whole class of cause.
 */
@LargeTest
@RunWith(AndroidJUnit4.class)
public class WhatTheInformationScreenAnswersTest {

    private static final String AN_EXPORTER = "OpenTagViewer.wizard:1.3.0";

    private ActivityScenario<InformationActivity> scenario;

    /** Put back whatever the device had, so this cannot bleed into another test. */
    private List<Import> before;

    @Before
    public void rememberWhatWasThere() {
        this.before = imports().getAll();
        for (final Import existing : this.before) {
            imports().delete(existing);
        }
    }

    @After
    public void putItBack() {
        if (this.scenario != null) {
            this.scenario.close();
        }
        for (final Import existing : imports().getAll()) {
            imports().delete(existing);
        }
        for (final Import original : this.before) {
            imports().insert(original);
        }
    }

    /**
     * <b>The exporter that made the bundle, on screen, so nobody opens the zip to find it.</b>
     */
    @Test
    public void awhichExporterMadeTheBundle() {
        imports().insert(Import.builder()
                .version("0.0.2")
                .importedAt(System.currentTimeMillis())
                .exportedAt(System.currentTimeMillis())
                .sourceUser("someone@example.com")
                .exportedVia(AN_EXPORTER)
                .build());

        this.scenario = ActivityScenario.launch(InformationActivity.class);

        // The read is a database call on a background thread, so the line arrives after the
        // screen does.
        Eventually.check(() -> onView(withId(R.id.appImportedFrom))
                .check(matches(withText(containsString(AN_EXPORTER)))));

        // And the version is still the thing above it, which is the other half the report wants.
        onView(withId(R.id.appVersion)).check(matches(withText(startsWith("Version"))));

        Shot.ofTheScreen("the_information_screen-imported_from_an_exporter");
    }

    /**
     * <b>And "nothing" said out loud, rather than an empty line.</b>
     *
     * <p>A blank where an answer should be reads as a bug in this screen. It is not - it is the
     * answer for anybody who connected an Apple account instead of importing a zip, and saying
     * so rules the exporter out of whatever they are reporting.
     */
    @Test
    public void bnothingImportedIsAlsoAnAnswer() {
        this.scenario = ActivityScenario.launch(InformationActivity.class);

        final Context context = getInstrumentation().getTargetContext();
        Eventually.check(() -> onView(withId(R.id.appImportedFrom))
                .check(matches(withText(context.getString(R.string.imported_from_nothing)))));
        onView(withId(R.id.appImportedFrom)).check(matches(isDisplayed()));

        Shot.ofTheScreen("the_information_screen-nothing_imported");
    }

    private static ImportDao imports() {
        return OpenTagViewerDatabase
                .getInstance(getInstrumentation().getTargetContext().getApplicationContext())
                .importDao();
    }
}
