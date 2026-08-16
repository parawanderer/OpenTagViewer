package dev.wander.android.opentagviewer.util.parse;

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

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
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
import net.lingala.zip4j.exception.ZipException;
import net.lingala.zip4j.io.inputstream.ZipInputStream;
import net.lingala.zip4j.model.LocalFileHeader;

import javax.xml.xpath.XPath;
import javax.xml.xpath.XPathFactory;

import dev.wander.android.opentagviewer.R;
import dev.wander.android.opentagviewer.data.model.BeaconNamingRecordCloudKitMetadata;
import dev.wander.android.opentagviewer.db.room.entity.BeaconNamingRecord;
import dev.wander.android.opentagviewer.db.room.entity.Import;
import dev.wander.android.opentagviewer.db.room.entity.OwnedBeacon;
import dev.wander.android.opentagviewer.util.parse.ZipImporterException.Reason;
import dev.wander.android.opentagviewer.db.repo.model.ImportData;
import lombok.AccessLevel;
import lombok.Data;
import lombok.Getter;
import lombok.NonNull;

@Getter(AccessLevel.PRIVATE)
public class AppleZipImporterUtil {
    enum FILE_TYPE {
        EXPORT_INFO,
        OWNED_BEACON,
        BEACON_NAMING_RECORD,
        /**
         * Optional. Carries the rolling-key alignment macOS last observed for an accessory,
         * which lets FindMy 0.9.x start fetching from the right key index instead of
         * searching the tag's entire history. Absent from exports made before format 0.0.2.
         */
        KEY_ALIGNMENT_RECORD;
    }

    private static final String TAG = AppleZipImporterUtil.class.getSimpleName();

    private static final List<String> ALLOWED_FILE_ENDINGS = List.of(".yml", ".plist");

    private static final Map<FILE_TYPE, Pattern> MATCHERS = Map.of(
            FILE_TYPE.EXPORT_INFO, Pattern.compile("^OPENTAGVIEWER\\.yml$"),
            FILE_TYPE.OWNED_BEACON, Pattern.compile("^OwnedBeacons/([0-9A-F]{8}-[0-9A-F]{4}-4[0-9A-F]{3}-[89AB][0-9A-F]{3}-[0-9A-F]{12})\\.plist$"),
            FILE_TYPE.BEACON_NAMING_RECORD, Pattern.compile("^BeaconNamingRecord/([0-9A-F]{8}-[0-9A-F]{4}-4[0-9A-F]{3}-[89AB][0-9A-F]{3}-[0-9A-F]{12})/([0-9A-F]{8}-[0-9A-F]{4}-4[0-9A-F]{3}-[89AB][0-9A-F]{3}-[0-9A-F]{12})\\.plist$"),
            // Same nesting as BeaconNamingRecord: <accessory uuid>/<record uuid>. The wizard
            // rewrites the .record extension macOS uses to .plist on export.
            FILE_TYPE.KEY_ALIGNMENT_RECORD, Pattern.compile("^KeyAlignmentRecords/([0-9A-F]{8}-[0-9A-F]{4}-4[0-9A-F]{3}-[89AB][0-9A-F]{3}-[0-9A-F]{12})/([0-9A-F]{8}-[0-9A-F]{4}-4[0-9A-F]{3}-[89AB][0-9A-F]{3}-[0-9A-F]{12})\\.plist$")
    );

    private static final String X_PATH_TO_CLOUDKIT_METADATA = "/plist/dict/key[.='cloudKitMetadata']/following-sibling::data[1]";

    private static final int ZIP_SIGNATURE_LENGTH = 4;

    /** Local file header, end of central directory (an empty archive), and spanned marker. */
    private static final byte[][] ZIP_SIGNATURES = {
            {'P', 'K', 0x03, 0x04},
            {'P', 'K', 0x05, 0x06},
            {'P', 'K', 0x07, 0x08},
    };

    private final Context appContext;

    public AppleZipImporterUtil(Context appContext) {
        this.appContext = appContext;
    }

    public ImportData extractZip(@NonNull Uri zipFileUri) throws ZipImporterException {
        return this.extractZip(zipFileUri, null);
    }

    /**
     * Read a bundle, unlocking it if it is locked.
     *
     * @param passcode the code the bundle was locked with, already through
     *                 {@link BundlePasscode#normalise}, or null if none has been asked for yet.
     *                 A locked bundle with no code raises {@link Reason#LOCKED} before any entry
     *                 is read, which is the caller's cue to prompt and try again.
     */
    public ImportData extractZip(@NonNull Uri zipFileUri, final String passcode)
            throws ZipImporterException {

        String openTagViewerYaml = null;
        Map<String, String> ownedBeacons = new HashMap<>();
        Map<String, Pair<String, String>> beaconNamingRecords = new HashMap<>();
        // Optional per accessory: absent for exports made before format 0.0.2, and for
        // accessories macOS has never observed a key index for.
        Map<String, String> keyAlignmentRecords = new HashMap<>();

        int entriesSeen = 0;

        // zip4j rather than java.util.zip, which cannot decrypt anything at all - not the AES
        // the exporter writes, not even the legacy ZipCrypto. A plain bundle reads the same way
        // through it, so there is only one path here rather than one per kind of bundle.
        try (BufferedInputStream source = this.open(zipFileUri);
             ZipInputStream zipInput = passcode == null
                     ? new ZipInputStream(source, StandardCharsets.UTF_8)
                     : new ZipInputStream(source, passcode.toCharArray(), StandardCharsets.UTF_8);
             ByteArrayOutputStream baos = new ByteArrayOutputStream()) {

            byte[] buffer = new byte[1024];
            int read = 0;
            LocalFileHeader zipEntry;

            while ((zipEntry = zipInput.getNextEntry()) != null) {
                entriesSeen++;
                // read data
                String fileName = zipEntry.getFileName();
                Log.d(TAG, "Now reading file " + fileName + " while unzipping...");

                if (zipEntry.isEncrypted() && passcode == null) {
                    // Rarely reached: for the AES the exporter writes, zip4j raises from
                    // getNextEntry above rather than handing back a header, and that path is
                    // what actually reports this. Kept for any scheme where the header does
                    // come back first, so the question is asked before content is touched.
                    throw new ZipImporterException(Reason.LOCKED,
                            "Bundle is encrypted and no passcode was supplied");
                }

                if (zipEntry.isDirectory()) {
                    Log.d(TAG, "Skipping " + fileName + ": this is a directory, nothing to do here");
                    continue;
                }

                if (!isExpectedFileType(fileName)) {
                    Log.d(TAG, "Skipping " + fileName + ": this is a file with an unallowed file type (file extension not in whitelist)");
                    continue;
                }

                // which type is it?
                final Pair<FILE_TYPE, List<String>> typeAndRegexGroups = getAllowedFileType(fileName);
                if (typeAndRegexGroups == null) {
                    Log.w(TAG, "Encountered unexpected file " + fileName
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
                        openTagViewerYaml = fileContent;
                        break;
                    case OWNED_BEACON:
                        ownedBeacons.put(typeAndRegexGroups.second.get(1), fileContent);
                        break;
                    case BEACON_NAMING_RECORD:
                        processBeaconNamingRecord(typeAndRegexGroups.second, fileContent, beaconNamingRecords);
                        break;
                    case KEY_ALIGNMENT_RECORD:
                        // Keyed by the parent directory, which is the accessory id.
                        keyAlignmentRecords.put(typeAndRegexGroups.second.get(1), fileContent);
                        break;
                }
            }
        } catch (ZipException e) {
            if (e.getType() == ZipException.Type.WRONG_PASSWORD) {
                // Both "locked, and nobody has been asked yet" and "asked, and that was not it"
                // arrive here, because zip4j raises WRONG_PASSWORD for a missing password as
                // readily as for an incorrect one. Which of the two it is is not something the
                // zip knows - it is whether we had a code to try.
                //
                // AES carries a two-byte password verifier, so an incorrect one is known at the
                // first entry rather than after decrypting something into nonsense. That is what
                // makes "that code did not work" sayable at all, instead of "this is damaged".
                throw new ZipImporterException(
                        passcode == null ? Reason.LOCKED : Reason.WRONG_PASSCODE,
                        passcode == null
                                ? "Bundle is encrypted and no passcode was supplied"
                                : "Passcode did not open the bundle",
                        e);
            }
            // The signature said zip, so it is one - it just cannot be read to the end.
            throw new ZipImporterException(Reason.DAMAGED,
                    "Zip could not be read after " + entriesSeen + " entries", e);
        } catch (IOException e) {
            throw new ZipImporterException(Reason.DAMAGED,
                    "Import stream failed after " + entriesSeen + " entries", e);
        }

        if (openTagViewerYaml == null) {
            // A zip, but not one of ours: this is the common case of somebody picking the wrong
            // file, so it has to be distinguishable from an export of ours that went wrong.
            throw new ZipImporterException(Reason.NOT_AN_EXPORT,
                    "No OPENTAGVIEWER.yml among the " + entriesSeen + " entries in this zip");
        }

        assertOpenTagViewerYamlIsValid(openTagViewerYaml);
        innerJoinBeaconFiles(ownedBeacons, beaconNamingRecords);

        if (ownedBeacons.isEmpty()) {
            // Otherwise this succeeds and reports "0 new imported devices", which reads as the
            // import having worked and the tags having gone missing afterwards.
            throw new ZipImporterException(Reason.NO_TAGS,
                    "Export is well-formed but carries no OwnedBeacons");
        }

        // now we can produce the expected data
        return convert(
          openTagViewerYaml,
          ownedBeacons,
          beaconNamingRecords,
          keyAlignmentRecords
        );
    }

    /**
     * Open the picked file, having first established that it is a zip at all.
     *
     * <p>Checked up front rather than inferred from the parse, because {@link ZipInputStream}
     * does not report a non-zip: {@code getNextEntry} simply returns null, so a JPEG and an
     * empty archive and somebody else's zip all arrived at the same place, and the first
     * complaint the code could make was that the manifest was missing. "This export has no
     * OPENTAGVIEWER.yml" is a strange thing to tell somebody who picked a photo.
     */
    private BufferedInputStream open(@NonNull Uri zipFileUri) throws ZipImporterException {
        final BufferedInputStream source;
        try {
            final InputStream raw = this.appContext.getContentResolver().openInputStream(zipFileUri);
            if (raw == null) {
                throw new ZipImporterException(Reason.UNREADABLE,
                        "Content resolver returned no stream for " + zipFileUri);
            }
            source = new BufferedInputStream(raw);
        } catch (IOException | SecurityException e) {
            // Picked from a provider that has since revoked access, or the file has moved.
            throw new ZipImporterException(Reason.UNREADABLE,
                    "Could not open " + zipFileUri, e);
        }

        try {
            source.mark(ZIP_SIGNATURE_LENGTH);
            final byte[] signature = new byte[ZIP_SIGNATURE_LENGTH];
            final int read = readFully(source, signature);
            source.reset();

            if (read < ZIP_SIGNATURE_LENGTH || !isZipSignature(signature)) {
                throw new ZipImporterException(Reason.NOT_A_ZIP,
                        "File does not start with a zip signature");
            }
        } catch (IOException e) {
            throw new ZipImporterException(Reason.UNREADABLE,
                    "Could not read the start of " + zipFileUri, e);
        }

        return source;
    }

    /** Read until the buffer is full or the stream ends, which a single read does not promise. */
    private static int readFully(final InputStream in, final byte[] into) throws IOException {
        int total = 0;
        while (total < into.length) {
            final int read = in.read(into, total, into.length - total);
            if (read < 0) {
                break;
            }
            total += read;
        }
        return total;
    }

    /**
     * Whether these first bytes are any of the three things a zip can begin with.
     *
     * <p>An archive with no entries starts at the end-of-central-directory record rather than
     * at a local file header, and a spanned one carries its own marker. Both are zips, so both
     * are accepted here and rejected later for the real reason - that they are not an export.
     */
    private static boolean isZipSignature(final byte[] bytes) {
        for (final byte[] candidate : ZIP_SIGNATURES) {
            if (Arrays.equals(bytes, candidate)) {
                return true;
            }
        }
        return false;
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

    private void assertOpenTagViewerYamlIsValid(final String content) {
        if (content == null || content.isBlank()) {
            throw new ImportFileFormatException("OPENTAGVIEWER.yml was empty!");
        }

        // validate content: see https://github.com/networknt/json-schema-validator
        JsonSchemaFactory jsonSchemaFactory = JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012);
        SchemaValidatorsConfig.Builder builder = SchemaValidatorsConfig.builder();
        SchemaValidatorsConfig config = builder.build();

        try (InputStream inputStream = this.appContext.getResources()
                .openRawResource(R.raw.opentagviewer_schema)) {

            JsonSchema schema = jsonSchemaFactory.getSchema(inputStream);
            Set<ValidationMessage> assertions = schema.validate(content, InputFormat.YAML, executionContext -> {
                // By default since Draft 2019-09 the format keyword only generates annotations and not assertions
                executionContext.getExecutionConfig().setFormatAssertionsEnabled(true);
            });

            if (!assertions.isEmpty()) {
                throw new ImportFileFormatException(
                        String.format(
                                "OPENTAGVIEWER.yml format validation failed: %s",
                                assertions.stream()
                                        .filter(a -> !a.isValid())
                                        .map(ValidationMessage::getMessage)
                                        .collect(Collectors.joining(", ")))
                );
            }

            // OK
        } catch (IOException e) {
            throw new ImportFileFormatException("OPENTAGVIEWER.yml could not be parsed!", e);
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
            final Map<String, Pair<String, String>> beaconNamingRecords,
            final Map<String, String> keyAlignmentRecords) {
        try {
            Import anImport = parseImportInfo(importInfo);
            List<OwnedBeacon> beacons = makeOwnedBeacons(ownedBeacons, keyAlignmentRecords, anImport.version);
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
        OpenTagViewerYamlContent content = YamlParser.MAPPER.readValue(importInfo, OpenTagViewerYamlContent.class);

        return Import.builder()
                .importedAt(System.currentTimeMillis())
                .exportedAt(content.getExportTimestamp())
                .version(content.getVersion())
                .exportedVia(content.getVia())
                .sourceUser(content.getSourceUser())
                .build();
    }

    private static List<OwnedBeacon> makeOwnedBeacons(
            final Map<String, String> ownedBeacons,
            final Map<String, String> keyAlignmentRecords,
            final String version) {
        return ownedBeacons.entrySet().stream()
                .map(kvp -> OwnedBeacon.builder()
                        .id(kvp.getKey())
                        .importId(null) // TODO: fill on create Import
                        .version(version)
                        .content(kvp.getValue())
                        // Null when the export predates format 0.0.2 or macOS had no
                        // alignment for this accessory; conversion handles that.
                        .alignmentPlist(keyAlignmentRecords.get(kvp.getKey()))
                        // Eagerly convert plist → JSON for FindMy 0.9.x; nullable on failure
                        // (lazy backfill in BeaconRepository will retry on first fetch).
                        .accessoryJson(plistToAccessoryJsonOrNull(
                                kvp.getValue(), keyAlignmentRecords.get(kvp.getKey())))
                        .build())
                .collect(Collectors.toList());
    }

    private static String plistToAccessoryJsonOrNull(
            final String plistXml, final String alignmentPlistXml) {
        try {
            var py = com.chaquo.python.Python.getInstance();
            var module = py.getModule("main");
            var result = module.callAttr("convertPlistToJson", plistXml, alignmentPlistXml);
            return result == null ? null : result.toString();
        } catch (Exception e) {
            Log.w(TAG, "convertPlistToJson at import time failed; will backfill lazily on first fetch", e);
            return null;
        }
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

    /**
     * The manifest was there and did not hold up.
     *
     * <p>A {@link ZipImporterException} because it is one - it previously extended
     * {@code RuntimeException} directly, so the {@code catch (ZipImporterException)} written to
     * handle import failures never saw it and it fell through to the generic handler beside it.
     * That made no difference only because both did the same thing.
     */
    public static class ImportFileFormatException extends ZipImporterException {
        public ImportFileFormatException(String message) {
            super(Reason.DAMAGED, message);
        }

        public ImportFileFormatException(String message, Throwable cause) {
            super(Reason.DAMAGED, message, cause);
        }
    }

    @Data
    private static final class OpenTagViewerYamlContent {
        private String version;
        private long exportTimestamp;
        private String sourceUser;
        private String via;
    }
}
