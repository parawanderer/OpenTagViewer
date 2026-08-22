package dev.wander.android.opentagviewer.data.model;

import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;

import lombok.Builder;
import lombok.Getter;
import lombok.Setter;
import lombok.ToString;

@Getter
@Builder
@ToString
public class BeaconInformation {
    private static final String IPAD = "iPad";

    private static final int AIRTAG_PRODUCT_ID = 21760;

    /**
     * The {@code <family><major>,<minor>} shape Apple's own hardware uses and accessories do
     * not - {@code iPad13,18}, {@code MacBookAir10,1}.
     *
     * <p>Kept character-for-character in step with {@code _APPLE_MODEL} in
     * {@code opentagviewer_export/hardware.py}. See {@link #isOwnDevice()}.
     */
    private static final Pattern APPLE_MODEL = Pattern.compile("^[A-Za-z]+\\d+,\\d+$");

    /**
     * Beacon id. This is present in both {@code .plist} files,
     * and matches to the id stored in the Beacon repository.
     */
    private final String beaconId;
    /**
     * BeaconNamingRecord identifier.
     * <br><br>
     * Sourced from: {@code BeaconNamingRecord/<beacon-identifier>/<some-id>.plist}
     */
    private final String namingRecordId;
    /**
     * Emoji for the beacon. I believe this is optional,
     * and can be configured using apple devices
     * (e.g. using an iPad you can set a custom emoji for your AirTag).
     * <br><br>
     * Sourced from: {@code BeaconNamingRecord/<beacon-identifier>/<some-id>.plist}
     */
    private final String originalEmoji;
    /**
     * The name set up for the beacon.
     * This MAY be optional, not sure but probably more likely to be present than {@link #getOriginalEmoji()}.
     * This can be configured using apple devices
     * (e.g. using an iPad you can set a custom name for your beacon)
     * <br><br>
     * Sourced from: {@code BeaconNamingRecord/<beacon-identifier>/<some-id>.plist}
     */
    private final String originalName;
    /**
     * Creation time of the BeaconNamingRecord. May be {@code null} on failure to parse the inner node.
     * <br><br>
     * Sourced from inner {@code cloudKitMetadata} node of: {@code BeaconNamingRecord/<beacon-identifier>/<some-id>.plist}
     */
    private final Long namingRecordCreationTime;
    /**
     * Modification time of the BeaconNamingRecord. May be {@code null} on failure to parse the inner node.
     * <br><br>
     * Sourced from inner {@code cloudKitMetadata} node of: {@code BeaconNamingRecord/<beacon-identifier>/<some-id>.plist}
     */
    private final Long namingRecordModifiedTime;
    /**
     * Which device last modified the information for the beacon.<br>
     * E.g. if an iPad user last modified the Beacon name or emoji, this field will contain that iPad's name, e.g. {@code "Jhonny's iPad"}.
     * May be {@code null} on failure to parse the inner node.
     * <br><br>
     * Sourced from inner {@code cloudKitMetadata} node of: {@code BeaconNamingRecord/<beacon-identifier>/<some-id>.plist}
     */
    private final String namingRecordModifiedByDevice;
    /**
     * Raw string contents of the decoded .plist xml file
     * <br><br>
     * Sourced from the primary file: {@code OwnedBeacons/<beacon identifier>.plist}
     */
    private final String ownedBeaconPlistRaw;
    /**
     * {@code 0} or {@code 1} (?)
     * <br><br>
     * Sourced from the primary file: {@code OwnedBeacons/<beacon identifier>.plist}
     */
    private final int batteryLevel;
    /**
     * <b>Possibly empty!</b>
     * <br>
     * iPad for example does fill this with a value like this: {@code iPad13,18}
     * <br><br>
     * Sourced from the primary file: {@code OwnedBeacons/<beacon identifier>.plist}
     */
    private final String model;
    /**
     * Pairing date for the device (this is {@code ISO 8601})
     * <br><br>
     * Sourced from the primary file: {@code OwnedBeacons/<beacon identifier>.plist}
     */
    private final String pairingDate;
    /**
     * Possibly {@code -1}
     * <ul>
     *     <li>Tested iPad has this as {@code -1} (but in that case it did fill {@link #getModel()})</li>
     *     <li>Tested AirTags all had this as {@code 21760} (note that AirTag have {@link #getModel()} as empty for some reason)</li>
     * </ul>
     * <br>
     * Sourced from the primary file: {@code OwnedBeacons/<beacon identifier>.plist}
     */
    private final int productId;
    /**
     * Actually I am not sure what this is, but both my tested AirTags and iPad seem to have this.
     * The format for these tested devices is an array containing a single item (string).<br>
     * The format of these strings matches for the AirTags, but is different for the iPad.
     * <br><br>
     * Sourced from the primary file: {@code OwnedBeacons/<beacon identifier>.plist}
     */
    private final List<String> stableIdentifier;
    /**
     * <ul>
     *     <li>
     *         For my tested iPad, this was a string {@code 22B91}
     *         (seems to match <a href="https://en.wikipedia.org/wiki/IPadOS_18#Release_history[13]">
     *          the {@code Build} in ReleaseHistory</a>)
     *     </li>
     *     <li>
     *         For my tested AirTags, this was a string {@code 2.0.73}
     *         (matches to what is shown in iPad `FindMy` app when opening
     *         the item and then tapping the item name --
     *         this will show you the current firmware version of the airtag.
     *         See thread <a href="https://www.reddit.com/r/AirTags/comments/1bkbqzj/my_airtags_updated_firmware_to_2073/">here</a>)
     *
     *         <ul>
     *             <li>
     *                 It seems like Apple meant to document changes to AirTag firmware
     *                 <a href="https://support.apple.com/en-us/102183">here</a>, however this page appears
     *                 out of date (it is missing {@code 2.0.73}) at the time of writing.
     *             </li>
     *         </ul>
     *     </li>
     * </ul>
     * <br>
     * Sourced from the primary file: {@code OwnedBeacons/<beacon identifier>.plist}
     */
    private final String systemVersion;
    /**
     * This can be {@code -1}. <br><br>
     * This seems to be some sort of "Bluetooth Beacon Manufacturer Id"
     * (official name: <a href="https://www.bluetooth.com/specifications/assigned-numbers/">{@code Bluetooth Assigned Number}</a>).
     * <br><br>
     * See: <a href="https://www.reddit.com/r/airpods/comments/kp649n/windows_10_airpods_max_bluetooth_codec_analysis/">thread 1</a>,
     * <a href="https://stackoverflow.com/questions/43301395/does-an-ibeacon-have-to-use-apples-company-id-if-not-how-to-identify-an-ibeac">thread 2</a>,
     * <a href="https://developer.apple.com/ibeacon/">webpage 1</a>,
     * <a href="https://www.bluetooth.com/specifications/assigned-numbers/">webpage 2</a> and
     * <a href="https://www.bluetooth.com/wp-content/uploads/Files/Specification/HTML/Assigned_Numbers/out/en/Assigned_Numbers.pdf?v=1740839006923">this document</a>
     * (search for {@code 0x004c})
     * <br><br>
     * In testing:
     * <ul>
     *     <li>iPad had this as {@code -1}</li>
     *     <li>AirTags had this as {@code 76} (This is {@code 0x4C} aka {@code 0x004c}, see
     *     <a href="https://www.reddit.com/r/airpods/comments/kp649n/windows_10_airpods_max_bluetooth_codec_analysis/">here</a>)
     *     </li>
     * </ul>
     * <br><br>
     * Sourced from the primary file: {@code OwnedBeacons/<beacon identifier>.plist}
     */
    private final int vendorId;

    /**
     * User can use this to override the name in the UI
     */
    @Setter
    private String userOverrideName;
    /**
     * User can use this to override the emoji in the UI
     */
    @Setter
    private String userOverrideEmoji;

    /**
     * Where the user dragged this tag to on the device list, or null if they never have.
     *
     * <p>An override like the two above, and stored beside them - see
     * {@code UserBeaconOptions.uiOrder}. Read by {@link dev.wander.android.opentagviewer.util.TagOrder},
     * where null means "unarranged" rather than "first".
     */
    @Setter
    private Integer uiOrder;


    public String getName() {
        return Optional.ofNullable(this.userOverrideName).orElse(this.originalName);
    }

    public String getEmoji() {
        return Optional.ofNullable(this.userOverrideEmoji).orElse(this.originalEmoji);
    }

    public boolean isEmojiFilled() {
        var emoji = this.getEmoji();
        return emoji != null && !emoji.isBlank();
    }


    /**
     * Disclaimer: not clear if this is exhaustive enough of a check, but it might be
     */
    public boolean isIpad() {
        return Optional.ofNullable(this.getModel()).map(model -> model.contains(IPAD)).orElse(false);
    }

    /**
     * Whether this record carries {@code secureLocationsSharedSecret}.
     *
     * <p>The second of the two signals {@link #isOwnDevice()} reads. Only the presence of the
     * node is kept, never the value: this is key material, and nothing in the app needs it -
     * the question being asked is what kind of thing this is.
     */
    private final boolean secureLocationsSecret;

    /**
     * Whether this is one of the owner's own devices - an iPhone, iPad or Mac - rather than an
     * accessory.
     *
     * <p><b>The same two signals as {@code opentagviewer_export.hardware.is_own_device}, and
     * they have to stay the same two.</b> That function is the shared one, used by both desktop
     * exporters and reachable from here over the bridge - but it costs a Python call and
     * answers asynchronously, which is no use to a list being bound or a fetch batch being
     * assembled. So this is a second implementation of one rule, which is a thing that drifts;
     * {@code OwnDeviceMatchesThePythonRuleTest} runs both over the same fixtures for that
     * reason.
     *
     * <ul>
     *   <li><b>An Apple model identifier</b> in {@code model} - {@code iPad13,18},
     *       {@code MacBookPro18,3}. An accessory leaves {@code model} empty and says what it is
     *       through {@code productId} and {@code vendorId} instead.</li>
     *   <li><b>{@link #secureLocationsSecret}</b>, which an iPhone, iPad or Mac carries in place
     *       of the {@code secondarySharedSecret} an accessory carries. See findmy-export
     *       06-output section 2.3.</li>
     * </ul>
     *
     * <p><b>Unsure means accessory, and that direction is deliberate.</b> This decides whether a
     * tag is hidden and whether it is searched for at all, so a false positive is a tag that
     * silently stops being located - no error, no empty state, just a row that never updates
     * and no way to tell why. A false negative is an iPad in the list, which is merely the
     * behaviour of every version before this one.
     *
     * <p>AirPods are deliberately not caught, matching the Python: their model lives inside
     * {@code stableIdentifier} rather than in {@code model}, and they are an accessory somebody
     * bought rather than the computer they work on.
     */
    public boolean isOwnDevice() {
        if (this.model != null && APPLE_MODEL.matcher(this.model).matches()) {
            return true;
        }
        return this.secureLocationsSecret;
    }

    /**
     * Disclaimer: not clear if this is exhaustive enough of a check
     */
    public boolean isAirTag() {
        return this.productId == AIRTAG_PRODUCT_ID;
    }

    /**
     * Whether this tag was generated rather than paired - OpenHaystack-style.
     *
     * <p>Set at construction rather than inferred, unlike {@link #isAirTag()} and
     * {@link #isIpad()}, which guess from a plist field. There is nothing to guess from here:
     * a self-generated tag has no plist, no product id and no vendor id, so every heuristic on
     * this class returns false for it and would go on saying "Unknown" forever.
     */
    private final boolean customAccessory;

    /**
     * How many pre-generated keys it carries, or 0 if that could not be read.
     *
     * <p>Shown because it is the one number that says something real about this kind of tag:
     * the keys are a finite list rather than a rolling derivation, so it will stop being
     * findable once they run out. An Apple-paired tag has no equivalent.
     */
    private final int customAccessoryKeyCount;

    /**
     * Whether this tag is a cache of the user's Apple account rather than the app's own copy.
     *
     * <p><b>It decides whether the app may remove it.</b> A file-imported tag exists only here,
     * so removing it is the user's decision and nobody else's. An account tag is a copy of what
     * Apple holds, and the next refresh rewrites the row from the account - so "remove" would
     * appear to work and then quietly undo itself. Removing one for real means removing it in
     * Find My, which this app deliberately does not do on the user's behalf.
     *
     * <p>Set at construction from {@code OwnedBeacon.fromAccount}, like {@link #isCustomAccessory()}
     * and unlike the heuristics on this class - there is nothing in a plist to infer it from.
     */
    private final boolean fromAccount;

    /**
     * When the app gave up looking for this tag, or null if it has not.
     *
     * <p>Set only after a search covering months of history found nothing anywhere - not merely
     * after an empty search, which for a recently paired tag means very little. Shown on the
     * device list in place of "no last location known", because those two states look identical
     * and are not: one is a tag nobody has walked past this week, the other is a tag that has
     * stopped broadcasting and is no longer being looked for.
     */
    private final Long ignoredAt;

    /** Consecutive searches that found nothing, which is what paces the next one. */
    private final int fruitlessScans;

    /** When it was last searched for, or null if never. */
    private final Long lastScanAt;

    public boolean isIgnored() {
        return this.ignoredAt != null;
    }

    public boolean isCustomAccessory() {
        return this.customAccessory;
    }
}
