package dev.wander.android.opentagviewer.python;

import android.util.Log;

import com.chaquo.python.Kwarg;
import com.chaquo.python.PyObject;
import com.chaquo.python.Python;

import java.util.Optional;

import dev.wander.android.opentagviewer.data.model.BeaconNamingRecordCloudKitMetadata;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;

@NoArgsConstructor(access = AccessLevel.PRIVATE)
public final class PythonUtils {
    private static final String TAG = PythonUtils.class.getSimpleName();

    public static BeaconNamingRecordCloudKitMetadata decodeBeaconNamingRecordCloudKitMetadata(final String cleanBase64) {
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
        try {
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
        } catch (Exception e){
            Log.e(TAG, "Error while parsing NSKeyedArchiver-formatted BPList of 'cloudKitMetadata' for BeaconNamingRecord", e);
            throw new PythonUtilsException("Failed to call decodeBeaconNamingRecordCloudKitMetadata in main module in python", e);
        }
    }
}
