package dev.wander.android.opentagviewer.util.parse;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import androidx.test.ext.junit.runners.AndroidJUnit4;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.List;

import dev.wander.android.opentagviewer.data.model.BeaconInformation;
import dev.wander.android.opentagviewer.db.repo.model.BeaconData;
import dev.wander.android.opentagviewer.db.room.entity.OwnedBeacon;

/**
 * Where a tag came from has to survive the trip to the screen.
 *
 * <p><b>This is a plumbing test for a permission decision.</b> {@code from_account} is stored on
 * the row, but every screen works from {@link BeaconInformation}, and the parser rebuilds that
 * object field by field - so a flag that is not copied across arrives as {@code false} and reads
 * as "this is the app's own copy, go ahead and delete it". Nothing throws when that happens. The
 * user removes an account tag, the row is marked removed, and the next refresh writes it back
 * with no explanation.
 *
 * <p>Both construction paths are covered because there are two: an Apple-paired tag is built by
 * {@link BeaconDataParser} out of its plists, and a generated one by {@code CustomAccessoryParser}
 * out of the accessory JSON, with no plist to read at all. Only one of them would have been
 * noticed by hand.
 */
@RunWith(AndroidJUnit4.class)
public class AccountTagSourceTest {

    /**
     * Enough of an {@code OwnedBeacons} plist for every XPath in the parser to find something.
     *
     * <p>Written out rather than loaded from the fixtures under {@code src/test/resources},
     * because those carry a DOCTYPE pointing at apple.com and this needs no network to run.
     */
    private static final String A_PLIST = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
            + "<plist version=\"1.0\"><dict>"
            + "<key>batteryLevel</key><integer>1</integer>"
            + "<key>identifier</key><string>a-tag</string>"
            + "<key>model</key><string></string>"
            + "<key>pairingDate</key><date>2025-02-27T20:03:32Z</date>"
            + "<key>privateKey</key><dict><key>key</key><dict>"
            + "<key>data</key><data>bm90LWEtcmVhbC1rZXk=</data></dict></dict>"
            + "<key>productId</key><integer>21760</integer>"
            + "<key>stableIdentifier</key><array><string>2001~#0~#A0</string></array>"
            + "<key>systemVersion</key><string>2.0.73</string>"
            + "<key>vendorId</key><integer>76</integer>"
            + "</dict></plist>";

    private static final String A_MAPPING =
            "{\"type\":\"custom_rolling_key_accessory\",\"name\":\"Homemade\","
                    + "\"private_keys\":[\"aaa\",\"bbb\"]}";

    private static BeaconInformation parseOne(final OwnedBeacon row) {
        final List<BeaconInformation> parsed =
                BeaconDataParser.parse(List.of(new BeaconData(row.id, row, null, null)));
        return parsed.get(0);
    }

    private static OwnedBeacon appleTag(final boolean fromAccount) {
        return OwnedBeacon.builder()
                .id("a-tag")
                .content(A_PLIST)
                .version(fromAccount ? "account" : "0.0.2")
                .fromAccount(fromAccount)
                .isRemoved(false)
                .build();
    }

    private static OwnedBeacon generatedTag(final boolean fromAccount) {
        return OwnedBeacon.builder()
                .id("a-generated-tag")
                // No plist at all - that absence is what makes it one of these.
                .content(null)
                .accessoryJson(A_MAPPING)
                .version(fromAccount ? "account" : "0.0.3")
                .fromAccount(fromAccount)
                .isRemoved(false)
                .build();
    }

    @Test
    public void atagReadFromTheAccountSaysSo() {
        assertTrue("a tag read from the Apple account must arrive on screen marked as one",
                parseOne(appleTag(true)).isFromAccount());
    }

    @Test
    public void atagImportedFromAFileDoesNot() {
        assertFalse("a file-imported tag must not be mistaken for an account one",
                parseOne(appleTag(false)).isFromAccount());
    }

    /**
     * The path with no plist to read.
     *
     * <p>A generated tag arrives in a bundle today, so this is the answer that matters - but it
     * is read from the row rather than assumed, and the next test is why.
     */
    @Test
    public void agenerateTagFromAFileDoesNotClaimToBeFromTheAccount() {
        assertFalse(parseOne(generatedTag(false)).isFromAccount());
    }

    @Test
    public void agenerateTagCarriesTheFlagItWasStoredWith() {
        assertTrue("the custom-accessory path must read the flag, not hardcode it",
                parseOne(generatedTag(true)).isFromAccount());
    }
}
