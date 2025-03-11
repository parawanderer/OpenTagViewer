package dev.wander.android.airtagforall.data.model;

import java.util.List;
import java.util.Optional;

import lombok.Builder;
import lombok.Getter;
import lombok.ToString;

@Getter
@Builder
@ToString
public class BeaconInformation {
    private static final String IPAD = "iPad";

    private static final int AIRTAG_PRODUCT_ID = 21760;

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
    private final String emoji;
    /**
     * The name set up for the beacon.
     * This MAY be optional, not sure but probably more likely to be present than {@link #getEmoji()}.
     * This can be configured using apple devices
     * (e.g. using an iPad you can set a custom name for your beacon)
     * <br><br>
     * Sourced from: {@code BeaconNamingRecord/<beacon-identifier>/<some-id>.plist}
     */
    private final String name;
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
     * Disclaimer: not clear if this is exhaustive enough of a check, but it might be
     */
    public boolean isIpad() {
        return Optional.ofNullable(this.getModel()).map(model -> model.contains(IPAD)).orElse(false);
    }

    /**
     * Disclaimer: not clear if this is exhaustive enough of a check
     */
    public boolean isAirTag() {
        return this.productId == AIRTAG_PRODUCT_ID;
    }
}
