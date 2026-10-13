package dev.wander.android.opentagviewer.source;

import static androidx.test.platform.app.InstrumentationRegistry.getInstrumentation;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.content.SharedPreferences;

import androidx.test.ext.junit.runners.AndroidJUnit4;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

/**
 * The googlefind service's settings, against the real keystore.
 *
 * <p>The API token reads every location the service holds, so it is encrypted like the Apple
 * session. Plain text in SharedPreferences would still pass every test that only reads it back,
 * which is why these look at what is actually on disk.
 */
@RunWith(AndroidJUnit4.class)
public class ExtraSourcesSettingsTest {

    private Context context;
    private SharedPreferences raw;

    @Before
    public void startEmpty() {
        this.context = getInstrumentation().getTargetContext();
        this.raw = this.context.getSharedPreferences("extra_sources", Context.MODE_PRIVATE);
        this.raw.edit().clear().commit();
    }

    @After
    public void leaveNothingBehind() {
        this.raw.edit().clear().commit();
    }

    @Test
    public void theTokenReadsBackButIsNotStoredInPlainText() {
        new ExtraSourcesSettings(this.context).put(ExtraSourcesSettings.GOOGLE_TOKEN, "s3cret-token");

        assertEquals("s3cret-token",
                new ExtraSourcesSettings(this.context).get(ExtraSourcesSettings.GOOGLE_TOKEN));
        for (final Object stored : this.raw.getAll().values()) {
            assertFalse("the token must not be on disk as written: " + stored,
                    String.valueOf(stored).contains("s3cret-token"));
        }
    }

    @Test
    public void thePasswordIsEncryptedToo() {
        new ExtraSourcesSettings(this.context).put(ExtraSourcesSettings.GOOGLE_PASSWORD, "hunter2-pass");

        for (final Object stored : this.raw.getAll().values()) {
            assertFalse(String.valueOf(stored).contains("hunter2-pass"));
        }
    }

    /** Builds of the fork stored the token in plain text; it is moved over on first read. */
    @Test
    public void aPlainTextTokenFromAnEarlierBuildIsMovedOver() {
        this.raw.edit().putString(ExtraSourcesSettings.GOOGLE_TOKEN, "old-token").commit();

        assertEquals("old-token", new ExtraSourcesSettings(this.context).get(ExtraSourcesSettings.GOOGLE_TOKEN));

        assertFalse("the plain copy should be gone", this.raw.contains(ExtraSourcesSettings.GOOGLE_TOKEN));
        assertEquals("old-token", new ExtraSourcesSettings(this.context).get(ExtraSourcesSettings.GOOGLE_TOKEN));
    }

    @Test
    public void clearingTheTokenRemovesIt() {
        final ExtraSourcesSettings settings = new ExtraSourcesSettings(this.context);
        settings.put(ExtraSourcesSettings.GOOGLE_TOKEN, "s3cret-token");

        settings.put(ExtraSourcesSettings.GOOGLE_TOKEN, "");

        assertTrue("nothing should be stored for an empty token", this.raw.getAll().isEmpty());
        assertEquals("", settings.get(ExtraSourcesSettings.GOOGLE_TOKEN));
    }

    @Test
    public void theAddressIsTrimmedAndIsWhatTurnsTheFeatureOn() {
        final ExtraSourcesSettings settings = new ExtraSourcesSettings(this.context);
        assertFalse(settings.isGoogleConfigured());

        settings.put(ExtraSourcesSettings.GOOGLE_URL, "  https://find.example  ");

        assertEquals("https://find.example", settings.get(ExtraSourcesSettings.GOOGLE_URL));
        assertTrue(settings.isGoogleConfigured());
    }
}
