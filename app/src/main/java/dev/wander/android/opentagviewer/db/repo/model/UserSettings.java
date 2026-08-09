package dev.wander.android.opentagviewer.db.repo.model;

import lombok.Builder;
import lombok.Data;

@Builder
@Data
public class UserSettings {
    private Boolean useDarkTheme;
    private String anisetteServerUrl;
    private String language;
    private Boolean enableDebugData;
    private String mapProvider; // "google" or "amap"

    /**
     * The user's own AMap (高德地图) API key.
     * <br>
     * Not shipped with the app: AMap keys are issued per developer account, bound to a
     * package name and signing fingerprint, and their terms expect the key holder to be
     * the app's operator. So anyone wanting AMap supplies their own, the same way the
     * Anisette server URL works.
     */
    private String amapApiKey;

    public boolean hasDarkThemeEnabled() {
        return this.useDarkTheme == Boolean.TRUE;
    }

    /**
     * The selected map provider, defaulting to Google Maps.
     */
    public String getMapProvider() {
        return mapProvider != null && !mapProvider.isEmpty() ? mapProvider : "google";
    }

    public boolean hasAmapApiKey() {
        return this.amapApiKey != null && !this.amapApiKey.isBlank();
    }
}
