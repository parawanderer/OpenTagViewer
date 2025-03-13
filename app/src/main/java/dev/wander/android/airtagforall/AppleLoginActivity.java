package dev.wander.android.airtagforall;

import static android.view.View.GONE;
import static android.view.View.INVISIBLE;
import static android.view.View.VISIBLE;
import static android.widget.Toast.LENGTH_LONG;

import static dev.wander.android.airtagforall.python.PythonAuthService.TWO_FACTOR_METHOD.PHONE;
import static dev.wander.android.airtagforall.python.PythonAuthService.TWO_FACTOR_METHOD.TRUSTED_DEVICE;
import static dev.wander.android.airtagforall.ui.settings.SharedMainSettingsManager.ANISETTE_TEST_STATUS.ERROR;
import static dev.wander.android.airtagforall.ui.settings.SharedMainSettingsManager.ANISETTE_TEST_STATUS.OK;
import static dev.wander.android.airtagforall.util.android.TextChangedWatcherFactory.justWatchOnChanged;

import android.content.Intent;
import android.os.Bundle;
import android.util.Log;
import android.util.Patterns;
import android.view.View;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.OnBackPressedCallback;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.app.AppCompatDelegate;
import androidx.core.os.LocaleListCompat;
import androidx.databinding.DataBindingUtil;

import com.google.android.material.progressindicator.CircularProgressIndicator;
import com.google.android.material.textfield.MaterialAutoCompleteTextView;
import com.google.android.material.textfield.TextInputEditText;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Pattern;

import dev.wander.android.airtagforall.databinding.ActivityAppleLoginBinding;
import dev.wander.android.airtagforall.db.datastore.UserAuthDataStore;
import dev.wander.android.airtagforall.db.datastore.UserCacheDataStore;
import dev.wander.android.airtagforall.db.datastore.UserSettingsDataStore;
import dev.wander.android.airtagforall.db.repo.UserAuthRepository;
import dev.wander.android.airtagforall.db.repo.UserSettingsRepository;
import dev.wander.android.airtagforall.db.repo.model.UserSettings;
import dev.wander.android.airtagforall.python.PythonAuthService;
import dev.wander.android.airtagforall.python.PythonAuthService.AuthMethod;
import dev.wander.android.airtagforall.python.PythonAuthService.AuthMethodPhone;
import dev.wander.android.airtagforall.python.PythonAuthService.PythonAuthResponse;
import dev.wander.android.airtagforall.service.web.AnisetteServerTesterService;
import dev.wander.android.airtagforall.service.web.CronetProvider;
import dev.wander.android.airtagforall.service.web.GitHubService;
import dev.wander.android.airtagforall.service.web.GithubRawUtilityFilesService;
import dev.wander.android.airtagforall.ui.login.Apple2FACodeInputManager;
import dev.wander.android.airtagforall.ui.settings.SharedMainSettingsManager;
import dev.wander.android.airtagforall.util.android.AppCryptographyUtil;
import dev.wander.android.airtagforall.util.android.PropertiesUtil;
import io.reactivex.rxjava3.core.Observable;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public class AppleLoginActivity extends AppCompatActivity {
    private static final String TAG = AppleLoginActivity.class.getSimpleName();

    private UserSettingsRepository userSettingsRepo;

    private UserAuthRepository userAuthRepo;

    private UserSettings userSettings;

    private GithubRawUtilityFilesService github;

    private AnisetteServerTesterService anisetteServerTesterService;

    private SharedMainSettingsManager sharedMainSettingsManager;

    private ActivityAppleLoginBinding binding;

    private boolean isValidEmailOrPhone = false;
    private boolean isValidPassword = false;

    private boolean isLoggingIn = false;

    private final Map<View, AuthMethodPhone> sms2FAButtonToAuthMethod = new HashMap<>();

    private PythonAuthResponse authResponse = null;

    private AuthMethod chosenAuthMethod = null;

    private Apple2FACodeInputManager twoFactorEntryManager = null;

    private static final Pattern REGEX_2FA_CODE = Pattern.compile("^[0-9]{6}$");

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        this.getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                // don't finish, actually don't do anything at all...
                Log.d(TAG, "On back pressed was called");
            }
        });


        userAuthRepo = new UserAuthRepository(
                UserAuthDataStore.getInstance(getApplicationContext()),
                new AppCryptographyUtil());

        var cronet = CronetProvider.getInstance(this.getApplicationContext());
        this.github = new GithubRawUtilityFilesService(
                new GitHubService(cronet),
                UserCacheDataStore.getInstance(this.getApplicationContext())
        );

        userSettingsRepo = new UserSettingsRepository(
                UserSettingsDataStore.getInstance(this.getApplicationContext()));

        this.sharedMainSettingsManager = new SharedMainSettingsManager(
                this,
                this::updateLocale,
                this::testAndSaveAnisetteUrl,
                github,
                this.getUserSettings()
        );

        this.anisetteServerTesterService = new AnisetteServerTesterService(cronet);

        this.twoFactorEntryManager = new Apple2FACodeInputManager(this, this::on2FAAuthCodeFilled);


        //setContentView(R.layout.activity_apple_login);
        this.binding = DataBindingUtil.setContentView(this, R.layout.activity_apple_login);


        if (this.getSupportActionBar() != null) {
            this.getSupportActionBar().hide();
        }

        this.setupProgressBars();
        this.sharedMainSettingsManager.setupProgressBars();
        this.sharedMainSettingsManager.setupLanguageSwitchField();
        this.sharedMainSettingsManager.setupAnisetteServerUrlField();
        this.twoFactorEntryManager.init();

        this.handleAuth();
    }


    private void testAndSaveAnisetteUrl(final String newUrl) {
        this.sharedMainSettingsManager.showAnisetteTestStatus(SharedMainSettingsManager.ANISETTE_TEST_STATUS.IN_FLIGHT);

        // verify that the server is live right now!
        try {
            var obs = this.anisetteServerTesterService.getIndex(newUrl)
                    .subscribe(success -> {
                        Log.d(TAG, "Got successful response from anisette server @ " + newUrl);

                        this.getUserSettings().setAnisetteServerUrl(newUrl);
                        this.saveSettings();

                        this.runOnUiThread(() -> {
                            this.binding.setAllowServerConfNext(true);
                            this.sharedMainSettingsManager.showAnisetteTestStatus(OK);
                            this.sharedMainSettingsManager.setAnisetteTextFieldError(null);
                        });
                    }, error -> {
                        Log.d(TAG, "Got error response from anisette server @ " + newUrl, error);

                        this.runOnUiThread(() -> {
                            this.binding.setAllowServerConfNext(false);
                            this.sharedMainSettingsManager.showAnisetteTestStatus(ERROR);
                            this.sharedMainSettingsManager.setAnisetteTextFieldError(R.string.anisette_server_at_x_could_not_be_reached, newUrl);
                        });
                    });
        } catch (Exception e) {
            Log.e(TAG, "Failed to call anisette server", e);
            this.binding.setAllowServerConfNext(false);
            this.sharedMainSettingsManager.showAnisetteTestStatus(ERROR);
            this.sharedMainSettingsManager.setAnisetteTextFieldError(R.string.anisette_server_at_x_could_not_be_reached, newUrl);
        }
    }

    private void setCurrentStepText(final int stringResId) {
        TextView textView = this.findViewById(R.id.login_current_input_indicator);
        textView.setText(stringResId);
    }

    private void showLoading(final Integer stringResId) {
        LinearLayout loadingContainer = this.findViewById(R.id.login_spinning_container);
        loadingContainer.setVisibility(VISIBLE);

        CircularProgressIndicator progressIndicator = this.findViewById(R.id.apple_login_progress_indicator);
        progressIndicator.show();

        TextView textView = this.findViewById(R.id.login_spinner_text);
        if (stringResId == null) {
            textView.setVisibility(INVISIBLE);
            textView.setText(null);
        } else {
            textView.setVisibility(VISIBLE);
            textView.setText(this.getString(stringResId));
        }
    }

    private void hideLoading() {
        LinearLayout loadingContainer = this.findViewById(R.id.login_spinning_container);
        loadingContainer.setVisibility(GONE);
    }

    private void handleAuth() {
        this.setCurrentStepText(R.string.welcome);
        this.showLoading(null);

        var sub = this.getAnisetteServerSetupStatus()
            .subscribe(status -> {
                this.runOnUiThread(() -> {
                    this.hideLoading();

                    if (status == SETUP_STATUS.OK) {
                        // show account login step
                        // TODO: show login credentials step!
                        this.binding.setAllowServerConfNext(true);
                        this.sharedMainSettingsManager.showAnisetteTestStatus(OK);
                        this.sharedMainSettingsManager.setAnisetteTextFieldError(null);

                        this.showAccountLoginAuthOptions();

                    } else {
                        // show welcome step/server setup step
                        this.showInitialWelcomeConfOptions(status);
                    }
                });
            });
    }

    public void onClickToLoginAccount(View view) {
        Log.d(TAG, "Clicked onwards to account login!");
        if (this.binding.getAllowServerConfNext()) {
            // TODO: make a nice transition
            this.showAccountLoginAuthOptions();
        }
    }

    public void onClickBackToAnisetteSettings(View view) {
        Log.d(TAG, "Clicked backwards to language + anisette settings");
        this.showInitialWelcomeConfOptions(SETUP_STATUS.NO_SERVER_CONFIGURED);
    }

    public void onClickLogin(View view) {
        if (this.isLoggingIn) return;
        this.isLoggingIn = true;
        Log.d(TAG, "Clicked login button");
        this.showLoading(R.string.logging_in);

        TextInputEditText emailOrPhoneInput = this.findViewById(R.id.email_or_phone_input_field);
        TextInputEditText passwordInput = this.findViewById(R.id.password_input_field);

        // don't allow the user to change their inputs
        emailOrPhoneInput.setEnabled(false);
        passwordInput.setEnabled(false);

        Button loginButton = this.findViewById(R.id.login_button_main);
        loginButton.setClickable(false); // temporarily disable it

        // show spinner in button
        // TODO: don't take away the entire UI like this.
        // for now this is good enough...
        final LinearLayout accountLoginContainer = this.findViewById(R.id.login_maininfo_container);
        accountLoginContainer.setVisibility(GONE);

        final String emailOrPhone = Objects.requireNonNull(emailOrPhoneInput.getText()).toString();
        final String password = Objects.requireNonNull(passwordInput.getText()).toString();
        final String anisetteServerUrl = Objects.requireNonNull(this.getUserSettings().getAnisetteServerUrl());

        var async = PythonAuthService.pythonLogin(emailOrPhone, password, anisetteServerUrl)
            .subscribe(authResponse -> {
                Log.i(TAG, "Got logged in with response ");
                this.isLoggingIn = false;
                this.authResponse = authResponse;
                this.runOnUiThread(() -> this.handleLoginResponse(authResponse));
            }, error -> {
                this.isLoggingIn = false;
                this.authResponse = null;
                Log.e(TAG, "Error while trying to log in via python", error);

                this.runOnUiThread(() -> {
                    // undo loading and allow user to try again, basically.
                    this.hideLoading();
                    accountLoginContainer.setVisibility(VISIBLE);
                    emailOrPhoneInput.setEnabled(true);
                    passwordInput.setEnabled(true);
                    loginButton.setClickable(true);

                    FrameLayout loginErrorMessage = this.findViewById(R.id.login_error_container);
                    loginErrorMessage.setVisibility(VISIBLE);
                });
            });
    }

    private void handleLoginResponse(PythonAuthResponse authResponse) {
        final var loginState = authResponse.getLoginState();
        Log.d(TAG, "Login state was " + loginState);
        switch (loginState) {
            case LOGGED_OUT:
                // TODO: invalid password?
                // TODO: show error
                Toast.makeText(this, "[ERROR] Received login response LOGGED_OUT!", LENGTH_LONG).show();
                break;
            case LOGGED_IN:
            case AUTHENTICATED:
                this.handleIsAlreadyLoggedIn();
                break;
            case REQUIRE_2FA:
                // require 2FA!
                this.show2FAChoiceScreen(authResponse);
                break;
        }
    }

    private void show2FAChoiceScreen(PythonAuthResponse authResponse) {
        // TODO: make this all nice and animated...
        // determine which options should be shown:
        this.hideLoading();

        this.setCurrentStepText(R.string.two_factor_authentication);
        Button trustedDeviceButton = this.findViewById(R.id.twofactorauth_choice_trusted_device);
        final boolean hasTrustedDevice = authResponse.getAuthMethods().stream().anyMatch(authMethod -> authMethod.getType() == TRUSTED_DEVICE);
        trustedDeviceButton.setVisibility(hasTrustedDevice ? VISIBLE : GONE);

        // SMS needs to be duplicated by template
        LinearLayout accountLoginContainer = this.findViewById(R.id.login_2fa_choice);
        sms2FAButtonToAuthMethod.forEach((view, authMethod) -> accountLoginContainer.removeView(view));
        sms2FAButtonToAuthMethod.clear();

        // add new SMS buttons
        authResponse.getAuthMethods().stream().filter(authMethod -> authMethod.getType() == PHONE)
                .forEach(authMethod -> {
                    assert authMethod instanceof AuthMethodPhone;
                    AuthMethodPhone authMethodPhone = (AuthMethodPhone) authMethod;

                    View v = this.getLayoutInflater().inflate(R.layout.apple_login_sms_button, null);

                    Button smsButton = v.findViewById(R.id.twofactorauth_choice_sms);
                    smsButton.setOnClickListener(this::onClick2FAWithSMS);
                    smsButton.setText(
                            this.getString(R.string.auth_by_sms_to_x, authMethodPhone.getPhoneNumber())
                    );

                    accountLoginContainer.addView(v);
                    sms2FAButtonToAuthMethod.put(v, authMethodPhone);
                });

        accountLoginContainer.setVisibility(VISIBLE);
    }

    public void onClick2FAWithTrustedDevice(View view) {
        this.chosenAuthMethod = this.authResponse.getAuthMethods().stream()
                .filter(method -> method.getType() == TRUSTED_DEVICE)
                .findFirst()
                .orElseThrow();

        // TODO: try to do the auth

        LinearLayout accountLoginContainer = this.findViewById(R.id.login_2fa_choice);
        accountLoginContainer.setVisibility(GONE);
        this.showLoading(R.string.requesting_code);

        var async = PythonAuthService.requestCode(this.chosenAuthMethod)
                .doOnError((error) -> Log.e(TAG, "Error occurred when trying to request 2FA code from Trusted Devices", error))
                .subscribe(() -> this.runOnUiThread(this::show2FACodeEntryTextbox));
    }

    private void onClick2FAWithSMS(View view) {
        AuthMethodPhone phoneAuthMethod = Objects.requireNonNull(sms2FAButtonToAuthMethod.get(view));
        this.chosenAuthMethod = phoneAuthMethod;

        // TODO: try to do the auth
        LinearLayout accountLoginContainer = this.findViewById(R.id.login_2fa_choice);
        accountLoginContainer.setVisibility(GONE);
        this.showLoading(R.string.requesting_code);

        var async = PythonAuthService.requestCode(this.chosenAuthMethod)
                .doOnError((error) -> Log.e(TAG, "Error occurred when trying to request 2FA code to SMS for phone number " + phoneAuthMethod.getPhoneNumber(), error))
                .subscribe(() -> this.runOnUiThread(this::show2FACodeEntryTextbox));
    }

    private void showInitialWelcomeConfOptions(SETUP_STATUS setupStatus) {
        LinearLayout accountLoginContainer = this.findViewById(R.id.login_maininfo_container);
        accountLoginContainer.setVisibility(GONE);

        // TODO: make this all nice and animated...
        final UserSettings userSettings = this.getUserSettings();

        LinearLayout anisetteSetupContainer = this.findViewById(R.id.login_anisette_container);
        anisetteSetupContainer.setVisibility(VISIBLE);

        MaterialAutoCompleteTextView urlTextInput = findViewById(R.id.anisetteServerUrl);

        var properties = PropertiesUtil.getProperties(this.getAssets(), "app.properties");
        assert properties != null;

        final String currentAnisetteServerSelection = Optional.ofNullable(userSettings.getAnisetteServerUrl())
                .orElse(properties.getProperty("defaultAnisetteUrl"));
        urlTextInput.setText(currentAnisetteServerSelection);

        if (setupStatus == SETUP_STATUS.NO_SERVER_CONFIGURED) {
            this.setCurrentStepText(R.string.welcome);
        } else {
            this.setCurrentStepText(R.string.choose_your_server);

            this.sharedMainSettingsManager.showAnisetteTestStatus(ERROR);
            this.sharedMainSettingsManager.setAnisetteTextFieldError(
                    R.string.anisette_server_at_x_could_not_be_reached,
                    userSettings.getAnisetteServerUrl()
            );
        }
    }

    private void showAccountLoginAuthOptions() {
        // TODO: make this all nice and animated...
        LinearLayout anisetteSetupContainer = this.findViewById(R.id.login_anisette_container);
        anisetteSetupContainer.setVisibility(GONE);

        // main:
        LinearLayout accountLoginContainer = this.findViewById(R.id.login_maininfo_container);
        accountLoginContainer.setVisibility(VISIBLE);

        this.setCurrentStepText(R.string.apple_account);

        // TODO: check valid email + valid password
        // then send to python for check & 2FA options

        TextInputEditText emailOrPhoneInput = this.findViewById(R.id.email_or_phone_input_field);
        TextInputEditText passwordInput = this.findViewById(R.id.password_input_field);

        emailOrPhoneInput.addTextChangedListener(justWatchOnChanged((s, start, before, count) -> {
            final String currentEmailOrPhone = s.toString();
            this.isValidEmailOrPhone = isEmailOrPhoneNumber(currentEmailOrPhone);
            this.updateLoginButtonState();
        }));

        passwordInput.addTextChangedListener(justWatchOnChanged((s, start, before, count) -> {
            final String currentPassword = s.toString();
            this.isValidPassword = !currentPassword.isEmpty();
            this.updateLoginButtonState();
        }));
    }

    private void show2FACodeEntryTextbox() {
        this.hideLoading();

        this.setCurrentStepText(R.string.two_factor_authentication);

        LinearLayout twoFACodeEntryContainer = this.findViewById(R.id.login_2fa_container);
        twoFACodeEntryContainer.setVisibility(VISIBLE);

        TextView infoText = this.findViewById(R.id.twofa_sent_info_text);
        if (this.chosenAuthMethod.getType() == PHONE) {
            final String phoneNumber = ((AuthMethodPhone) this.chosenAuthMethod).getPhoneNumber();
            infoText.setText(
                    this.getString(R.string.enter_the_verification_code_sent_to_your_number_x, phoneNumber));
        } else if (this.chosenAuthMethod.getType() == TRUSTED_DEVICE) {
            infoText.setText(this.getString(R.string.enter_the_verification_code_sent_to_your_apple_devices));
        } else {
            throw new UnsupportedOperationException("2FA code entry for this device is not supported by the app yet");
        }
    }

    private void on2FAAuthCodeFilled(final String authCode) {
        if (!REGEX_2FA_CODE.matcher(authCode).matches()) {
            Log.w(TAG, "2FA Auth code from callback was invalid: " + authCode);
            return;
        }

        this.showLoading(R.string.logging_in);
        final LinearLayout twoFACodeEntryContainer = this.findViewById(R.id.login_2fa_container);
        twoFACodeEntryContainer.setVisibility(GONE); // for now: on error unhide


        var async = PythonAuthService.submitCode(
                Objects.requireNonNull(this.chosenAuthMethod),
                authCode
        ).doOnError(error -> Log.e(TAG, "Failed to authenticate using auth code " + authCode))
        .andThen(PythonAuthService.retrieveAuthData(this.authResponse))
        .flatMapCompletable(userAuthRepo::storeUserAuth)
        .subscribe(() -> {
            Log.i(TAG, "Retrieved login info after 2FA success and stored it successfully!");
            this.runOnUiThread(() -> {
                this.hideLoading();
                Toast.makeText(this, "Successfully logged in (after 2FA)", LENGTH_LONG).show();
                this.finish();
                this.sendToMapActivity();
            });
        }, error -> {
            Log.e(TAG, "Error during auth data retrieval and storage after 2FA success", error);
            this.runOnUiThread(() -> {
                this.hideLoading();
                twoFACodeEntryContainer.setVisibility(VISIBLE);
                this.twoFactorEntryManager.clear();
                Toast.makeText(this, "Failed to retrieve login data for used (after 2FA attempt)!", LENGTH_LONG).show();
            });
        });
    }

    private void handleIsAlreadyLoggedIn() {
        var async = PythonAuthService.retrieveAuthData(this.authResponse)
            .flatMapCompletable(userAuthRepo::storeUserAuth)
            .subscribe(() -> {
                Log.i(TAG, "Retrieved login info without 2FA (already logged in!) and stored it successfully!");
                this.runOnUiThread(() -> {
                    Toast.makeText(this, "Successfully logged in (no 2FA)", LENGTH_LONG).show();
                    this.finish();
                    this.sendToMapActivity();
                });
            }, error -> {
                Log.e(TAG, "Error during auth data retrieval and storage (when already logged in)", error);
                this.runOnUiThread(() -> {
                    Toast.makeText(this, "Failed to retrieve login data for used (when no 2FA required)!", LENGTH_LONG).show();
                });
            });
    }

    private void updateLoginButtonState() {
        this.binding.setAllowAccountLogin(
                this.isValidEmailOrPhone && this.isValidPassword
        );
    }

    private Observable<SETUP_STATUS> getAnisetteServerSetupStatus() {
        // check if user has server selected already or not?
        var settings = this.getUserSettings();
        final String currentServerUrl = settings.getAnisetteServerUrl();

        if (currentServerUrl == null) {
            return Observable.just(SETUP_STATUS.NO_SERVER_CONFIGURED);
        }

        // but maybe the server is not available (anymore): check this
        return this.anisetteServerTesterService.getIndex(currentServerUrl)
            .map(rootInfo -> {
                Log.d(TAG, "Got successful response from anisette server @ " + currentServerUrl);
                return SETUP_STATUS.OK;
            })
            .onErrorReturn(error -> {
                Log.d(TAG, "Server did not seem available @ " + currentServerUrl);
                return SETUP_STATUS.SERVER_UNAVAILABLE;
            });
    }

    private UserSettings getUserSettings() {
        if (this.userSettings == null) {
            this.userSettings = this.userSettingsRepo.getUserSettings();
        }
        return this.userSettings;
    }

    private void setupProgressBars() {
        CircularProgressIndicator progressIndicator = findViewById(R.id.apple_login_progress_indicator);
        progressIndicator.hide();
    }

    private void updateLocale(final String newLocale) {
        this.getUserSettings().setLanguage(newLocale);
        this.saveSettings();

        LocaleListCompat appLocale = LocaleListCompat.forLanguageTags(newLocale);
        AppCompatDelegate.setApplicationLocales(appLocale);

        Log.i(TAG, "Updating app settings language");
    }

    private void saveSettings()  {
        var asyncOp = this.userSettingsRepo.storeUserSettings(this.getUserSettings())
                .subscribe(
                        () -> this.runOnUiThread(() -> Toast.makeText(this, "Successfully stored settings!", LENGTH_LONG).show()),
                        error -> Log.e(TAG, "Error occurred", error.getCause()));
    }

    private static boolean isEmailOrPhoneNumber(final String input) {
        return input != null && !input.isEmpty() &&
                (Patterns.EMAIL_ADDRESS.matcher(input).matches()
                || Patterns.PHONE.matcher(input).matches());
    }


    private void sendToMapActivity() {
        Intent intent = new Intent(this, MapsActivity.class);
        startActivity(intent);
    }

    enum SETUP_STATUS {
        NO_SERVER_CONFIGURED,
        SERVER_UNAVAILABLE,
        OK;
    }
}