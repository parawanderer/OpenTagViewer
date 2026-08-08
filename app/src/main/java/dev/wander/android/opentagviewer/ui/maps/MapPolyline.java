package dev.wander.android.opentagviewer.ui.maps;

import androidx.annotation.ColorInt;

import java.util.List;

/**
 * 地图路径线数据类
 */
public class MapPolyline {
    private final List<LatLng> points;
    private final String id;
    
    // 样式相关
    @ColorInt
    private final int color;
    private final float width;
    private final float zIndex;
    private final boolean geodesic;
    private final boolean visible;
    
    // 透明度（0.0 - 1.0）
    private final float alpha;
    
    // 虚线模式（某些地图SDK支持）
    private final boolean dotted;
    private final float[] pattern; // 虚线模式数组
    
    private MapPolyline(Builder builder) {
        this.points = builder.points;
        this.id = builder.id;
        this.color = builder.color;
        this.width = builder.width;
        this.zIndex = builder.zIndex;
        this.geodesic = builder.geodesic;
        this.visible = builder.visible;
        this.alpha = builder.alpha;
        this.dotted = builder.dotted;
        this.pattern = builder.pattern;
    }
    
    public List<LatLng> getPoints() {
        return points;
    }
    
    public String getId() {
        return id;
    }
    
    @ColorInt
    public int getColor() {
        return color;
    }
    
    public float getWidth() {
        return width;
    }
    
    public float getZIndex() {
        return zIndex;
    }
    
    public boolean isGeodesic() {
        return geodesic;
    }
    
    public boolean isVisible() {
        return visible;
    }
    
    public float getAlpha() {
        return alpha;
    }
    
    public boolean isDotted() {
        return dotted;
    }
    
    public float[] getPattern() {
        return pattern;
    }
    
    public static Builder builder() {
        return new Builder();
    }
    
    public static class Builder {
        private List<LatLng> points;
        private String id;
        @ColorInt
        private int color = 0xFF0000FF; // 默认蓝色
        private float width = 10.0f;
        private float zIndex = 0.0f;
        private boolean geodesic = false;
        private boolean visible = true;
        private float alpha = 1.0f;
        private boolean dotted = false;
        private float[] pattern = null;
        
        public Builder points(List<LatLng> points) {
            this.points = points;
            return this;
        }
        
        public Builder id(String id) {
            this.id = id;
            return this;
        }
        
        public Builder color(@ColorInt int color) {
            this.color = color;
            return this;
        }
        
        public Builder width(float width) {
            this.width = Math.max(1.0f, width);
            return this;
        }
        
        public Builder zIndex(float zIndex) {
            this.zIndex = zIndex;
            return this;
        }
        
        public Builder geodesic(boolean geodesic) {
            this.geodesic = geodesic;
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
        
        public Builder dotted(boolean dotted) {
            this.dotted = dotted;
            return this;
        }
        
        public Builder pattern(float[] pattern) {
            this.pattern = pattern;
            return this;
        }
        
        public MapPolyline build() {
            if (points == null || points.isEmpty()) {
                throw new IllegalArgumentException("Polyline must have at least one point");
            }
            if (id == null || id.isEmpty()) {
                id = "polyline_" + System.currentTimeMillis() + "_" + Math.random();
            }
            return new MapPolyline(this);
        }
    }
    
    /**
     * 经纬度坐标点
     */
    public static class LatLng {
        private final double latitude;
        private final double longitude;
        
        public LatLng(double latitude, double longitude) {
            this.latitude = latitude;
            this.longitude = longitude;
        }
        
        public double getLatitude() {
            return latitude;
        }
        
        public double getLongitude() {
            return longitude;
        }
        
        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;
            LatLng latLng = (LatLng) o;
            return Double.compare(latLng.latitude, latitude) == 0 &&
                   Double.compare(latLng.longitude, longitude) == 0;
        }
        
        @Override
        public int hashCode() {
            long temp = Double.doubleToLongBits(latitude);
            int result = (int) (temp ^ (temp >>> 32));
            temp = Double.doubleToLongBits(longitude);
            result = 31 * result + (int) (temp ^ (temp >>> 32));
            return result;
        }
    }
}

