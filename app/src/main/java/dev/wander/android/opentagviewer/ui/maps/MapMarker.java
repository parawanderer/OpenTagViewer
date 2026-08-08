package dev.wander.android.opentagviewer.ui.maps;

import android.graphics.Bitmap;
import androidx.annotation.ColorInt;
import androidx.annotation.DrawableRes;
import androidx.annotation.Nullable;

/**
 * 地图标记点数据类
 */
public class MapMarker {
    private final double latitude;
    private final double longitude;
    private final String title;
    private final String snippet;
    private final String id;
    
    // 图标相关
    @Nullable
    private final Bitmap iconBitmap;
    @DrawableRes
    private final int iconResourceId;
    private final boolean useDefaultIcon;
    
    // 标记颜色（用于默认图标）
    @ColorInt
    private final int markerColor;
    
    // 是否可拖拽
    private final boolean draggable;
    
    // 是否可见
    private final boolean visible;
    
    // 透明度（0.0 - 1.0）
    private final float alpha;
    
    private MapMarker(Builder builder) {
        this.latitude = builder.latitude;
        this.longitude = builder.longitude;
        this.title = builder.title;
        this.snippet = builder.snippet;
        this.id = builder.id;
        this.iconBitmap = builder.iconBitmap;
        this.iconResourceId = builder.iconResourceId;
        this.useDefaultIcon = builder.useDefaultIcon;
        this.markerColor = builder.markerColor;
        this.draggable = builder.draggable;
        this.visible = builder.visible;
        this.alpha = builder.alpha;
    }
    
    public double getLatitude() {
        return latitude;
    }
    
    public double getLongitude() {
        return longitude;
    }
    
    public String getTitle() {
        return title;
    }
    
    public String getSnippet() {
        return snippet;
    }
    
    public String getId() {
        return id;
    }
    
    @Nullable
    public Bitmap getIconBitmap() {
        return iconBitmap;
    }
    
    @DrawableRes
    public int getIconResourceId() {
        return iconResourceId;
    }
    
    public boolean isUseDefaultIcon() {
        return useDefaultIcon;
    }
    
    @ColorInt
    public int getMarkerColor() {
        return markerColor;
    }
    
    public boolean isDraggable() {
        return draggable;
    }
    
    public boolean isVisible() {
        return visible;
    }
    
    public float getAlpha() {
        return alpha;
    }
    
    public static Builder builder() {
        return new Builder();
    }
    
    public static class Builder {
        private double latitude;
        private double longitude;
        private String title;
        private String snippet;
        private String id;
        private Bitmap iconBitmap;
        @DrawableRes
        private int iconResourceId = 0;
        private boolean useDefaultIcon = true;
        @ColorInt
        private int markerColor = 0xFF000000; // 默认黑色
        private boolean draggable = false;
        private boolean visible = true;
        private float alpha = 1.0f;
        
        public Builder latitude(double latitude) {
            this.latitude = latitude;
            return this;
        }
        
        public Builder longitude(double longitude) {
            this.longitude = longitude;
            return this;
        }
        
        public Builder position(double latitude, double longitude) {
            this.latitude = latitude;
            this.longitude = longitude;
            return this;
        }
        
        public Builder title(String title) {
            this.title = title;
            return this;
        }
        
        public Builder snippet(String snippet) {
            this.snippet = snippet;
            return this;
        }
        
        public Builder id(String id) {
            this.id = id;
            return this;
        }
        
        public Builder icon(Bitmap iconBitmap) {
            this.iconBitmap = iconBitmap;
            this.useDefaultIcon = false;
            return this;
        }
        
        public Builder iconResource(@DrawableRes int iconResourceId) {
            this.iconResourceId = iconResourceId;
            this.useDefaultIcon = false;
            return this;
        }
        
        public Builder useDefaultIcon(boolean useDefaultIcon) {
            this.useDefaultIcon = useDefaultIcon;
            return this;
        }
        
        public Builder markerColor(@ColorInt int markerColor) {
            this.markerColor = markerColor;
            return this;
        }
        
        public Builder draggable(boolean draggable) {
            this.draggable = draggable;
            return this;
        }
        
        public Builder visible(boolean visible) {
            this.visible = visible;
            return this;
        }
        
        public Builder alpha(float alpha) {
            this.alpha = Math.max(0.0f, Math.min(1.0f, alpha));
            return this;
        }
        
        public MapMarker build() {
            if (id == null || id.isEmpty()) {
                id = "marker_" + System.currentTimeMillis() + "_" + Math.random();
            }
            return new MapMarker(this);
        }
    }
}

