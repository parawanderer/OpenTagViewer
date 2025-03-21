package dev.wander.android.opentagviewer.ui.settings;

import static android.view.View.GONE;
import static android.view.View.VISIBLE;

import static dev.wander.android.opentagviewer.util.android.TextChangedWatcherFactory.justWatchOnChanged;

import android.util.Log;
import android.util.Pair;
import android.view.KeyEvent;
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

import dev.wander.android.opentagviewer.R;
import dev.wander.android.opentagviewer.db.repo.model.UserSettings;
import dev.wander.android.opentagviewer.service.web.GithubRawUtilityFilesService;
import dev.wander.android.opentagviewer.service.web.sidestore.AnisetteServerSuggestion;
import dev.wander.android.opentagviewer.ui.extensions.AppAutoCompleteTextView;
import dev.wander.android.opentagviewer.util.validate.AnisetteUrlValidatorUtil;
import dev.wander.android.opentagviewer.util.android.LocaleConfigUtil;
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


    private final Set<String> urlOptions = new HashSet<>();

    private final GithubRawUtilityFilesService github;

    private final Consumer<String> onNewAnisetteUrlSelectedCallback;

    private final UserSettings currentUserSettings;

    public SharedMainSettingsManager(
            @NonNull AppCompatActivity context,
            @NonNull Consumer<String> onLanguageSelected,
            @NonNull Consumer<String> onNewAnisetteUrlSelected,
            @NonNull GithubRawUtilityFilesService github,
            @NonNull UserSettings currentUserSettings,
            @NonNull Consumer<Boolean> onAnisetteUrlInputTyped
    ) {
        this.context = context;
        this.onLanguageSelectedCallback = onLanguageSelected;
        this.onNewAnisetteUrlSelectedCallback = onNewAnisetteUrlSelected;
        this.github = github;
        this.currentUserSettings = currentUserSettings;
        this.onAnisetteUrlInputTyped = onAnisetteUrlInputTyped;
    }

    public void setupProgressBars() {
        this.anisetteProgressIndicator = this.context.findViewById(R.id.anisetteServerUrlProgressIndicator);
        this.anisetteProgressIndicator.setVisibilityAfterHide(GONE);
        this.anisetteProgressIndicator.hide();

        this.anisetteSuccessIcon = this.context.findViewById(R.id.anisetteServerUrlOkIcon);
        this.anisetteErrorIcon = this.context.findViewById(R.id.anisetteServerUrlErrorIcon);
    }

    public void setupLanguageSwitchField() {
        final String currentLocale = Locale.getDefault().getLanguage();

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

        this.mappedLocales.entrySet().stream()
                .filter(kvp -> kvp.getValue().equals(currentLocale))
                .findFirst()
                .map(Map.Entry::getKey)
                .ifPresent(option -> languageDropdown.setText(option, false));

        languageDropdown.setOnItemClickListener((parent, view, position, id) -> {
            final String selectedLocalePretty = parent.getItemAtPosition(position).toString();
            final String selectedLocaleId = mappedLocales.get(selectedLocalePretty);
            languageDropdown.setText(selectedLocalePretty, false);
            languageDropdown.clearFocus();

            this.onLanguageSelectedCallback.accept(selectedLocaleId);
        });
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
                        "lang_" + languageId,
                        "string",
                        this.context.getPackageName()));
    }

    public void handleOnResume() {
        AppAutoCompleteTextView languageDropdown = this.context.findViewById(R.id.languageSelectDropdown);
        final String currentLocale = Locale.getDefault().getLanguage();

        final String selectedLocalePrettyName = mappedLocales.entrySet().stream()
                .filter(kvp -> kvp.getValue().equals(currentLocale))
                .findFirst()
                .map(Map.Entry::getKey)
                .orElse(null);

        languageDropdown.setText(selectedLocalePrettyName, false);
        languageDropdown.clearFocus();
    }

    public enum ANISETTE_TEST_STATUS {
        IN_FLIGHT,
        OK,
        ERROR,
        NONE;
    }
}
