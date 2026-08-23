package dev.wander.android.opentagviewer.python;

import android.util.Base64;
import android.util.Log;

import com.chaquo.python.PyObject;
import com.chaquo.python.Python;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * {@link BundleBuilder} over {@code main.buildExportBundle}, which calls the same
 * {@code opentagviewer_export.build_export} the desktop exporter does.
 *
 * <p><b>Blocking, and needs a started interpreter.</b> Never call it on the main thread.
 *
 * <p>Bytes cross as base64 inside JSON. A plist is not UTF-8 and an {@code OwnedBeacons} record
 * carries the accessory's private key, so anything lossy in the crossing produces a bundle that
 * imports cleanly and then locates nothing - a failure discovered days later, by the recipient,
 * after the sender has deleted their copy.
 */
public class ChaquopyBundleBuilder implements BundleBuilder {
    private static final String TAG = ChaquopyBundleBuilder.class.getSimpleName();

    private static final String MODULE = "main";

    @Override
    public Built build(
            final List<Accessory> accessories,
            final String via,
            final String sourceUser,
            final long exportedAtMs) throws BundleBuildException {

        final String reply;
        try {
            final PyObject module = Python.getInstance().getModule(MODULE);
            reply = module.callAttr(
                    "buildExportBundle",
                    describe(accessories),
                    via,
                    sourceUser,
                    exportedAtMs).toString();
        } catch (final Exception e) {
            // Python did not start, or the module is not in the APK. Not something a user can act
            // on, so it goes up as-is and the screen offers a bug report.
            throw new BundleBuildException("The export could not be built.", e);
        }

        return read(reply);
    }

    /** The selection, as the shape {@code buildExportBundle} documents. */
    private static String describe(final List<Accessory> accessories) throws BundleBuildException {
        try {
            final JSONArray described = new JSONArray();

            for (final Accessory accessory : accessories) {
                final JSONObject one = new JSONObject();
                one.put("ownedBeaconPlist", accessory.getOwnedBeaconPlist());
                one.put("namingRecordPlist", accessory.getNamingRecordPlist());
                if (accessory.getAlignmentPlist() != null) {
                    one.put("alignmentPlist", accessory.getAlignmentPlist());
                }
                described.put(one);
            }

            return described.toString();
        } catch (final Exception e) {
            throw new BundleBuildException("The export could not be built.", e);
        }
    }

    private static Built read(final String reply) throws BundleBuildException {
        try {
            final JSONObject answer = new JSONObject(reply);

            // A refusal with a reason, which the shared package wrote and a person can read.
            if (answer.has("error")) {
                throw new BundleBuildException(answer.getString("error"));
            }

            final JSONObject entries = answer.getJSONObject("entries");

            // Linked, so the archive comes out in the order Python built it rather than in
            // whatever order a hash gives. Nothing depends on it; a reproducible file is simply
            // easier to reason about when two of them differ.
            final Map<String, byte[]> files = new LinkedHashMap<>();
            for (final java.util.Iterator<String> names = entries.keys(); names.hasNext(); ) {
                final String name = names.next();
                files.put(name, Base64.decode(entries.getString(name), Base64.DEFAULT));
            }

            final String warning = answer.optString("warning", null);
            if (warning != null) {
                Log.w(TAG, "The bundle was written without something: " + warning);
            }

            return new Built(files, warning);
        } catch (final BundleBuildException e) {
            throw e;
        } catch (final Exception e) {
            throw new BundleBuildException("The export could not be built.", e);
        }
    }
}
