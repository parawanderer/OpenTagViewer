package dev.wander.android.opentagviewer.anisette;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.security.SecureRandom;
import java.util.HashSet;
import java.util.Set;

/**
 * The serial a fresh install draws, and the one property that matters about it.
 *
 * <p><b>Every install must not get the same one.</b> That is the whole point of the change this
 * belongs to: the serial was a constant, so one value went to Apple from thousands of installs,
 * against thousands of different machine identities and Apple IDs, from every continent at once.
 * Real hardware does not look like that, and it is the leading suspect for the Grand Slam 503s
 * in issues #168, #176 and #181. Replacing one constant with a generator that is effectively
 * another constant would change nothing while looking like a fix.
 *
 * <p>So the draws below take a <b>fresh {@link SecureRandom} each time</b>, which is the case
 * being tested: a first run is one process, calling {@link AdiDeviceIdentity#generate()} once,
 * and then never again. A single shared instance producing a good spread would prove nothing
 * about that - it is exactly the shape that hides a fixed seed.
 *
 * <p>JVM rather than instrumented (rule 13): this is arithmetic over a string, and nothing here
 * touches Android.
 */
public class DrawingTheSerialTest {

    private static final int DRAWS = 500;

    /** As a first run does it: one process, one generator, one draw. */
    private static String asAFreshInstallWould() {
        return AdiDeviceIdentity.generateSerial(new SecureRandom());
    }

    @Test
    public void ithasTheShapeAppleAccepts() {
        final String serial = asAFreshInstallWould();

        assertEquals("Apple's serials are twelve characters", 12, serial.length());
        assertTrue("uppercase alphanumeric only", serial.matches("[0-9A-Z]{12}"));
    }

    @Test
    public void itisRecognisableAsThisApp() {
        assertTrue("a user who cannot recognise the entry removes it",
                asAFreshInstallWould().startsWith(AdiDeviceIdentity.SERIAL_PREFIX));
    }

    @Test
    public void everyCharacterDrawnComesFromTheDeclaredAlphabet() {
        for (int i = 0; i < DRAWS; i++) {
            final String tail =
                    asAFreshInstallWould().substring(AdiDeviceIdentity.SERIAL_PREFIX.length());

            for (final char drawn : tail.toCharArray()) {
                assertTrue("drew " + drawn + ", which is outside the alphabet",
                        AdiDeviceIdentity.SERIAL_ALPHABET.indexOf(drawn) >= 0);
            }
        }
    }

    /**
     * The assertion this class exists for.
     *
     * <p>Five hundred separate first runs, five hundred separate generators. The threshold is
     * loose on purpose - four characters from a 26-character alphabet is about 457,000 values, so
     * a collision in 500 draws is possible and not a fault. A <b>seeded</b> generator would
     * produce one distinct value, not 450.
     */
    @Test
    public void afreshInstallDoesNotGetWhatEveryOtherFreshInstallGot() {
        final Set<String> drawn = new HashSet<>();
        for (int i = 0; i < DRAWS; i++) {
            drawn.add(asAFreshInstallWould());
        }

        assertTrue("only " + drawn.size() + " distinct serials in " + DRAWS + " first runs,"
                        + " which is what a seeded generator looks like",
                drawn.size() > 450);
    }

    /** And the identity as a whole carries it, rather than the serial being drawn elsewhere. */
    @Test
    public void ageneratedIdentityAlreadyHasOne() {
        final AdiDeviceIdentity identity = AdiDeviceIdentity.generate();

        assertTrue(identity.serial().startsWith(AdiDeviceIdentity.SERIAL_PREFIX));
        assertNotEquals("a fresh install is not an install from before serials were drawn",
                AdiDeviceIdentity.LEGACY_SERIAL, identity.serial());
        assertNotEquals("two installs are two identities",
                identity.serial(), AdiDeviceIdentity.generate().serial());
    }

    /**
     * The pairs a person comparing this screen against their device list would confuse.
     *
     * <p>Nobody types a serial. They read it off one screen and look for it on another, and the
     * only failure mode is looking at the right row and believing it is the wrong one.
     */
    @Test
    public void thealphabetLeavesOutWhatAReaderWouldConfuse() {
        for (final char confusable : "O0I1S5B8Z2".toCharArray()) {
            assertEquals(confusable + " is a confusable and must not be drawable",
                    -1, AdiDeviceIdentity.SERIAL_ALPHABET.indexOf(confusable));
        }
    }

    /**
     * The old constant can never be drawn, which is what makes it legible in a bug report.
     *
     * <p>{@code IEWR} contains an {@code I}, and the alphabet does not. So a serial with an
     * {@code I} in it is an install from before this change - and that is worth pinning, because
     * a widened alphabet would quietly take the distinction away.
     */
    @Test
    public void thelegacySerialIsNotSomethingAnInstallCanDraw() {
        final String tail = AdiDeviceIdentity.LEGACY_SERIAL
                .substring(AdiDeviceIdentity.SERIAL_PREFIX.length());

        boolean undrawable = false;
        for (final char character : tail.toCharArray()) {
            undrawable |= AdiDeviceIdentity.SERIAL_ALPHABET.indexOf(character) < 0;
        }

        assertTrue("every character of " + AdiDeviceIdentity.LEGACY_SERIAL + " is drawable,"
                + " so a drawn serial can no longer be told from a legacy one", undrawable);
    }
}
