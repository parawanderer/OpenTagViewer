package dev.wander.android.opentagviewer.util.parse;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import dev.wander.android.opentagviewer.util.parse.BundlePasscode.PasscodeFormatException;

/**
 * The half of the passcode contract that lives on this side.
 *
 * <p>The exporter locks bundles with the string its own {@code normalise_passcode} produces, and
 * a zip password is compared as bytes. So the only thing that matters here is that these two
 * agree exactly: if they diverge, a user with the correct code written on paper is told it is
 * wrong, with nothing on either side to suggest why.
 *
 * <p>The cases below are taken from {@code opentagviewer_export/passcode.py} - its docstrings
 * state the accepted forms, and each one is asserted here rather than assumed.
 *
 * <p>A JVM test on purpose: no Android in it, so a divergence is caught in seconds rather than
 * behind an emulator.
 */
public class BundlePasscodeTest {

    /** A code as the exporter would generate and display it. */
    private static final String RAW = "H4K29WMR7TQX";
    private static final String GROUPED = "H4K2-9WMR-7TQX";

    @Test
    public void anUngroupedCodePassesThroughUnchanged() throws Exception {
        assertEquals(RAW, BundlePasscode.normalise(RAW));
    }

    /** The form the exporter actually shows. Hyphens are for reading, not part of the password. */
    @Test
    public void theGroupedFormLosesItsHyphens() throws Exception {
        assertEquals(RAW, BundlePasscode.normalise(GROUPED));
    }

    @Test
    public void typingItInLowerCaseWorks() throws Exception {
        assertEquals(RAW, BundlePasscode.normalise(GROUPED.toLowerCase()));
    }

    /**
     * Every separator the exporter drops, including the ones a paste drags in.
     *
     * <p>The handover doc summarises this as "strip spaces and hyphens", which is not the whole
     * list - {@code passcode.py} also drops underscores, tabs and newlines. Copying a code out
     * of an email brings a trailing newline with it far more often than anybody types one.
     */
    @Test
    public void everySeparatorTheExporterDropsIsDropped() throws Exception {
        assertEquals(RAW, BundlePasscode.normalise(" H4K2 9WMR 7TQX "));
        assertEquals(RAW, BundlePasscode.normalise("H4K2_9WMR_7TQX"));
        assertEquals(RAW, BundlePasscode.normalise("H4K2\t9WMR\r\n7TQX\n"));
    }

    /**
     * The reason the alphabet excludes these letters in the first place.
     *
     * <p>{@code I}, {@code L} and {@code O} are left out because people write them for
     * {@code 1}, {@code 1} and {@code 0}. A code read off paper depends on them folding back.
     */
    @Test
    public void confusableLettersFoldOntoTheDigitsTheyAreWrittenFor() throws Exception {
        assertEquals("0123", BundlePasscode.normalise("OI23"));
        assertEquals("0123", BundlePasscode.normalise("OL23"));
        assertEquals("11", BundlePasscode.normalise("IL"));
    }

    /** Lower case has to fold too, or a pasted lower-case code fails for one character. */
    @Test
    public void lowerCaseConfusablesFoldAsWell() throws Exception {
        assertEquals("0123", BundlePasscode.normalise("oi23"));
        assertEquals("11", BundlePasscode.normalise("il"));
    }

    /**
     * {@code U} is excluded from the alphabet and is <b>not</b> a confusable.
     *
     * <p>It is dropped so a random code cannot spell something unfortunate, not because anybody
     * misreads it - so it has no digit to fold onto and must be rejected. Folding it to
     * something would silently change a code.
     */
    @Test
    public void uIsRejectedRatherThanFolded() {
        assertThrows(PasscodeFormatException.class, () -> BundlePasscode.normalise("H4K2U9WM"));
    }

    @Test
    public void nothingTypedIsRejected() {
        assertThrows(PasscodeFormatException.class, () -> BundlePasscode.normalise(""));
        assertThrows(PasscodeFormatException.class, () -> BundlePasscode.normalise("   "));
        assertThrows(PasscodeFormatException.class, () -> BundlePasscode.normalise("- - -"));
        assertThrows(PasscodeFormatException.class, () -> BundlePasscode.normalise(null));
    }

    @Test
    public void charactersNoCodeCanContainAreRejected() {
        assertThrows(PasscodeFormatException.class, () -> BundlePasscode.normalise("H4K2!9WM"));
        assertThrows(PasscodeFormatException.class, () -> BundlePasscode.normalise("H4K2@9WM"));
    }

    /**
     * The alphabet itself, character for character.
     *
     * <p>Asserted as a literal rather than derived, because deriving it from the same idea that
     * built it would agree with a mistake. This is the string in {@code passcode.py}.
     */
    @Test
    public void theAlphabetMatchesTheExporters() {
        assertEquals("0123456789ABCDEFGHJKMNPQRSTVWXYZ", BundlePasscode.ALPHABET);
        assertEquals(12, BundlePasscode.LENGTH);

        for (final char excluded : new char[] {'I', 'L', 'O', 'U'}) {
            assertEquals("the alphabet must not contain " + excluded,
                    -1, BundlePasscode.ALPHABET.indexOf(excluded));
        }
    }

    /** Every character of the alphabet survives a round trip, so none is accidentally folded. */
    @Test
    public void everyCharacterOfTheAlphabetIsAcceptedAsItself() throws Exception {
        assertEquals(BundlePasscode.ALPHABET, BundlePasscode.normalise(BundlePasscode.ALPHABET));
    }

    @Test
    public void plausibilityFollowsWhetherItWouldNormalise() {
        assertTrue(BundlePasscode.isPlausible(GROUPED));
        assertFalse(BundlePasscode.isPlausible(""));
        assertFalse(BundlePasscode.isPlausible("nope!"));
    }
}
