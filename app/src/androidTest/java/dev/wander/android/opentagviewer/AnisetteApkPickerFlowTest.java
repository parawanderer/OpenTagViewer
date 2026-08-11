package dev.wander.android.opentagviewer;

import static androidx.test.espresso.Espresso.onView;
import static androidx.test.espresso.action.ViewActions.click;
import static androidx.test.espresso.action.ViewActions.scrollTo;
import static androidx.test.espresso.assertion.ViewAssertions.matches;
import static androidx.test.espresso.intent.Intents.intended;
import static androidx.test.espresso.intent.Intents.intending;
import static androidx.test.espresso.intent.matcher.IntentMatchers.hasAction;
import static androidx.test.espresso.matcher.RootMatchers.isDialog;
import static androidx.test.espresso.matcher.ViewMatchers.isDisplayed;
import static androidx.test.espresso.matcher.ViewMatchers.withId;
import static androidx.test.platform.app.InstrumentationRegistry.getInstrumentation;
import static org.junit.Assert.assertFalse;

import android.app.Activity;
import android.app.Instrumentation.ActivityResult;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.SystemClock;

import androidx.test.core.app.ActivityScenario;
import androidx.test.espresso.intent.Intents;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.filters.LargeTest;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import dev.wander.android.opentagviewer.anisette.FakeAnisetteSource;
import dev.wander.android.opentagviewer.anisette.LocalAnisette;
import dev.wander.android.opentagviewer.db.datastore.UserSettingsDataStore;
import dev.wander.android.opentagviewer.db.repo.UserSettingsRepository;
import dev.wander.android.opentagviewer.db.repo.model.UserSettings;
import dev.wander.android.opentagviewer.python.AppDependencies;

/**
 * Supplying your own Apple Music APK, driven through the screen.
 *
 * <p>This only ever appears for somebody whose sign-in is already broken: Apple has replaced
 * the build these libraries come from, and until this app ships an update the only way back in
 * is a copy of the old build found elsewhere. Nobody will be exercising this by hand, and the
 * state that reveals it cannot be produced on demand - it depends on Apple shipping something,
 * which they last did in April 2025.
 *
 * <p>What is <b>not</b> here is a successful import. That needs the genuine 100 MB APK, since
 * any file this test could build fails the hash check by design - and weakening that check to
 * make a test pass would remove the only thing standing between a file off the internet and
 * code this app loads and runs. Acceptance is covered by the opt-in tests that use the real
 * download; everything here is about what happens when it does not work, which is the part
 * somebody in trouble will actually meet.
 */
@LargeTest
@RunWith(AndroidJUnit4.class)
public class AnisetteApkPickerFlowTest {

    private static final String APPLE_MUSIC_VERSION = "4.9.6.1447";

    private Context context;
    private ActivityScenario<SettingsActivity> scenario;

    @Before
    public void pretendAppleShippedANewBuild() {
        this.context = getInstrumentation().getTargetContext();

        // Local mode explicitly: this screen is for somebody already signed in, and an
        // unchosen mode deliberately keeps those people on their server.
        storeSettings(UserSettings.builder().anisetteMode(UserSettings.ANISETTE_LOCAL).build());
        deleteExtractedLibraries();

        // The one state that offers this at all, and the reason a fake has to exist for it.
        AppDependencies.replaceAnisette(settings ->
                FakeAnisetteSource.appleChangedTheLibraries(APPLE_MUSIC_VERSION));

        Intents.init();
    }

    @After
    public void putTheRealOnesBack() {
        if (this.scenario != null) {
            this.scenario.close();
        }
        Intents.release();
        AppDependencies.reset();

        deleteExtractedLibraries();
        storeSettings(UserSettings.builder().build());
    }

    /**
     * The controls appear, and only because Apple changed something.
     *
     * <p>Every other failure hides them: telling somebody to go and find an APK when their
     * problem is a dropped connection sends them off to do something pointless and risky.
     */
    @Test
    public void appleChangingTheLibrariesOffersTheChoice() {
        openTheAnisetteSettings();

        eventually(() -> onView(withId(R.id.anisetteOwnApkContainer)).inRoot(isDialog())
                .check(matches(isDisplayed())));
        onView(withId(R.id.anisetteChooseApkButton)).inRoot(isDialog())
                .check(matches(isDisplayed()));
    }

    /**
     * Choosing a file asks the system for one, rather than going looking itself.
     *
     * <p>OPEN_DOCUMENT specifically: it is the one that comes with a lasting read grant, and
     * the file being read is tens of megabytes.
     */
    @Test
    public void choosingAFileAsksTheSystemForOne() {
        stubThePickerWith(null);
        openTheAnisetteSettings();

        eventually(() -> onView(withId(R.id.anisetteChooseApkButton)).inRoot(isDialog())
                .perform(click()));

        eventually(() -> intended(hasAction(Intent.ACTION_OPEN_DOCUMENT)));
    }

    /**
     * A file that is not the build this app knows how to read is refused, and forgotten.
     *
     * <p>The remembering is the part worth pinning down. It exists only so the screen can
     * offer to go back to Apple's copy, so recording a file that was rejected would offer to
     * revert to something that was never used.
     */
    @Test
    public void anApkThatDoesNotMatchIsRefusedAndNotRemembered() {
        stubThePickerWith(aZipPretendingToBeAppleMusic());
        openTheAnisetteSettings();

        eventually(() -> onView(withId(R.id.anisetteChooseApkButton)).inRoot(isDialog())
                .perform(click()));

        // Give the import - which reads and hashes the file - time to finish and be rejected.
        settle();

        // Asked as "is one in use", not "is the field null": the store writes an empty string
        // for an absent URI, so a null check would pass without meaning anything.
        assertFalse("a rejected file must not be recorded as the one in use",
                storedSettings().hasOwnAnisetteApk());
        assertNothingWasExtracted();
    }

    /**
     * Backing out of the file picker changes nothing.
     *
     * <p>The obvious wiring treats "no file" as an outcome to act on, and clears or rewrites
     * something on the way past.
     */
    @Test
    public void backingOutOfThePickerChangesNothing() {
        stubThePickerWith(null);
        openTheAnisetteSettings();

        eventually(() -> onView(withId(R.id.anisetteChooseApkButton)).inRoot(isDialog())
                .perform(click()));
        settle();

        assertFalse(storedSettings().hasOwnAnisetteApk());
        assertNothingWasExtracted();
    }

    // ------------------------------------------------------------------------------------

    /** Open Settings and the Anisette row, which is where all of this lives. */
    private void openTheAnisetteSettings() {
        this.scenario = ActivityScenario.launch(SettingsActivity.class);
        TestPace.afterAStep();

        eventually(() -> onView(withId(R.id.setting_app_anisette_server))
                .perform(click()));
        TestPace.afterAStep();
    }

    /**
     * Answer the file picker without one appearing.
     *
     * @param chosen the file to hand back, or null for somebody who backed out
     */
    private void stubThePickerWith(final Uri chosen) {
        final Intent data = chosen == null ? null : new Intent().setData(chosen);
        intending(hasAction(Intent.ACTION_OPEN_DOCUMENT)).respondWith(new ActivityResult(
                chosen == null ? Activity.RESULT_CANCELED : Activity.RESULT_OK, data));
    }

    /** A zip with the right entry names and the wrong contents. */
    private Uri aZipPretendingToBeAppleMusic() {
        final File apk = new File(context.getCacheDir(), "picked-apk.zip");
        final String abi = Build.SUPPORTED_ABIS[0];

        try (ZipOutputStream zip = new ZipOutputStream(new FileOutputStream(apk))) {
            for (final String name : LocalAnisette.requiredLibraries()) {
                zip.putNextEntry(new ZipEntry("lib/" + abi + "/" + name));
                zip.write("not Apple's bytes".getBytes(StandardCharsets.UTF_8));
                zip.closeEntry();
            }
        } catch (final Exception e) {
            throw new IllegalStateException("could not build the test APK", e);
        }

        return Uri.fromFile(apk);
    }

    private void assertNothingWasExtracted() {
        final File directory = LocalAnisette.libraryDirectory(context, Build.SUPPORTED_ABIS[0]);
        for (final String name : LocalAnisette.requiredLibraries()) {
            assertFalse("a refused APK left " + name + " where the loader would find it",
                    new File(directory, name).exists());
        }
    }

    private void deleteExtractedLibraries() {
        final File directory = LocalAnisette.libraryDirectory(context, Build.SUPPORTED_ABIS[0]);
        for (final String name : LocalAnisette.requiredLibraries()) {
            final File library = new File(directory, name);
            if (library.exists() && !library.delete()) {
                throw new IllegalStateException("could not remove " + library);
            }
        }
    }

    private UserSettings storedSettings() {
        return new UserSettingsRepository(
                UserSettingsDataStore.getInstance(context)).getUserSettings();
    }

    private void storeSettings(final UserSettings settings) {
        new UserSettingsRepository(UserSettingsDataStore.getInstance(context))
                .storeUserSettings(settings)
                .blockingAwait();
    }

    /** Let the background import run to completion, since Espresso only waits for the UI. */
    private static void settle() {
        for (int i = 0; i < 20; i++) {
            getInstrumentation().waitForIdleSync();
            SystemClock.sleep(100);
        }
    }

    /** See AppleLoginFlowTest.eventually - the same reason, and the same RuntimeException catch. */
    private static void eventually(final Runnable assertion) {
        Throwable last = null;

        for (int attempt = 0; attempt < 50; attempt++) {
            try {
                assertion.run();
                return;
            } catch (final AssertionError | RuntimeException error) {
                last = error;
                getInstrumentation().waitForIdleSync();
                SystemClock.sleep(100);
            }
        }

        if (last instanceof RuntimeException) {
            throw (RuntimeException) last;
        }
        throw (AssertionError) last;
    }
}
