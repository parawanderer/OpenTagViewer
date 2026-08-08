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

    public boolean hasDarkThemeEnabled() {
        return this.useDarkTheme == Boolean.TRUE;
    }
    
    /**
     * 获取地图提供商，默认为"google"
     */
    public String getMapProvider() {
        return mapProvider != null && !mapProvider.isEmpty() ? mapProvider : "google";
    }
}
