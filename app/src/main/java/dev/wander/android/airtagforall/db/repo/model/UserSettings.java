package dev.wander.android.airtagforall.db.repo.model;

import lombok.Builder;
import lombok.Data;

@Builder
@Data
public class UserSettings {
    private Boolean useDarkTheme;
    private String anisetteServerUrl;
    private String language;
    private Boolean enableDebugData;

    public boolean hasDarkThemeEnabled() {
        return this.useDarkTheme == Boolean.TRUE;
    }
}
