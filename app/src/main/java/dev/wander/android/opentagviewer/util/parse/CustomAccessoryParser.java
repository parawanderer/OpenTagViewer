package dev.wander.android.opentagviewer.util.parse;

import android.util.Log;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.List;

import dev.wander.android.opentagviewer.data.model.BeaconInformation;
import dev.wander.android.opentagviewer.db.repo.model.BeaconData;
import dev.wander.android.opentagviewer.db.room.entity.UserBeaconOptions;

/**
 * Reading a tag whose keys were never in an Apple account.
 *
 * <p>An OpenHaystack-style tag carries a flat list of pre-generated keys and derives nothing, so
 * there is no {@code OwnedBeacons} plist describing it and no naming record - nobody's iPad ever
 * gave it a name or an emoji, because no Apple device has ever seen it. What the bundle carries
 * instead is FindMy.py's own {@code custom_rolling_key_accessory} mapping, and that is the whole
 * source of truth for one of these.
 *
 * <p><b>Kept apart from {@link BeaconDataParser} rather than folded into it.</b> That one is
 * XPath over two plists from the first line to the last; the difference here is not a few null
 * checks, it is a different document in a different format with different fields. Threading
 * nulls through the other path would have made both harder to read and left every field one
 * missing-plist bug away from a crash on the map screen.
 */
final class CustomAccessoryParser {
    private static final String TAG = CustomAccessoryParser.class.getSimpleName();

    /** FindMy.py's tag for the mapping. The same string {@code main.py} dispatches on. */
    private static final String CUSTOM_TYPE = "custom_rolling_key_accessory";


    private CustomAccessoryParser() {
    }

    /**
     * Whether this row is one of these, decided by what it holds rather than by its version.
     *
     * <p>The absence of a plist is the fact that matters - that is what makes every XPath in
     * {@link BeaconDataParser} inapplicable. Reading the bundle's format version instead would
     * be a second source of truth for the same question, and wrong for any row whose plist
     * failed to store for some unrelated reason.
     */
    static boolean isCustomAccessory(final BeaconData beaconData) {
        return beaconData.getOwnedBeaconInfo() != null
                && beaconData.getOwnedBeaconInfo().content == null
                && beaconData.getOwnedBeaconInfo().accessoryJson != null;
    }

    /**
     * Build the display model from the mapping.
     *
     * <p>Nothing here throws. A tag that cannot be described is still a tag that can be located,
     * and the fetch path reads the same JSON independently through Python - so a field this
     * cannot read costs a label, not an accessory.
     */
    static BeaconInformation parse(
            final BeaconData beaconData, final UserBeaconOptions userOverrides) {
        final String beaconId = beaconData.getBeaconId();

        String name = null;
        int keyCount = 0;

        try {
            final JSONObject mapping = new JSONObject(beaconData.getOwnedBeaconInfo().accessoryJson);

            if (!CUSTOM_TYPE.equals(mapping.optString("type"))) {
                Log.w(TAG, "Accessory " + beaconId + " has no plist but is not a "
                        + CUSTOM_TYPE + " either - describing it as best we can");
            }

            name = emptyToNull(mapping.optString("name", null));

            final JSONArray keys = mapping.optJSONArray("private_keys");
            keyCount = keys == null ? 0 : keys.length();
        } catch (final JSONException e) {
            Log.w(TAG, "Could not read the mapping for " + beaconId
                    + "; it will show with no name", e);
        }

        final BeaconInformation.BeaconInformationBuilder built = BeaconInformation.builder()
                .beaconId(beaconId)
                // No naming record exists, so there is no record id, no cloudKitMetadata, and
                // nothing that was ever "modified by" a device. Left null rather than invented.
                .namingRecordId(null)
                .originalName(name)
                // **No emoji, deliberately.** It used to default to a haystack so the row was
                // not blank, but that sent it down the emoji path and past the icon. There is a
                // drawable for exactly this kind now - see BeaconIcon - and leaving this null is
                // what lets it through.
                .originalEmoji(null)
                .customAccessory(true)
                .customAccessoryKeyCount(keyCount)
                // Not an Apple product, so it has no product or vendor id to report and no
                // battery it can tell us about. Zero rather than a guess.
                .productId(0)
                .vendorId(0)
                .batteryLevel(0)
                .stableIdentifier(List.of());

        if (userOverrides != null) {
            built.userOverrideName(userOverrides.uiName).userOverrideEmoji(userOverrides.uiEmoji);
        }

        return built.build();
    }

    private static String emptyToNull(final String value) {
        return value == null || value.isBlank() ? null : value;
    }
}
