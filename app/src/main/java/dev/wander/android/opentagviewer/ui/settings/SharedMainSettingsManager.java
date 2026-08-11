package dev.wander.android.opentagviewer.ui.settings;

import static android.view.View.GONE;
import static android.view.View.VISIBLE;

import static dev.wander.android.opentagviewer.util.android.TextChangedWatcherFactory.justWatchOnChanged;

import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.util.Pair;
import android.view.KeyEvent;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.widget.ArrayAdapter;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;

import com.google.android.material.progressindicator.CircularProgressIndicator;
import com.google.android.material.textfield.MaterialAutoCompleteTextView;
import com.google.android.material.textfield.TextInputLayout;

import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Consumer;
import java.util.stream.Collectors;

import androidx.appcompat.app.AppCompatDelegate;
import dev.wander.android.opentagviewer.R;
import dev.wander.android.opentagviewer.anisette.AnisetteStatus;
import dev.wander.android.opentagviewer.db.repo.model.UserSettings;
import dev.wander.android.opentagviewer.service.web.GithubRawUtilityFilesService;
import dev.wander.android.opentagviewer.service.web.sidestore.AnisetteServerSuggestion;
import dev.wander.android.opentagviewer.ui.extensions.AppAutoCompleteTextView;
import dev.wander.android.opentagviewer.util.validate.AnisetteUrlValidatorUtil;
import dev.wander.android.opentagviewer.util.android.LocaleConfigUtil;
import io.reactivex.rxjava3.core.Completable;
import lombok.NonNull;

public class SharedMainSettingsManager {

    private static final String TAG = SharedMainSettingsManager.class.getSimpleName();
    private final Consumer<Boolean> onAnisetteUrlInputTyped;

    private CircularProgressIndicator anisetteProgressIndicator = null;

    private ImageView anisetteSuccessIcon = null;
    private ImageView anisetteErrorIcon = null;

    private final AppCompatActivity context;


    private String[] availableLocales = new String[0];

    private Map<String, String> mappedLocales = Map.of();

    private ArrayAdapter<String> shownLocalesAdapter = null;

    private final Consumer<String> onLanguageSelectedCallback;
    private final Consumer<String> onMapProviderSelectedCallback;


    private final Set<String> urlOptions = new HashSet<>();

    private final GithubRawUtilityFilesService github;

    private final Consumer<String> onNewAnisetteUrlSelectedCallback;

    private final UserSettings currentUserSettings;

    /** Told which Anisette mode was picked. Null when the screen does not offer the choice. */
    private final Consumer<String> onAnisetteModeSelectedCallback;

    public SharedMainSettingsManager(
            @NonNull AppCompatActivity context,
            @NonNull Consumer<String> onLanguageSelected,
            @NonNull Consumer<String> onMapProviderSelected,
            @NonNull Consumer<String> onNewAnisetteUrlSelected,
            @NonNull GithubRawUtilityFilesService github,
            @NonNull UserSettings currentUserSettings,
            @NonNull Consumer<Boolean> onAnisetteUrlInputTyped
    ) {
        this(context, onLanguageSelected, onMapProviderSelected, onNewAnisetteUrlSelected,
                github, currentUserSettings, onAnisetteUrlInputTyped, null);
    }

    public SharedMainSettingsManager(
            @NonNull AppCompatActivity context,
            @NonNull Consumer<String> onLanguageSelected,
            @NonNull Consumer<String> onMapProviderSelected,
            @NonNull Consumer<String> onNewAnisetteUrlSelected,
            @NonNull GithubRawUtilityFilesService github,
            @NonNull UserSettings currentUserSettings,
            @NonNull Consumer<Boolean> onAnisetteUrlInputTyped,
            Consumer<String> onAnisetteModeSelected
    ) {
        this.context = context;
        this.onLanguageSelectedCallback = onLanguageSelected;
        this.onMapProviderSelectedCallback = onMapProviderSelected;
        this.onNewAnisetteUrlSelectedCallback = onNewAnisetteUrlSelected;
        this.github = github;
        this.currentUserSettings = currentUserSettings;
        this.onAnisetteUrlInputTyped = onAnisetteUrlInputTyped;
        this.onAnisetteModeSelectedCallback = onAnisetteModeSelected;
    }

    public void setupProgressBars() {
        this.anisetteProgressIndicator = this.context.findViewById(R.id.anisetteServerUrlProgressIndicator);
        this.anisetteProgressIndicator.setVisibilityAfterHide(GONE);
        this.anisetteProgressIndicator.hide();

        this.anisetteSuccessIcon = this.context.findViewById(R.id.anisetteServerUrlOkIcon);
        this.anisetteErrorIcon = this.context.findViewById(R.id.anisetteServerUrlErrorIcon);
    }

    public void setupLanguageSwitchField() {
        this.availableLocales = LocaleConfigUtil.getAvailableLocales(this.context.getResources())
                .toArray(new String[0]);

        TextInputLayout languageDropdownContainer = this.context.findViewById(R.id.languageSelectContainer);
        AppAutoCompleteTextView languageDropdown = this.context.findViewById(R.id.languageSelectDropdown);

        this.mappedLocales = Arrays.stream(this.availableLocales)
                .map(lang -> Pair.create(lang, this.getPrettyLanguageName(lang)))
                .collect(Collectors.toMap(p -> p.second, p -> p.first));

        List<String> sortedLanguageOptions = this.mappedLocales.keySet().stream()
                .sorted().collect(Collectors.toList());

        this.shownLocalesAdapter = new ArrayAdapter<>(this.context, android.R.layout.simple_dropdown_item_1line, sortedLanguageOptions);
        languageDropdown.setAdapter(this.shownLocalesAdapter);

        this.setupCurrentLocalePretty();

        languageDropdown.setOnItemClickListener((parent, view, position, id) -> {
            final String selectedLocalePretty = parent.getItemAtPosition(position).toString();
            final String selectedLocaleId = mappedLocales.get(selectedLocalePretty);
            languageDropdown.setText(selectedLocalePretty, false);
            languageDropdown.clearFocus();

            this.onLanguageSelectedCallback.accept(selectedLocaleId);
        });
    }

    private void setupCurrentLocalePretty() {
        final String currentLocale = this.getCurrentLocaleTag();
        AppAutoCompleteTextView languageDropdown = this.context.findViewById(R.id.languageSelectDropdown);

        this.mappedLocales.entrySet().stream()
                .filter(kvp -> this.localeTagMatches(kvp.getValue(), currentLocale))
                .findFirst()
                .map(Map.Entry::getKey)
                .ifPresent(option -> languageDropdown.setText(option, false));

        // this fixes a stupid issue where I think the dropdown will try to reset its state from before the locale change, which results
        // in the wrong language choice appearing in the input box (that of the language choice translated in the previous UI language)
        // BTW: this is probably some kind of race condition situation so not exactly a perfect fix,
        // but good enough for when the user will perform this switch like once in their lifetime of usage of the app
        final Handler handler = new Handler(Looper.getMainLooper());
        handler.postDelayed(languageDropdown::clearFocus, 10);
    }

    public void setupMapProviderField() {
        AppAutoCompleteTextView mapProviderDropdown = this.context.findViewById(R.id.mapProviderSelectDropdown);

        String[] providerLabels = new String[] {
                this.context.getString(R.string.map_provider_google),
                this.context.getString(R.string.map_provider_amap)
        };

        mapProviderDropdown.setSimpleItems(providerLabels);
        this.setupCurrentMapProviderPretty();

        mapProviderDropdown.setOnItemClickListener((parent, view, position, id) -> {
            final String selectedProvider = position == 1 ? "amap" : "google";
            final String selectedLabel = parent.getItemAtPosition(position).toString();
            mapProviderDropdown.setText(selectedLabel, false);
            mapProviderDropdown.clearFocus();
            this.onMapProviderSelectedCallback.accept(selectedProvider);
        });
    }

    private void setupCurrentMapProviderPretty() {
        AppAutoCompleteTextView mapProviderDropdown = this.context.findViewById(R.id.mapProviderSelectDropdown);
        if (mapProviderDropdown == null) {
            return;
        }

        final String provider = this.currentUserSettings.getMapProvider();
        final int labelRes = "amap".equals(provider)
                ? R.string.map_provider_amap
                : R.string.map_provider_google;
        mapProviderDropdown.setText(this.context.getString(labelRes), false);
    }

    /**
     * The choice between producing Anisette here and asking a server for it.
     *
     * @param hasExistingSession whether somebody is already signed in. Only used to work out
     *                           what to show when nobody has chosen yet - an existing session
     *                           stays on its server, because moving it would force a re-login.
     */
    public void setupAnisetteModeField(boolean hasExistingSession) {
        AppAutoCompleteTextView dropdown = this.context.findViewById(R.id.anisetteModeSelectDropdown);
        if (dropdown == null) {
            return;
        }

        dropdown.setSimpleItems(new String[] {
                this.context.getString(R.string.anisette_mode_local),
                this.context.getString(R.string.anisette_mode_remote)
        });

        final String current = this.currentUserSettings.resolveAnisetteMode(hasExistingSession);
        this.showAnisetteMode(current);

        dropdown.setOnItemClickListener((parent, view, position, id) -> {
            final String selected = position == 1
                    ? UserSettings.ANISETTE_REMOTE : UserSettings.ANISETTE_LOCAL;
            dropdown.setText(parent.getItemAtPosition(position).toString(), false);
            dropdown.clearFocus();

            if (this.onAnisetteModeSelectedCallback != null) {
                this.onAnisetteModeSelectedCallback.accept(selected);
            }
        });
    }

    /** Show one mode's controls and hide the other's, on this screen. */
    public void showAnisetteMode(final String mode) {
        applyAnisetteMode(this.context.findViewById(android.R.id.content), mode);
    }

    /**
     * Show one mode's controls and hide the other's, anywhere they appear.
     *
     * <p>The remote server's URL and its test button are meaningless when Anisette is produced
     * here, so they go away entirely rather than sitting there greyed out.
     *
     * <p>Static and taking a root view because these controls live in two places - inline on
     * the login screen, and inside a dialog in Settings - and because it makes the behaviour
     * testable by inflating the layout, with no activity and no Anisette of any kind.
     *
     * @param root anything containing the controls; missing ones are skipped, so a screen that
     *             shows only some of them is fine
     */
    public static void applyAnisetteMode(final View root, final String mode) {
        if (root == null) {
            return;
        }
        final boolean local = !UserSettings.ANISETTE_REMOTE.equals(mode);

        AppAutoCompleteTextView dropdown = root.findViewById(R.id.anisetteModeSelectDropdown);
        if (dropdown != null) {
            dropdown.setText(root.getContext().getString(
                    local ? R.string.anisette_mode_local : R.string.anisette_mode_remote), false);
        }

        View localSection = root.findViewById(R.id.anisetteLocalStatusContainer);
        if (localSection != null) {
            localSection.setVisibility(local ? VISIBLE : GONE);
        }

        View remoteSection = root.findViewById(R.id.anisetteRemoteSection);
        if (remoteSection != null) {
            remoteSection.setVisibility(local ? GONE : VISIBLE);
        }
    }

    /**
     * On the sign-in screen: show the Anisette server field only when signing in needs it.
     *
     * <p>Signing in normally needs no server at all now, so the field is hidden and the screen
     * is quieter for it. But Settings is behind a login, and local Anisette can fail on a
     * device that has never signed in - so somebody in that position would have no way to
     * reach the one setting that would get them in. Hiding it is therefore conditional on it
     * being unnecessary, and the condition is re-evaluated rather than assumed.
     *
     * <p>Hidden while the answer is still coming and once the answer is yes; shown for
     * everything else, including {@code PENDING}. Erring towards showing is deliberate: an
     * unnecessary field costs a moment's confusion, which the explanation above it covers,
     * while a missing one strands somebody with no way out of the screen.
     *
     * @param root            anything containing the section; missing views are skipped
     * @param status          what local Anisette had to say, from a background thread
     * @param remoteWasChosen whether a server is being used because somebody asked for one.
     *                        Decides only whether the field is explained - it appears either
     *                        way, but telling somebody their device could not manage when they
     *                        chose the server themselves would be wrong and alarming
     */
    public static void applyLoginAnisetteFallback(final View root, final AnisetteStatus status,
                                                  final boolean remoteWasChosen) {
        if (root == null) {
            return;
        }

        final View remoteSection = root.findViewById(R.id.anisetteRemoteSection);
        if (remoteSection == null) {
            return;
        }

        final AnisetteStatus.State state = status.state();
        final boolean signInNeedsAServer = state != AnisetteStatus.State.READY
                && state != AnisetteStatus.State.CHECKING;

        remoteSection.setVisibility(signInNeedsAServer ? VISIBLE : GONE);

        // Only explained when it appeared unbidden. PENDING means nothing has been tried yet,
        // so there is nothing to explain either.
        final boolean explain = signInNeedsAServer
                && !remoteWasChosen
                && state != AnisetteStatus.State.PENDING;

        final View reason = root.findViewById(R.id.anisetteLoginFallbackReason);
        if (reason != null) {
            reason.setVisibility(explain ? VISIBLE : GONE);
        }
    }

    /**
     * Warn about the re-sign-in, but only once there is something to warn about.
     *
     * <p>It used to be on from the moment the dialog opened, so it announced the consequence
     * of a change nobody had made - on a screen most people open to look rather than to edit.
     * A warning that is always there is one nobody reads by the time it matters.
     *
     * @param changing whether the mode or the server actually differs from what is stored
     */
    public static void applyChangeWarning(final View root, final boolean changing) {
        if (root == null) {
            return;
        }

        final View warning = root.findViewById(R.id.anisetteChangeWarningContainer);
        if (warning != null) {
            warning.setVisibility(changing ? VISIBLE : GONE);
        }
    }

    /**
     * Say why a chosen APK was refused, or clear a previous refusal.
     *
     * <p>Shown in the dialog rather than as a toast. The reason names the library whose hash
     * did not match, which runs to two lines, and a toast is capped at two lines and then
     * disappears - so the one piece of information that tells somebody whether to go and find
     * a different copy was being cut off mid-sentence.
     *
     * @param problem what was wrong with the file, or null to clear
     */
    public static void applyApkRejection(final View root, final String problem) {
        if (root == null) {
            return;
        }

        final TextView rejection = root.findViewById(R.id.anisetteApkRejection);
        if (rejection == null) {
            return;
        }

        if (problem == null) {
            rejection.setVisibility(GONE);
            rejection.setText(null);
            return;
        }

        rejection.setText(root.getContext().getString(R.string.anisette_apk_rejected, problem));
        rejection.setVisibility(VISIBLE);
    }

    /**
     * Draw what local Anisette has to say for itself.
     *
     * <p>Takes an already-computed {@link AnisetteStatus} rather than a source, because working
     * one out blocks on downloads and Apple while this has to run on the main thread. That
     * split is also what makes this testable by inflating a layout - no activity, no network.
     *
     * @param root        anything containing the status views; missing ones are skipped
     * @param status      what to say
     * @param apkVersion  the Apple Music build the app knows how to read, shown only when
     *                    somebody has to go and find a copy of it themselves
     * @param hasOwnApk   whether a file has already been supplied, which decides whether
     *                    "go back to Apple's copy" is offered
     */
    public static void applyLocalAnisetteStatus(final View root, final AnisetteStatus status,
                                                final String apkVersion,
                                                final boolean hasOwnApk) {
        if (root == null) {
            return;
        }

        final TextView statusText = root.findViewById(R.id.anisetteLocalStatus);
        final ImageView okIcon = root.findViewById(R.id.anisetteLocalOkIcon);
        final ImageView errorIcon = root.findViewById(R.id.anisetteLocalErrorIcon);
        if (statusText == null) {
            return;
        }

        final AnisetteStatus.State state = status.state();
        final boolean failed = state == AnisetteStatus.State.UNAVAILABLE
                || state == AnisetteStatus.State.APPLE_CHANGED;

        final boolean checking = state == AnisetteStatus.State.CHECKING;

        if (okIcon != null) {
            okIcon.setVisibility(state == AnisetteStatus.State.READY ? VISIBLE : GONE);
        }
        if (errorIcon != null) {
            errorIcon.setVisibility(failed ? VISIBLE : GONE);
        }

        final View progress = root.findViewById(R.id.anisetteLocalProgress);
        if (progress != null) {
            progress.setVisibility(checking ? VISIBLE : GONE);
        }

        if (state == AnisetteStatus.State.READY) {
            statusText.setText(R.string.anisette_local_status_ready);
        } else if (failed) {
            statusText.setText(root.getContext().getString(
                    R.string.anisette_local_status_unavailable, status.detail()));
        } else if (checking) {
            statusText.setText(R.string.anisette_local_status_checking);
        } else {
            statusText.setText(R.string.anisette_local_status_pending);
        }

        // Only offered once the automatic route has actually failed for the one reason that
        // supplying a file would fix. Suggesting people go and find an APK while everything
        // works would be an invitation to break it.
        final View ownApk = root.findViewById(R.id.anisetteOwnApkContainer);
        if (ownApk == null) {
            return;
        }
        ownApk.setVisibility(status.needsOwnApk() ? VISIBLE : GONE);

        final TextView explanation = root.findViewById(R.id.anisetteOwnApkExplanation);
        if (status.needsOwnApk() && explanation != null) {
            explanation.setText(root.getContext().getString(
                    R.string.anisette_local_needs_own_file, apkVersion));
        }

        final View clear = root.findViewById(R.id.anisetteClearApkButton);
        if (clear != null) {
            clear.setVisibility(hasOwnApk ? VISIBLE : GONE);
        }
    }

    public void setupAnisetteServerUrlField() {
        TextInputLayout urlTextInputContainer = this.context.findViewById(R.id.anisetteServerUrlContainer);
        MaterialAutoCompleteTextView urlTextInput = this.context.findViewById(R.id.anisetteServerUrl);

        urlTextInput.setText(this.currentUserSettings.getAnisetteServerUrl());

        urlTextInput.setOnItemClickListener((parent, view, position, id) -> {
            final String selectedUrlFromDropdown = parent.getItemAtPosition(position).toString();

            if (this.validateAnisetteUrl(selectedUrlFromDropdown)) {
                this.onNewAnisetteUrlSelectedCallback.accept(selectedUrlFromDropdown);
            }
        });

        urlTextInput.setOnEditorActionListener((TextView v, int actionId, KeyEvent event) -> {
            if (actionId == EditorInfo.IME_ACTION_DONE) {
                // check validity URL
                var currentInput = v.getText().toString();
                if (this.validateAnisetteUrl(currentInput)) {
                    this.onNewAnisetteUrlSelectedCallback.accept(currentInput);
                }
            }
            return true;
        });

        urlTextInput.addTextChangedListener(justWatchOnChanged((s, start, before, count) -> {
            boolean result = validateAnisetteUrl(s.toString());
            this.onAnisetteUrlInputTyped.accept(result);
        }));

        var disp = this.github.getSuggestedServers().subscribe(suggestedServers -> {
            this.context.runOnUiThread(() -> {
                // add them to the suggested servers list!

                Optional.ofNullable(this.currentUserSettings.getAnisetteServerUrl())
                        .ifPresent(urlOptions::add);

                suggestedServers.getServers().stream()
                        .map(AnisetteServerSuggestion::getAddress)
                        .forEach(urlOptions::add);

                String[] optionsArray = this.urlOptions.toArray(new String[0]);
                Arrays.sort(optionsArray);

                urlTextInput.setSimpleItems(optionsArray);

            });
        }, error -> Log.e(TAG, "Error occurred while fetching servers", error));
    }

    public void showAnisetteTestStatus(ANISETTE_TEST_STATUS status) {
        switch (status) {
            case OK:
                this.anisetteProgressIndicator.setVisibility(GONE);
                this.anisetteProgressIndicator.hide();
                this.anisetteSuccessIcon.setVisibility(VISIBLE);
                this.anisetteErrorIcon.setVisibility(GONE);
                this.setAnisetteServerUrlTitleColor(true);
                break;
            case ERROR:
                this.anisetteProgressIndicator.setVisibility(GONE);
                this.anisetteProgressIndicator.hide();
                this.anisetteSuccessIcon.setVisibility(GONE);
                this.anisetteErrorIcon.setVisibility(VISIBLE);
                this.setAnisetteServerUrlTitleColor(false);
                break;
            case IN_FLIGHT:
                this.anisetteProgressIndicator.setVisibility(VISIBLE);
                this.anisetteProgressIndicator.show();
                this.anisetteSuccessIcon.setVisibility(GONE);
                this.anisetteErrorIcon.setVisibility(GONE);
                this.setAnisetteServerUrlTitleColor(true);
                break;
            case NONE:
                this.anisetteProgressIndicator.setVisibility(GONE);
                this.anisetteProgressIndicator.hide();
                this.anisetteSuccessIcon.setVisibility(GONE);
                this.anisetteErrorIcon.setVisibility(GONE);
                this.setAnisetteServerUrlTitleColor(true);
                break;
        }
    }

    private void setAnisetteServerUrlTitleColor(boolean isOkColor) {
        TextView serverHeadingTitle = this.context.findViewById(R.id.selectAnisetteServerUrlTitle);

        int color;
        if (isOkColor) {
            color = ContextCompat.getColor(this.context.getApplicationContext(), R.color.md_theme_outlineVariant_mediumContrast);
        } else {
            color = ContextCompat.getColor(this.context.getApplicationContext(), R.color.md_theme_error);
        }

        serverHeadingTitle.setTextColor(color);
    }

    public boolean validateAnisetteUrl(final String urlInput) {
        TextInputLayout urlTextInputContainer = this.context.findViewById(R.id.anisetteServerUrlContainer);

        boolean isValidUrl = AnisetteUrlValidatorUtil.isValidAnisetteUrl(urlInput);
        if (!isValidUrl) {
            CharSequence error = this.context.getResources().getString(R.string.this_is_not_a_valid_url);
            urlTextInputContainer.setError(error);
            this.setAnisetteServerUrlTitleColor(false);
            return false;
        }
        urlTextInputContainer.setError(null);
        this.showAnisetteTestStatus(SharedMainSettingsManager.ANISETTE_TEST_STATUS.NONE);
        return true;
    }

    public void setAnisetteTextFieldError(final String error) {
        TextInputLayout urlTextInputContainer = this.context.findViewById(R.id.anisetteServerUrlContainer);
        urlTextInputContainer.setError(error);
    }

    public void setAnisetteTextFieldError(final int stringId, Object... formatArgs) {
        TextInputLayout urlTextInputContainer = this.context.findViewById(R.id.anisetteServerUrlContainer);

        urlTextInputContainer.setError(
                this.context.getResources().getString(stringId, formatArgs)
        );
    }

    private String getPrettyLanguageName(final String languageId) {
        var res = this.context.getResources();
        return res.getString(res.getIdentifier(
                        LocaleConfigUtil.toLocaleLabelResourceName(languageId),
                        "string",
                        this.context.getPackageName()));
    }

    public void handleOnResume() {
        this.setupCurrentLocalePretty();
        this.setupCurrentMapProviderPretty();
    }

    private String getCurrentLocaleTag() {
        String configuredTag = this.currentUserSettings.getLanguage();
        if (configuredTag != null && !configuredTag.isBlank()) {
            return configuredTag;
        }

        String appLocaleTags = AppCompatDelegate.getApplicationLocales().toLanguageTags();
        if (appLocaleTags != null && !appLocaleTags.isBlank()) {
            return appLocaleTags.split(",")[0];
        }

        return Locale.getDefault().toLanguageTag();
    }

    private boolean localeTagMatches(String supportedLocaleTag, String currentLocaleTag) {
        if (supportedLocaleTag == null || currentLocaleTag == null) {
            return false;
        }
        if (supportedLocaleTag.equalsIgnoreCase(currentLocaleTag)) {
            return true;
        }
        return currentLocaleTag.toLowerCase(Locale.ROOT).startsWith(supportedLocaleTag.toLowerCase(Locale.ROOT) + "-");
    }

    public enum ANISETTE_TEST_STATUS {
        IN_FLIGHT,
        OK,
        ERROR,
        NONE;
    }
}
