package dev.wander.android.opentagviewer.anisette;

import android.util.Base64;

import org.json.JSONException;
import org.json.JSONObject;

import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Locale;
import java.util.UUID;

/**
 * The identity this device presents to Apple when provisioning Anisette.
 *
 * <p>It is entirely invented. There is no hardware attestation to defeat and nothing to
 * extract from a real Apple device - every public Anisette server in use today runs on values
 * generated exactly like these, which is the working proof that Apple does not check them
 * against anything real.
 *
 * <p>One length is not free. {@code ADISetAndroidID} rejects an identifier of the wrong size
 * with -45001 (invalid parameters), so {@link #adiIdentifier()} matches the reference
 * implementation exactly: 8 random bytes as 16 lowercase hex characters. <b>The other two never
 * reach ADI at all</b> - they are HTTP headers and nothing more - so their shape is a matter of
 * agreeing with whoever else sends them, which is what {@link Hardware} decides.
 *
 * <p><b>The one rule that matters is that this is generated once and then kept.</b> Regenerating
 * it per login would make every session look like a brand new machine to Apple, which is
 * precisely the pattern two-factor authentication exists to notice. So this is persisted, and
 * it must survive anything short of the user deliberately resetting Anisette.
 */
public final class AdiDeviceIdentity {

    private final String uniqueDeviceIdentifier;
    private final String adiIdentifier;
    private final String localUserUuid;
    private final Hardware hardware;

    /**
     * @param hardware which machine this install claims to be. An install that already existed
     *                 before profiles were introduced passes {@link Hardware#LEGACY_MAC}, and
     *                 must keep doing so - see the enum.
     */
    public AdiDeviceIdentity(String uniqueDeviceIdentifier, String adiIdentifier,
                             String localUserUuid, Hardware hardware) {
        this.uniqueDeviceIdentifier = uniqueDeviceIdentifier;
        this.adiIdentifier = adiIdentifier;
        this.localUserUuid = localUserUuid;
        this.hardware = hardware;
    }

    /**
     * A fresh identity. Call once, persist, never call again.
     *
     * <p><b>The Mac, not the iPhone, and that is a retreat from evidence rather than a
     * preference.</b> Claiming an iPhone provisioned fine and signed in fine, and then Apple
     * answered 401 to the very next request - {@code get_2fa_methods}, asking which phone
     * numbers could receive a code. The desktop exporter makes the same call against the same
     * account and is answered, and the largest remaining difference between them was this.
     *
     * <p>An iPhone <i>is</i> a trusted device, so a client claiming to be one asking where to
     * send an SMS code is a question a real iPhone would not ask. That is a guess at the
     * mechanism; what is not a guess is that this profile works and that one did not get past
     * sign-in, and a nicer icon is not worth an app nobody can log into.
     *
     * <p>These values are byte-identical to what the {@code anisette} package provisions with,
     * which is what the exporter uses - so the app and the working program now introduce
     * themselves to Apple as the same machine.
     */
    public static AdiDeviceIdentity generate() {
        final SecureRandom random = new SecureRandom();
        final Hardware hardware = Hardware.LEGACY_MAC;

        return new AdiDeviceIdentity(
                UUID.randomUUID().toString().toUpperCase(Locale.ROOT),
                hex(random, 8).toLowerCase(Locale.ROOT),
                hardware.newLocalUserId(random),
                hardware);
    }

    private static String hex(SecureRandom random, int bytes) {
        final byte[] buffer = new byte[bytes];
        random.nextBytes(buffer);

        final StringBuilder out = new StringBuilder(bytes * 2);
        for (final byte b : buffer) {
            out.append(String.format("%02X", b));
        }
        return out.toString();
    }

    /**
     * The machine this install claims to be, chosen once and then kept.
     *
     * <p>Two profiles, and <b>which one an install has is not a preference</b>: it is part of
     * what Apple binds a session to, so moving an install from one to the other costs that user
     * a sign-in and leaves a second entry in their device list. An install that already has an
     * ADI identity keeps {@link #LEGACY_MAC}, and so, for now, does a fresh one - see
     * {@link #generate()} for why {@link #IPHONE} is built but not chosen.
     *
     * <p>Each carries all six parts because <b>they describe one real release and move
     * together</b> - model, OS, build, CFNetwork and Darwin. FindMy.py's {@code DeviceIdentity}
     * takes the same six for the same reason, and the Python side reads whichever one this
     * install has rather than deciding for itself. Rule 11.
     */
    public enum Hardware {
        /**
         * What every install before this shipped as, preserved exactly.
         *
         * <p><b>Not corrected, deliberately.</b> macOS 13.1 is Darwin 22.2.0, and this sends
         * 22.3.0 - so the client info and the user agent name different releases. It is wrong,
         * it has always been wrong, and changing it now would re-identify every existing
         * install to fix a contradiction Apple has evidently never minded. The new profile does
         * not have it.
         */
        LEGACY_MAC(
                "MacBookPro13,2", "macOS", "13.1", "22C65",
                "1404.0.5", "22.3.0",
                "com.apple.dt.Xcode/3594.4.19") {

            /** 32 bytes as 64 uppercase hex, which is what Dadoum's Provision generates. */
            @Override
            String newLocalUserId(SecureRandom random) {
                return hex(random, 32).toUpperCase(Locale.ROOT);
            }

            /**
             * Sent exactly as stored, because that is what this install already sent.
             *
             * <p>It cannot be brought into line with FindMy.py, and the attempt would make it
             * worse. FindMy.py sends {@code base64(uid)}; matching that would need a {@code uid}
             * whose base64 is a 64-character hex string, which is 48 bytes of arbitrary binary
             * and not a string at all. So for these installs the two halves stay different, the
             * device id aligns, and this does not.
             */
            @Override
            public String localUserHeader(String localUserUuid) {
                return localUserUuid;
            }
        },

        /**
         * An iPhone 14 Pro. <b>Built, tested, and not currently used.</b>
         *
         * <p>It was what a fresh install claimed until Apple started answering 401 to
         * {@code get_2fa_methods} for clients presenting it - see {@link #generate()}. Kept
         * rather than deleted because the values are right and the reasoning below still holds
         * if the 2FA question is ever answered; deleting it would mean rediscovering all of it.
         *
         * <p><b>For the icon, and for the words next to it.</b> Apple synthesises the device-list
         * entry from the claimed model, so {@code iPhone15,2} renders as "iPhone 14 Pro" with a
         * phone icon rather than as a bare "MacBookPro" among the user's real Macs. See
         * {@code docs/findmy-export/01-authentication.md} section 2.2, which is where these
         * values are from - observed, not invented.
         *
         * <p>Safe on both counts that matter. Claiming to be a phone does <b>not</b> make this
         * eligible as a second factor: that is decided by the push token, which FindMy.py has no
         * parameter for and never sends, and an iPhone-shaped entry still reports "This device
         * cannot be used to receive Apple Account verification codes". And it arrives together
         * with the serial {@code 0PENTAGVIEWR}, which is what keeps it recognisable - an iPhone
         * claim on its own, unnamed and unserialled, would be worse than the Mac it replaces.
         */
        IPHONE(
                "iPhone15,2", "iPhone OS", "17.4", "21E219",
                "1494.0.7", "23.4.0",
                "com.apple.akd/1.0") {

            /** A UUID, because that is what FindMy.py mints and this has to be the same value. */
            @Override
            String newLocalUserId(SecureRandom random) {
                return UUID.randomUUID().toString().toUpperCase(Locale.ROOT);
            }

            /**
             * Base64, matching FindMy.py, so that provisioning and login send the same bytes.
             *
             * <p><b>This is the whole reason the convention is per profile.</b> Two conventions
             * exist for this header: the Anisette servers send the value raw, and FindMy.py
             * sends {@code base64(uid)} - and there is exactly one place a client can choose,
             * which is here, before it has told Apple anything. A fresh install provisions ADI
             * under this and then hands the same string to FindMy.py, which encodes it
             * identically. One installation, one local user id.
             */
            @Override
            public String localUserHeader(String localUserUuid) {
                return Base64.encodeToString(
                        localUserUuid.getBytes(StandardCharsets.UTF_8), Base64.NO_WRAP);
            }
        };

        /**
         * A new local user id in whatever shape this profile sends it.
         *
         * <p>Package-private: generating one is {@link #generate()}'s job, and an install that
         * already has one must never be given another.
         */
        abstract String newLocalUserId(SecureRandom random);

        /**
         * {@code X-Apple-I-MD-LU}, rendered from the stored id.
         *
         * <p>Only ADI provisioning actually sends this. The header set {@link AnisetteHeaders}
         * builds is shaped like an Anisette server's response, but FindMy.py reads two values
         * out of it and composes the rest itself - so at login this header is Apple's view of
         * {@code base64(uid)}, and the value here is what Apple saw when the machine was
         * provisioned. Making those the same is the point.
         */
        public abstract String localUserHeader(String localUserUuid);

        private final String model;
        private final String osName;
        private final String osVersion;
        private final String osBuild;
        private final String cfnetwork;
        private final String darwin;
        private final String authKitApp;

        Hardware(String model, String osName, String osVersion, String osBuild,
                 String cfnetwork, String darwin, String authKitApp) {
            this.model = model;
            this.osName = osName;
            this.osVersion = osVersion;
            this.osBuild = osBuild;
            this.cfnetwork = cfnetwork;
            this.darwin = darwin;
            this.authKitApp = authKitApp;
        }

        /** Sent as X-MMe-Client-Info. */
        public String clientInfo() {
            return String.format(Locale.ROOT, "<%s> <%s;%s;%s> <com.apple.AuthKit/1 (%s)>",
                    this.model, this.osName, this.osVersion, this.osBuild, this.authKitApp);
        }

        /** The User-Agent the provisioning requests go out under. */
        public String userAgent() {
            return String.format(Locale.ROOT, "akd/1.0 CFNetwork/%s Darwin/%s",
                    this.cfnetwork, this.darwin);
        }

        public String model() {
            return this.model;
        }

        public String osName() {
            return this.osName;
        }

        public String osVersion() {
            return this.osVersion;
        }

        public String osBuild() {
            return this.osBuild;
        }

        public String cfnetwork() {
            return this.cfnetwork;
        }

        public String darwin() {
            return this.darwin;
        }

        /**
         * This profile as FindMy.py's {@code DeviceIdentity} mapping.
         *
         * <p><b>This is the whole point of the enum being reachable from Python.</b> The Python
         * side used to hold its own copy of these six values, which meant one install could
         * claim a Mac in its ADI provisioning headers and something else at login - the
         * contradiction rule 11 exists to prevent, and the one Apple's own clients never
         * produce. It reads them from here instead, so there is one source of truth and a
         * profile added later needs no second edit.
         *
         * <p>The key names are <b>FindMy.py's</b>, not this class's: they are fed straight to
         * {@code DeviceIdentity.from_json}. That is deliberate but it is also a trap, because
         * {@code from_json} fills a missing key from the library's own defaults rather than
         * failing - so a rename on either side would silently produce a half-Apple, half-us
         * identity. {@code IdentityBridgeTest} asserts the two agree, on device, through
         * Chaquopy.
         */
        public String toJson() {
            try {
                return new JSONObject()
                        .put("model", this.model)
                        .put("os_name", this.osName)
                        .put("os_version", this.osVersion)
                        .put("os_build", this.osBuild)
                        .put("cfnetwork", this.cfnetwork)
                        .put("darwin", this.darwin)
                        .toString();
            } catch (final JSONException e) {
                // Six string literals into a fresh object. Unreachable short of the platform
                // being broken, and there is no sensible half-identity to return instead.
                throw new IllegalStateException("could not describe " + name(), e);
            }
        }
    }

    /** Which machine this install claims to be. Never changes once an install has one. */
    public Hardware hardware() {
        return this.hardware;
    }

    /** Sent as X-Mme-Device-Id. */
    public String uniqueDeviceIdentifier() {
        return this.uniqueDeviceIdentifier;
    }

    /** Handed to ADISetAndroidID. 16 lowercase hex characters; other lengths are rejected. */
    public String adiIdentifier() {
        return this.adiIdentifier;
    }

    /** Sent as X-Apple-I-MD-LU. */
    public String localUserUuid() {
        return this.localUserUuid;
    }
}
