package dev.wander.android.opentagviewer.db;

/**
 * Something encrypted is still here, and the key that opens it is not.
 *
 * <p><b>Its own type because it is the one decryption failure that is not a bug.</b> The keys
 * live in the Android keystore and the ciphertext lives in this app's data, and those have
 * different lifetimes: an OS upgrade, a keystore that got wiped, or a device-transfer tool that
 * copied app data - which can never copy keystore keys - all leave exactly this. There is no
 * repair; what was written is gone, and the remedy is whatever re-establishes it.
 *
 * <p>Everything else that fails to decrypt is unexplained: the key is present, was used to write
 * the data, and no longer opens it. That is worth a bug report, and telling the two apart is why
 * this class exists rather than one message covering both.
 *
 * <p><b>And the key is never re-created on the decrypt path.</b> It used to be - the same
 * "fetch or generate" helper served encrypt and decrypt - so a missing key was quietly replaced
 * with a new one that could not open anything already written. That turned a problem which might
 * have been momentary into a permanent one, and destroyed the evidence on the way: the alias
 * existed again afterwards, so nothing could tell that it had ever gone.
 */
public class MissingKeystoreKeyException extends AppCryptographyException {

    public MissingKeystoreKeyException(final String message) {
        super(message);
    }
}
