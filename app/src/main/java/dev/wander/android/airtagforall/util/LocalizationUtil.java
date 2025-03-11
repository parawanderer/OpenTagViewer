package dev.wander.android.airtagforall.util;

import android.content.res.Resources;

import dev.wander.android.airtagforall.R;

public class LocalizationUtil {
    public static String getCurrentLocale(Resources resources) {
        return resources.getString(R.string._current_locale);
    }
}
