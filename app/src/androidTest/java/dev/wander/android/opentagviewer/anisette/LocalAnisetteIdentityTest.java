package dev.wander.android.opentagviewer.anisette;

import static androidx.test.platform.app.InstrumentationRegistry.getInstrumentation;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.content.SharedPreferences;

import androidx.test.ext.junit.runners.AndroidJUnit4;

import dev.wander.android.opentagviewer.db.repo.model.UserSettings;

import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

/**
 * What an upgrade does to an identity that already exists.
 *
 * <p><b>This is the half nobody can test by hand.</b> Reaching the interesting case for real
 * means having installed a build from before hardware profiles existed, signed in to a real
 * Apple account on it, and then upgrading - and the way it fails is that Apple stops
 * recognising the machine hours later, on somebody else's phone, with nothing in a log to say
 * why. So the older shapes are written into {@link LocalAnisette#PREFERENCES} directly and read
 * back through the same door the app uses.
 *
 * <p>The rule being tested is one sentence: <b>an install that already has an identity keeps
 * the machine it has always claimed.</b> Everything below is a way of getting that wrong.
 */
@RunWith(AndroidJUnit4.class)
public class LocalAnisetteIdentityTest {

    /** What every install shipped before profiles existed - three keys and no fourth. */
    private static final String OLD_DEVICE_ID = "8C6C1F0B-6C46-4B39-B4E5-3E5A0C8B2E11";
    private static final String OLD_ADI_ID = "a1b2c3d4e5f60718";
    private static final String OLD_LOCAL_USER =
            "3F2A1B0C9D8E7F6A5B4C3D2E1F0A9B8C7D6E5F4A3B2C1D0E9F8A7B6C5D4E3F2A";

    private Context context;
    private SharedPreferences preferences;

    @Before
    public void clearAnyRealIdentity() {
        this.context = getInstrumentation().getTargetContext().getApplicationContext();
        this.preferences =
                this.context.getSharedPreferences(LocalAnisette.PREFERENCES, Context.MODE_PRIVATE);
        this.preferences.edit().clear().commit();
    }

    /**
     * The device is shared with every other test in the suite, and an identity left behind here
     * would be adopted as real by whatever ran next.
     */
    @After
    public void leaveNothingBehind() {
        this.preferences.edit().clear().commit();
    }

    private LocalAnisette subject() {
        // Remote deliberately: it proves the answer does not depend on ADI. This settings object
        // makes ensureReady() decline before it downloads anything, and the profile still has to
        // come back - a sign-in relayed through a server claims a machine too.
        return new LocalAnisette(
                this.context,
                UserSettings.builder().anisetteMode(UserSettings.ANISETTE_REMOTE).build(),
                true);
    }

    private void writeTheOldShape() {
        assertTrue(this.preferences.edit()
                .putString(LocalAnisette.KEY_DEVICE_ID, OLD_DEVICE_ID)
                .putString(LocalAnisette.KEY_ADI_ID, OLD_ADI_ID)
                .putString(LocalAnisette.KEY_LOCAL_USER, OLD_LOCAL_USER)
                .commit());
    }

    private static String modelOf(String json) throws Exception {
        return new JSONObject(json).getString("model");
    }

    /**
     * The one that matters.
     *
     * <p>Three keys and no hardware key can only mean an install from before there was a
     * choice, and the only thing this app ever claimed then was the Mac. Defaulting it to the
     * new profile instead would present Apple with a different machine on an existing session:
     * a sign-in the user did not ask for, and a second device-list entry beside a *Remove from
     * Account* button.
     */
    @Test
    public void anIdentityWrittenBeforeProfilesExistedIsStillTheMac() throws Exception {
        writeTheOldShape();

        assertEquals(AdiDeviceIdentity.Hardware.LEGACY_MAC.toJson(),
                subject().hardwareProfileJson());
        assertEquals("MacBookPro13,2", modelOf(subject().hardwareProfileJson()));
    }

    /** Nothing stored at all is a genuinely new install, and gets the profile worth having. */
    @Test
    public void afreshInstallIsAnIphone() throws Exception {
        assertEquals(AdiDeviceIdentity.Hardware.IPHONE.toJson(), subject().hardwareProfileJson());
        assertEquals("iPhone15,2", modelOf(subject().hardwareProfileJson()));
    }

    /**
     * Reading the profile must not quietly rewrite an existing install's identity.
     *
     * <p>Persisting {@code LEGACY_MAC} on read would be harmless today and a trap tomorrow: the
     * derivation is what encodes "this predates the choice", and writing it down converts a
     * fact that can be re-derived into stored state that can be wrong.
     */
    @Test
    public void readingALegacyProfileDoesNotWriteOneOverTheTopOfIt() {
        writeTheOldShape();

        subject().hardwareProfileJson();

        assertFalse("the absence of this key is what marks a legacy install",
                this.preferences.contains(LocalAnisette.KEY_HARDWARE));
        assertEquals(OLD_DEVICE_ID, this.preferences.getString(LocalAnisette.KEY_DEVICE_ID, null));
        assertEquals(OLD_ADI_ID, this.preferences.getString(LocalAnisette.KEY_ADI_ID, null));
        assertEquals(OLD_LOCAL_USER,
                this.preferences.getString(LocalAnisette.KEY_LOCAL_USER, null));
    }

    /**
     * A fresh install's profile is written down, and written down <i>with</i> the identity.
     *
     * <p>If the three keys were persisted and the fourth were not, the very next read would see
     * the legacy shape and decide this brand-new install was a Mac.
     */
    @Test
    public void afreshInstallRecordsWhatItDecided() {
        subject().hardwareProfileJson();

        assertEquals(AdiDeviceIdentity.Hardware.IPHONE.name(),
                this.preferences.getString(LocalAnisette.KEY_HARDWARE, null));
        assertNotNull(this.preferences.getString(LocalAnisette.KEY_DEVICE_ID, null));
        assertNotNull(this.preferences.getString(LocalAnisette.KEY_ADI_ID, null));
        assertNotNull(this.preferences.getString(LocalAnisette.KEY_LOCAL_USER, null));
    }

    /**
     * Asking twice gives the same answer, including across instances.
     *
     * <p>Regenerating per call would make every login look like a new machine, which is
     * precisely what two-factor authentication exists to notice.
     */
    @Test
    public void theIdentityIsGeneratedOnceAndThenKept() {
        final String first = subject().hardwareProfileJson();
        final String storedDeviceId = this.preferences.getString(LocalAnisette.KEY_DEVICE_ID, null);

        assertEquals(first, subject().hardwareProfileJson());
        assertEquals(storedDeviceId,
                this.preferences.getString(LocalAnisette.KEY_DEVICE_ID, null));
    }

    /**
     * A stored profile this version has never heard of falls back rather than throwing.
     *
     * <p>The bad outcome is re-identifying that install; the worse one is an app that cannot
     * start at all, which is what a {@code valueOf} straight off stored text would give after a
     * profile was renamed or removed. It falls back to the Mac because the keys beside it say
     * this install is not new.
     */
    @Test
    public void anUnrecognisedStoredProfileFallsBackInsteadOfCrashing() {
        writeTheOldShape();
        this.preferences.edit().putString(LocalAnisette.KEY_HARDWARE, "VISION_PRO").commit();

        assertEquals(AdiDeviceIdentity.Hardware.LEGACY_MAC.toJson(),
                subject().hardwareProfileJson());
    }

    /** A profile stored explicitly is honoured, which is the whole point of storing it. */
    @Test
    public void astoredProfileIsUsedAsWritten() {
        writeTheOldShape();
        this.preferences.edit()
                .putString(LocalAnisette.KEY_HARDWARE, AdiDeviceIdentity.Hardware.IPHONE.name())
                .commit();

        assertEquals(AdiDeviceIdentity.Hardware.IPHONE.toJson(), subject().hardwareProfileJson());
    }

    /**
     * The ids handed to FindMy.py are the ones stored, not new ones.
     *
     * <p>This is the whole alignment in one assertion: what a legacy install already told Apple
     * during provisioning is what its next sign-in claims to be. Minting a pair here instead
     * would put it back to talking to Apple as two devices, silently.
     */
    @Test
    public void thestoredIdsAreTheOnesHandedOver() throws Exception {
        writeTheOldShape();

        final JSONObject ids = new JSONObject(subject().deviceIdsJson());

        assertEquals(OLD_DEVICE_ID, ids.getString("devid"));
        assertEquals(OLD_LOCAL_USER, ids.getString("uid"));
    }

    /**
     * And the local user id is handed over as stored, never as the header renders it.
     *
     * <p>FindMy.py encodes it on the way out. Handing it the encoded form would encode it twice.
     */
    @Test
    public void theuidIsHandedOverUnencoded() throws Exception {
        writeTheOldShape();
        final AdiDeviceIdentity.Hardware profile = AdiDeviceIdentity.Hardware.LEGACY_MAC;

        final String handedOver = new JSONObject(subject().deviceIdsJson()).getString("uid");

        assertEquals(OLD_LOCAL_USER, handedOver);
        assertEquals("this install's header convention is raw, so these coincide here",
                profile.localUserHeader(OLD_LOCAL_USER), handedOver);
    }

    /** A fresh install's ids are stable too - asking twice must not mint a second device. */
    @Test
    public void afreshInstallsIdsAreStableAcrossCalls() {
        final String first = subject().deviceIdsJson();

        assertEquals(first, subject().deviceIdsJson());
    }

    /**
     * A fresh install hands over exactly what it stored, which is where a double-encode hides.
     *
     * <p>The legacy tests above cannot see this one: that profile sends its local user id raw,
     * so the stored value and the header value coincide and a bug that returned the header form
     * would pass. On a fresh install they differ - base64 against the UUID - and only the UUID
     * is correct, because FindMy.py encodes it again on the way out.
     */
    @Test
    public void afreshInstallHandsOverTheStoredIdsAndNotTheHeaderForms() throws Exception {
        final JSONObject ids = new JSONObject(subject().deviceIdsJson());

        assertEquals(this.preferences.getString(LocalAnisette.KEY_LOCAL_USER, null),
                ids.getString("uid"));
        assertEquals(this.preferences.getString(LocalAnisette.KEY_DEVICE_ID, null),
                ids.getString("devid"));
        assertNotEquals("this is the header form, and handing it over would encode it twice",
                AdiDeviceIdentity.Hardware.IPHONE.localUserHeader(ids.getString("uid")),
                ids.getString("uid"));
    }

    /**
     * A partly written identity is not a legacy install.
     *
     * <p>SharedPreferences applies asynchronously, so a process killed mid-write can leave one
     * key and not the others. Treating that as "from before profiles existed" would hand a
     * brand-new install the Mac, permanently.
     */
    @Test
    public void ahalfWrittenIdentityIsTreatedAsAbsent() {
        assertTrue(this.preferences.edit()
                .putString(LocalAnisette.KEY_DEVICE_ID, OLD_DEVICE_ID)
                .commit());

        assertEquals(AdiDeviceIdentity.Hardware.IPHONE.toJson(), subject().hardwareProfileJson());
    }
}
