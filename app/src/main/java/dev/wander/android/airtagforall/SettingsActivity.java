package dev.wander.android.airtagforall;

import static android.view.View.GONE;
import static android.view.View.VISIBLE;
import static dev.wander.android.airtagforall.ui.settings.SharedMainSettingsManager.ANISETTE_TEST_STATUS.ERROR;
import static dev.wander.android.airtagforall.ui.settings.SharedMainSettingsManager.ANISETTE_TEST_STATUS.OK;

import android.content.Intent;
import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.app.AppCompatDelegate;
import androidx.core.os.LocaleListCompat;
import androidx.databinding.DataBindingUtil;

import java.util.Optional;

import dev.wander.android.airtagforall.databinding.SettingsActivityBinding;
import dev.wander.android.airtagforall.db.datastore.UserAuthDataStore;
import dev.wander.android.airtagforall.db.datastore.UserCacheDataStore;
import dev.wander.android.airtagforall.db.datastore.UserSettingsDataStore;
import dev.wander.android.airtagforall.db.repo.UserAuthRepository;
import dev.wander.android.airtagforall.db.repo.UserSettingsRepository;
import dev.wander.android.airtagforall.db.repo.model.UserAuthData;
import dev.wander.android.airtagforall.db.repo.model.UserSettings;
import dev.wander.android.airtagforall.service.web.AnisetteServerTesterService;
import dev.wander.android.airtagforall.service.web.CronetProvider;
import dev.wander.android.airtagforall.service.web.GitHubService;
import dev.wander.android.airtagforall.service.web.GithubRawUtilityFilesService;
import dev.wander.android.airtagforall.ui.settings.SharedMainSettingsManager;
import dev.wander.android.airtagforall.util.android.AppCryptographyUtil;


public class SettingsActivity extends AppCompatActivity {
    private static final String TAG = SettingsActivity.class.getSimpleName();

    private AnisetteServerTesterService anisetteServerTesterService;
    private UserSettingsRepository settingsRepository;
    private UserSettings currentSettings;

    private UserAuthRepository authRepository;

    private SharedMainSettingsManager sharedMainSettingsManager = null;

    private UserAuthData userAuthData = null;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        var cronet = CronetProvider.getInstance(this.getApplicationContext());
        var github = new GithubRawUtilityFilesService(
                new GitHubService(cronet),
                UserCacheDataStore.getInstance(this.getApplicationContext())
        );

        this.anisetteServerTesterService = new AnisetteServerTesterService(cronet);

        this.settingsRepository = new UserSettingsRepository(
                UserSettingsDataStore.getInstance(this.getApplicationContext()));

        this.authRepository = new UserAuthRepository(
                UserAuthDataStore.getInstance(this.getApplicationContext()),
                new AppCryptographyUtil()
        );

        this.currentSettings = this.settingsRepository.getUserSettings();

        this.sharedMainSettingsManager = new SharedMainSettingsManager(
                this,
                this::updateLocale,
                this::saveNewAnisetteUrlAndTest,
                github,
                currentSettings
        );

        SettingsActivityBinding binding = DataBindingUtil.setContentView(this, R.layout.settings_activity);
        binding.setHandleClickBack(this::finish);

        if (this.getSupportActionBar() != null) {
            this.getSupportActionBar().hide();
        }

        this.sharedMainSettingsManager.setupProgressBars();
        this.sharedMainSettingsManager.setupLanguageSwitchField();
        this.sharedMainSettingsManager.setupAnisetteServerUrlField();
        this.setupUserInfo();
    }

    @Override
    protected void onResume() {
        super.onResume();
        this.sharedMainSettingsManager.handleOnResume();
    }

    private void updateLocale(final String newLocale) {
        this.currentSettings.setLanguage(newLocale);
        this.saveSettings();

        LocaleListCompat appLocale = LocaleListCompat.forLanguageTags(newLocale);
        AppCompatDelegate.setApplicationLocales(appLocale);

        Log.i(TAG, "Updating app settings language");
    }

    private void saveNewAnisetteUrlAndTest(final String newUrl) {
        this.sharedMainSettingsManager.showAnisetteTestStatus(SharedMainSettingsManager.ANISETTE_TEST_STATUS.IN_FLIGHT);

        // verify that the server is live right now!
        try {
            var obs = this.anisetteServerTesterService.getIndex(newUrl)
                    .subscribe(success -> {
                        Log.d(TAG, "Got successful response from anisette server @ " + newUrl);

                        this.currentSettings.setAnisetteServerUrl(newUrl);
                        this.saveSettings();

                        this.runOnUiThread(() -> {
                            this.sharedMainSettingsManager.showAnisetteTestStatus(OK);
                            this.sharedMainSettingsManager.setAnisetteTextFieldError(null);
                        });
                    }, error -> {
                        Log.d(TAG, "Got error response from anisette server @ " + newUrl, error);

                        this.runOnUiThread(() -> {
                            this.sharedMainSettingsManager.showAnisetteTestStatus(ERROR);
                            this.sharedMainSettingsManager.setAnisetteTextFieldError(R.string.anisette_server_at_x_could_not_be_reached, newUrl);
                        });
                    });
        } catch (Exception e) {
            Log.e(TAG, "Failed to call anisette server", e);
            this.sharedMainSettingsManager.showAnisetteTestStatus(ERROR);
            this.sharedMainSettingsManager.setAnisetteTextFieldError(R.string.anisette_server_at_x_could_not_be_reached, newUrl);
        }
    }


    private void setupUserInfo() {
        var async = this.authRepository.getUserAuth()
                .filter(Optional::isPresent)
                .map(auth -> auth.get().getUser())
                .subscribe((authData) -> {
                    this.userAuthData = authData;
                    this.runOnUiThread(() -> this.fillInUIAuthInfo(authData));
                }, error -> {
                    Log.e(TAG, "Failed to fetch user auth data");
                    this.userAuthData = null;
                    this.runOnUiThread(() -> {
                        LinearLayout loginDataContainer = this.findViewById(R.id.login_info_container);
                        loginDataContainer.setVisibility(GONE);
                    });
                });
    }

    private void fillInUIAuthInfo(UserAuthData userAuthData) {
        LinearLayout loginDataContainer = this.findViewById(R.id.login_info_container);
        loginDataContainer.setVisibility(VISIBLE);

        TextView firstnameLastnameText = this.findViewById(R.id.firstame_lastname_settings_block);
        final String userFirstNameLastName = userAuthData.getAccount().getInfo().getFirstName() + " " + userAuthData.getAccount().getInfo().getLastName();
        firstnameLastnameText.setText(userFirstNameLastName);

        TextView emailText = this.findViewById(R.id.email_settings_block);
        final String userEmail = userAuthData.getAccount().getInfo().getAccountName();
        emailText.setText(userEmail);

        Button logoutButton = this.findViewById(R.id.logout_button);
        logoutButton.setOnClickListener(this::onClickLogout);
    }

    private void onClickLogout(View view) {
        if (this.userAuthData == null) return;

        var async = this.authRepository.clearUser()
            .subscribe(() -> {
                // logout by sending back to login page
                this.finish();
                Intent intent = new Intent(this, AppleLoginActivity.class);
                startActivity(intent);
            }, error -> Log.e(TAG, "Failed to clear current user!", error));
    }

    private void saveSettings()  {
        var asyncOp = this.settingsRepository.storeUserSettings(this.currentSettings)
                .subscribe(
                        () -> this.runOnUiThread(() -> Toast.makeText(this, "Successfully stored settings!", Toast.LENGTH_LONG).show()),
                        error -> Log.e(TAG, "Error occurred", error.getCause()));
    }
}