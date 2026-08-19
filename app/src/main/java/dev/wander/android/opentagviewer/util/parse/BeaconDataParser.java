package dev.wander.android.opentagviewer.util.parse;

import android.util.Log;

import org.w3c.dom.Document;
import org.w3c.dom.Node;

import java.util.ArrayList;
import java.util.List;

import javax.xml.xpath.XPath;
import javax.xml.xpath.XPathConstants;
import javax.xml.xpath.XPathExpression;
import javax.xml.xpath.XPathExpressionException;
import javax.xml.xpath.XPathFactory;

import dev.wander.android.opentagviewer.data.model.BeaconInformation;
import dev.wander.android.opentagviewer.data.model.BeaconNamingRecordCloudKitMetadata;
import dev.wander.android.opentagviewer.db.repo.model.BeaconData;
import dev.wander.android.opentagviewer.db.room.entity.UserBeaconOptions;
import io.reactivex.rxjava3.core.Observable;
import io.reactivex.rxjava3.schedulers.Schedulers;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;

@NoArgsConstructor(access = AccessLevel.PRIVATE)
public final class BeaconDataParser {
    private static final String TAG = BeaconDataParser.class.getSimpleName();


    private static final String XPATH_FOR_VALUE = "/plist/dict/key[.='%s']/following-sibling::%s[1]";

    private static final String XPATH_STABLE_IDENTIFIER_FIRST_ITEM = "/plist/dict/key[.='stableIdentifier']/following-sibling::array[1]/*[1]";

    private static final String XPATH_REQUIRED_PRIVATE_KEY = "/plist/dict/key[.='privateKey']";

    private static final String TYPE_STRING = "string";

    private static final String TYPE_INTEGER = "integer";

    private static final String TYPE_DATE = "date";

    public static List<BeaconInformation> parse(final List<BeaconData> rawBeaconData) {
        final XPath xPath = XPathFactory.newInstance().newXPath();

        try {
            // BeaconNamingRecord:
            final XPathExpression identifierQuery = getXPathFor(xPath, "identifier", TYPE_STRING);
            final XPathExpression associatedBeaconQuery = getXPathFor(xPath, "associatedBeacon", TYPE_STRING);
            final XPathExpression emojiQuery = getXPathFor(xPath, "emoji", TYPE_STRING);
            final XPathExpression nameQuery = getXPathFor(xPath, "name", TYPE_STRING);
            // BeaconNamingRecord

            // OwnedBeacons:
            final XPathExpression batteryLevelQuery = getXPathFor(xPath, "batteryLevel", TYPE_INTEGER);
            //final XPathExpression beaconIdentifierQuery = getXPathForEntry(xPath, "identifier", TYPE_STRING);
            final XPathExpression modelQuery = getXPathFor(xPath, "model", TYPE_STRING);
            final XPathExpression pairingDateQuery = getXPathFor(xPath, "pairingDate", TYPE_DATE);
            final XPathExpression productIdQuery = getXPathFor(xPath, "productId", TYPE_INTEGER);
            final XPathExpression stableIdentifierQuery = xPath.compile(XPATH_STABLE_IDENTIFIER_FIRST_ITEM);
            final XPathExpression systemVersionQuery = getXPathFor(xPath, "systemVersion", TYPE_STRING);
            final XPathExpression vendorIdQuery = getXPathFor(xPath, "vendorId", TYPE_INTEGER);
            final XPathExpression privateKeyNodeExistsQuery = xPath.compile(XPATH_REQUIRED_PRIVATE_KEY);

            var res = new ArrayList<BeaconInformation>();
            for (var beaconData : rawBeaconData) {
                UserBeaconOptions userOverrides = beaconData.getUserBeaconOptions();

                // A tag that was never in an Apple account has neither plist to read - no
                // OwnedBeacons entry, and no naming record, because nobody's iPad ever named
                // it. Everything below is XPath over those two documents, so it is a different
                // construction rather than the same one with nulls threaded through it.
                if (CustomAccessoryParser.isCustomAccessory(beaconData)) {
                    res.add(CustomAccessoryParser.parse(beaconData, userOverrides));
                    continue;
                }

                // An Apple tag with no naming record cannot be rendered - the name, the emoji
                // and the CloudKit metadata all live in that plist. It should not happen, since
                // the importer inner-joins the two, but skipping one beacon is a far better
                // failure than throwing here: this runs over the whole list, so an NPE takes
                // out every other tag too and the screen comes up empty.
                if (beaconData.getBeaconNamingRecord() == null) {
                    Log.e(TAG, "Skipping beaconId=" + beaconData.getBeaconId()
                            + ": it has an OwnedBeacons plist but no BeaconNamingRecord, so there"
                            + " is nothing to name it with");
                    continue;
                }

                final String beaconNamingRecordPList = beaconData.getBeaconNamingRecord().content;
                final String ownedBeaconPList = beaconData.getOwnedBeaconInfo().content;

                // Extract the most relevant fields from decrypted plist files
                // @ `BeaconNamingRecord/<beacon-identifier>/<some-id>.plist`
                //
                // Note that not all fields contained in the file are extracted here.
                //
                // Note also that it's not clear to me at this time why there can be
                // multiple `<some id>.plist` files per `beacon-identifier`.
                // In my testing thus far the directory for a single `beacon-identifier`
                // only contained a single `<some id>.plist` file.
                final Document beaconNamingData = XmlParser.parse(beaconNamingRecordPList);
                final String beaconNamingRecordIdentifier = getString(beaconNamingData, identifierQuery);
                final String associatedBeacon = getString(beaconNamingData, associatedBeaconQuery);
                final String emoji = getString(beaconNamingData, emojiQuery);
                final String name = getString(beaconNamingData, nameQuery);
                // nested PLIST evaluation (this contains some useful info)
                var metaData = BeaconNamingRecordInnerParser.extractBeaconNamingRecordMetadata(xPath, beaconNamingData);


                // Extract the most relevant fields from decrypted plist files
                //    @ `OwnedBeacons/<beacon identifier>.plist`
                //
                // Note that not all fields are being extracted here
                // (particularly the sensitive ones were skipped)

                // assert that private key node exists!
                final Document ownedBeacon = XmlParser.parse(ownedBeaconPList);

                if (!isNodeNotEmpty(ownedBeacon, privateKeyNodeExistsQuery)) {
                    Log.e(TAG, "Could not locate 'privateKey' node for beaconId=" + beaconData.getBeaconId() + "! This will likely lead to downstream errors later...");
                    // TODO: for now this is not blocking, but maybe it should be?
                }

                final int batteryLevel = getInteger(ownedBeacon, batteryLevelQuery);
                final String model = getString(ownedBeacon, modelQuery);
                final String pairingDate = getString(ownedBeacon, pairingDateQuery);
                final int productId = getInteger(ownedBeacon, productIdQuery);
                final String stableIdentifier = getString(ownedBeacon, stableIdentifierQuery);
                final String systemVersion = getString(ownedBeacon, systemVersionQuery);
                final int vendorId = getInteger(ownedBeacon, vendorIdQuery);

                var extractedData = BeaconInformation.builder()
                        // BeaconNamingRecord
                        .beaconId(associatedBeacon)
                        .namingRecordId(beaconNamingRecordIdentifier)
                        .originalEmoji(emoji)
                        .originalName(name)
                        // BeaconNamingRecord->cloudKitMetadata
                        .namingRecordModifiedTime(
                                metaData.map(BeaconNamingRecordCloudKitMetadata::getModifiedTime).orElse(null))
                        .namingRecordCreationTime(
                                metaData.map(BeaconNamingRecordCloudKitMetadata::getCreationTime).orElse(null))
                        .namingRecordModifiedByDevice(
                                metaData.map(BeaconNamingRecordCloudKitMetadata::getModifiedByDevice).orElse(null))
                        // OwnedBeacon
                        .batteryLevel(batteryLevel)
                        .model(model)
                        .pairingDate(pairingDate)
                        .productId(productId)
                        .stableIdentifier(List.of(stableIdentifier))
                        .systemVersion(systemVersion)
                        .vendorId(vendorId)
                        .ownedBeaconPlistRaw(ownedBeaconPList);

                if (userOverrides != null) {
                    // configure user overrides too
                    extractedData.userOverrideName(userOverrides.uiName)
                            .userOverrideEmoji(userOverrides.uiEmoji);
                }

                res.add(extractedData.build());
            }

            return res;

        } catch (Exception e) {
            throw new BeaconDataParsingException("Failed to parse data for beacons", e);
        }
    }

    public static Observable<List<BeaconInformation>> parseAsync(final List<BeaconData> rawBeaconData) {
        return Observable.fromCallable(() -> parse(rawBeaconData))
                .subscribeOn(Schedulers.computation());
    }

    private static String getString(Document doc, XPathExpression expression) throws XPathExpressionException {
        return (String) expression.evaluate(doc, XPathConstants.STRING);
    }

    private static int getInteger(Document doc, XPathExpression expression) throws XPathExpressionException {
        String stringVal = getString(doc, expression);
        if (stringVal == null) {
            throw new BeaconDataParsingException("Failed to parse return value of " + expression + " to integer because the query returned an empty value");
        }
        return Integer.parseInt(stringVal);
    }

    private static boolean isNodeNotEmpty(Document doc, XPathExpression expression) {
        try {
            var node = (Node) expression.evaluate(doc, XPathConstants.NODE);
            return node != null;
        } catch (XPathExpressionException e) {
            return false; // let's assume false?
        }
    }

    private static XPathExpression getXPathFor(XPath xPath, String key, String valueType) throws XPathExpressionException {
        return xPath.compile(String.format(XPATH_FOR_VALUE, key, valueType));
    }
}
