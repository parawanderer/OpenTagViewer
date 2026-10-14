package dev.wander.android.opentagviewer.anisette;

import static androidx.test.platform.app.InstrumentationRegistry.getInstrumentation;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
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
 * means having installed an older build, signed in to a real Apple account on it, and then
 * upgrading - and the way it fails is that Apple stops recognising the machine hours later, on
 * somebody else's phone, with nothing in a log to say why. So the older shapes are written into
 * {@link LocalAnisette#PREFERENCES} directly and read back through the same door the app uses.
 *
 * <p>The rule being tested is two sentences. <b>An install whose profile can still sign in
 * keeps the machine it has always claimed</b> - its ids, its serial, its profile. <b>An install
 * whose profile Apple refuses is replaced</b>, once, by one that can: since 2026-10-12 that is
 * every install claiming the Mac or the iPhone, see {@link AdiDeviceIdentity#generate()}.
 * Everything below is a way of getting one of those wrong.
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

    /** Three keys and no profile: an install from before there was a choice. */
    private void writeTheOldShape() {
        assertTrue(this.preferences.edit()
                .putString(LocalAnisette.KEY_DEVICE_ID, OLD_DEVICE_ID)
                .putString(LocalAnisette.KEY_ADI_ID, OLD_ADI_ID)
                .putString(LocalAnisette.KEY_LOCAL_USER, OLD_LOCAL_USER)
                .commit());
    }

    /** The old ids under an explicit profile, as an install that chose one would have them. */
    private void writeTheOldShapeAs(final AdiDeviceIdentity.Hardware hardware) {
        writeTheOldShape();
        assertTrue(this.preferences.edit()
                .putString(LocalAnisette.KEY_HARDWARE, hardware.name())
                .commit());
    }

    private static String modelOf(String json) throws Exception {
        return new JSONObject(json).getString("model");
    }

    private void assertTheOldIdsWereReplaced() {
        assertNotEquals(OLD_DEVICE_ID, this.preferences.getString(LocalAnisette.KEY_DEVICE_ID, null));
        assertNotEquals(OLD_ADI_ID, this.preferences.getString(LocalAnisette.KEY_ADI_ID, null));
        assertNotEquals(OLD_LOCAL_USER,
                this.preferences.getString(LocalAnisette.KEY_LOCAL_USER, null));
        assertEquals(AdiDeviceIdentity.Hardware.ANDROID.name(),
                this.preferences.getString(LocalAnisette.KEY_HARDWARE, null));
    }

    private void assertTheOldIdsWereKept() {
        assertEquals(OLD_DEVICE_ID, this.preferences.getString(LocalAnisette.KEY_DEVICE_ID, null));
        assertEquals(OLD_ADI_ID, this.preferences.getString(LocalAnisette.KEY_ADI_ID, null));
        assertEquals(OLD_LOCAL_USER, this.preferences.getString(LocalAnisette.KEY_LOCAL_USER, null));
    }

    /**
     * The one that matters.
     *
     * <p>Three keys and no hardware key can only mean an install from before there was a
     * choice, and the only thing this app ever claimed then was the Mac - which Apple no longer
     * lets sign in. Keeping it would be an install that can never sign in again, so it is
     * replaced by the one profile that can, ids and all: a machine id provisioned under the
     * Mac claim and presented under the Android one is answered with a re-sync demand, so a
     * half-migration that kept the ids and changed the claim would not work either.
     */
    @Test
    public void anIdentityWrittenBeforeProfilesExistedIsReplacedByTheAndroidPhone()
            throws Exception {
        writeTheOldShape();

        assertEquals(AdiDeviceIdentity.Hardware.ANDROID.toJson(),
                subject().hardwareProfileJson());
        assertEquals("Pixel 7", modelOf(subject().hardwareProfileJson()));
        assertTheOldIdsWereReplaced();
    }

    /** Nothing stored at all is a genuinely new install - and it claims the Android phone. */
    @Test
    public void afreshInstallIsTheAndroidPhone() throws Exception {
        assertEquals(AdiDeviceIdentity.Hardware.ANDROID.toJson(),
                subject().hardwareProfileJson());
        assertEquals("Pixel 7", modelOf(subject().hardwareProfileJson()));
    }

    /**
     * An install that already claims the Android phone keeps everything it has.
     *
     * <p>This is the rule the replacement must not break: re-identifying an install that
     * works would make its session look like a new machine, which is what two-factor
     * authentication exists to notice.
     */
    @Test
    public void anInstallThatCanSignInKeepsItsIdentity() throws Exception {
        writeTheOldShapeAs(AdiDeviceIdentity.Hardware.ANDROID);

        assertEquals(AdiDeviceIdentity.Hardware.ANDROID.toJson(),
                subject().hardwareProfileJson());
        assertTheOldIdsWereKept();
    }

    /**
     * A replaced identity is written down, so the replacement happens exactly once.
     *
     * <p>Left unwritten, every read would see the legacy shape again and mint another machine,
     * which is the per-login regeneration the whole class exists to prevent.
     */
    @Test
    public void thereplacementIsRecordedSoItHappensOnce() {
        writeTheOldShape();

        subject().hardwareProfileJson();
        final String replacedDeviceId =
                this.preferences.getString(LocalAnisette.KEY_DEVICE_ID, null);

        assertEquals(AdiDeviceIdentity.Hardware.ANDROID.name(),
                this.preferences.getString(LocalAnisette.KEY_HARDWARE, null));
        subject().hardwareProfileJson();
        assertEquals("asked again, the replacement was kept rather than replaced again",
                replacedDeviceId, this.preferences.getString(LocalAnisette.KEY_DEVICE_ID, null));
    }

    /**
     * A fresh install's profile is written down, and written down <i>with</i> the identity.
     *
     * <p>If the three keys were persisted and the fourth were not, the very next read would see
     * the legacy shape, decide this brand-new install was a Mac, and replace it.
     */
    @Test
    public void afreshInstallRecordsWhatItDecided() {
        subject().hardwareProfileJson();

        assertEquals(AdiDeviceIdentity.Hardware.ANDROID.name(),
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
     * <p>The fallback is the Mac, because the keys beside it say this install is not new - and
     * the Mac cannot sign in, so it is then replaced like any other. The outcome is a
     * re-identified install, which is bad but recoverable; a {@code valueOf} straight off
     * stored text would give an app that cannot start at all.
     */
    @Test
    public void anUnrecognisedStoredProfileFallsBackInsteadOfCrashing() {
        writeTheOldShape();
        this.preferences.edit().putString(LocalAnisette.KEY_HARDWARE, "VISION_PRO").commit();

        assertEquals(AdiDeviceIdentity.Hardware.ANDROID.toJson(),
                subject().hardwareProfileJson());
        assertTheOldIdsWereReplaced();
    }

    /** The iPhone was never a fresh install's default, but an install that has it is refused too. */
    @Test
    public void astoredIphoneIsReplacedAsWell() {
        writeTheOldShapeAs(AdiDeviceIdentity.Hardware.IPHONE);

        assertEquals(AdiDeviceIdentity.Hardware.ANDROID.toJson(), subject().hardwareProfileJson());
        assertTheOldIdsWereReplaced();
    }

    /**
     * The ids handed to FindMy.py are the ones stored, not new ones.
     *
     * <p>This is the whole alignment in one assertion: what an install already told Apple
     * during provisioning is what its next sign-in claims to be. Minting a pair here instead
     * would put it back to talking to Apple as two devices, silently.
     */
    @Test
    public void thestoredIdsAreTheOnesHandedOver() throws Exception {
        writeTheOldShapeAs(AdiDeviceIdentity.Hardware.ANDROID);

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
        writeTheOldShapeAs(AdiDeviceIdentity.Hardware.ANDROID);

        final String handedOver = new JSONObject(subject().deviceIdsJson()).getString("uid");

        assertEquals(OLD_LOCAL_USER, handedOver);
        assertNotEquals("this is the header form, and handing it over would encode it twice",
                AdiDeviceIdentity.Hardware.ANDROID.localUserHeader(OLD_LOCAL_USER), handedOver);
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
     * <p>The Android profile sends its local user id as base64 of the stored UUID, so the
     * stored value and the header value differ - and only the stored one is correct, because
     * FindMy.py encodes it again on the way out.
     */
    @Test
    public void afreshInstallHandsOverTheStoredIdsAndNotTheHeaderForms() throws Exception {
        final JSONObject ids = new JSONObject(subject().deviceIdsJson());

        assertEquals(this.preferences.getString(LocalAnisette.KEY_LOCAL_USER, null),
                ids.getString("uid"));
        assertEquals(this.preferences.getString(LocalAnisette.KEY_DEVICE_ID, null),
                ids.getString("devid"));
        assertNotEquals("this is the header form, and handing it over would encode it twice",
                AdiDeviceIdentity.Hardware.ANDROID.localUserHeader(ids.getString("uid")),
                ids.getString("uid"));
    }

    /**
     * A partly written identity is not a legacy install.
     *
     * <p>SharedPreferences applies asynchronously, so a process killed mid-write can leave one
     * key and not the others. It is treated as absent and a fresh identity written.
     */
    @Test
    public void ahalfWrittenIdentityIsTreatedAsAbsent() {
        assertTrue(this.preferences.edit()
                .putString(LocalAnisette.KEY_DEVICE_ID, OLD_DEVICE_ID)
                .commit());

        assertEquals(AdiDeviceIdentity.Hardware.ANDROID.toJson(),
                subject().hardwareProfileJson());
        // Not the point of this test, but worth pinning: it wrote a *new* identity rather than
        // adopting the orphaned device id.
        assertNotEquals(OLD_DEVICE_ID,
                this.preferences.getString(LocalAnisette.KEY_DEVICE_ID, null));
    }

    /**
     * An install from before serials were drawn keeps the one it has been presenting.
     *
     * <p>Three keys and a profile that can sign in, but no serial, can only be an install that
     * has been telling Apple {@code 0PENTAGVIEWR} for its whole life. Drawing it a new one now
     * registers a <i>second</i> device beside the row the user already recognises, and may cost
     * them a sign-in - to change a label.
     */
    @Test
    public void anIdentityWrittenBeforeSerialsWereDrawnStillPresentsTheOldOne() {
        writeTheOldShapeAs(AdiDeviceIdentity.Hardware.ANDROID);

        assertEquals(AdiDeviceIdentity.LEGACY_SERIAL, subject().serial());
    }

    /** And it is not back-filled, so it stays distinguishable from a serial that was drawn. */
    @Test
    public void thelegacySerialIsNotWrittenBackOverTheOldShape() {
        writeTheOldShapeAs(AdiDeviceIdentity.Hardware.ANDROID);

        subject().serial();

        assertNull("writing it back would make an old install look like one that drew it",
                this.preferences.getString(LocalAnisette.KEY_SERIAL, null));
    }

    /**
     * A replaced identity draws a serial with the rest of it.
     *
     * <p>It is a new device-list row either way, so there is nothing for the old serial to
     * keep recognisable - and the old row is the one the user is about to see a second entry
     * beside, which a different serial at least makes tellable apart.
     */
    @Test
    public void areplacedIdentityDrawsASerial() {
        writeTheOldShape();

        final String drawn = subject().serial();

        assertTrue(drawn.startsWith(AdiDeviceIdentity.SERIAL_PREFIX));
        assertNotEquals(AdiDeviceIdentity.LEGACY_SERIAL, drawn);
    }

    /**
     * A fresh install draws one, and <b>keeps it</b>.
     *
     * <p>The keeping is the part that matters. A serial redrawn per sign-in would add a
     * device-list entry every time, which is the failure the constant did not have and this
     * change could easily introduce.
     */
    @Test
    public void afreshInstallDrawsASerialAndThenKeepsIt() {
        final String drawn = subject().serial();

        assertTrue("a drawn serial has to be recognisable as this app",
                drawn.startsWith(AdiDeviceIdentity.SERIAL_PREFIX));
        assertNotEquals("a fresh install is not an install from before this change",
                AdiDeviceIdentity.LEGACY_SERIAL, drawn);
        assertEquals("it was stored, so a restart presents the same device",
                drawn, this.preferences.getString(LocalAnisette.KEY_SERIAL, null));
        assertEquals("asked twice, answered twice the same", drawn, subject().serial());
    }

    /** A stored serial is presented unchanged, whatever it is. */
    @Test
    public void astoredSerialIsWhatGoesToApple() {
        writeTheOldShapeAs(AdiDeviceIdentity.Hardware.ANDROID);
        assertTrue(this.preferences.edit()
                .putString(LocalAnisette.KEY_SERIAL, "0PENTAGVK7QX").commit());

        assertEquals("0PENTAGVK7QX", subject().serial());
    }

    /**
     * The screen asks read-only, and a screen must not be what decides an install's identity.
     *
     * <p>Same contract as {@link LocalAnisette#profileToShow}: opening a page on a device that
     * has never signed in would otherwise mint and store an identity, fixing what this install
     * is by having looked at it.
     */
    @Test
    public void showingTheSerialDoesNotMintOne() {
        assertEquals(AdiDeviceIdentity.LEGACY_SERIAL, LocalAnisette.serialToShow(this.context));

        assertNull("reading it for a label must not write one",
                this.preferences.getString(LocalAnisette.KEY_SERIAL, null));
        assertNull(this.preferences.getString(LocalAnisette.KEY_DEVICE_ID, null));
    }

    /** And once there is one, that is what the screen shows. */
    @Test
    public void showingTheSerialShowsTheOneThisInstallWillSend() {
        final String drawn = subject().serial();

        assertEquals("the screen named a serial Apple never saw for this user",
                drawn, LocalAnisette.serialToShow(this.context));
    }

    /**
     * The screen shows the profile the next load will actually use, not the one about to go.
     *
     * <p>A stored Mac is replaced on the next identity read; telling the user to look for a
     * MacBook row would describe the entry they are about to see a Pixel appear beside.
     */
    @Test
    public void showingTheProfileShowsTheOneThisInstallWillSend() {
        writeTheOldShape();

        assertEquals(AdiDeviceIdentity.Hardware.ANDROID, LocalAnisette.profileToShow(this.context));
        assertNull("reading it for a label must not replace anything",
                this.preferences.getString(LocalAnisette.KEY_HARDWARE, null));
        assertEquals(OLD_DEVICE_ID, this.preferences.getString(LocalAnisette.KEY_DEVICE_ID, null));
    }
}
