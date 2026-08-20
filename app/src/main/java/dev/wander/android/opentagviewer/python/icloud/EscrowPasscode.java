package dev.wander.android.opentagviewer.python.icloud;

import android.util.Base64;

import java.security.SecureRandom;

/**
 * The passcode this app's own escrow record is enrolled under.
 *
 * <p><b>Not a passcode anybody types.</b> It protects the record created when this device is
 * added to the user's account, and it is generated, stored and used entirely by the app. Every
 * reason a screen-lock passcode is short - somebody has to remember it, somebody has to key it in
 * on a phone - simply does not apply, so the only sensible size is one nobody would attempt.
 *
 * <p><b>Deliberately not Crockford base32</b>, which is what
 * {@link dev.wander.android.opentagviewer.util.parse.BundlePasscode} uses. That alphabet drops
 * I, L, O and U so a person reading a code off one screen does not mistype it into another - a
 * real constraint there, and an irrelevant one here that costs entropy per character to honour.
 * Sharing it would also tie this to a decision made for legibility: narrowing that alphabet
 * further, for better reasons, would quietly weaken this.
 *
 * <p>So the strength is stated in <b>bytes of randomness</b> and the encoding is incidental -
 * base64url only because it survives every layer between here and Apple without escaping, and
 * because 64 divides 256 evenly, so no character is likelier than another.
 *
 * <p>Deliberately not numeric, either. Enrolment publishes
 * {@code SecureBackupUsesNumericPassphrase} in the record's metadata, and a numeric passphrase
 * announces itself as the kind of thing a six-digit PIN protects. The honest disclosure here is
 * "not a PIN".
 */
public final class EscrowPasscode {

    private EscrowPasscode() {
    }

    /**
     * Bytes of randomness behind each passcode: <b>256 bits</b>.
     *
     * <p>There is no upper bound in the protocol. Enrolment rejects only an <i>empty</i> passcode,
     * on the grounds that a record enrolled under one "could be recovered by anyone" - everything
     * above that is the caller's choice, and nothing about this one is rationed.
     */
    public static final int ENTROPY_BYTES = 32;

    /**
     * A fresh passcode, from the platform's cryptographically secure source.
     *
     * <p>{@link SecureRandom} rather than {@code Random}: this is the only secret protecting a
     * record that can yield the keys to the user's Find My data, and a predictable secret is
     * indistinguishable from a strong one by looking at it.
     */
    public static String generate() {
        final byte[] entropy = new byte[ENTROPY_BYTES];
        new SecureRandom().nextBytes(entropy);

        // NO_WRAP and NO_PADDING so the value is one unbroken token: a newline or an `=` in
        // something that travels through a plist and an SRP exchange is an avoidable variable.
        return Base64.encodeToString(
                entropy, Base64.URL_SAFE | Base64.NO_WRAP | Base64.NO_PADDING);
    }

    /**
     * Whether a stored value is one of ours and still intact.
     *
     * <p>Worth checking rather than assuming: this is read back from storage before being used to
     * recover, and a truncated or empty one fails in a way indistinguishable from Apple refusing
     * the exchange - which would send somebody hunting the wrong problem entirely.
     */
    public static boolean isWellFormed(final String passcode) {
        if (passcode == null || passcode.isEmpty()) {
            return false;
        }

        try {
            final byte[] decoded = Base64.decode(
                    passcode, Base64.URL_SAFE | Base64.NO_WRAP | Base64.NO_PADDING);
            return decoded.length == ENTROPY_BYTES;
        } catch (IllegalArgumentException e) {
            return false;
        }
    }
}
