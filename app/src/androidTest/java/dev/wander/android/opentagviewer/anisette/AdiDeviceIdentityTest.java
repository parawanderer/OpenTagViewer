package dev.wander.android.opentagviewer.anisette;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

import android.util.Base64;

import androidx.test.ext.junit.runners.AndroidJUnit4;

import dev.wander.android.opentagviewer.anisette.AdiDeviceIdentity.Hardware;

import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Set;

/**
 * The exact strings this app tells Apple it is.
 *
 * <p>These are <b>literals on purpose</b>, which is unusual and worth defending. Composing the
 * expected value from the same enum that produces it would assert only that
 * {@link String#format} works. What needs pinning down is that the bytes have not moved,
 * because a session is bound to them: an install whose client info changes is a different
 * machine, which costs its user a sign-in and leaves an entry in their Apple device list that
 * they are invited to remove.
 *
 * <p>So a failure here is not "update the expected value". It is "somebody has re-identified
 * every install that has this profile, and should have meant to".
 */
@RunWith(AndroidJUnit4.class)
public class AdiDeviceIdentityTest {

    /**
     * What every install before profiles existed sent, preserved exactly.
     *
     * <p>Reproduced from the constant this replaced. The whole point of {@code LEGACY_MAC} is
     * that it did not change, so this string is the specification and the enum is the thing
     * being checked.
     */
    @Test
    public void theLegacyMacsClientInfoHasNotMoved() {
        assertEquals("<MacBookPro13,2> <macOS;13.1;22C65> <com.apple.AuthKit/1 "
                        + "(com.apple.dt.Xcode/3594.4.19)>",
                Hardware.LEGACY_MAC.clientInfo());
    }

    /**
     * Including the part of it that is wrong.
     *
     * <p>macOS 13.1 is Darwin 22.2.0, and this says 22.3.0 - so the user agent and the client
     * info name different releases. Kept, because correcting it would re-identify every install
     * that has this profile to fix a contradiction Apple has evidently never minded.
     */
    @Test
    public void theLegacyMacsUserAgentHasNotMovedEither() {
        assertEquals("akd/1.0 CFNetwork/1404.0.5 Darwin/22.3.0", Hardware.LEGACY_MAC.userAgent());
    }

    /** And the six parts Python is handed have to describe the same machine as those two. */
    @Test
    public void theLegacyMacsProfileSaysTheSameThingAsItsHeaders() throws Exception {
        final JSONObject json = new JSONObject(Hardware.LEGACY_MAC.toJson());

        assertTrue(Hardware.LEGACY_MAC.clientInfo().startsWith(
                "<" + json.getString("model") + "> <" + json.getString("os_name") + ";"
                        + json.getString("os_version") + ";" + json.getString("os_build") + ">"));
        assertTrue(Hardware.LEGACY_MAC.userAgent().endsWith(
                "CFNetwork/" + json.getString("cfnetwork")
                        + " Darwin/" + json.getString("darwin")));
    }

    /**
     * The new profile, whose values are observed rather than invented.
     *
     * <p>From {@code docs/findmy-export/01-authentication.md} section 2.2. Apple synthesises the
     * device-list row from the claimed model, so {@code iPhone15,2} renders as "iPhone 14 Pro"
     * with a phone icon - which is the entire reason for it.
     */
    @Test
    public void theIphoneProfileClaimsOneRealRelease() {
        assertEquals("<iPhone15,2> <iPhone OS;17.4;21E219> <com.apple.AuthKit/1 "
                        + "(com.apple.akd/1.0)>",
                Hardware.IPHONE.clientInfo());
        assertEquals("akd/1.0 CFNetwork/1494.0.7 Darwin/23.4.0", Hardware.IPHONE.userAgent());
    }

    /**
     * Nothing may quietly claim to be a phone and a Mac at once.
     *
     * <p>The failure this guards is a profile added later that copies one of these and edits
     * half of it - a new model beside the old CFNetwork. It is the contradiction
     * {@code DeviceIdentity} requires all six fields to prevent, and Apple's own clients never
     * produce it.
     */
    @Test
    public void everyProfileIsInternallyConsistent() throws Exception {
        for (final Hardware hardware : Hardware.values()) {
            final JSONObject json = new JSONObject(hardware.toJson());
            final boolean phone = json.getString("model").startsWith("iPhone");

            assertEquals(hardware + " names a phone in one field and a Mac in another",
                    phone, "iPhone OS".equals(json.getString("os_name")));
            assertEquals(hardware + "'s user agent describes a different release",
                    hardware.userAgent(),
                    "akd/1.0 CFNetwork/" + hardware.cfnetwork()
                            + " Darwin/" + hardware.darwin());
        }
    }

    /** Two profiles that were the same machine would make the distinction meaningless. */
    @Test
    public void theProfilesAreActuallyDifferentMachines() {
        assertNotEquals(Hardware.LEGACY_MAC.toJson(), Hardware.IPHONE.toJson());
        assertNotEquals(Hardware.LEGACY_MAC.clientInfo(), Hardware.IPHONE.clientInfo());
    }

    /**
     * The keys are FindMy.py's, and every profile supplies all six.
     *
     * <p>{@code DeviceIdentity.from_json} back-fills a missing key from the library's own
     * identity instead of failing, so a field dropped here would not throw anywhere - it would
     * ship a machine that is part this app and part FindMy.py. Python refuses that too; this is
     * the same fence on the side that can actually name the fields.
     */
    @Test
    public void everyProfileSuppliesTheSixFieldsFindMyExpects() throws Exception {
        final Set<String> expected = new HashSet<>(java.util.Arrays.asList(
                "model", "os_name", "os_version", "os_build", "cfnetwork", "darwin"));

        for (final Hardware hardware : Hardware.values()) {
            final JSONObject json = new JSONObject(hardware.toJson());
            final Set<String> actual = new HashSet<>();
            for (final java.util.Iterator<String> keys = json.keys(); keys.hasNext(); ) {
                actual.add(keys.next());
            }

            assertEquals(hardware + " does not describe one whole machine", expected, actual);
        }
    }

    /**
     * A fresh install claims the Mac.
     *
     * <p>It claimed the iPhone until Apple began answering 401 to {@code get_2fa_methods} for
     * clients presenting that profile - provisioning and password auth both succeeded, and the
     * very next request did not. The desktop exporter makes the same call against the same
     * account and is answered, and this profile is byte-identical to what it provisions with.
     *
     * <p>So this asserts a decision taken from evidence, not a preference. If it ever changes
     * back, that has to be because the 2FA question was answered - not because an iPhone icon
     * looks better in a device list.
     */
    @Test
    public void afreshIdentityIsTheMacTheExporterAlsoUses() {
        assertEquals(Hardware.LEGACY_MAC, AdiDeviceIdentity.generate().hardware());
    }

    /**
     * The generated parts keep the lengths ADI rejects other values for.
     *
     * <p>{@code ADISetAndroidID} answers -45001 to an identifier of the wrong size, and that
     * arrives as a login that fails with nothing visibly wrong.
     */
    @Test
    public void afreshIdentityHasTheShapesAdiAccepts() {
        final AdiDeviceIdentity fresh = AdiDeviceIdentity.generate();

        assertEquals(16, fresh.adiIdentifier().length());
        assertEquals(fresh.adiIdentifier().toLowerCase(java.util.Locale.ROOT),
                fresh.adiIdentifier());
        assertEquals(36, fresh.uniqueDeviceIdentifier().length());
        assertEquals(fresh.uniqueDeviceIdentifier().toUpperCase(java.util.Locale.ROOT),
                fresh.uniqueDeviceIdentifier());
    }

    /**
     * The iPhone profile's local user id is a UUID, because FindMy.py's is.
     *
     * <p>Not cosmetic. The value Java provisions ADI with is handed to FindMy.py verbatim and
     * encoded there, so it has to be a string both sides can carry and that Apple has seen in
     * this shape before - which is the UUID every FindMy.py client already sends.
     */
    @Test
    public void theiphoneProfilesLocalUserIdIsAUuid() {
        final String id = Hardware.IPHONE.newLocalUserId(new java.security.SecureRandom());

        assertEquals(36, id.length());
        assertEquals(id.toUpperCase(java.util.Locale.ROOT), id);
        java.util.UUID.fromString(id);
    }

    /**
     * And provisioning sends the encoded form, which is what FindMy.py will send at login.
     *
     * <p>This equality <i>is</i> the alignment. Java's ADI provisioning is one exchange, made
     * once, before FindMy.py exists; the login happens later and FindMy.py composes the header
     * itself as {@code base64(uid)}. If these two ever differ, one installation is introducing
     * itself to Apple as two.
     */
    @Test
    public void theiphoneProfileProvisionsUnderWhatFindMyWouldSend() {
        final String id = Hardware.IPHONE.newLocalUserId(new java.security.SecureRandom());
        final String whatJavaSends = Hardware.IPHONE.localUserHeader(id);
        final String whatFindMyWillSend = Base64.encodeToString(
                id.getBytes(StandardCharsets.UTF_8), Base64.NO_WRAP);

        assertEquals(whatFindMyWillSend, whatJavaSends);
    }

    /**
     * A legacy install sends it raw, and keeps doing so.
     *
     * <p>Changing this would only matter if such an install ever re-provisioned - which happens
     * when somebody resets Anisette - and it should then send what it sent the first time.
     */
    @Test
    public void alegacyInstallSendsItsLocalUserIdUnencoded() {
        final String stored = "3F2A1B0C9D8E7F6A5B4C3D2E1F0A9B8C7D6E5F4A3B2C1D0E9F8A7B6C5D4E3F2A";

        assertEquals(stored, Hardware.LEGACY_MAC.localUserHeader(stored));
    }

    /** Two installs must not be the same machine. */
    @Test
    public void twoFreshIdentitiesAreDifferent() {
        assertNotEquals(AdiDeviceIdentity.generate().uniqueDeviceIdentifier(),
                AdiDeviceIdentity.generate().uniqueDeviceIdentifier());
        assertNotEquals(AdiDeviceIdentity.generate().adiIdentifier(),
                AdiDeviceIdentity.generate().adiIdentifier());
    }
}
