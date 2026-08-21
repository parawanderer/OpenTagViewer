package dev.wander.android.opentagviewer;

import static android.view.View.GONE;
import static android.view.View.VISIBLE;
import static android.view.View.inflate;
import static dev.wander.android.opentagviewer.util.android.TextChangedWatcherFactory.justWatchOnChanged;

import android.content.Intent;
import android.net.Uri;
import android.os.Build;
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

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.app.AppCompatDelegate;
import androidx.core.os.LocaleListCompat;
import androidx.databinding.DataBindingUtil;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.color.DynamicColors;
import com.google.android.material.materialswitch.MaterialSwitch;
import com.google.android.material.progressindicator.CircularProgressIndicatorSpec;
import com.google.android.material.progressindicator.IndeterminateDrawable;
import com.google.android.material.snackbar.Snackbar;
import com.google.android.material.textfield.MaterialAutoCompleteTextView;
import com.google.android.material.textfield.TextInputEditText;
import com.google.android.material.textfield.TextInputLayout;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

import dev.wander.android.opentagviewer.anisette.AdiLibraryImporter;
import dev.wander.android.opentagviewer.anisette.AdiLibraryManifest;
import dev.wander.android.opentagviewer.anisette.AnisetteSource;
import dev.wander.android.opentagviewer.anisette.AnisetteStatus;
import dev.wander.android.opentagviewer.anisette.LocalAnisette;
import dev.wander.android.opentagviewer.databinding.ActivitySettingsBinding;
import dev.wander.android.opentagviewer.db.datastore.UserAuthDataStore;
import dev.wander.android.opentagviewer.db.datastore.UserCacheDataStore;
import dev.wander.android.opentagviewer.db.datastore.UserSettingsDataStore;
import dev.wander.android.opentagviewer.db.repo.KeychainMembershipRepository;
import dev.wander.android.opentagviewer.db.repo.UserAuthRepository;
import dev.wander.android.opentagviewer.db.repo.UserSettingsRepository;
import dev.wander.android.opentagviewer.db.repo.model.UserAuthData;
import dev.wander.android.opentagviewer.db.repo.model.UserSettings;
import dev.wander.android.opentagviewer.python.AppDependencies;
import dev.wander.android.opentagviewer.service.web.AnisetteServerTesterService;
import dev.wander.android.opentagviewer.service.web.CronetProvider;
import dev.wander.android.opentagviewer.service.web.GitHubService;
import dev.wander.android.opentagviewer.service.web.GithubRawUtilityFilesService;
import dev.wander.android.opentagviewer.service.web.sidestore.AnisetteServerSuggestion;
import dev.wander.android.opentagviewer.ui.compat.WindowPaddingUtil;
import dev.wander.android.opentagviewer.ui.settings.AmapApiKeyDialog;
import dev.wander.android.opentagviewer.ui.settings.SharedMainSettingsManager;
import dev.wander.android.opentagviewer.ui.extensions.AppAutoCompleteTextView;
import dev.wander.android.opentagviewer.util.android.AppCryptographyUtil;
import dev.wander.android.opentagviewer.util.android.LocaleConfigUtil;
import dev.wander.android.opentagviewer.util.android.PropertiesUtil;
import dev.wander.android.opentagviewer.util.android.SigningInfoUtil;
import dev.wander.android.opentagviewer.util.validate.AnisetteUrlValidatorUtil;
import io.reactivex.rxjava3.android.schedulers.AndroidSchedulers;
import io.reactivex.rxjava3.core.Observable;
import io.reactivex.rxjava3.disposables.Disposable;
import io.reactivex.rxjava3.schedulers.Schedulers;
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

    /** Reading whether the account is linked, so leaving does not land on dead views. */
    private Disposable membershipLookup;

    /** Forgetting the membership. Held for the same reason - it reports back on the main thread. */
    private Disposable unlinking;


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
        this.binding.setOnClickFetchFromAccount(this::onClickFetchFromAccount);
        this.binding.setOnClickUnlinkAccount(this::onClickUnlinkAccount);
        this.sayWhetherTheAccountIsLinked();

        this.binding.setOnClickTheme(this::onClickEditTheme);
        this.binding.setCurrentTheme(this.getCurrentThemeUiString());
        this.binding.setOnClickLanguage(this::onClickEditLanguage);
        this.binding.setCurrentLanguage(Optional.ofNullable(this.currentSettings.getLanguage()).map(this::getPrettyLanguageName).orElse(this.getString(R.string.use_system_default)));
        this.binding.setOnClickAnisetteServerUrl(this::onClickEditAnisetteServerUrl);
        this.binding.setCurrentAnisetteServerUrl(this.getAnisetteProviderSummary());
        this.binding.setOnClickMapProvider(this::onClickEditMapProvider);
        this.binding.setCurrentMapProvider(this.getCurrentMapProviderUiString());
        this.binding.setIsDebugDataEnabled(Optional.ofNullable(this.currentSettings.getEnableDebugData()).orElse(false));
        this.binding.setIsSystemColorsSupported(DynamicColors.isDynamicColorAvailable());
        this.binding.setIsSystemColorsEnabled(
                this.currentSettings.getUseSystemColors() == Boolean.TRUE);

        if (this.getSupportActionBar() != null) {
            this.getSupportActionBar().hide();
        }

        MaterialSwitch switcher = this.findViewById(R.id.settings_app_debug_data_enabled);
        switcher.setOnCheckedChangeListener(this::onDebugDataEnabledChange);

        MaterialSwitch systemColors = this.findViewById(R.id.settings_app_use_system_colors);
        systemColors.setOnCheckedChangeListener(this::onUseSystemColorsChange);

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

    /**
     * Turns wallpaper colouring on or off, and rebuilds the screen so the change is visible
     * immediately.
     *
     * <p>A theme overlay is chosen when an activity is created, so this one has to be
     * recreated to show the new palette. Everything behind it is recreated by Android when it
     * returns to the foreground, having already been through {@code onStop}, so the stack
     * ends up consistent without restarting the app.
     *
     * <p>The in-memory flag and the stored setting are both updated, so a recreate now and a
     * cold start later agree.
     */
    private void onUseSystemColorsChange(CompoundButton buttonView, boolean isChecked) {
        final boolean oldChoice = this.currentSettings.getUseSystemColors() == Boolean.TRUE;
        if (oldChoice == isChecked) {
            return;
        }

        this.currentSettings.setUseSystemColors(isChecked);
        this.binding.setIsSystemColorsEnabled(isChecked);
        this.saveSettings();

        OpenAirTagApplication.setUseSystemColors(isChecked);

        Log.i(TAG, "Updating system colours choice to " + isChecked);

        this.recreate();
    }

    private String getCurrentThemeUiString() {
        if (this.currentSettings.getUseDarkTheme() == null) {
            return this.getString(R.string.use_system_default);
        }
        return this.currentSettings.getUseDarkTheme()
                ? this.getString(R.string.dark_theme)
                : this.getString(R.string.light_theme);
    }

    /**
     * Say whether this app has already joined the account's keychain, and offer to undo it.
     *
     * <p><b>The row read the same either way</b>, which is the wrong answer twice over: somebody
     * who has linked cannot tell that they have, and somebody who has not is told their tags will
     * "update" when nothing has ever been read. Being a member is the thing that makes a later
     * read cost one tap and no device passcode, so it is worth saying out loud.
     *
     * <p>Set to the unlinked wording first and corrected when the store answers, rather than left
     * blank until then. Reading it is a decryption on a background thread, and a row that appears
     * with no subtitle and grows one a moment later is worse than one that starts by describing
     * the more common case.
     *
     * <p>The unlink row follows the same answer. Hidden rather than disabled while unlinked -
     * there is nothing to undo yet, and a permanently greyed row in Settings reads as something
     * broken rather than something not applicable.
     */
    private void sayWhetherTheAccountIsLinked() {
        this.binding.setFetchFromAccountSubtitle(
                this.getString(R.string.icloud_fetch_from_settings_subtitle));
        this.showTheUnlinkRow(false);

        this.membershipLookup = this.memberships().get()
                .firstOrError()
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(
                        held -> {
                            this.binding.setFetchFromAccountSubtitle(this.getString(
                                    held.isPresent()
                                            ? R.string.icloud_fetch_from_settings_linked
                                            : R.string.icloud_fetch_from_settings_subtitle));
                            this.showTheUnlinkRow(held.isPresent());
                        },
                        error -> Log.w(TAG, "Could not read whether the account is linked;"
                                + " leaving the row describing the unlinked case", error));
    }

    private KeychainMembershipRepository memberships() {
        return new KeychainMembershipRepository(
                UserAuthDataStore.getInstance(this.getApplicationContext()),
                new AppCryptographyUtil());
    }

    private void showTheUnlinkRow(final boolean visible) {
        this.binding.settingsUnlinkAccount.getRoot()
                .setVisibility(visible ? View.VISIBLE : View.GONE);
    }

    /**
     * Unlink, once the user has been told what that does and - more importantly - what it does
     * not.
     *
     * <p><b>Nothing here can leave the account's trust circle</b>, so the peer this app joined as
     * goes on existing. All this does is forget the keys for it, which is what stops the
     * background read. The entry the user can see in their Apple device list stays until they
     * remove it there, and the dialog says so: somebody who unlinks expecting the device list to
     * tidy itself up will otherwise go looking for a bug.
     *
     * <p>Confirmed rather than immediate because linking again is not free - it needs the Apple
     * device passcode, which is not something people have to hand.
     */
    private void onClickUnlinkAccount() {
        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.icloud_unlink_confirm_title)
                .setMessage(R.string.icloud_unlink_confirm_message)
                .setNegativeButton(R.string.cancel, (dialog, which) -> dialog.dismiss())
                .setPositiveButton(R.string.icloud_unlink_confirm_button,
                        (dialog, which) -> this.unlinkTheAccount())
                .show();
    }

    private void unlinkTheAccount() {
        this.unlinking = this.memberships().forget()
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(
                        () -> {
                            Log.i(TAG, "The keychain membership has been forgotten");
                            this.binding.setFetchFromAccountSubtitle(this.getString(
                                    R.string.icloud_fetch_from_settings_subtitle));
                            this.showTheUnlinkRow(false);
                            Toast.makeText(this, R.string.icloud_unlink_done,
                                    Toast.LENGTH_LONG).show();
                        },
                        error -> {
                            // **Left linked, and said so.** The row is not hidden on failure:
                            // reporting an unlink that did not happen would leave the background
                            // read still running against an account the user believes it has let
                            // go of.
                            Log.e(TAG, "Could not forget the keychain membership", error);
                            Toast.makeText(this, R.string.icloud_unlink_failed,
                                    Toast.LENGTH_LONG).show();
                        });
    }

    @Override
    protected void onDestroy() {
        // The lookup hops back to the main thread to set a subtitle. If the screen has gone by
        // then that is a binding update on detached views, so it is stopped rather than left to
        // land wherever it lands.
        if (this.membershipLookup != null && !this.membershipLookup.isDisposed()) {
            this.membershipLookup.dispose();
        }
        // **Disposed, but the write is not cancelled by it.** forget() is a DataStore update that
        // has already been handed off; dropping the subscription only stops the toast and the
        // binding update arriving at a screen that has gone. The membership is forgotten either
        // way, which is the behaviour somebody who taps Unlink and immediately leaves expects.
        if (this.unlinking != null && !this.unlinking.isDisposed()) {
            this.unlinking.dispose();
        }
        super.onDestroy();
    }

    private void onClickFetchFromAccount() {
        this.startActivity(new Intent(this, FetchFromICloudActivity.class));
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
        SharedMainSettingsManager.applyAnisetteMode(view, current);
        // Nothing has changed yet, so there is nothing to warn about yet.
        SharedMainSettingsManager.applyChangeWarning(view, false);

        modeDropdown.setOnItemClickListener((parent, v, position, id) -> {
            final String selected = position == 1
                    ? UserSettings.ANISETTE_REMOTE : UserSettings.ANISETTE_LOCAL;
            modeDropdown.setText(parent.getItemAtPosition(position).toString(), false);
            modeDropdown.clearFocus();
            SharedMainSettingsManager.applyAnisetteMode(view, selected);
            this.pendingAnisetteMode = selected;
            // Warn about the re-sign-in only once they have actually picked something else,
            // and take it back if they pick their original mode again.
            SharedMainSettingsManager.applyChangeWarning(view, !selected.equals(current));
        });

        this.loadLocalAnisetteStatus(view);

        final View learnMore = view.findViewById(R.id.anisetteLearnMoreButton);
        if (learnMore != null) {
            learnMore.setOnClickListener(v -> this.openConfiguredLink("anisetteWikiPage"));
        }

        final View whereToGetApk = view.findViewById(R.id.anisetteWhereToGetApkButton);
        if (whereToGetApk != null) {
            whereToGetApk.setOnClickListener(v -> this.openConfiguredLink("anisetteApkWikiPage"));
        }

        final View chooseApk = view.findViewById(R.id.anisetteChooseApkButton);
        if (chooseApk != null) {
            chooseApk.setOnClickListener(v -> {
                // Held so the result can be applied to the dialog that is still on screen.
                this.anisetteDialogView = view;
                // Not the APK mime type: files downloaded from a browser or a file host
                // routinely arrive as octet-stream or with no type at all, and filtering them
                // out would hide the very file people were told to go and find.
                this.pickAnisetteApkLauncher.launch(new String[] {"*/*"});
            });
        }

        final View clearApk = view.findViewById(R.id.anisetteClearApkButton);
        if (clearApk != null) {
            clearApk.setOnClickListener(v -> this.forgetTheSuppliedApk(view));
        }
    }

    /** The Anisette dialog currently on screen, so a file-picker result can update it. */
    private View anisetteDialogView;

    /**
     * Picks an Apple Music APK to take the ADI libraries from.
     *
     * <p>{@code OpenDocument} rather than {@code GetContent}: this reads a file of tens of
     * megabytes, and a persistable grant means a copy that stays readable rather than one that
     * evaporates the moment the dialog closes.
     */
    private final ActivityResultLauncher<String[]> pickAnisetteApkLauncher =
            registerForActivityResult(new ActivityResultContracts.OpenDocument(), uri -> {
                if (uri != null) {
                    this.importAnisetteApk(uri);
                }
            });

    /**
     * Take the libraries out of a chosen APK, if they are the ones this app knows how to use.
     *
     * <p>Off the main thread: it reads and hashes tens of megabytes.
     *
     * <p>Nothing about accepting a file from elsewhere lowers the bar. It is checked against
     * the same recorded hashes as Apple's own copy, so a wrong or hostile file is rejected by
     * exactly the check that rejects a corrupted download - and nothing is kept unless every
     * library matched.
     */
    private void importAnisetteApk(final Uri apk) {
        final View view = this.anisetteDialogView;
        if (view != null) {
            SharedMainSettingsManager.applyApkRejection(view, null);
            SharedMainSettingsManager.applyLocalAnisetteStatus(
                    view, AnisetteStatus.checking(), "", this.currentSettings.hasOwnAnisetteApk());
        }

        var async = Observable.fromCallable(() -> {
                    final String abi = Build.SUPPORTED_ABIS[0];
                    final String problem = AdiLibraryImporter.importFrom(
                            this, apk, LocalAnisette.libraryDirectory(this, abi), abi);
                    return Optional.ofNullable(problem);
                })
                .subscribeOn(Schedulers.io())
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(problem -> {
                    if (problem.isPresent()) {
                        // The status has to be put back, not merely left. The spinner was
                        // turned on for this import, and returning without restoring it left
                        // the dialog saying "Checking..." for ever after a refused file.
                        if (view != null) {
                            SharedMainSettingsManager.applyApkRejection(view, problem.get());
                            this.loadLocalAnisetteStatus(view);
                        }
                        return;
                    }

                    // Remembered only so the screen can offer to go back to Apple's copy. The
                    // libraries themselves are already extracted and verified, so nothing
                    // later has to read this file again - or still have permission to.
                    this.currentSettings.setAnisetteApkUri(apk.toString());
                    this.saveSettings();

                    if (view != null) {
                        this.loadLocalAnisetteStatus(view);
                    }
                }, error -> Log.e(TAG, "failed to import the supplied APK", error));
    }

    /**
     * Go back to Apple's copy.
     *
     * <p>Deletes the extracted libraries as well as forgetting the file: leaving them would
     * mean "use Apple's copy" quietly kept using the other one, since the fetcher skips the
     * network for files that are already there.
     */
    private void forgetTheSuppliedApk(final View view) {
        SharedMainSettingsManager.applyApkRejection(view, null);

        var async = Observable.fromCallable(() -> {
                    final String abi = Build.SUPPORTED_ABIS[0];
                    final File directory = LocalAnisette.libraryDirectory(this, abi);
                    for (final String name : LocalAnisette.requiredLibraries()) {
                        final File library = new File(directory, name);
                        if (library.exists() && !library.delete()) {
                            Log.w(TAG, "could not remove " + library);
                        }
                    }
                    return Boolean.TRUE;
                })
                .subscribeOn(Schedulers.io())
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(done -> {
                    this.currentSettings.setAnisetteApkUri(null);
                    this.saveSettings();
                    this.loadLocalAnisetteStatus(view);
                }, error -> Log.e(TAG, "failed to discard the supplied APK", error));
    }

    /**
     * Find out how local Anisette is doing, and say so.
     *
     * <p>Off the main thread, because finding out means downloading Apple's libraries,
     * hashing them and possibly provisioning with Apple. The dialog shows "sets itself up the
     * next time you sign in" until an answer arrives, which is true while it is being worked
     * out and stays true if nothing ever comes back.
     *
     * <p><b>This can do the setup rather than merely observe it.</b> There is no way to know
     * whether something will work without trying it, and a status that said "unknown" would be
     * worth nothing on the one screen built to answer the question. Opening these settings is
     * a reasonable moment for it: somebody is looking at it, and the result explains itself.
     *
     * <p>Only attempted in local mode. Somebody using a server should not have 3 MB downloaded
     * on their behalf for a status they are not reading.
     */
    private void loadLocalAnisetteStatus(final View view) {
        if (!this.currentSettings.usesLocalAnisette(true)) {
            SharedMainSettingsManager.applyLocalAnisetteStatus(
                    view, AnisetteStatus.pending(), "",
                    this.currentSettings.hasOwnAnisetteApk());
            return;
        }

        // Spinner up front, because the answer can take a full connection timeout to arrive -
        // the worst case being offline, which waits 30 seconds and then fails.
        SharedMainSettingsManager.applyLocalAnisetteStatus(
                view, AnisetteStatus.checking(), "", this.currentSettings.hasOwnAnisetteApk());

        var async = Observable.fromCallable(() -> {
                    final AnisetteSource source =
                            AppDependencies.anisette(this, this.currentSettings, true);
                    final AnisetteStatus status = AnisetteStatus.of(source);

                    // Read here rather than on the main thread: it opens an asset.
                    String version = "";
                    try {
                        version = AdiLibraryManifest.load(this).apkVersion();
                    } catch (final Exception e) {
                        Log.w(TAG, "could not read the ADI manifest for its version", e);
                    }
                    return Pair.create(status, version);
                })
                .subscribeOn(Schedulers.io())
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(
                        result -> SharedMainSettingsManager.applyLocalAnisetteStatus(
                                view, result.first, result.second,
                                this.currentSettings.hasOwnAnisetteApk()),
                        error -> Log.w(TAG, "could not determine the local Anisette status",
                                error));
    }

    /**
     * What the Anisette row says underneath its title.
     *
     * <p>Never blank. It used to be the stored server URL, which is null for anybody who has
     * only ever signed in with Anisette from their own device - they never visit the step that
     * sets it - so the row sat there with a heading and nothing under it. Falling back to the
     * mode's own name says something true in every case.
     */
    private String getAnisetteProviderSummary() {
        // Anyone on this screen is signed in, so an unchosen mode means they came from a
        // version that only had servers, and stay on theirs until they say otherwise.
        if (this.currentSettings.usesLocalAnisette(true)) {
            return this.getString(R.string.anisette_mode_local);
        }

        final String url = this.currentSettings.getAnisetteServerUrl();
        return url == null || url.isBlank()
                ? this.getString(R.string.anisette_mode_remote) : url;
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
                // Not "Anisette Server URL": this dialog chooses where sign-in data comes
                // from, and in the usual case that is this device, with no server and no URL
                // anywhere in it. The login screen keeps that wording, where it does label a
                // URL field.
                .setTitle(R.string.anisette_provider)
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
        this.binding.setCurrentAnisetteServerUrl(this.getAnisetteProviderSummary());
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
