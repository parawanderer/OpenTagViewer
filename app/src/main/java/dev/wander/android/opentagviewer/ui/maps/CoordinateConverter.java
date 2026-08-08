package dev.wander.android.opentagviewer.ui.maps;

/**
 * 坐标系转换工具
 * Google Maps使用WGS84坐标系（GPS原始坐标）
 * 高德地图使用GCJ02坐标系（火星坐标，中国加密后的坐标）
 */
public class CoordinateConverter {
    
    private static final double PI = 3.1415926535897932384626;
    private static final double A = 6378245.0; // 长半轴
    private static final double EE = 0.00669342162296594323; // 偏心率平方
    
    /**
     * WGS84转GCJ02（GPS坐标转火星坐标）
     * @param wgsLat WGS84纬度
     * @param wgsLon WGS84经度
     * @return GCJ02坐标 [纬度, 经度]
     */
    public static double[] wgs84ToGcj02(double wgsLat, double wgsLon) {
        if (isOutOfChina(wgsLat, wgsLon)) {
            return new double[]{wgsLat, wgsLon};
        }
        double dLat = transformLat(wgsLon - 105.0, wgsLat - 35.0);
        double dLon = transformLon(wgsLon - 105.0, wgsLat - 35.0);
        double radLat = wgsLat / 180.0 * PI;
        double magic = Math.sin(radLat);
        magic = 1 - EE * magic * magic;
        double sqrtMagic = Math.sqrt(magic);
        dLat = (dLat * 180.0) / ((A * (1 - EE)) / (magic * sqrtMagic) * PI);
        dLon = (dLon * 180.0) / (A / sqrtMagic * Math.cos(radLat) * PI);
        double mgLat = wgsLat + dLat;
        double mgLon = wgsLon + dLon;
        return new double[]{mgLat, mgLon};
    }
    
    /**
     * GCJ02转WGS84（火星坐标转GPS坐标）
     * @param gcjLat GCJ02纬度
     * @param gcjLon GCJ02经度
     * @return WGS84坐标 [纬度, 经度]
     */
    public static double[] gcj02ToWgs84(double gcjLat, double gcjLon) {
        if (isOutOfChina(gcjLat, gcjLon)) {
            return new double[]{gcjLat, gcjLon};
        }
        double dLat = transformLat(gcjLon - 105.0, gcjLat - 35.0);
        double dLon = transformLon(gcjLon - 105.0, gcjLat - 35.0);
        double radLat = gcjLat / 180.0 * PI;
        double magic = Math.sin(radLat);
        magic = 1 - EE * magic * magic;
        double sqrtMagic = Math.sqrt(magic);
        dLat = (dLat * 180.0) / ((A * (1 - EE)) / (magic * sqrtMagic) * PI);
        dLon = (dLon * 180.0) / (A / sqrtMagic * Math.cos(radLat) * PI);
        double mgLat = gcjLat + dLat;
        double mgLon = gcjLon + dLon;
        return new double[]{gcjLat * 2 - mgLat, gcjLon * 2 - mgLon};
    }
    
    /**
     * 判断是否在中国范围外
     */
    private static boolean isOutOfChina(double lat, double lon) {
        return lon < 72.004 || lon > 137.8347 || lat < 0.8293 || lat > 55.8271;
    }
    
    /**
     * 纬度转换
     */
    private static double transformLat(double x, double y) {
        double ret = -100.0 + 2.0 * x + 3.0 * y + 0.2 * y * y + 0.1 * x * y + 0.2 * Math.sqrt(Math.abs(x));
        ret += (20.0 * Math.sin(6.0 * x * PI) + 20.0 * Math.sin(2.0 * x * PI)) * 2.0 / 3.0;
        ret += (20.0 * Math.sin(y * PI) + 40.0 * Math.sin(y / 3.0 * PI)) * 2.0 / 3.0;
        ret += (160.0 * Math.sin(y / 12.0 * PI) + 320 * Math.sin(y * PI / 30.0)) * 2.0 / 3.0;
        return ret;
    }
    
    /**
     * 经度转换
     */
    private static double transformLon(double x, double y) {
        double ret = 300.0 + x + 2.0 * y + 0.1 * x * x + 0.1 * x * y + 0.1 * Math.sqrt(Math.abs(x));
        ret += (20.0 * Math.sin(6.0 * x * PI) + 20.0 * Math.sin(2.0 * x * PI)) * 2.0 / 3.0;
        ret += (20.0 * Math.sin(x * PI) + 40.0 * Math.sin(x / 3.0 * PI)) * 2.0 / 3.0;
        ret += (150.0 * Math.sin(x / 12.0 * PI) + 300.0 * Math.sin(x / 30.0 * PI)) * 2.0 / 3.0;
        return ret;
    }
}

