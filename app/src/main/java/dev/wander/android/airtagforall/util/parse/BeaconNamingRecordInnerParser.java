package dev.wander.android.airtagforall.util.parse;

import android.util.Log;

import org.w3c.dom.Document;

import java.util.Optional;

import javax.xml.xpath.XPath;
import javax.xml.xpath.XPathConstants;
import javax.xml.xpath.XPathExpression;

import dev.wander.android.airtagforall.data.model.BeaconNamingRecordCloudKitMetadata;
import dev.wander.android.airtagforall.python.PythonUtils;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;

@NoArgsConstructor(access = AccessLevel.PRIVATE)
public final class BeaconNamingRecordInnerParser {
    private static final String TAG = BeaconNamingRecordInnerParser.class.getSimpleName();

    private static final String X_PATH_TO_CLOUDKIT_METADATA = "/plist/dict/key[.='cloudKitMetadata']/following-sibling::data[1]";

    public static Optional<BeaconNamingRecordCloudKitMetadata> extractBeaconNamingRecordMetadata(XPath xPath, final String beaconNamingRecordPlist) {
        try {
            final Document doc = XmlParser.parse(beaconNamingRecordPlist);
            return extractBeaconNamingRecordMetadata(xPath, doc);
        } catch (Exception e) {
            Log.e(TAG, "Failed to parse BeaconNamingRecord plist xml");
            return Optional.empty();
        }
    }

    public static Optional<BeaconNamingRecordCloudKitMetadata> extractBeaconNamingRecordMetadata(XPath xPath, final Document beaconNamingRecordPlist) {
        try {
            final XPathExpression xPathExpression = xPath.compile(X_PATH_TO_CLOUDKIT_METADATA);

            // retrieve the one node that contains time related data.
            // (All other processing of this these plist files is done in python)
            final String rawBase64String = (String) xPathExpression.evaluate(beaconNamingRecordPlist, XPathConstants.STRING);
            final String cleanBase64 = cleanXMLBase64Content(rawBase64String);

            // offload this task to python because python has a working implementation.
            return Optional.ofNullable(PythonUtils.decodeBeaconNamingRecordCloudKitMetadata(cleanBase64));
        } catch (Exception e) {
            Log.e(TAG, "Error while calling decodeBeaconNamingRecordCloudKitMetadata", e);
            return Optional.empty();
        }
    }

    /**
     * Gets rid of all extra tabs and spaces
     */
    private static String cleanXMLBase64Content(final String rawBase64String) {
        return rawBase64String.replaceAll("\\s+", "");
    }
}
