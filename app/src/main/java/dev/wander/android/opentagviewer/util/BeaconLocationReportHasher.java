package dev.wander.android.opentagviewer.util;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Locale;

import dev.wander.android.opentagviewer.data.model.BeaconLocationReport;

public final class BeaconLocationReportHasher {
    private static final String SHA_256 = "SHA-256";

    public static byte[] getSha256BytesFor(final String beaconId, final BeaconLocationReport beaconLocationReport) {
        try {
            // Try to create a consistent format.
            // Some of the fields we have are seemingly invalid
            // and might get fixed in the future, so this only
            // includes seemingly valid ones
            final String standardizedEncoding = String.format(
                    Locale.ROOT,
                    "%s-%d-%d-%d-%s-%.15f-%.15f",
                    beaconId,
                    beaconLocationReport.getStatus(),
                    beaconLocationReport.getTimestamp(),
                    beaconLocationReport.getPublishedAt(),
                    beaconLocationReport.getDescription(),
                    beaconLocationReport.getLatitude(),
                    beaconLocationReport.getLongitude()
            );

            MessageDigest digest = MessageDigest.getInstance(SHA_256);
            return digest.digest(standardizedEncoding.getBytes(StandardCharsets.UTF_8));

        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException(e);
        }
    }

    private static String bytesToHex(byte[] hash) {
        var hexString = new StringBuilder(2 * hash.length);
        for (int i = 0; i < hash.length; ++i) {
            String hex = Integer.toHexString(0xff & hash[i]);
            if (hex.length() == 1) {
                hexString.append('0');
            }
            hexString.append(hex);
        }
        return hexString.toString();
    }

    public static String getSha256HashFor(final String beaconId, final BeaconLocationReport beaconLocationReport) {
        return bytesToHex(getSha256BytesFor(beaconId, beaconLocationReport));
    }
}
