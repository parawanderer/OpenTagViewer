package dev.wander.android.opentagviewer.util.parse;

import java.security.SecureRandom;
import java.util.Locale;

/**
 * Turning what somebody typed into the exact string a bundle was locked with.
 *
 * <p><b>This is an interoperability contract, not a convenience.</b> The exporter's
 * {@code opentagviewer_export/passcode.py} does the same thing on the way in, and a zip password
 * is compared as bytes - so one character of difference here reads as the wrong code, and the
 * user is told their correct code is wrong. Any change to either side has to be made to both.
 *
 * <p>The alphabet is Crockford's base32: the digits plus the letters, minus {@code I}, {@code L},
 * {@code O} and {@code U}. The first three are left out precisely <em>because</em> they get
 * misread as {@code 1}, {@code 1} and {@code 0} - and a code is written on paper and read back by
 * a second person, which is exactly the case that goes wrong. So they are accepted on input and
 * folded onto the digits they were mistaken for. {@code U} is dropped so a random code cannot
 * spell something unfortunate.
 *
 * <p>Grouping is display only. The exporter shows {@code H4K2-9WMR-7TQX}; the password is
 * {@code H4K29WMR7TQX}.
 */
public final class BundlePasscode {

    /** Crockford's base32. Must stay identical to {@code PASSCODE_ALPHABET} in the exporter. */
    public static final String ALPHABET = "0123456789ABCDEFGHJKMNPQRSTVWXYZ";

    /** What the exporter generates. Not enforced on input - an older bundle may differ. */
    public static final int LENGTH = 12;

    /** Anything a person might put between groups, including what a paste can drag in. */
    private static final String SEPARATORS = " -_\t\r\n";

    /** How the code is broken up for reading. Display only - the password has no hyphens. */
    private static final int GROUP = 4;

    private BundlePasscode() {}

    /**
     * A new code, for a bundle this app is about to write.
     *
     * <p><b>{@link SecureRandom}, and it is not a formality.</b> The zip format derives its key
     * with PBKDF2-HMAC-SHA1 at 1000 iterations, which is fixed by the format and cannot be
     * raised - so the code itself is the whole of the security, and twelve characters of this
     * alphabet is about sixty bits. A predictable generator would reduce that to the seed, and
     * an exported accessory cannot be revoked except by unpairing it.
     *
     * <p>Matches {@code generate_passcode} in {@code opentagviewer_export/passcode.py}: same
     * alphabet, same length. It has to, because either program's bundle is opened by this app.
     */
    public static String generate() {
        final SecureRandom random = new SecureRandom();
        final StringBuilder code = new StringBuilder(LENGTH);

        for (int i = 0; i < LENGTH; i++) {
            code.append(ALPHABET.charAt(random.nextInt(ALPHABET.length())));
        }

        return code.toString();
    }

    /**
     * The code as a person should see it: {@code H4K2-9WMR-7TQX}.
     *
     * <p><b>Grouping is for reading and typing, and nothing else.</b> The password is the
     * undelimited string - {@link #normalise} folds these hyphens straight back out, which is
     * what lets somebody paste either form into the import dialog.
     *
     * <p>Twelve unbroken characters is what people mis-transcribe; the alphabet already drops the
     * letters that get misread, and grouping handles the rest of the problem, which is losing
     * your place.
     */
    public static String format(final String code) {
        final StringBuilder grouped = new StringBuilder(code.length() + code.length() / GROUP);

        for (int i = 0; i < code.length(); i++) {
            if (i > 0 && i % GROUP == 0) {
                grouped.append('-');
            }
            grouped.append(code.charAt(i));
        }

        return grouped.toString();
    }

    /**
     * The exact string the bundle was encrypted with.
     *
     * @throws PasscodeFormatException if nothing usable was typed, or what is left holds
     *                                 characters no code can contain
     */
    public static String normalise(final String typed) throws PasscodeFormatException {
        if (typed == null) {
            throw new PasscodeFormatException("No code was typed.");
        }

        // Upper-cased first, so that a lower-case l folds to 1 the way an upper-case L does.
        final String upper = typed.toUpperCase(Locale.ROOT);
        final StringBuilder cleaned = new StringBuilder(upper.length());

        for (int i = 0; i < upper.length(); i++) {
            final char c = upper.charAt(i);
            if (SEPARATORS.indexOf(c) >= 0) {
                continue;
            }
            cleaned.append(fold(c));
        }

        if (cleaned.length() == 0) {
            throw new PasscodeFormatException("No code was typed.");
        }

        for (int i = 0; i < cleaned.length(); i++) {
            if (ALPHABET.indexOf(cleaned.charAt(i)) < 0) {
                throw new PasscodeFormatException(
                        "A code is made only of " + ALPHABET + ", and this one holds "
                                + cleaned.charAt(i));
            }
        }

        return cleaned.toString();
    }

    /** Whether this could be a code at all, for deciding if a button should be enabled. */
    public static boolean isPlausible(final String typed) {
        try {
            normalise(typed);
            return true;
        } catch (PasscodeFormatException e) {
            return false;
        }
    }

    /** The confusable letters, onto the digits they are written for. */
    private static char fold(final char c) {
        switch (c) {
            case 'O':
                return '0';
            case 'I':
            case 'L':
                return '1';
            default:
                return c;
        }
    }

    /**
     * What was typed cannot be read as a code at all.
     *
     * <p>Distinct from a code that is well-formed and simply wrong: this one can be answered
     * before touching the file, and says something different to the user.
     */
    public static class PasscodeFormatException extends Exception {
        public PasscodeFormatException(String message) {
            super(message);
        }
    }
}
