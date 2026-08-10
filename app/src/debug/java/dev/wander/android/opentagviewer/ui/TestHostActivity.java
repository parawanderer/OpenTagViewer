package dev.wander.android.opentagviewer.ui;

import android.os.Build;
import android.os.Bundle;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;

/**
 * An empty activity, for instrumented tests that need a window and nothing else.
 *
 * <p>Dialogs, snackbars and anything inflating against an activity theme need one to exist. The
 * app's own activities build repositories, restore an Apple session and expect an account
 * before they finish starting, so hosting a dialog test on one would test the activity instead,
 * slowly and with a network in the loop.
 *
 * <p><b>In the debug source set, not androidTest,</b> despite only tests using it: an activity
 * declared in the instrumentation manifest belongs to the test package and runs in a different
 * process, which {@code ActivityScenario} refuses to launch into. Debug-only, so it never
 * reaches a release build.
 */
public class TestHostActivity extends AppCompatActivity {

    /**
     * Wakes the screen and shows over the lock screen.
     *
     * <p>Espresso will not touch a window that lacks focus, and a locked or asleep emulator
     * never gives it any - the failure is a ten-second timeout that says nothing about the
     * test. Emulators idle into that state between runs.
     */
    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            this.setShowWhenLocked(true);
            this.setTurnScreenOn(true);
        }
    }
}
