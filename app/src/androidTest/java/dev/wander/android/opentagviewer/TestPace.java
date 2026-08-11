package dev.wander.android.opentagviewer;

import android.os.SystemClock;

import androidx.test.platform.app.InstrumentationRegistry;

/**
 * An opt-in pause between the steps of a UI test, so a person can watch one happen.
 *
 * <p>These tests run at machine speed: a whole sign-in, four pages, is over in about three
 * seconds and looks like a flicker. That is what you want in CI and useless when you are
 * trying to see whether the screen does what you think it does, or to show somebody that it
 * does.
 *
 * <p>Off unless asked for, and asked for per run rather than by editing anything:
 *
 * <pre>
 * ./gradlew :app:connectedDebugAndroidTest \
 *     -Pandroid.testInstrumentationRunnerArguments.slowMotion=1200 \
 *     -Pandroid.testInstrumentationRunnerArguments.class=dev.wander.android.opentagviewer.AppleLoginFlowTest
 * </pre>
 *
 * <p>Use a device with a window for this - the managed device is headless, so there is nothing
 * to watch. Keep the emulator window focused, or Espresso stops being able to touch anything.
 */
public final class TestPace {

    private TestPace() {}

    private static final String ARGUMENT = "slowMotion";

    private static final long PAUSE_MS = readPause();

    private static long readPause() {
        final String requested = InstrumentationRegistry.getArguments().getString(ARGUMENT);
        if (requested == null || requested.isBlank()) {
            return 0;
        }
        try {
            return Long.parseLong(requested.trim());
        } catch (final NumberFormatException e) {
            // A mistyped number should not fail a test run - it is a viewing aid.
            return 1000;
        }
    }

    /** Whether anybody is watching. */
    public static boolean isSlow() {
        return PAUSE_MS > 0;
    }

    /**
     * Pause after a step, if slow motion was asked for. Does nothing otherwise, so leaving
     * these in costs a comparison per step in a normal run.
     */
    public static void afterAStep() {
        if (PAUSE_MS > 0) {
            SystemClock.sleep(PAUSE_MS);
        }
    }
}
