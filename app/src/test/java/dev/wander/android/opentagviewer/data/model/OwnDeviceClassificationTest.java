package dev.wander.android.opentagviewer.data.model;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * Telling one of the owner's own Apple devices from an accessory.
 *
 * <p><b>This decides whether a tag is searched for at all</b>, which makes it more load-bearing
 * than it looks. A false positive is not a cosmetic mistake: the tag vanishes from every screen
 * and is dropped from the fetch batch, so it silently never updates and there is nothing on
 * screen to explain why. That is the exact failure this app is worst at surfacing.
 *
 * <p>The rule is a second implementation of
 * {@code opentagviewer_export.hardware.is_own_device}, which is the shared one the desktop
 * exporters use. Two implementations of one rule drift, so the cases below are deliberately the
 * same cases {@code python/opentagviewer_export/tests/test_hardware.py} covers -
 * {@code OwnDeviceMatchesThePythonRuleTest} in the instrumented suite is what actually runs both
 * over the same input and compares.
 */
public class OwnDeviceClassificationTest {

    /** Apple's Bluetooth SIG company identifier, as an AirTag's record carries it. */
    private static final int APPLE_VENDOR_ID = 76;

    private static final int AIRTAG_PRODUCT_ID = 21760;

    // --- the owner's own hardware -------------------------------------------------------------

    /**
     * <b>An Apple model identifier is enough on its own.</b>
     *
     * <p>{@code <family><major>,<minor>} is the shape Apple's own hardware uses and accessories
     * do not - an accessory leaves {@code model} empty and identifies itself through its product
     * and vendor ids instead.
     */
    @Test
    public void anappleModelIdentifierMeansItIsOneOfTheOwnersDevices() {
        for (final String model : new String[] {
                "iPad13,18", "iPhone15,2", "MacBookAir10,1", "MacBookPro18,3", "iMac21,1",
                "Watch6,18", "RealityDevice14,1"}) {

            assertTrue(model + " should be recognised as one of the owner's own devices",
                    withModel(model).isOwnDevice());
        }
    }

    /**
     * <b>And so is the secret, with no model at all.</b>
     *
     * <p>An iPhone, iPad or Mac carries {@code secureLocationsSharedSecret} where an accessory
     * carries {@code secondarySharedSecret} - findmy-export 06-output section 2.3. Either signal
     * alone is sufficient, because a record can arrive with one and not the other.
     */
    @Test
    public void thesecureLocationsSecretMeansItIsOneOfTheOwnersDevicesToo() {
        final BeaconInformation noModelButASecret = BeaconInformation.builder()
                .beaconId("a")
                .model("")
                .secureLocationsSecret(true)
                .build();

        assertTrue("a record carrying secureLocationsSharedSecret is one of the owner's devices",
                noModelButASecret.isOwnDevice());
    }

    // --- accessories --------------------------------------------------------------------------

    /** An AirTag: empty model, Apple's vendor id, the AirTag product id. Not a device. */
    @Test
    public void anairTagIsNotOneOfTheOwnersDevices() {
        final BeaconInformation airTag = BeaconInformation.builder()
                .beaconId("a")
                .model("")
                .productId(AIRTAG_PRODUCT_ID)
                .vendorId(APPLE_VENDOR_ID)
                .build();

        assertFalse("an AirTag is an accessory, not one of the owner's devices",
                airTag.isOwnDevice());
    }

    /**
     * <b>A third-party tag is an accessory even though nothing here identifies it.</b>
     *
     * <p>A Chipolo or a Pebblebee has no Apple model, no Apple vendor id and no device secret.
     * The honest answer for anything unrecognised is "accessory" - see the unsure case below.
     */
    @Test
    public void athirdPartyTagIsNotOneOfTheOwnersDevices() {
        final BeaconInformation chipolo = BeaconInformation.builder()
                .beaconId("a")
                .model("")
                .productId(1234)
                .vendorId(999)
                .build();

        assertFalse(chipolo.isOwnDevice());
    }

    /**
     * <b>A self-generated tag is an accessory.</b>
     *
     * <p>It has no plist at all, so every field here is empty or null. It must keep being
     * fetched: it is somebody's OpenHaystack-style tag and the crowd-sourced network is the only
     * way it is ever found.
     */
    @Test
    public void aselfGeneratedTagIsNotOneOfTheOwnersDevices() {
        final BeaconInformation generated = BeaconInformation.builder()
                .beaconId("a")
                .customAccessory(true)
                .build();

        assertFalse(generated.isOwnDevice());
    }

    /**
     * <b>AirPods are an accessory, matching the Python.</b>
     *
     * <p>Deliberate, not an oversight: their model lives inside {@code stableIdentifier} rather
     * than in {@code model}, and they are a thing somebody bought rather than the computer they
     * work on. They are also genuinely found the crowd-sourced way, so hiding them would be
     * wrong on the merits as well.
     */
    @Test
    public void airPodsAreAnAccessory() {
        final BeaconInformation airPods = BeaconInformation.builder()
                .beaconId("a")
                .model("")
                .vendorId(APPLE_VENDOR_ID)
                .build();

        assertFalse(airPods.isOwnDevice());
    }

    // --- the unsure cases, which all have to fall the same way --------------------------------

    /**
     * <b>Anything unclassifiable is an accessory, and that direction is the whole point.</b>
     *
     * <p>Guessing "device" for something unknown hides it and stops searching for it, with no
     * error anywhere. Guessing "accessory" shows a row that might be an iPad, which is what every
     * version before this one did anyway. The cost of the two mistakes is not remotely equal.
     */
    @Test
    public void anythingUnrecognisedIsTreatedAsAnAccessory() {
        assertFalse("a null model must not be read as a device", withModel(null).isOwnDevice());
        assertFalse("an empty model must not be read as a device", withModel("").isOwnDevice());
        assertFalse(withModel("   ").isOwnDevice());
    }

    /**
     * <b>Near-misses on the model shape are not devices.</b>
     *
     * <p>Pinned because the pattern is anchored at both ends and somebody loosening it would
     * quietly start hiding accessories. An AirTag's firmware version is {@code 2.0.73}, which is
     * the kind of string that must never match.
     */
    @Test
    public void astringThatMerelyLooksLikeAModelIsNotADevice() {
        for (final String notAModel : new String[] {
                "2.0.73",            // an AirTag's firmware version
                "iPad",              // a family with no numbers
                "13,18",             // numbers with no family
                "iPad13",            // no minor
                "iPad13,",           // trailing separator
                "iPad13.18",         // a dot, not a comma
                "my iPad13,18",      // not anchored at the start
                "iPad13,18 spare"}) {// not anchored at the end

            assertFalse("\"" + notAModel + "\" must not be read as an Apple model identifier",
                    withModel(notAModel).isOwnDevice());
        }
    }

    private static BeaconInformation withModel(final String model) {
        return BeaconInformation.builder().beaconId("a").model(model).build();
    }
}
