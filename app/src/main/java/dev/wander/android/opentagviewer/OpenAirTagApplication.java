package dev.wander.android.opentagviewer;

import android.content.res.Configuration;
import android.util.Log;

import androidx.appcompat.app.AppCompatDelegate;

import com.chaquo.python.android.PyApplication;
import com.google.android.material.color.DynamicColors;

import dev.wander.android.opentagviewer.db.datastore.UserSettingsDataStore;
import dev.wander.android.opentagviewer.db.repo.UserSettingsRepository;
import dev.wander.android.opentagviewer.db.repo.model.UserSettings;
import dev.wander.android.opentagviewer.db.room.OpenTagViewerDatabase;
import dev.wander.android.opentagviewer.service.NearbyScanService;
import io.reactivex.rxjava3.schedulers.Schedulers;

public class OpenAirTagApplication extends PyApplication {
    private static final String TAG = OpenAirTagApplication.class.getSimpleName();

    @Override
    public void onCreate() {
        super.onCreate();

        // 高德地图SDK隐私合规初始化
        // 必须在调用任何SDK接口之前调用
        this.initAMapPrivacyCompliance();

        this.setupTheme();
        this.setupSystemColors();
        this.resumeBackgroundScanIfEnabled();
    }

    /**
     * Brings the background scan back after the process was gone.
     *
     * <p><b>The setting is the state, and the service is only its consequence.</b> A service does
     * not survive a reboot, a force-stop or the system reclaiming memory, so without this the
     * switch would silently stop meaning anything and the only cure would be toggling it off and
     * on - which reads as the setting having been forgotten.
     *
     * <p>Off by default, so this starts nothing for anyone who has not asked. Read
     * asynchronously because the setting lives in a DataStore and Application#onCreate blocks
     * the first activity.
     */
    private void resumeBackgroundScanIfEnabled() {
        // Off the main thread: the read hits a DataStore, and this runs before the first
        // activity is created.
        Schedulers.io().scheduleDirect(() -> {
            try {
                final UserSettings settings =
                        new UserSettingsRepository(UserSettingsDataStore.getInstance(this))
                                .getUserSettings();

                if (settings.shouldScanInBackground()) {
                    Log.i(TAG, "Background scanning is on; starting the service");
                    NearbyScanService.start(this);
                }
            } catch (final Exception e) {
                Log.w(TAG, "Could not read whether to scan in the background", e);
            }
        });
    }

    /**
     * Whether to colour activities from the user's wallpaper.
     *
     * <p>Read by the precondition below every time an activity is created, which is what lets
     * the Settings switch take effect in both directions. Volatile because it is written from
     * the UI thread and read during activity creation.
     */
    private static volatile boolean useSystemColors = false;

    /**
     * Arranges for activities to be coloured from the user's wallpaper when they have asked
     * for that.
     *
     * <p>Registration happens unconditionally and exactly once, because it cannot be undone -
     * {@code applyToActivitiesIfAvailable} adds an {@code ActivityLifecycleCallbacks} to the
     * process and Material offers no way to remove it. Registering with a <b>precondition</b>
     * instead of registering conditionally is what makes turning the setting back off work:
     * the precondition is consulted per activity, so a recreated activity picks up the
     * current answer rather than the one that was true at startup.
     *
     * <p>It must also happen before the first activity is created, since the callback only
     * sees activities created after it. That includes the login screen, which is the first
     * thing a new user sees and the one they cannot reach Settings from.
     *
     * <p>Reading the setting synchronously here is what {@link #setupTheme()} already does,
     * and for the same reason: there is nothing drawing yet to block.
     *
     * <p>The version check is left to Material, whose registration is a no-op below Android
     * 12. The Settings entry is hidden there, so the flag should never be set on such a
     * device - but a value can arrive from a backup restore, and being defensive is cheaper
     * than reasoning about it.
     */
    private void setupSystemColors() {
        try {
            var repository = new UserSettingsRepository(
                    UserSettingsDataStore.getInstance(this.getApplicationContext()));

            final Boolean stored = repository.getUserSettings().getUseSystemColors();

            if (stored != null) {
                useSystemColors = stored;
            } else {
                // Nobody has chosen. A first install should look like the phone it is on; an
                // update should look like it did yesterday. See UserSettings.useSystemColors.
                useSystemColors = this.isFirstRun();

                // Recorded now so the answer does not change out from under the user later:
                // the database appears as soon as anything reads it, so isFirstRun() is only
                // ever true during this one launch, and an unresolved setting would silently
                // flip their app back to the app palette on the next.
                var async = repository.storeUseSystemColors(useSystemColors)
                        .subscribe(() -> { },
                                error -> Log.e(TAG, "Could not record the system colours default", error));
            }
        } catch (Exception e) {
            // The app's own palette is a working fallback, and a theme is not worth failing
            // to start over.
            Log.e(TAG, "Failed to read the system colours setting; keeping the app palette", e);
            useSystemColors = false;
        }

        DynamicColors.applyToActivitiesIfAvailable(
                this, (activity, themeResId) -> useSystemColors);

        Log.i(TAG, "System colours available=" + DynamicColors.isDynamicColorAvailable()
                + " enabled=" + useSystemColors);
    }

    /**
     * Whether nobody has used this app on this device yet.
     *
     * <p>The signal is whether the database file exists. Room creates it the first time anything
     * reads from it, so it is absent only before the first run has got as far as looking at a
     * beacon or a session - which is exactly "new user".
     *
     * <p>Package install timestamps were tried first and are wrong for this. They answer "was
     * this package ever updated", not "has this person used the app". Clearing the app's data
     * leaves both timestamps untouched, so somebody starting over - or a developer with
     * "clear app data before launch" set - is reported as an existing user and quietly gets the
     * old default. The database is a property of the data, so it follows the data.
     *
     * <p>Errs towards {@code false} - the app palette - because that is the answer that changes
     * nothing for somebody who already had the app.
     */
    private boolean isFirstRun() {
        try {
            return !this.getDatabasePath(OpenTagViewerDatabase.DATABASE_NAME).exists();
        } catch (Exception e) {
            Log.e(TAG, "Could not tell whether this is a first run", e);
            return false;
        }
    }

    /**
     * Switches wallpaper colouring on or off for activities created from now on.
     *
     * <p>Callers are expected to recreate whatever is on screen; everything behind it is
     * recreated by Android when it returns to the foreground.
     */
    public static void setUseSystemColors(final boolean enabled) {
        useSystemColors = enabled;
    }

    /**
     * Hands the user's own AMap API key to the SDK.
     * <br>
     * Reflection is used throughout so the app still runs when the AMap SDK is absent,
     * which is the normal case for anyone using Google Maps.
     */
    private void applyUserSuppliedAMapKey() {
        try {
            var settings = new UserSettingsRepository(
                    UserSettingsDataStore.getInstance(this.getApplicationContext())
            ).getUserSettings();

            if (!settings.hasAmapApiKey()) {
                // Expected unless the user has chosen AMap and supplied a key.
                Log.d(TAG, "No user-supplied AMap API key; skipping AMap initialisation");
                return;
            }

            Class<?> mapsInitializerClass = Class.forName("com.amap.api.maps.MapsInitializer");
            mapsInitializerClass
                    .getMethod("setApiKey", String.class)
                    .invoke(null, settings.getAmapApiKey());

            Log.i(TAG, "Applied user-supplied AMap API key");
        } catch (ClassNotFoundException e) {
            Log.d(TAG, "AMap SDK not present; skipping");
        } catch (Exception e) {
            Log.e(TAG, "Failed to apply the user-supplied AMap API key", e);
        }
    }

    /**
     * 高德地图隐私合规设置
     * 根据《个人信息保护法》要求，必须在调用SDK任何接口之前进行隐私合规配置
     * 参考文档：https://lbs.amap.com/api/android-sdk/guide/create-map/dev-attention
     */
    private void initAMapPrivacyCompliance() {
        try {
            // The app ships no AMap key. Keys are issued per developer account and bound to
            // a package name and signing fingerprint, and AMap's terms expect the key holder
            // to be the app's operator - so each user supplies their own in Settings, the
            // same way the Anisette server URL works. Apply it before any SDK call.
            this.applyUserSuppliedAMapKey();

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

        if (useDarkTheme == null) {
            AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM);
            return;
        }

        if (currentNightMode == Configuration.UI_MODE_NIGHT_NO && useDarkTheme == Boolean.TRUE) {
            Log.i(TAG, "Updating to app dark theme choice");
            AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_YES);
        } else if (currentNightMode == Configuration.UI_MODE_NIGHT_YES && useDarkTheme == Boolean.FALSE) {
            Log.i(TAG, "Updating to app light theme choice");
            AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_NO);
        }
    }
}
