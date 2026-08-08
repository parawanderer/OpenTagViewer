package dev.wander.android.opentagviewer;

import android.content.res.Configuration;
import android.util.Log;

import androidx.appcompat.app.AppCompatDelegate;

import com.chaquo.python.android.PyApplication;

import dev.wander.android.opentagviewer.db.datastore.UserSettingsDataStore;
import dev.wander.android.opentagviewer.db.repo.UserSettingsRepository;

public class OpenAirTagApplication extends PyApplication {
    private static final String TAG = OpenAirTagApplication.class.getSimpleName();

    @Override
    public void onCreate() {
        super.onCreate();

        // 高德地图SDK隐私合规初始化
        // 必须在调用任何SDK接口之前调用
        this.initAMapPrivacyCompliance();

        this.setupTheme();
    }

    /**
     * 高德地图隐私合规设置
     * 根据《个人信息保护法》要求，必须在调用SDK任何接口之前进行隐私合规配置
     * 参考文档：https://lbs.amap.com/api/android-sdk/guide/create-map/dev-attention
     */
    private void initAMapPrivacyCompliance() {
        try {
            // FIX: 尝试读取并修复可能被误解析为Integer的AMap API Key
            try {
                android.content.pm.ApplicationInfo appInfo = this.getPackageManager().getApplicationInfo(this.getPackageName(), android.content.pm.PackageManager.GET_META_DATA);
                if (appInfo.metaData != null) {
                    Object keyObj = appInfo.metaData.get("com.amap.api.v2.apikey");
                    if (keyObj instanceof Integer) {
                        Log.w(TAG, "AMap API Key was parsed as Integer (" + keyObj + "), converting to String...");
                        String keyStr;
                        try {
                            // 尝试作为资源ID读取
                            keyStr = this.getString((Integer) keyObj);
                        } catch (Exception e) {
                            // 否则直接转换为字符串
                            keyStr = String.valueOf(keyObj);
                        }
                        
                        Log.i(TAG, "Recovered AMap Key: " + keyStr);

                        // 尝试通过反射设置Key
                        // com.amap.api.maps.MapsInitializer.setApiKey(String)
                        Class<?> mapsInitializerClass = Class.forName("com.amap.api.maps.MapsInitializer");
                        java.lang.reflect.Method setApiKeyMethod = mapsInitializerClass.getMethod("setApiKey", String.class);
                        setApiKeyMethod.invoke(null, keyStr);
                        Log.i(TAG, "Successfully set AMap API Key manually via reflection");
                    }
                }
            } catch (Exception e) {
                Log.e(TAG, "Failed to fix AMap API Key", e);
            }

            // 使用反射加载高德地图SDK，避免编译时依赖
            Class<?> mapsInitializerClass = Class.forName("com.amap.api.maps.MapsInitializer");
            
            // 更新隐私合规弹窗状态
            // updatePrivacyShow(Context context, boolean isContains, boolean isShow)
            // isContains: 隐私权政策是否包含高德开平隐私权政策
            // isShow: 隐私权政策是否弹窗展示告知用户
            java.lang.reflect.Method updatePrivacyShowMethod = mapsInitializerClass.getMethod(
                    "updatePrivacyShow", android.content.Context.class, boolean.class, boolean.class);
            updatePrivacyShowMethod.invoke(null, this, true, true);
            
            // 更新用户同意隐私政策状态
            // updatePrivacyAgree(Context context, boolean isAgree)
            // isAgree: 隐私权政策是否取得用户同意
            java.lang.reflect.Method updatePrivacyAgreeMethod = mapsInitializerClass.getMethod(
                    "updatePrivacyAgree", android.content.Context.class, boolean.class);
            updatePrivacyAgreeMethod.invoke(null, this, true);
            
            Log.i(TAG, "AMap privacy compliance initialized successfully");
        } catch (ClassNotFoundException e) {
            // 高德地图SDK未集成，这是正常情况
            Log.d(TAG, "AMap SDK not found, privacy compliance initialization skipped");
        } catch (Exception e) {
            Log.e(TAG, "Failed to initialize AMap privacy compliance", e);
        }
    }

    public void setupTheme() {
        final int currentNightMode = this.getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK;

        var userSettingsRepo = new UserSettingsRepository(
                UserSettingsDataStore.getInstance(this.getApplicationContext()));

        var userSettings = userSettingsRepo.getUserSettings();
        final Boolean useDarkTheme = userSettings.getUseDarkTheme();


        if (currentNightMode == Configuration.UI_MODE_NIGHT_NO && useDarkTheme == Boolean.TRUE) {
            Log.i(TAG, "Updating to app dark theme choice");
            AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_YES);
        } else if (currentNightMode == Configuration.UI_MODE_NIGHT_YES && useDarkTheme == Boolean.FALSE) {
            Log.i(TAG, "Updating to app light theme choice");
            AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_NO);
        }
    }
}
