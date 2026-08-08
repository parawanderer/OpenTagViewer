package dev.wander.android.opentagviewer.util.android;

import android.content.Context;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.Signature;
import android.os.Build;
import android.util.Log;

import java.security.MessageDigest;
import java.util.Locale;
import java.util.Optional;

/**
 * Reports the running app's package name and signing certificate fingerprints.
 * <br>
 * AMap issues Android API keys bound to a package name plus a SHA-1 signing fingerprint,
 * so anyone registering a key has to supply both. Reading them from the installed package
 * at runtime means the values are always right for the build in the user's hand - debug or
 * release, official or self-built - rather than a static value in a README that goes stale
 * the moment a signing key changes.
 */
public final class SigningInfoUtil {
    private static final String TAG = SigningInfoUtil.class.getSimpleName();

    private SigningInfoUtil() {}

    /**
     * @return the SHA-1 of the certificate this app was signed with, colon-separated and
     *         upper-case (the format AMap's console expects), or empty if it cannot be read.
     */
    public static Optional<String> getSha1Fingerprint(final Context context) {
        return getFingerprint(context, "SHA-1");
    }

    public static Optional<String> getSha256Fingerprint(final Context context) {
        return getFingerprint(context, "SHA-256");
    }

    private static Optional<String> getFingerprint(final Context context, final String algorithm) {
        try {
            final Signature[] signatures = getSignatures(context);
            if (signatures == null || signatures.length == 0) {
                Log.w(TAG, "No signatures found for this package");
                return Optional.empty();
            }

            // The first signer is the one certificate authorities and SDK consoles key off.
            MessageDigest digest = MessageDigest.getInstance(algorithm);
            byte[] hash = digest.digest(signatures[0].toByteArray());

            StringBuilder out = new StringBuilder(hash.length * 3);
            for (int i = 0; i < hash.length; i++) {
                if (i > 0) {
                    out.append(':');
                }
                out.append(String.format(Locale.ROOT, "%02X", hash[i]));
            }
            return Optional.of(out.toString());

        } catch (Exception e) {
            Log.e(TAG, "Failed to compute " + algorithm + " signing fingerprint", e);
            return Optional.empty();
        }
    }

    @SuppressWarnings("deprecation") // GET_SIGNATURES is the only option below API 28
    private static Signature[] getSignatures(final Context context) throws PackageManager.NameNotFoundException {
        final PackageManager packageManager = context.getPackageManager();
        final String packageName = context.getPackageName();

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            PackageInfo info = packageManager.getPackageInfo(
                    packageName, PackageManager.GET_SIGNING_CERTIFICATES);
            if (info.signingInfo == null) {
                return null;
            }
            return info.signingInfo.hasMultipleSigners()
                    ? info.signingInfo.getApkContentsSigners()
                    : info.signingInfo.getSigningCertificateHistory();
        }

        PackageInfo info = packageManager.getPackageInfo(packageName, PackageManager.GET_SIGNATURES);
        return info.signatures;
    }

    /**
     * The block a user pastes into a map provider's console when registering a key.
     */
    public static String getRegistrationDetails(final Context context) {
        return "Package Name: " + context.getPackageName() + "\n"
                + "SHA1: " + getSha1Fingerprint(context).orElse("(unavailable)");
    }
}