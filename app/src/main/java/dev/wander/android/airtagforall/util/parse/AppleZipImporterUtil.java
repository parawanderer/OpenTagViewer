package dev.wander.android.airtagforall.util.parse;

import android.content.Context;
import android.net.Uri;
import android.util.Log;
import android.util.Pair;


import com.chaquo.python.Kwarg;
import com.chaquo.python.PyObject;
import com.chaquo.python.Python;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.networknt.schema.InputFormat;
import com.networknt.schema.JsonSchema;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SchemaValidatorsConfig;
import com.networknt.schema.SpecVersion;
import com.networknt.schema.ValidationMessage;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import javax.xml.xpath.XPath;
import javax.xml.xpath.XPathFactory;

import dev.wander.android.airtagforall.R;
import dev.wander.android.airtagforall.data.model.BeaconNamingRecordCloudKitMetadata;
import dev.wander.android.airtagforall.db.room.entity.BeaconNamingRecord;
import dev.wander.android.airtagforall.db.room.entity.Import;
import dev.wander.android.airtagforall.db.room.entity.OwnedBeacon;
import dev.wander.android.airtagforall.db.repo.model.ImportData;
import lombok.AccessLevel;
import lombok.Data;
import lombok.Getter;
import lombok.NonNull;

@Getter(AccessLevel.PRIVATE)
public class AppleZipImporterUtil {
    enum FILE_TYPE {
        EXPORT_INFO,
        OWNED_BEACON,
        BEACON_NAMING_RECORD;
    }

    private static final String TAG = AppleZipImporterUtil.class.getSimpleName();

    private static final List<String> ALLOWED_FILE_ENDINGS = List.of(".yml", ".plist");

    private static final Map<FILE_TYPE, Pattern> MATCHERS = Map.of(
            FILE_TYPE.EXPORT_INFO, Pattern.compile("^AIRTAG4ALL\\.yml$"),
            FILE_TYPE.OWNED_BEACON, Pattern.compile("^OwnedBeacons/([0-9A-F]{8}-[0-9A-F]{4}-4[0-9A-F]{3}-[89AB][0-9A-F]{3}-[0-9A-F]{12})\\.plist$"),
            FILE_TYPE.BEACON_NAMING_RECORD, Pattern.compile("^BeaconNamingRecord/([0-9A-F]{8}-[0-9A-F]{4}-4[0-9A-F]{3}-[89AB][0-9A-F]{3}-[0-9A-F]{12})/([0-9A-F]{8}-[0-9A-F]{4}-4[0-9A-F]{3}-[89AB][0-9A-F]{3}-[0-9A-F]{12})\\.plist$")
    );

    private static final String X_PATH_TO_CLOUDKIT_METADATA = "/plist/dict/key[.='cloudKitMetadata']/following-sibling::data[1]";

    private final Context appContext;

    public AppleZipImporterUtil(Context appContext) {
        this.appContext = appContext;
    }

    public ImportData extractZip(@NonNull Uri zipFileUri) throws ZipImporterException {

        String airtag4AllYaml = null;
        Map<String, String> ownedBeacons = new HashMap<>();
        Map<String, Pair<String, String>> beaconNamingRecords = new HashMap<>();

        try (ZipInputStream zipInput = new ZipInputStream(
                this.appContext.getContentResolver()
                        .openInputStream(zipFileUri), StandardCharsets.UTF_8);
             ByteArrayOutputStream baos = new ByteArrayOutputStream()) {

            byte[] buffer = new byte[1024];
            int read = 0;
            ZipEntry zipEntry;

            while ((zipEntry = zipInput.getNextEntry()) != null) {
                // read data
                String fileName = zipEntry.getName();
                Log.d(TAG, "Now reading file " + fileName + " while unzipping...");

                if (zipEntry.isDirectory()) {
                    Log.d(TAG, "Skipping " + fileName + ": this is a directory, nothing to do here");
                    continue;
                }

                if (!isExpectedFileType(zipEntry.getName())) {
                    Log.d(TAG, "Skipping " + fileName + ": this is a file with an unallowed file type (file extension not in whitelist)");
                    continue;
                }

                // which type is it?
                final Pair<FILE_TYPE, List<String>> typeAndRegexGroups = getAllowedFileType(zipEntry.getName());
                if (typeAndRegexGroups == null) {
                    Log.w(TAG, "Encountered unexpected file " + zipEntry.getName()
                            + " that was not whitelisted for parsing! This file is being skipped.");
                    continue;
                }

                // read the file contents. They're always utf8 strings.
                // TODO: have some limits on how much bytes we can read...
                while ((read = zipInput.read(buffer, 0, buffer.length)) > 0) {
                    baos.write(buffer, 0, read);
                }
                final String fileContent = baos.toString(StandardCharsets.UTF_8.name());
                baos.reset();

                switch (typeAndRegexGroups.first) {
                    case EXPORT_INFO:
                        airtag4AllYaml = fileContent;
                        break;
                    case OWNED_BEACON:
                        ownedBeacons.put(typeAndRegexGroups.second.get(1), fileContent);
                        break;
                    case BEACON_NAMING_RECORD:
                        processBeaconNamingRecord(typeAndRegexGroups.second, fileContent, beaconNamingRecords);
                        break;
                }
            }
        } catch (IOException e) {
            throw new RuntimeException(e);
        }

        assertAirTag4AllIsValid(airtag4AllYaml);
        innerJoinBeaconFiles(ownedBeacons, beaconNamingRecords);

        // now we can produce the expected data
        return convert(
          airtag4AllYaml,
          ownedBeacons,
          beaconNamingRecords
        );
    }

    private static void processBeaconNamingRecord(final List<String> regexGroupMatches, final String newFileContent, Map<String, Pair<String, String>> beaconIdToEntryIdAndContent) {
        final String beaconId = regexGroupMatches.get(1);
        final String recordId = regexGroupMatches.get(2);

        if (beaconIdToEntryIdAndContent.containsKey(beaconId)) {
            resolveMultipleBeaconNamingRecordConflict(
                    beaconId,
                    recordId,
                    beaconIdToEntryIdAndContent,
                    newFileContent
            );
        } else {
            beaconIdToEntryIdAndContent.put(beaconId, Pair.create(recordId, newFileContent));
        }
    }

    private void innerJoinBeaconFiles(Map<String, String> ownedBeacons, Map<String, Pair<String, String>> beaconNamingRecords) {
        // every ownedBeacon must map to a beaconNamingRecord & the other way around
        // these checks won't throw for now
        Set<String> namingRecordBeaconIds = new HashSet<>(beaconNamingRecords.keySet());
        final Set<String> ownedBeaconIds = new HashSet<>(ownedBeacons.keySet());

        for (String beaconId : ownedBeaconIds) {
            if (!beaconNamingRecords.containsKey(beaconId)) {
                Log.w(TAG,
                        String.format(
                                "Found beaconId %s in OwnedBeacons, but it was missing in BeaconNamingRecord (there existed a file OwnedBeacons/%s.plist, but no file BeaconNamingRecord/%s/<any uuid>.plist)!",
                                beaconId,
                                beaconId,
                                beaconId
                        ));
                ownedBeacons.remove(beaconId); // cleanup this entry (to get "inner join" of both maps)
            } else {
                namingRecordBeaconIds.remove(beaconId);
            }
        }

        if (namingRecordBeaconIds.isEmpty()) {
            return;
        }

        for (String beaconId : namingRecordBeaconIds) {
            Log.w(TAG,
                String.format(
                        "Found beaconId %s in BeaconNamingRecord but not in OwnedBeacons (there existed a file BeaconNamingRecord/%s/%s.plist, but no OwnedBeacons/%s.plist file existed)!",
                        beaconId,
                        beaconId,
                        Objects.requireNonNull(beaconNamingRecords.get(beaconId)).first,
                        beaconId
                ));
            beaconNamingRecords.remove(beaconId);
        }
    }

    private void assertAirTag4AllIsValid(final String content) {
        if (content == null || content.isBlank()) {
            throw new ImportFileFormatException("AIRTAG4ALL.yml was empty!");
        }

        // validate content: see https://github.com/networknt/json-schema-validator
        JsonSchemaFactory jsonSchemaFactory = JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012);
        SchemaValidatorsConfig.Builder builder = SchemaValidatorsConfig.builder();
        SchemaValidatorsConfig config = builder.build();

        try (InputStream inputStream = this.appContext.getResources()
                .openRawResource(R.raw.airtag4all_schema)) {

            JsonSchema schema = jsonSchemaFactory.getSchema(inputStream);
            Set<ValidationMessage> assertions = schema.validate(content, InputFormat.YAML, executionContext -> {
                // By default since Draft 2019-09 the format keyword only generates annotations and not assertions
                executionContext.getExecutionConfig().setFormatAssertionsEnabled(true);
            });

            if (!assertions.isEmpty()) {
                throw new ImportFileFormatException(
                        String.format(
                                "AIRTAG4ALL.yml format validation failed: %s",
                                assertions.stream()
                                        .filter(a -> !a.isValid())
                                        .map(ValidationMessage::getMessage)
                                        .collect(Collectors.joining(", ")))
                );
            }

            // OK
        } catch (IOException e) {
            throw new ImportFileFormatException("AIRTAG4ALL.yml could not be parsed!", e);
        }
    }

    private static void resolveMultipleBeaconNamingRecordConflict(
            final String beaconId,
            final String alternativeNamingRecordId,
            final Map<String, Pair<String, String>> beaconNamingRecords,
            final String newBeaconFileContent
    ) {
        try {
            Pair<String, String> recordIdContentPair = Objects.requireNonNull(beaconNamingRecords.get(beaconId));
            final String firstBeaconNamingRecordIdSeen = recordIdContentPair.first;
            Log.w(TAG, String.format(
                    "We found a beaconId %s that had more than 1 BeaconNamingRecord plist file! We already saw %s.plist but now we also found %s.plist in the BeaconNamingRecord/%s folder!",
                    beaconId,
                    firstBeaconNamingRecordIdSeen,
                    alternativeNamingRecordId,
                    beaconId
            ));

            // try to resolve it now by parsing & comparing.
            final XPath xPath = XPathFactory.newInstance().newXPath();
            var currentMetadata = BeaconNamingRecordInnerParser.extractBeaconNamingRecordMetadata(xPath, recordIdContentPair.second);
            var newMetadata = BeaconNamingRecordInnerParser.extractBeaconNamingRecordMetadata(xPath, newBeaconFileContent);


            long currentTimestamp = currentMetadata
                    .map(BeaconNamingRecordCloudKitMetadata::getLastTime)
                    .orElse(0L);

            var newTimestamp = newMetadata
                    .map(BeaconNamingRecordCloudKitMetadata::getLastTime)
                    .orElse(0L);

            if (newTimestamp >= currentTimestamp) {
                // use new data instead! -> replace
                beaconNamingRecords.put(
                        beaconId,
                        Pair.create(alternativeNamingRecordId, newBeaconFileContent)
                );

                Log.d(TAG, String.format(
                        "Record %s for beaconId %s was found to be newer than record %s",
                        alternativeNamingRecordId,
                        beaconId,
                        firstBeaconNamingRecordIdSeen
                ));
            } else {
                // keep as is
                Log.d(TAG, String.format(
                        "Record %s for beaconId %s was found to be newer than record %s",
                        firstBeaconNamingRecordIdSeen,
                        beaconId,
                        alternativeNamingRecordId
                ));
            }

        } catch (Exception e) {
            Log.e(TAG, "Failed to resolve naming record conflict!", e);
        }
    }

    private static BeaconNamingRecordCloudKitMetadata extractBeaconNamingRecordMetadata(final String rawBase64String) {
        try {
            String cleanBase64 = cleanXMLBase64Content(rawBase64String);
            // This annoying thing is in the `NSKeyedArchiver` format
            // see:
            //  - https://www.mac4n6.com/blog/2016/1/1/manual-analysis-of-nskeyedarchiver-formatted-plist-files-a-review-of-the-new-os-x-1011-recent-items
            //  - https://github.com/malmeloo/FindMy.py/issues/31#issuecomment-2628072362
            //  - https://github.com/3breadt/dd-plist/issues/70

            // a parser for this (one that works a bit better) actually exists in python:
            // - https://github.com/avibrazil/NSKeyedUnArchiver
            // Which at least allows us to extract slightly more useful data from
            // this file than by using the Google plist library (https://mvnrepository.com/artifact/com.googlecode.plist/dd-plist)
            // which *formally* does not support NSKeyedArchiver at the time of writing: https://github.com/3breadt/dd-plist/issues/70
            //
            // Since this Python convertor (pip library NSKeyedUnArchiver) works quite well, we will use it here.
            // Maybe the owners of the google library should consider porting
            // over their logic as it seems to produce accurate results.

            // offload this to python
            var py = Python.getInstance();
            var module = py.getModule("main");

            // call python function: returned = `decodeBeaconNamingRecordCloudKitMetadata(cleanBase64)`
            var returned = module.callAttr(
                    "decodeBeaconNamingRecordCloudKitMetadata",
                    new Kwarg("cleanedBase64", cleanBase64)
            );

            if (returned == null) {
                Log.e(TAG, "Failure in python while parsing NSKeyedArchiver-formatted BPList of 'cloudKitMetadata' for BeaconNamingRecord. Check python logs for error details");
                return null;
            }

            var returnMap = returned.asMap();

            final Long creationTime = Optional.ofNullable(returnMap.get("creationTime")).map(PyObject::toLong).orElse(null);
            final Long modifiedTime = Optional.ofNullable(returnMap.get("modifiedTime")).map(PyObject::toLong).orElse(null);
            final String modifiedByDevice = Optional.ofNullable(returnMap.get("modifiedByDevice")).map(PyObject::toString).orElse(null);

            return new BeaconNamingRecordCloudKitMetadata(
                    creationTime,
                    modifiedTime,
                    modifiedByDevice
            );

        } catch (Exception e) {
            Log.e(TAG, "Error while parsing NSKeyedArchiver-formatted BPList of 'cloudKitMetadata' for BeaconNamingRecord", e);
            return null;
        }
    }

    /**
     * Gets rid of all extra tabs and spaces
     */
    private static String cleanXMLBase64Content(final String rawBase64String) {
        return rawBase64String.replaceAll("\\s+", "");
    }

    private static boolean isExpectedFileType(final String fileName) {
        return ALLOWED_FILE_ENDINGS.stream().anyMatch(fileName::endsWith);
    }

    private static Pair<FILE_TYPE, List<String>> getAllowedFileType(final String fileName) {
        for (var entry: MATCHERS.entrySet()) {
            Matcher matcher = entry.getValue().matcher(fileName);

            if (matcher.find()) {
                // found matching combo! -> extract all regex match groups
                List<String> results = new ArrayList<>();

                for (int i = 0; i <= matcher.groupCount(); ++i) {
                    results.add(matcher.group(i));
                }

                return Pair.create(entry.getKey(), results);
            }
        }

        return null;
    }

    private static ImportData convert(
            final String importInfo,
            final Map<String, String> ownedBeacons,
            final Map<String, Pair<String, String>> beaconNamingRecords) {
        try {
            Import anImport = parseImportInfo(importInfo);
            List<OwnedBeacon> beacons = makeOwnedBeacons(ownedBeacons, anImport.version);
            List<BeaconNamingRecord> records = makeBeaconNamingRecords(beaconNamingRecords, anImport.version);

            return new ImportData(
                    anImport,
                    beacons,
                    records
            );
        } catch (Exception e) {
            throw new ZipImporterException("Failed to convert data", e);
        }
    }

    private static Import parseImportInfo(final String importInfo) throws JsonProcessingException {
        Airtag4AllYamlContent content = YamlParser.MAPPER.readValue(importInfo, Airtag4AllYamlContent.class);

        return Import.builder()
                .importedAt(System.currentTimeMillis())
                .exportedAt(content.getExportTimestamp())
                .version(content.getVersion())
                .exportedVia(content.getVia())
                .sourceUser(content.getSourceUser())
                .build();
    }

    private static List<OwnedBeacon> makeOwnedBeacons(final Map<String, String> ownedBeacons, final String version) {
        return ownedBeacons.entrySet().stream()
                .map(kvp -> OwnedBeacon.builder()
                        .id(kvp.getKey())
                        .importId(null) // TODO: fill on create Import
                        .version(version)
                        .content(kvp.getValue())
                        .build())
                .collect(Collectors.toList());
    }

    private static List<BeaconNamingRecord> makeBeaconNamingRecords(final Map<String, Pair<String, String>> beaconNamingRecords, final String version) {
        return beaconNamingRecords.entrySet().stream()
                .map(kvp -> BeaconNamingRecord.builder()
                        .id(kvp.getKey())
                        .importId(null) // TODO: fill on create Import
                        .version(version)
                        .content(kvp.getValue().second)
                        .build())
                .collect(Collectors.toList());
    }

    public static class ImportFileFormatException extends RuntimeException {
        public ImportFileFormatException(String message) {
            super(message);
        }

        public ImportFileFormatException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    @Data
    private static final class Airtag4AllYamlContent {
        private String version;
        private long exportTimestamp;
        private String sourceUser;
        private String via;
    }
}
