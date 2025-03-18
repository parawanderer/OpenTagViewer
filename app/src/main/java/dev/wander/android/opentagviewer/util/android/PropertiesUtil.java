package dev.wander.android.opentagviewer.util.android;

import android.content.res.AssetManager;
import android.util.Log;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.util.Properties;

public class PropertiesUtil {
    private static final String TAG = PropertiesUtil.class.getSimpleName();

    public static Properties getProperties(AssetManager assetManager, String propertiesFileName) {
        Properties props = new Properties();
        try {
            var propsStream = assetManager.open(propertiesFileName);
            props.load(propsStream);
            propsStream.close();
            return props;
        } catch (FileNotFoundException e) {
            Log.e(TAG, "Properties file does not exist: " + propertiesFileName, e);
            return null;
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }
}
