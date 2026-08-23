package dev.wander.android.opentagviewer.ui;

import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.Assume;

/**
 * The switch that keeps the wiki capture classes out of an ordinary run.
 *
 * <p><b>They are a documentation tool, not tests.</b> Every one of them ends in a screenshot and
 * asserts almost nothing, so a green run of them says nothing about the app - and they are
 * expensive: seven captures of the map screens took six minutes on a windowed emulator, which is
 * most of it teardown, and two of them need Play Services and a real Maps key that the managed
 * device does not have. Left switched on they would add twenty minutes to a suite people are
 * meant to run constantly, which is how a suite stops being run.
 *
 * <p>So they are skipped unless asked for:
 *
 * <pre>
 * ANDROID_SERIAL=emulator-5554 ./gradlew :app:connectedDebugAndroidTest \
 *   -Pandroid.testInstrumentationRunnerArguments.captureScreenshots=true \
 *   -Pandroid.testInstrumentationRunnerArguments.class=dev.wander.android.opentagviewer.ui.WikiScreenshotsTest
 * </pre>
 *
 * <p>Skipped rather than deleted because the point of them is that the images can be made again -
 * the wiki's previous set came from several phones over several years, and nothing recorded how
 * any of them was taken. Same mechanism as the Anisette live tests, for the same reason.
 *
 * <p>Note {@code connectedDebugAndroidTest} <b>uninstalls the app afterwards</b>, so only ever
 * aim this at a throwaway emulator - see AGENTS.md.
 */
public final class OnlyWhenCapturing {

    private static final String ARG = "captureScreenshots";

    private OnlyWhenCapturing() {}

    /** Call from {@code @Before}. Skips the class unless the argument was passed. */
    public static void wasAskedFor() {
        Assume.assumeTrue(
                "skipped: pass -Pandroid.testInstrumentationRunnerArguments." + ARG + "=true to "
                        + "capture the wiki's screenshots. These take screenshots rather than "
                        + "asserting, and the map ones need Play Services.",
                "true".equals(InstrumentationRegistry.getArguments().getString(ARG)));
    }
}
