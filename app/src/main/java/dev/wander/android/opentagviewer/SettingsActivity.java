package dev.wander.android.opentagviewer;

import static android.view.View.GONE;
import static android.view.View.VISIBLE;
import static android.view.View.inflate;
import static dev.wander.android.opentagviewer.util.android.TextChangedWatcherFactory.justWatchOnChanged;

import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.util.Log;
import android.util.Pair;
import android.view.KeyEvent;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.CompoundButton;
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
import com.google.android.material.materialswitch.MaterialSwitch;
import com.google.android.material.progressindicator.CircularProgressIndicatorSpec;
import com.google.android.material.progressindicator.IndeterminateDrawable;
import com.google.android.material.snackbar.Snackbar;
import com.google.android.material.textfield.MaterialAutoCompleteTextView;
import com.google.android.material.textfield.TextInputEditText;
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

import dev.wander.android.opentagviewer.databinding.ActivitySettingsBinding;
import dev.wander.android.opentagviewer.db.datastore.UserAuthDataStore;
import dev.wander.android.opentagviewer.db.datastore.UserCacheDataStore;
import dev.wander.android.opentagviewer.db.datastore.UserSettingsDataStore;
import dev.wander.android.opentagviewer.db.repo.UserAuthRepository;
import dev.wander.android.opentagviewer.db.repo.UserSettingsRepository;
import dev.wander.android.opentagviewer.db.repo.model.UserAuthData;
import dev.wander.android.opentagviewer.db.repo.model.UserSettings;
import dev.wander.android.opentagviewer.service.web.AnisetteServerTesterService;
import dev.wander.android.opentagviewer.service.web.CronetProvider;
import dev.wander.android.opentagviewer.service.web.GitHubService;
import dev.wander.android.opentagviewer.service.web.GithubRawUtilityFilesService;
import dev.wander.android.opentagviewer.service.web.sidestore.AnisetteServerSuggestion;
import dev.wander.android.opentagviewer.ui.compat.WindowPaddingUtil;
import dev.wander.android.opentagviewer.ui.settings.AmapApiKeyDialog;
import dev.wander.android.opentagviewer.ui.extensions.AppAutoCompleteTextView;
import dev.wander.android.opentagviewer.util.android.AppCryptographyUtil;
import dev.wander.android.opentagviewer.util.android.LocaleConfigUtil;
import dev.wander.android.opentagviewer.util.android.PropertiesUtil;
import dev.wander.android.opentagviewer.util.android.SigningInfoUtil;
import dev.wander.android.opentagviewer.util.validate.AnisetteUrlValidatorUtil;
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

    private String initialAnisetteUrl = null;
    private boolean mapProviderChanged = false;


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
        this.initialAnisetteUrl = this.currentSettings.getAnisetteServerUrl();

        this.themeChoices.add(this.getString(R.string.use_system_default));
        this.themeChoices.add(this.getString(R.string.light_theme));
        this.themeChoices.add(this.getString(R.string.dark_theme));

        this.binding = DataBindingUtil.setContentView(this, R.layout.activity_settings);
        WindowPaddingUtil.insertUITopPadding(binding.getRoot());
        this.binding.setHandleClickBack(this::handleEndActivity);
        this.binding.setOnClickTheme(this::onClickEditTheme);
        this.binding.setCurrentTheme(this.getCurrentThemeUiString());
        this.binding.setOnClickLanguage(this::onClickEditLanguage);
        this.binding.setCurrentLanguage(Optional.ofNullable(this.currentSettings.getLanguage()).map(this::getPrettyLanguageName).orElse(this.getString(R.string.use_system_default)));
        this.binding.setOnClickAnisetteServerUrl(this::onClickEditAnisetteServerUrl);
        this.binding.setCurrentAnisetteServerUrl(this.currentSettings.getAnisetteServerUrl());
        this.binding.setOnClickMapProvider(this::onClickEditMapProvider);
        this.binding.setCurrentMapProvider(this.getCurrentMapProviderUiString());
        this.binding.setIsDebugDataEnabled(Optional.ofNullable(this.currentSettings.getEnableDebugData()).orElse(false));

        if (this.getSupportActionBar() != null) {
            this.getSupportActionBar().hide();
        }

        MaterialSwitch switcher = this.findViewById(R.id.settings_app_debug_data_enabled);
        switcher.setOnCheckedChangeListener(this::onDebugDataEnabledChange);

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

    private void handleEndActivity() {
        if (this.mapProviderChanged) {
            Intent data = new Intent();
            data.putExtra("mapProviderChanged", true);
            setResult(RESULT_OK, data);
        }
        this.finish();
    }

    @Override
    public void onBackPressed() {
        this.handleEndActivity();
    }

    private void onDebugDataEnabledChange(CompoundButton buttonView, boolean isChecked) {
        final Boolean oldChoice = this.currentSettings.getEnableDebugData();
        if (oldChoice == null || oldChoice != isChecked) {
            this.currentSettings.setEnableDebugData(isChecked);
            this.binding.setIsDebugDataEnabled(isChecked);
            this.saveSettings();
        }
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

    private String getCurrentMapProviderUiString() {
        String provider = this.currentSettings.getMapProvider();
        if (provider == null || provider.isEmpty() || "google".equals(provider)) {
            return this.getString(R.string.map_provider_google);
        } else if ("amap".equals(provider)) {
            return this.getString(R.string.map_provider_amap);
        }
        return this.getString(R.string.map_provider_google);
    }
    
    private void onClickEditMapProvider() {
        List<String> providerChoices = new ArrayList<>();
        providerChoices.add(this.getString(R.string.map_provider_google));
        providerChoices.add(this.getString(R.string.map_provider_amap));
        
        String currentProvider = this.currentSettings.getMapProvider();
        int currentOption = 0; // 默认Google Maps
        if ("amap".equals(currentProvider)) {
            currentOption = 1;
        }
        
        var builder = new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.map_provider)
                .setPositiveButton(R.string.accept, (dialog, which) -> {
                    Log.d(TAG, "Selected new map provider option!");
                    
                    int checkedItemPosition = ((AlertDialog) dialog).getListView().getCheckedItemPosition();
                    
                    if (checkedItemPosition != AdapterView.INVALID_POSITION) {
                        String selectedProvider = checkedItemPosition == 0 ? "google" : "amap";
                        Log.d(TAG, "Selected map provider: " + selectedProvider);

                        // AMap ships with no key: they are issued per developer account and
                        // bound to a package name and signing fingerprint, so each user
                        // brings their own. Selecting it without one would leave a blank map
                        // and no explanation, so gate the change on having a key and take
                        // the user straight to entering one.
                        if ("amap".equals(selectedProvider) && !this.currentSettings.hasAmapApiKey()) {
                            Toast.makeText(this, R.string.amap_key_required, Toast.LENGTH_LONG).show();
                            this.onClickEditAmapApiKey(selectedProvider);
                            return;
                        }

                        this.updateMapProvider(selectedProvider);
                    }
                })
                .setNegativeButton(R.string.cancel, null)
                .setSingleChoiceItems(providerChoices.toArray(new CharSequence[0]), currentOption, null);
        
        builder.show();
    }
    
    /**
     * Prompt for the user's own AMap API key.
     *
     * @param providerToApplyOnSuccess if non-null, the provider to switch to once a key has
     *                                 been supplied. Lets the picker route the user here and
     *                                 have their selection complete afterwards, rather than
     *                                 silently dropping it.
     */
    private void onClickEditAmapApiKey(final String providerToApplyOnSuccess) {
        AmapApiKeyDialog.show(this, this.currentSettings.getAmapApiKey(), enteredKey -> {
            this.currentSettings.setAmapApiKey(enteredKey);
            this.saveSettings();

            if (enteredKey == null) {
                // Without a key AMap cannot render anything, so fall back rather than
                // leaving a provider selected that will only ever show a blank map.
                Toast.makeText(this, R.string.amap_key_cleared, Toast.LENGTH_SHORT).show();
                if ("amap".equals(this.currentSettings.getMapProvider())) {
                    this.updateMapProvider("google");
                }
                return;
            }

            Toast.makeText(this, R.string.amap_key_saved, Toast.LENGTH_SHORT).show();
            if (providerToApplyOnSuccess != null) {
                this.updateMapProvider(providerToApplyOnSuccess);
            }
        });
    }

    private void updateMapProvider(String provider) {
        final String currentProvider = this.currentSettings.getMapProvider();
        this.mapProviderChanged = this.mapProviderChanged || !java.util.Objects.equals(currentProvider, provider);
        this.currentSettings.setMapProvider(provider);
        this.binding.setCurrentMapProvider(this.getCurrentMapProviderUiString());
        this.saveSettings();
        Log.i(TAG, "Updated map provider to: " + provider);
    }
    
    private void onClickEditLanguage() {
        View view = inflate(this, R.layout.language_input_dialog, null);

        final String currentLocale = Optional.ofNullable(this.currentSettings.getLanguage())
                .orElseGet(() -> {
                    String appLocales = AppCompatDelegate.getApplicationLocales().toLanguageTags();
                    if (appLocales != null && !appLocales.isBlank()) {
                        return appLocales.split(",")[0];
                    }
                    return Locale.getDefault().toLanguageTag();
                });
        var availableLocales = LocaleConfigUtil.getAvailableLocales(this.getResources())
                .toArray(new String[0]);

        AppAutoCompleteTextView languageDropdown = view.findViewById(R.id.languageSelectDropdown);

        var mappedLocales = Arrays.stream(availableLocales)
                .map(lang -> Pair.create(lang, this.getPrettyLanguageName(lang)))
                .collect(Collectors.toMap(p -> p.second, p -> p.first));

        languageDropdown.setSimpleItems(mappedLocales.keySet().stream()
                .sorted().toArray(String[]::new));

        mappedLocales.entrySet().stream()
                .filter(kvp -> kvp.getValue().equalsIgnoreCase(currentLocale)
                        || currentLocale.toLowerCase(Locale.ROOT).startsWith(kvp.getValue().toLowerCase(Locale.ROOT) + "-"))
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

    /**
     * The choice between producing sign-in data here and asking a server for it, plus whatever
     * the local side currently has to say for itself.
     *
     * <p>Anyone reaching this screen is already signed in, so an unchosen mode means they have
     * been carried over from a version that only had servers: they stay on theirs until they
     * say otherwise, because moving a live session onto a different machine identity forces a
     * re-login. That is what the warning in this dialog is about.
     */
    private void setupAnisetteModeControls(final View view) {
        final AppAutoCompleteTextView modeDropdown =
                view.findViewById(R.id.anisetteModeSelectDropdown);
        if (modeDropdown == null) {
            return;
        }

        modeDropdown.setSimpleItems(new String[] {
                this.getString(R.string.anisette_mode_local),
                this.getString(R.string.anisette_mode_remote)
        });

        final String current = this.currentSettings.resolveAnisetteMode(true);
        applyAnisetteMode(view, current);

        modeDropdown.setOnItemClickListener((parent, v, position, id) -> {
            final String selected = position == 1
                    ? UserSettings.ANISETTE_REMOTE : UserSettings.ANISETTE_LOCAL;
            modeDropdown.setText(parent.getItemAtPosition(position).toString(), false);
            modeDropdown.clearFocus();
            applyAnisetteMode(view, selected);
            this.pendingAnisetteMode = selected;
        });

        final View learnMore = view.findViewById(R.id.anisetteLearnMoreButton);
        if (learnMore != null) {
            learnMore.setOnClickListener(v -> this.openConfiguredLink("anisetteWikiPage"));
        }

        final View whereToGetApk = view.findViewById(R.id.anisetteWhereToGetApkButton);
        if (whereToGetApk != null) {
            whereToGetApk.setOnClickListener(v -> this.openConfiguredLink("anisetteApkWikiPage"));
        }
    }

    /** Show one mode's controls and hide the other's. */
    private static void applyAnisetteMode(final View view, final String mode) {
        final boolean local = !UserSettings.ANISETTE_REMOTE.equals(mode);

        final AppAutoCompleteTextView dropdown =
                view.findViewById(R.id.anisetteModeSelectDropdown);
        if (dropdown != null) {
            dropdown.setText(view.getContext().getString(
                    local ? R.string.anisette_mode_local : R.string.anisette_mode_remote), false);
        }

        final View localSection = view.findViewById(R.id.anisetteLocalStatusContainer);
        if (localSection != null) {
            localSection.setVisibility(local ? View.VISIBLE : View.GONE);
        }

        final View remoteSection = view.findViewById(R.id.anisetteRemoteSection);
        if (remoteSection != null) {
            remoteSection.setVisibility(local ? View.GONE : View.VISIBLE);
        }
    }

    /** Open one of the reference links in app.properties, or do nothing if it is not set. */
    private void openConfiguredLink(final String property) {
        final var properties = PropertiesUtil.getProperties(this.getAssets(), "app.properties");
        if (properties == null) {
            return;
        }

        final String url = properties.getProperty(property);
        if (url == null || url.isBlank()) {
            Log.w(TAG, "No " + property + " configured in app.properties");
            return;
        }

        final Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
        if (intent.resolveActivity(getPackageManager()) != null) {
            this.startActivity(intent);
        }
    }

    /** Set while the dialog is open; only meaningful until it is confirmed or dismissed. */
    private String pendingAnisetteMode = null;

    private void onClickEditAnisetteServerUrl() {
        View view = inflate(this, R.layout.anisette_server_url_input_dialog, null);

        this.setupAnisetteModeControls(view);

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
                manager.getDialog().dismiss();
                this.handleAnisetteUrlChangeSave(currentUrlInput);

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

    private void handleAnisetteUrlChangeSave(final String validNewAnisetteUrl) {
        final String originalMode = this.currentSettings.resolveAnisetteMode(true);
        final boolean modeChanged = this.pendingAnisetteMode != null
                && !this.pendingAnisetteMode.equals(originalMode);

        if (this.pendingAnisetteMode != null) {
            this.currentSettings.setAnisetteMode(this.pendingAnisetteMode);
            // Choosing for themselves answers the question the upgrade prompt would ask, so
            // it should not then go on to ask it.
            this.currentSettings.setAnisetteUpgradeOffered(true);
        }

        this.currentSettings.setAnisetteServerUrl(validNewAnisetteUrl);
        this.binding.setCurrentAnisetteServerUrl(
                this.currentSettings.usesLocalAnisette(true)
                        ? this.getString(R.string.anisette_mode_local)
                        : validNewAnisetteUrl);
        this.saveSettings();

        var originalUrl = Optional.ofNullable(this.initialAnisetteUrl);
        var finalUrl = Optional.ofNullable(this.currentSettings.getAnisetteServerUrl());

        // Changing the mode binds the session to a different machine identity just as surely
        // as changing the server does, so it takes the same path.
        if (modeChanged || !originalUrl.equals(finalUrl)) {
            // A re-login is genuinely required, not just a limitation of how the account
            // is serialized. Anisette supplies a machine identity (X-Apple-I-MD-M and
            // friends) derived from that server's own ADI provisioning, and Apple binds
            // the session to it, so a session established via one server is not valid
            // when presented with another server's identity. Rewriting the stored
            // provider would keep the app running but leave it failing auth against
            // Apple, which is worse than an honest re-login. The dialog warns about this
            // up front (see anisette_url_change_warning).
            this.performLogout();
        }
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

        // Swap the loading skeleton for the real thing. Both are sized alike, so this does not
        // move anything below it.
        this.findViewById(R.id.login_details_placeholder).setVisibility(GONE);
        this.findViewById(R.id.login_details).setVisibility(VISIBLE);

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
                    Intent data = new Intent();
                    data.putExtra("requestSendToLogin", true);
                    setResult(RESULT_OK, data);
                    this.finish();
                }, error -> Log.e(TAG, "Failed to clear current user!", error));
    }

    private void saveSettings()  {
        var asyncOp = this.settingsRepository.storeUserSettings(this.currentSettings)
                .subscribeOn(AndroidSchedulers.mainThread())
                .subscribe(
                        () -> {
                            Log.d(TAG, "Settings were updated");
                        },
                        error -> {
                            Log.e(TAG, "Error occurred when updating settings", error);
                            Snackbar.make(
                                    this.findViewById(R.id.settings_main_container),
                                    R.string.error_updating_settings,
                                    Snackbar.LENGTH_SHORT).show();
                        });
    }

    private String getPrettyLanguageName(final String languageId) {
        var res = this.getResources();
        return res.getString(res.getIdentifier(
                LocaleConfigUtil.toLocaleLabelResourceName(languageId),
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
