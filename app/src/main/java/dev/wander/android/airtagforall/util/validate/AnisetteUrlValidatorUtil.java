package dev.wander.android.airtagforall.util.validate;

import android.webkit.URLUtil;

import java.net.URL;

import lombok.AccessLevel;
import lombok.NoArgsConstructor;

@NoArgsConstructor(access = AccessLevel.PRIVATE)
public final class AnisetteUrlValidatorUtil {
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
