package dev.wander.android.opentagviewer.python.icloud;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

import androidx.test.ext.junit.runners.AndroidJUnit4;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.HashSet;
import java.util.Set;

/**
 * The passcode this app's own escrow record is enrolled under.
 *
 * <p>Nobody ever sees it, which removes every constraint a human-facing code has and leaves only
 * one: that it is genuinely unguessable. These check the properties that would still look fine
 * if they were wrong - a generator that repeated itself, or one that quietly produced something
 * far shorter than intended, reads identically in a log.
 */
@RunWith(AndroidJUnit4.class)
public class EscrowPasscodeTest {

    @Test
    public void itcarriesTheEntropyItClaims() {
        assertTrue("256 bits is the point of it", EscrowPasscode.ENTROPY_BYTES * 8 >= 256);
        assertTrue(EscrowPasscode.isWellFormed(EscrowPasscode.generate()));
    }

    /**
     * Every passcode is different.
     *
     * <p>The failure this rules out is a generator seeded once, or one accidentally returning a
     * constant - both of which produce a perfectly well-formed value that protects nothing, and
     * neither of which looks wrong anywhere.
     */
    @Test
    public void ineverRepeatsItself() {
        final Set<String> seen = new HashSet<>();

        for (int i = 0; i < 500; i++) {
            seen.add(EscrowPasscode.generate());
        }

        assertTrue("the generator repeated itself, so it is not random", seen.size() == 500);
    }

    @Test
    public void twoInARowDiffer() {
        assertNotEquals(EscrowPasscode.generate(), EscrowPasscode.generate());
    }

    /**
     * It is not a PIN, and the record's metadata says as much.
     *
     * <p>Enrolment publishes {@code SecureBackupUsesNumericPassphrase}, computed by asking
     * whether every character is a digit. An all-numeric passcode would advertise itself as the
     * sort of secret a six-digit code protects.
     */
    @Test
    public void itisNeverNumeric() {
        for (int i = 0; i < 200; i++) {
            final String passcode = EscrowPasscode.generate();

            boolean allDigits = true;
            for (int c = 0; c < passcode.length(); c++) {
                if (!Character.isDigit(passcode.charAt(c))) {
                    allDigits = false;
                    break;
                }
            }

            assertFalse("a numeric passcode advertises itself as a PIN", allDigits);
        }
    }

    /**
     * It travels as one unbroken token.
     *
     * <p>It passes through a property list and an SRP exchange. A newline or padding character in
     * the middle is an avoidable variable in a value whose failure mode is indistinguishable from
     * Apple refusing the exchange.
     */
    @Test
    public void itisOneTokenWithNothingToEscape() {
        final String passcode = EscrowPasscode.generate();

        assertFalse(passcode.contains("\n"));
        assertFalse(passcode.contains("="));
        assertFalse(passcode.contains("+"));
        assertFalse(passcode.contains("/"));
        assertFalse(passcode.contains(" "));
    }

    /** A stored value that came back damaged is caught rather than used. */
    @Test
    public void itrejectsWhatIsNotOne() {
        assertFalse(EscrowPasscode.isWellFormed(null));
        assertFalse("empty is the one thing enrolment itself refuses",
                EscrowPasscode.isWellFormed(""));
        assertFalse("a truncated passcode must not pass",
                EscrowPasscode.isWellFormed(EscrowPasscode.generate().substring(0, 10)));
        assertFalse(EscrowPasscode.isWellFormed("123456"));
    }
}
