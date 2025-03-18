package dev.wander.android.opentagviewer.util.android;

import static org.xmlpull.v1.XmlPullParser.END_DOCUMENT;
import static org.xmlpull.v1.XmlPullParser.START_TAG;

import android.content.res.Resources;
import android.content.res.XmlResourceParser;
import android.util.Log;

import org.xmlpull.v1.XmlPullParserException;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import dev.wander.android.opentagviewer.R;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;

@NoArgsConstructor(access = AccessLevel.PRIVATE)
public final class LocaleConfigUtil {
    private static final String TAG = LocaleConfigUtil.class.getSimpleName();

    private static final String LOCALE = "locale";

    private static final String LOCALE_NAME_NAMESPACE = "http://schemas.android.com/apk/res/android";
    private static final String LOCALE_NAME = "name";

    /**
     * Extract the list of locales available in the app from the `locales_config.xml` resource file
     * E.g. for use in UI display for locale selection purposes.
     *
     * @return  The available locales that can be used in the app.
     */
    public static List<String> getAvailableLocales(Resources resources) {
        // SEE https://developer.android.com/reference/org/xmlpull/v1/XmlPullParser#getAttributeValue(java.lang.String,%20java.lang.String)
        var res = new ArrayList<String>();

        try {
            XmlResourceParser xml = resources.getXml(R.xml.locales_config);
            int eventType = xml.getEventType();
            do {
                if (eventType == START_TAG) {
                    handleStartTag(xml, res);
                }

                eventType = xml.next();
            } while (eventType != END_DOCUMENT);
        } catch (XmlPullParserException e) {
            Log.e(TAG, "Error occurred while parsing locales_config.xml", e);
        } catch (IOException e) {
            Log.e(TAG, "IO Exception occurred while parsing locales_config.xml", e);
        }

        return res;
    }

    private static void handleStartTag(XmlResourceParser xml, List<String> out) {
        boolean isLocaleElement = Optional.ofNullable(xml.getName()).map(n -> n.equals(LOCALE)).orElse(false);

        if (!isLocaleElement) return;

        final String name = xml.getAttributeValue(LOCALE_NAME_NAMESPACE, LOCALE_NAME);

        if (name != null) {
            out.add(name);
        }
    }
}
