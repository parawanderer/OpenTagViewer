package dev.wander.android.opentagviewer.ui.maps;

import android.app.Activity;
import android.os.Bundle;

/**
 * An activity that claims to open {@code geo:} links, so a test has something to navigate to.
 *
 * <p>It does nothing and shows nothing - it exists to be <i>resolvable</i>. The managed device
 * has no maps application at all, so without it {@code MapsActivity#onClickNavigateTo} correctly
 * finds no handler and shows its "no app can open a map" message, and a test watching for the
 * intent cannot tell that apart from the button being broken.
 *
 * <p>It is also the only thing that proves the {@code <queries>} entry for {@code geo:} works:
 * from Android 11 the app cannot see a package it has not declared, so with that entry removed
 * this activity becomes invisible to {@code resolveActivity} and the test fails - which is
 * exactly the bug it was added for.
 *
 * <p>Debug builds only. It is never started in a test either, because Espresso stubs the intent
 * before it can be.
 */
public class SomewhereToNavigateTo extends Activity {

    @Override
    protected void onCreate(final Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        this.finish();
    }
}
