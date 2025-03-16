package dev.wander.android.airtagforall;

import static android.view.View.GONE;
import static android.view.View.VISIBLE;
import static android.view.View.inflate;
import static dev.wander.android.airtagforall.util.android.TextChangedWatcherFactory.justWatchOnChanged;

import android.content.Intent;
import android.os.Bundle;
import android.util.Log;
import android.util.Pair;
import android.view.KeyEvent;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.app.AppCompatDelegate;
import androidx.core.os.LocaleListCompat;
import androidx.databinding.DataBindingUtil;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.progressindicator.CircularProgressIndicatorSpec;
import com.google.android.material.progressindicator.IndeterminateDrawable;
import com.google.android.material.textfield.MaterialAutoCompleteTextView;
import com.google.android.material.textfield.TextInputLayout;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

import dev.wander.android.airtagforall.databinding.ActivitySettingsBinding;
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
import dev.wander.android.airtagforall.service.web.sidestore.AnisetteServerSuggestion;
import dev.wander.android.airtagforall.ui.extensions.AppAutoCompleteTextView;
import dev.wander.android.airtagforall.util.android.AppCryptographyUtil;
import dev.wander.android.airtagforall.util.android.LocaleConfigUtil;
import dev.wander.android.airtagforall.util.validate.AnisetteUrlValidatorUtil;
import io.reactivex.rxjava3.android.schedulers.AndroidSchedulers;
import lombok.Data;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;


public class SettingsActivity extends AppCompatActivity {
    private static final String TAG = SettingsActivity.class.getSimpleName();

    private static final int THEME_CHOICE_SYSTEM = 0;
    private static final int THEME_CHOICE_LIGHT = 1;
    private static final int THEME_CHOICE_DARK = 2;

    private ActivitySettingsBinding binding;

    private AnisetteServerTesterService anisetteServerTesterService;
    private GithubRawUtilityFilesService github;
    private UserSettingsRepository settingsRepository;
    private UserSettings currentSettings;

    private UserAuthRepository authRepository;

    private UserAuthData userAuthData = null;

    private final Set<String> urlOptions = new HashSet<>();

    private List<CharSequence> themeChoices = new ArrayList<>();

    private String editorSelectedLocateId = null;


    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        var cronet = CronetProvider.getInstance(this.getApplicationContext());
        this.github = new GithubRawUtilityFilesService(
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

        this.themeChoices.add(this.getString(R.string.use_system_default));
        this.themeChoices.add(this.getString(R.string.light_theme));
        this.themeChoices.add(this.getString(R.string.dark_theme));

        this.binding = DataBindingUtil.setContentView(this, R.layout.activity_settings);
        this.binding.setHandleClickBack(this::finish);
        this.binding.setOnClickTheme(this::onClickEditTheme);
        this.binding.setCurrentTheme(this.getCurrentThemeUiString());
        this.binding.setOnClickLanguage(this::onClickEditLanguage);
        this.binding.setCurrentLanguage(this.getPrettyLanguageName(this.currentSettings.getLanguage()));
        this.binding.setOnClickAnisetteServerUrl(this::onClickEditAnisetteServerUrl);
        this.binding.setCurrentAnisetteServerUrl(this.currentSettings.getAnisetteServerUrl());

        if (this.getSupportActionBar() != null) {
            this.getSupportActionBar().hide();
        }

        this.setupUserInfo();

        var async = this.github.getSuggestedServers().subscribe(suggestedServers -> {
            this.runOnUiThread(() -> {
                // add them to the suggested servers list!

                Optional.ofNullable(this.currentSettings.getAnisetteServerUrl())
                        .ifPresent(urlOptions::add);

                suggestedServers.getServers().stream()
                        .map(AnisetteServerSuggestion::getAddress)
                        .forEach(urlOptions::add);
            });
        }, error -> Log.e(TAG, "Error occurred while fetching servers", error));
    }

    private String getCurrentThemeUiString() {
        if (this.currentSettings.getUseDarkTheme() == null) {
            return this.getString(R.string.use_system_default);
        }
        return this.currentSettings.getUseDarkTheme()
                ? this.getString(R.string.dark_theme)
                : this.getString(R.string.light_theme);
    }

    private void onClickEditTheme() {
        final int currentOption = Optional.ofNullable(this.currentSettings.getUseDarkTheme())
                .map(useDarkTheme -> useDarkTheme ? THEME_CHOICE_DARK : THEME_CHOICE_LIGHT)
                .orElse(THEME_CHOICE_SYSTEM);

        var builder = new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.theme)
                .setPositiveButton(R.string.accept, (dialog, which) -> {
                    Log.d(TAG, "Selected new theme option!");

                    int checkedItemPosition = ((AlertDialog) dialog).getListView().getCheckedItemPosition();

                    if (checkedItemPosition != AdapterView.INVALID_POSITION) {
                        var choice = this.themeChoices.get(checkedItemPosition);
                        Log.d(TAG, "Selected theme choice=" + choice);
                        this.updateAppTheme(checkedItemPosition);
                    }
                })
                .setNegativeButton(R.string.cancel, null)
                .setSingleChoiceItems(this.themeChoices.toArray(new CharSequence[0]), currentOption, null);

        builder.show();
    }

    private void onClickEditLanguage() {
        View view = inflate(this, R.layout.language_input_dialog, null);

        final String currentLocale = Locale.getDefault().getLanguage();
        var availableLocales = LocaleConfigUtil.getAvailableLocales(this.getResources())
                .toArray(new String[0]);

        AppAutoCompleteTextView languageDropdown = view.findViewById(R.id.languageSelectDropdown);

        var mappedLocales = Arrays.stream(availableLocales)
                .map(lang -> Pair.create(lang, this.getPrettyLanguageName(lang)))
                .collect(Collectors.toMap(p -> p.second, p -> p.first));
        List<String> sortedLanguageOptions = mappedLocales.keySet().stream()
                .sorted().collect(Collectors.toList());

        var shownLocalesAdapter = new ArrayAdapter<>(getApplicationContext(), android.R.layout.simple_dropdown_item_1line, sortedLanguageOptions);
        languageDropdown.setAdapter(shownLocalesAdapter);

        mappedLocales.entrySet().stream()
                .filter(kvp -> kvp.getValue().equals(currentLocale))
                .findFirst()
                .map(Map.Entry::getKey)
                .ifPresent(option -> languageDropdown.setText(option, false));

        languageDropdown.setOnItemClickListener((parent, view1, position, id) -> {
            final String selectedLocalePretty = parent.getItemAtPosition(position).toString();
            this.editorSelectedLocateId = mappedLocales.get(selectedLocalePretty);
            languageDropdown.setText(selectedLocalePretty, false);
            languageDropdown.clearFocus();
        });

        var builder = new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.language)
                .setView(view)
                .setPositiveButton(R.string.accept, (dialog, which) -> {
                    Log.d(TAG, "Selected new language option: " + this.editorSelectedLocateId);
                    if (this.editorSelectedLocateId != null) {
                        this.updateLocale(this.editorSelectedLocateId);
                    }
                })
                .setNegativeButton(R.string.cancel, null);

        builder.show();
    }

    private void onClickEditAnisetteServerUrl() {
        View view = inflate(this, R.layout.anisette_server_url_input_dialog, null);


        CircularProgressIndicatorSpec spec = new CircularProgressIndicatorSpec(view.getContext(), /* attrs= */ null, 0, com.google.android.material.R.style.Widget_Material3_CircularProgressIndicator_ExtraSmall);
        final IndeterminateDrawable<CircularProgressIndicatorSpec> progressIndicatorDrawable = IndeterminateDrawable.createCircularDrawable(view.getContext(), spec);

        // setup DECLINE button
        final MaterialButton declineButton = view.findViewById(R.id.anisette_dialog_button_decline);

        // setup ACCEPT button
        final MaterialButton performTestButton = view.findViewById(R.id.anisette_dialog_button_test);
        var manager = new AnisetteServerUrlDialogManager(performTestButton, progressIndicatorDrawable);

        performTestButton.setIcon(null);
        performTestButton.setEnabled(false); // default false unless URL valid & tested

        final MaterialAutoCompleteTextView urlTextInput = view.findViewById(R.id.anisetteServerUrl);
        final TextInputLayout urlTextInputContainer = view.findViewById(R.id.anisetteServerUrlContainer);

        urlTextInput.setText(this.currentSettings.getAnisetteServerUrl());

        urlTextInput.setOnItemClickListener((parent, view1, position, id) -> {
            final String selectedUrlFromDropdown = parent.getItemAtPosition(position).toString();
            manager.setUrlTestOk(false); // reset to false
            performTestButton.setEnabled(validateAnisetteUrl(view, selectedUrlFromDropdown));
        });

        urlTextInput.setOnEditorActionListener((TextView v, int actionId, KeyEvent event) -> {
            if (actionId == EditorInfo.IME_ACTION_DONE) {
                // check validity URL
                var currentInput = v.getText().toString();
                manager.setUrlTestOk(false); // reset to false
                performTestButton.setEnabled(validateAnisetteUrl(view, currentInput));
            }
            return false;
        });

        urlTextInput.addTextChangedListener(justWatchOnChanged((s, start, before, count) -> {
            manager.setUrlTestOk(false); // reset to false
            performTestButton.setEnabled(validateAnisetteUrl(view, s.toString()));
        }));

        String[] optionsArray = this.urlOptions.toArray(new String[0]);
        Arrays.sort(optionsArray);
        urlTextInput.setSimpleItems(optionsArray);

        performTestButton.setOnClickListener(v -> {
            Log.d(TAG, "Clicked anisette URL TEST button");
            final String currentUrlInput = urlTextInput.getText().toString();

            if (manager.isUrlTestOk()) {
                Log.d(TAG, "Confirming new anisette URL after successful test");
                performTestButton.setClickable(false); // disable, save current input
                this.currentSettings.setAnisetteServerUrl(currentUrlInput);
                this.binding.setCurrentAnisetteServerUrl(currentUrlInput);
                this.saveSettings();
                manager.getDialog().dismiss();

            } else {
                // DO TEST
                Log.d(TAG, "Performing anisette URL test");
                manager.setTestLoading(true);

                try {
                    var async = this.anisetteServerTesterService.getIndex(currentUrlInput)
                            .observeOn(AndroidSchedulers.mainThread())
                            .subscribe(success -> {
                                Log.d(TAG, "Got successful response from anisette server @ " + currentUrlInput);
                                manager.setTestLoading(false);
                                manager.setUrlTestOk(true);
                            }, error -> {
                                Log.d(TAG, "Got error response from anisette server @ " + currentUrlInput);
                                manager.setTestLoading(false);
                                manager.setUrlTestOk(false);
                                urlTextInputContainer.setError(this.getString(R.string.anisette_server_at_x_could_not_be_reached, currentUrlInput));
                            });
                } catch (Exception e) {
                    manager.setTestLoading(false);
                    manager.setUrlTestOk(false);
                    urlTextInputContainer.setError(this.getString(R.string.anisette_server_at_x_could_not_be_reached, currentUrlInput));
                }
            }
        });

        declineButton.setOnClickListener(v -> {
            Log.d(TAG, "Clicked anisette URL decline button");
            manager.getDialog().cancel();
        });

        AlertDialog dialog = new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.anisette_server_url)
                .setView(view)
                .show();

        manager.setDialog(dialog);
    }

    private static boolean validateAnisetteUrl(View view, final String urlInput) {
        TextInputLayout urlTextInputContainer = view.findViewById(R.id.anisetteServerUrlContainer);

        boolean isValidUrl = AnisetteUrlValidatorUtil.isValidAnisetteUrl(urlInput);
        if (!isValidUrl) {
            CharSequence error = view.getResources().getString(R.string.this_is_not_a_valid_url);
            urlTextInputContainer.setError(error);
            return false;
        }
        urlTextInputContainer.setError(null);
        return true;
    }

    private void updateLocale(final String newLocale) {
        this.currentSettings.setLanguage(newLocale);
        this.saveSettings();

        LocaleListCompat appLocale = LocaleListCompat.forLanguageTags(newLocale);
        AppCompatDelegate.setApplicationLocales(appLocale);

        Log.i(TAG, "Updating app settings language");
    }

    private void updateAppTheme(final int themeChoice) {
        // https://developer.android.com/develop/ui/views/theming/darktheme#change-themes
        if (themeChoice == THEME_CHOICE_SYSTEM) {
            this.currentSettings.setUseDarkTheme(null);
        } else {
            this.currentSettings.setUseDarkTheme(themeChoice == THEME_CHOICE_DARK);
        }
        this.binding.setCurrentTheme(this.getCurrentThemeUiString());
        this.saveSettings();

        final int choice = themeChoice == THEME_CHOICE_SYSTEM ? AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
                : themeChoice == THEME_CHOICE_LIGHT ? AppCompatDelegate.MODE_NIGHT_NO
                : AppCompatDelegate.MODE_NIGHT_YES;

        AppCompatDelegate.setDefaultNightMode(choice);

        Log.i(TAG, "Updating app theme choice");
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

        var dialog = new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.logout)
                .setMessage(R.string.are_you_sure_you_want_to_logout)
                .setNegativeButton(R.string.cancel, null)
                .setPositiveButton(R.string.logout, (dialog1, which) -> this.performLogout())
                .show();
    }

    private void performLogout() {
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

    private String getPrettyLanguageName(final String languageId) {
        var res = this.getResources();
        return res.getString(res.getIdentifier(
                "lang_" + languageId,
                "string",
                this.getPackageName()));
    }


    @RequiredArgsConstructor
    @Data
    private static class AnisetteServerUrlDialogManager {
        @NonNull private final MaterialButton performTestButton;
        @NonNull private final IndeterminateDrawable<CircularProgressIndicatorSpec> spinnerIcon;

        private AlertDialog dialog;

        private boolean isUrlTestOk = false;

        public void setUrlTestOk(boolean isUrlTestOk) {
            this.isUrlTestOk = isUrlTestOk;
            this.setButtonStage(isUrlTestOk);
        }

        public void setTestLoading(boolean isLoading) {
            if (isLoading) {
                this.performTestButton.setIcon(this.spinnerIcon);
                this.performTestButton.setClickable(false); // temporarily disable until has result
            } else {
                this.performTestButton.setIcon(null);
                this.performTestButton.setClickable(true);
            }
        }

        public void setButtonStage(boolean successStage) {
            if (successStage) {
                this.performTestButton.setText(R.string.accept);
            } else {
                this.performTestButton.setText(R.string.test);
            }
        }
    }
}