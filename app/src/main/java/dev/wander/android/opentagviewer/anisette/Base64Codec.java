package dev.wander.android.opentagviewer.anisette;

import android.util.Base64;

/**
 * Base64, as the emulated Mac's Java side needs it, behind an interface the JVM tests can fill.
 *
 * <p>{@code android.util.Base64} is a stub that returns null on the JVM
 * ({@code isReturnDefaultValues}), and {@code java.util.Base64} needs API 26 where this app's
 * {@code minSdk} is 24. So the app uses {@link #ANDROID} and the tests pass {@code java.util}'s.
 */
interface Base64Codec {

    /** No line breaks, which is what every header and plist field here wants. */
    String encode(byte[] data);

    /** Lenient about whitespace, as Apple's plists wrap their base64. */
    byte[] decode(String text);

    Base64Codec ANDROID = new Base64Codec() {
        @Override
        public String encode(final byte[] data) {
            return Base64.encodeToString(data, Base64.NO_WRAP);
        }

        @Override
        public byte[] decode(final String text) {
            return Base64.decode(text, Base64.DEFAULT);
        }
    };
}
