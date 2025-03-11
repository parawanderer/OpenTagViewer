package dev.wander.android.airtagforall.util;

import android.webkit.URLUtil;

import java.net.URL;

public class AnisetteUrlValidatorUtil {
    public static boolean isValidAnisetteUrl(final String urlInput) {
        if (!URLUtil.isHttpsUrl(urlInput) && !URLUtil.isHttpUrl(urlInput)) {
            return false;
        }
        try {
            var url = new URL(urlInput).toURI();

            // no path allowed -> this must be BASE URL
            return url.getPath() == null || url.getPath().isEmpty();

        } catch (Exception e) {
            return false;
        }
    }
}
