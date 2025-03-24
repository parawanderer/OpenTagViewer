package dev.wander.android.opentagviewer;

import static android.view.View.GONE;
import static android.view.View.INVISIBLE;
import static android.view.View.VISIBLE;
import static android.widget.Toast.LENGTH_LONG;

import static dev.wander.android.opentagviewer.python.PythonAuthService.TWO_FACTOR_METHOD.PHONE;
import static dev.wander.android.opentagviewer.python.PythonAuthService.TWO_FACTOR_METHOD.TRUSTED_DEVICE;
import static dev.wander.android.opentagviewer.ui.settings.SharedMainSettingsManager.ANISETTE_TEST_STATUS.ERROR;
import static dev.wander.android.opentagviewer.ui.settings.SharedMainSettingsManager.ANISETTE_TEST_STATUS.OK;
import static dev.wander.android.opentagviewer.util.android.TextChangedWatcherFactory.justWatchOnChanged;

import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.util.Patterns;
import android.view.View;
import android.view.inputmethod.InputMethodManager;
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
import androidx.lifecycle.ViewModelProvider;

import com.google.android.material.progressindicator.CircularProgressIndicator;
import com.google.android.material.textfield.MaterialAutoCompleteTextView;
import com.google.android.material.textfield.TextInputEditText;

import java.util.Objects;
import java.util.Optional;
import java.util.regex.Pattern;

import dev.wander.android.opentagviewer.databinding.ActivityAppleLoginBinding;
import dev.wander.android.opentagviewer.db.datastore.UserAuthDataStore;
import dev.wander.android.opentagviewer.db.datastore.UserCacheDataStore;
import dev.wander.android.opentagviewer.db.datastore.UserSettingsDataStore;
import dev.wander.android.opentagviewer.db.repo.UserAuthRepository;
import dev.wander.android.opentagviewer.db.repo.UserSettingsRepository;
import dev.wander.android.opentagviewer.db.repo.model.UserSettings;
import dev.wander.android.opentagviewer.python.PythonAuthService;
import dev.wander.android.opentagviewer.python.PythonAuthService.AuthMethodPhone;
import dev.wander.android.opentagviewer.python.PythonAuthService.PythonAuthResponse;
import dev.wander.android.opentagviewer.service.web.AnisetteServerTesterService;
import dev.wander.android.opentagviewer.service.web.CronetProvider;
import dev.wander.android.opentagviewer.service.web.GitHubService;
import dev.wander.android.opentagviewer.service.web.GithubRawUtilityFilesService;
import dev.wander.android.opentagviewer.ui.login.Apple2FACodeInputManager;
import dev.wander.android.opentagviewer.ui.settings.SharedMainSettingsManager;
import dev.wander.android.opentagviewer.util.android.AppCryptographyUtil;
import dev.wander.android.opentagviewer.util.android.PropertiesUtil;
import dev.wander.android.opentagviewer.viewmodel.AppleLoginViewModel;
import dev.wander.android.opentagviewer.viewmodel.LoginActivityState;
import dev.wander.android.opentagviewer.viewmodel.LoginActivityState.PAGE;
import io.reactivex.rxjava3.android.schedulers.AndroidSchedulers;
import io.reactivex.rxjava3.core.Completable;
import io.reactivex.rxjava3.core.Observable;
import lombok.extern.slf4j.Slf4j;

/**
 * This entire thing should be refactored and made less convoluted and spaghetti-like
 * Also: prettier (i.e. add animations)
 */
@Slf4j
public class AppleLoginActivity extends AppCompatActivity {
    private static final String TAG = AppleLoginActivity.class.getSimpleName();

    private static final Pattern REGEX_2FA_CODE = Pattern.compile("^[0-9]{6}$");

    private static final int HINT_DIFFERENT_ANISETTE_SERVER_AFTER_FAILED_2FACODES = 3;

    private static final int DELAY_BEFORE_ALLOW_CHOOSE_OTHER_2FA_METHOD = 15000; // 15 sec

    private AppleLoginViewModel model;

    private UserSettingsRepository userSettingsRepo;

    private UserAuthRepository userAuthRepo;

    private GithubRawUtilityFilesService github;

    private AnisetteServerTesterService anisetteServerTesterService;

    private SharedMainSettingsManager sharedMainSettingsManager;

    private ActivityAppleLoginBinding binding;

    private Apple2FACodeInputManager twoFactorEntryManager;

    private TextInputEditText emailOrPhoneInput;

    private TextInputEditText passwordInput;

    private Button loginButton;

    private Button twoFactorAuthChoiceBackButton;

    private final Handler delayedBackTo2FAOptionList = new Handler(Looper.getMainLooper());;


    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        this.model = new ViewModelProvider(this).get(AppleLoginViewModel.class);

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
                this.getUserSettings(),
                this::onAnisetteUrlInputTyped
        );

        this.anisetteServerTesterService = new AnisetteServerTesterService(cronet);

        this.twoFactorEntryManager = new Apple2FACodeInputManager(this, this::on2FAAuthCodeFilled);

        this.binding = DataBindingUtil.setContentView(this, R.layout.activity_apple_login);

        if (this.getSupportActionBar() != null) {
            this.getSupportActionBar().hide();
        }

        var currentUser = this.userAuthRepo.getUserAuth().blockingFirst();
        if (currentUser.isPresent()) {
            this.finish();
            Intent intent = new Intent(this, MapsActivity.class);
            startActivity(intent);
            return;
        }

        this.setupProgressBars();
        this.sharedMainSettingsManager.setupProgressBars();
        this.sharedMainSettingsManager.setupLanguageSwitchField();
        this.sharedMainSettingsManager.setupAnisetteServerUrlField();
        this.twoFactorEntryManager.init();

        model.getUiState().observe(this, this::handleAuth);

        this.emailOrPhoneInput = this.findViewById(R.id.email_or_phone_input_field);
        this.passwordInput = this.findViewById(R.id.password_input_field);
        this.loginButton = this.findViewById(R.id.login_button_main);
        this.twoFactorAuthChoiceBackButton = this.findViewById(R.id.twofactorauthchoice_back_button);
    }

    @Override
    protected void onResume() {
        super.onResume();

        this.sharedMainSettingsManager.handleOnResume();
    }

    private void testAndSaveAnisetteUrl(final String newUrl) {
        this.sharedMainSettingsManager.showAnisetteTestStatus(SharedMainSettingsManager.ANISETTE_TEST_STATUS.IN_FLIGHT);

        // verify that the server is live right now!
        try {
            var obs = this.anisetteServerTesterService.getIndex(newUrl)
                    .observeOn(AndroidSchedulers.mainThread())
                    .subscribe(success -> {
                        Log.d(TAG, "Got successful response from anisette server @ " + newUrl);

                        this.getUserSettings().setAnisetteServerUrl(newUrl);
                        this.saveSettings();

                        this.binding.setAllowServerConfNext(true);
                        this.sharedMainSettingsManager.showAnisetteTestStatus(OK);
                        this.sharedMainSettingsManager.setAnisetteTextFieldError(null);
                    }, error -> {
                        Log.d(TAG, "Got error response from anisette server @ " + newUrl, error);

                        this.binding.setAllowServerConfNext(false);
                        this.sharedMainSettingsManager.showAnisetteTestStatus(ERROR);
                        this.sharedMainSettingsManager.setAnisetteTextFieldError(R.string.anisette_server_at_x_could_not_be_reached, newUrl);
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

    private void onAnisetteUrlInputTyped(Boolean isValid) {
        // typing overrides until explicitly validated by pressing the "confirm"
        // checkmark to test the server
        this.binding.setAllowServerConfNext(false);
    }

    private void handleAuth(LoginActivityState state) {
        this.setCurrentStepText(R.string.welcome);
        this.showLoading(null);

        var sub = this.getAnisetteServerSetupStatus()
            .observeOn(AndroidSchedulers.mainThread())
            .subscribe(status -> {
                this.hideLoading();

                if (status == SETUP_STATUS.OK && (!state.hasSpecifiedCurrentPage() || state.getCurrentPage() == PAGE.LOGIN)) {
                    this.binding.setAllowServerConfNext(true);
                    this.sharedMainSettingsManager.showAnisetteTestStatus(OK);
                    this.sharedMainSettingsManager.setAnisetteTextFieldError(null);
                    this.showAccountLoginAuthOptions();
                } else if (state.getCurrentPage() == PAGE.CHOOSE_2FA) {
                    this.showNextAuthPage(state.getAuthResponse().getLoginState());
                } else if (state.currentPageIs2faEntry()) {
                    this.show2FACodeEntryTextbox();
                } else {
                    // show welcome step/server setup step
                    this.showInitialWelcomeConfOptions(status);
                }
            });
    }

    public void onClickToLoginAccount(View view) {
        Log.d(TAG, "Clicked onwards to account login!");
        // TODO: make a nice transition
        if (this.binding.getAllowServerConfNext()) {
            this.getUiState().setCurrentPage(PAGE.LOGIN);
            this.showAccountLoginAuthOptions();
        }
    }

    public void onClickBackToAnisetteSettings(View view) {
        Log.d(TAG, "Clicked backwards to language + anisette settings");
        this.showInitialWelcomeConfOptions(SETUP_STATUS.NO_SERVER_CONFIGURED);
    }

    public void onClickLoginButton(View view) {
        var state = this.getUiState();
        if (state.isLoggingIn()) return;
        state.setLoggingIn(true);
        Log.d(TAG, "Clicked login button");
        this.showLoading(R.string.logging_in);

        // don't allow the user to change their inputs
        emailOrPhoneInput.setEnabled(false);
        passwordInput.setEnabled(false);
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
            .observeOn(AndroidSchedulers.mainThread())
            .subscribe(authResponse -> {
                Log.i(TAG, "Got logged in with response ");
                state.setLoggingIn(false);
                FrameLayout loginErrorMessage = this.findViewById(R.id.login_error_container);
                loginErrorMessage.setVisibility(GONE);

                this.handleLoginResponse(authResponse);
            }, error -> {
                state.setLoggingIn(false);
                this.getUiState().setAuthResponse(null);
                Log.e(TAG, "Error while trying to log in via python", error);

                // undo loading and allow user to try again, basically.
                this.hideLoading();
                accountLoginContainer.setVisibility(VISIBLE);
                emailOrPhoneInput.setEnabled(true);
                passwordInput.setEnabled(true);
                loginButton.setClickable(true);

                FrameLayout loginErrorMessage = this.findViewById(R.id.login_error_container);
                loginErrorMessage.setVisibility(VISIBLE);

                TextView loginErrorText = this.findViewById(R.id.login_error_message_text);
                loginErrorText.setText(this.getString(R.string.login_failed_x, error.getLocalizedMessage()));
            });
    }

    private void handleLoginResponse(PythonAuthResponse authResponse) {
        final PythonAuthService.LOGIN_STATE loginState = authResponse.getLoginState();
        Log.d(TAG, "Login state was " + loginState);
        this.getUiState().setAuthResponse(authResponse);

        this.showNextAuthPage(loginState);
    }

    private void showNextAuthPage(PythonAuthService.LOGIN_STATE loginState) {
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
                this.show2FAChoiceScreen();
                break;
        }
    }

    private void show2FAChoiceScreen() {
        var state = this.getUiState();
        state.setCurrentPage(PAGE.CHOOSE_2FA);

        PythonAuthResponse authResponse = state.getAuthResponse();
        // TODO: make this all nice and animated...
        // determine which options should be shown:
        this.hideLoading();
        LinearLayout twoFACodeEntryContainer = this.findViewById(R.id.login_2fa_container);
        twoFACodeEntryContainer.setVisibility(GONE);

        this.setCurrentStepText(R.string.two_factor_authentication);
        Button trustedDeviceButton = this.findViewById(R.id.twofactorauth_choice_trusted_device);
        final boolean hasTrustedDevice = authResponse.getAuthMethods().stream().anyMatch(authMethod -> authMethod.getType() == TRUSTED_DEVICE);
        trustedDeviceButton.setVisibility(hasTrustedDevice ? VISIBLE : GONE);

        // SMS needs to be duplicated by template
        LinearLayout accountLoginContainerList = this.findViewById(R.id.login_2fa_choice_inner);
        var sms2FAButtonToAuthMethod = this.getUiState().getSms2FAButtonToAuthMethod();
        sms2FAButtonToAuthMethod.forEach((view, authMethod) -> accountLoginContainerList.removeView(view));
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

                    accountLoginContainerList.addView(v);
                    this.getUiState().getSms2FAButtonToAuthMethod().put(v, authMethodPhone);
                });

        LinearLayout accountLoginContainer = this.findViewById(R.id.login_2fa_choice);
        accountLoginContainer.setVisibility(VISIBLE);
    }

    public void onClick2FAWithTrustedDevice(View view) {
        var chosenAuthMethod = this.getUiState().getAuthResponse().getAuthMethods().stream()
                .filter(method -> method.getType() == TRUSTED_DEVICE)
                .findFirst()
                .orElseThrow();

        this.getUiState().setChosenAuthMethod(chosenAuthMethod);

        LinearLayout accountLoginContainer = this.findViewById(R.id.login_2fa_choice);
        accountLoginContainer.setVisibility(GONE);
        this.showLoading(R.string.requesting_code);

        var async = PythonAuthService.requestCode(chosenAuthMethod)
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(this::show2FACodeEntryTextbox,
                error -> {
                    Log.e(TAG, "Error occurred when trying to request 2FA code from Trusted Devices", error);
                    this.hideLoading();
                    Toast.makeText(this, this.getString(R.string.failed_to_request_code_please_try_again), LENGTH_LONG)
                            .show();
                });
    }

    private void onClick2FAWithSMS(View view) {
        AuthMethodPhone phoneAuthMethod = Objects.requireNonNull(this.getUiState().getSms2FAButtonToAuthMethod().get(view));

        this.getUiState().setChosenAuthMethod(phoneAuthMethod);

        // TODO: try to do the auth
        LinearLayout accountLoginContainer = this.findViewById(R.id.login_2fa_choice);
        accountLoginContainer.setVisibility(GONE);
        this.showLoading(R.string.requesting_code);

        var async = PythonAuthService.requestCode(phoneAuthMethod)
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(this::show2FACodeEntryTextbox,
                error -> {
                    Log.e(TAG, "Error occurred when trying to request 2FA code to SMS for phone number " + phoneAuthMethod.getPhoneNumber(), error);
                    this.hideLoading();
                    Toast.makeText(this, this.getString(R.string.failed_to_request_code_please_try_again), LENGTH_LONG)
                            .show();
                });
    }

    private void showInitialWelcomeConfOptions(SETUP_STATUS setupStatus) {
        var state = this.getUiState();
        state.setCurrentPage(PAGE.SETUP);
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
        this.testAndSaveAnisetteUrl(currentAnisetteServerSelection);

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

        LinearLayout login2FAChoice = this.findViewById(R.id.login_2fa_choice);
        login2FAChoice.setVisibility(GONE);

        // main:
        LinearLayout accountLoginContainer = this.findViewById(R.id.login_maininfo_container);
        accountLoginContainer.setVisibility(VISIBLE);

        this.setCurrentStepText(R.string.apple_account);

        TextInputEditText emailOrPhoneInput = this.findViewById(R.id.email_or_phone_input_field);
        TextInputEditText passwordInput = this.findViewById(R.id.password_input_field);

        emailOrPhoneInput.addTextChangedListener(justWatchOnChanged((s, start, before, count) -> {
            final String currentEmailOrPhone = s.toString();
            this.getUiState().setValidEmailOrPhone(isEmailOrPhoneNumber(currentEmailOrPhone));
            this.updateLoginButtonState();
        }));

        passwordInput.addTextChangedListener(justWatchOnChanged((s, start, before, count) -> {
            final String currentPassword = s.toString();
            this.getUiState().setValidPassword(!currentPassword.isEmpty());
            this.updateLoginButtonState();
        }));
    }

    private void show2FACodeEntryTextbox() {
        this.getUiState().setCurrentPage(PAGE.ENTER_2FA_CODE);

        this.hideLoading();
        this.setCurrentStepText(R.string.two_factor_authentication);

        LinearLayout twoFACodeEntryContainer = this.findViewById(R.id.login_2fa_container);
        twoFACodeEntryContainer.setVisibility(VISIBLE);

        TextView infoText = this.findViewById(R.id.twofa_sent_info_text);
        var chosenAuthMethod = this.getUiState().getChosenAuthMethod();

        if (chosenAuthMethod.getType() == PHONE) {
            final String phoneNumber = ((AuthMethodPhone) chosenAuthMethod).getPhoneNumber();
            infoText.setText(
                    this.getString(R.string.enter_the_verification_code_sent_to_your_number_x, phoneNumber));
        } else if (chosenAuthMethod.getType() == TRUSTED_DEVICE) {
            infoText.setText(this.getString(R.string.enter_the_verification_code_sent_to_your_apple_devices));
        } else {
            throw new UnsupportedOperationException("2FA code entry for this device is not supported by the app yet");
        }

        // don't allow user to spam 2FA requests...
        this.twoFactorAuthChoiceBackButton.setEnabled(false);
        this.delayedBackTo2FAOptionList.removeCallbacksAndMessages(null);
        this.delayedBackTo2FAOptionList.postDelayed(() -> {
            Log.d(TAG, "Unblocked the button to navigate back to the 2FA choice list");
            this.twoFactorAuthChoiceBackButton.setEnabled(true);
        }, DELAY_BEFORE_ALLOW_CHOOSE_OTHER_2FA_METHOD);
    }

    public void onClickBackToLogin(View view) {
        this.getUiState().setAuthResponse(null); // undo auth response

        emailOrPhoneInput.setText("");
        passwordInput.setText("");
        emailOrPhoneInput.setEnabled(true);
        passwordInput.setEnabled(true);
        loginButton.setClickable(true);

        this.showAccountLoginAuthOptions();
    }

    public void onClickBackTo2FAMethodChoice(View view) {
        View focusView = this.getCurrentFocus();
        if (focusView != null) {
            InputMethodManager imm = (InputMethodManager)getSystemService(Context.INPUT_METHOD_SERVICE);
            imm.hideSoftInputFromWindow(view.getWindowToken(), 0);
        }

        this.twoFactorEntryManager.clear();
        final FrameLayout twoFactorErrorMessage = this.findViewById(R.id.verification_code_error_msg_container);
        twoFactorErrorMessage.setVisibility(GONE); // re-show it later if relevant...
        this.show2FAChoiceScreen();
    }

    private void on2FAAuthCodeFilled(final String authCode) {
        if (!REGEX_2FA_CODE.matcher(authCode).matches()) {
            Log.w(TAG, "2FA Auth code from callback was invalid: " + authCode);
            return;
        }

        this.showLoading(R.string.logging_in);
        final LinearLayout twoFACodeEntryContainer = this.findViewById(R.id.login_2fa_container);
        twoFACodeEntryContainer.setVisibility(GONE); // for now: on error unhide

        final FrameLayout twoFactorErrorMessage = this.findViewById(R.id.verification_code_error_msg_container);
        final TextView errorMessageText = this.findViewById(R.id.verification_code_error_message);

        var chosenAuthMethod = this.getUiState().getChosenAuthMethod();

        var async = PythonAuthService.submitCode(
                Objects.requireNonNull(chosenAuthMethod),
                authCode
        ).observeOn(AndroidSchedulers.mainThread())
        .subscribe(() -> {

            var nextAsync = PythonAuthService.retrieveAuthData(this.getUiState().getAuthResponse())
                .flatMapCompletable(userAuthRepo::storeUserAuth)
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(() -> {
                    Log.i(TAG, "Retrieved login info after 2FA success and stored it successfully!");
                    this.sendToMapActivity();
                }, error -> {

                    Log.e(TAG, "Error during auth data retrieval and storage after 2FA success", error);
                    this.hideLoading();
                    twoFACodeEntryContainer.setVisibility(VISIBLE);
                    this.twoFactorEntryManager.clear();
                    this.twoFactorAuthChoiceBackButton.setEnabled(true);

                    // I don't think this error should really happen. Maybe there's some issue with the python backend in this case...
                    twoFactorErrorMessage.setVisibility(VISIBLE);
                    errorMessageText.setText(this.getString(R.string.error_occurred_please_retry_submitting_your_2fa_code));
                });

        }, error -> {
            // I really would like to handle this error separately from the one above, hence the nesting above.
            Log.e(TAG, "Failed to authenticate using auth code " + authCode, error);
            var state = this.getUiState();
            final int failedLoginAttemptCount = state.getFailed2FAAttemptCount() + 1;
            state.setFailed2FAAttemptCount(failedLoginAttemptCount);

            this.hideLoading();
            twoFACodeEntryContainer.setVisibility(VISIBLE);
            this.twoFactorEntryManager.clear();
            this.twoFactorAuthChoiceBackButton.setEnabled(true);

            // show error box
            twoFactorErrorMessage.setVisibility(VISIBLE);
            errorMessageText.setText(this.getString(
                    failedLoginAttemptCount >= HINT_DIFFERENT_ANISETTE_SERVER_AFTER_FAILED_2FACODES
                            ? R.string.twofactor_failed_x_help_msg : R.string.twofactor_failed_x,
                    error.getLocalizedMessage()
            ));
        });
    }

    private void handleIsAlreadyLoggedIn() {
        var async = PythonAuthService.retrieveAuthData(this.getUiState().getAuthResponse())
            .observeOn(AndroidSchedulers.mainThread())
            .flatMapCompletable(userAuthRepo::storeUserAuth)
            .subscribe(() -> {
                Log.i(TAG, "Retrieved login info without 2FA (already logged in!) and stored it successfully!");
                //Toast.makeText(this, "Successfully logged in (no 2FA)", LENGTH_LONG).show();
                this.sendToMapActivity();
            }, error -> {
                Log.e(TAG, "Error during auth data retrieval and storage (when already logged in)", error);
                // USER should retry. UI should actually allow him to do that.
            });
    }

    private void updateLoginButtonState() {
        var state = this.getUiState();

        this.binding.setAllowAccountLogin(
                state.isValidEmailOrPhone() && state.isValidPassword()
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
        if (this.getUiState().getUserSettings() == null) {
            var userSettings = this.userSettingsRepo.getUserSettings();
            this.getUiState().setUserSettings(userSettings);
        }
        return this.getUiState().getUserSettings();
    }

    private void setupProgressBars() {
        CircularProgressIndicator progressIndicator = findViewById(R.id.apple_login_progress_indicator);
        progressIndicator.hide();
    }

    private void updateLocale(final String newLocale) {
        this.getUserSettings().setLanguage(newLocale);
        this.saveSettings();

        this.getUiState().setCurrentPage(PAGE.SETUP);

        LocaleListCompat appLocale = LocaleListCompat.forLanguageTags(newLocale);
        AppCompatDelegate.setApplicationLocales(appLocale);

        Log.i(TAG, "Updating app settings language");
    }

    private void saveSettings()  {
        var asyncOp = this.userSettingsRepo.storeUserSettings(this.getUserSettings())
                .subscribe(
                        () -> Log.d(TAG, "Successfully stored change to settings!"),
                        error -> Log.e(TAG, "Error occurred", error.getCause()));
    }

    private static boolean isEmailOrPhoneNumber(final String input) {
        return input != null && !input.isEmpty() &&
                (Patterns.EMAIL_ADDRESS.matcher(input).matches()
                || Patterns.PHONE.matcher(input).matches());
    }

    private void sendToMapActivity() {
        this.model.resetUiState();
        this.finish();
        Intent intent = new Intent(this, MapsActivity.class);
        startActivity(intent);
    }

    private LoginActivityState getUiState() {
        return Objects.requireNonNull(this.model.getUiState().getValue());
    }

    enum SETUP_STATUS {
        NO_SERVER_CONFIGURED,
        SERVER_UNAVAILABLE,
        OK;
    }
}