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
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.OnBackPressedCallback;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.app.AppCompatDelegate;
import androidx.core.os.LocaleListCompat;
import androidx.databinding.DataBindingUtil;

import dev.wander.android.opentagviewer.ui.compat.WindowPaddingUtil;
import androidx.lifecycle.ViewModelProvider;

import com.chaquo.python.PyObject;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.progressindicator.CircularProgressIndicator;
import com.google.android.material.textfield.MaterialAutoCompleteTextView;
import com.google.android.material.textfield.TextInputEditText;

import java.util.List;
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
import dev.wander.android.opentagviewer.python.AppleAuthService;
import dev.wander.android.opentagviewer.python.AppDependencies;
import dev.wander.android.opentagviewer.python.PythonAccountLoginException;
import dev.wander.android.opentagviewer.python.PythonAuthService;
import dev.wander.android.opentagviewer.python.PythonAuthService.AuthMethodPhone;
import dev.wander.android.opentagviewer.python.PythonAuthService.PythonAuthResponse;
import dev.wander.android.opentagviewer.python.TermsDocument;
import dev.wander.android.opentagviewer.anisette.AnisetteSource;
import dev.wander.android.opentagviewer.anisette.AnisetteStatus;
import dev.wander.android.opentagviewer.service.web.AnisetteServerTesterService;
import dev.wander.android.opentagviewer.service.web.CronetProvider;
import dev.wander.android.opentagviewer.service.web.GitHubService;
import dev.wander.android.opentagviewer.service.web.GithubRawUtilityFilesService;
import dev.wander.android.opentagviewer.ui.login.Apple2FACodeInputManager;
import dev.wander.android.opentagviewer.ui.login.StepTransition;
import dev.wander.android.opentagviewer.ui.login.StepTransition.Direction;
import dev.wander.android.opentagviewer.ui.settings.AmapApiKeyDialog;
import dev.wander.android.opentagviewer.ui.settings.SharedMainSettingsManager;
import dev.wander.android.opentagviewer.util.android.AppCryptographyUtil;
import dev.wander.android.opentagviewer.util.android.PropertiesUtil;
import dev.wander.android.opentagviewer.util.rx.ACodeAppleAlreadyTook;
import dev.wander.android.opentagviewer.viewmodel.AppleLoginViewModel;
import dev.wander.android.opentagviewer.viewmodel.LoginActivityState;
import dev.wander.android.opentagviewer.viewmodel.LoginActivityState.PAGE;
import io.reactivex.rxjava3.android.schedulers.AndroidSchedulers;
import io.reactivex.rxjava3.core.Observable;
import io.reactivex.rxjava3.schedulers.Schedulers;
import lombok.extern.slf4j.Slf4j;

/**
 * This entire thing should be refactored and made less convoluted and spaghetti-like
 */
@Slf4j
public class AppleLoginActivity extends AppCompatActivity {
    private static final String TAG = AppleLoginActivity.class.getSimpleName();

    private static final Pattern REGEX_2FA_CODE = Pattern.compile("^[0-9]{6}$");

    /**
     * The mutually exclusive steps of the flow, in the order the user walks through them.
     *
     * <p>Listed once so that showing a step is "show this one" rather than every caller
     * remembering to hide each of the others - which was six separate {@code setVisibility}
     * calls spread over the file, and is the kind of thing that leaves two steps stacked on
     * top of each other when one of them is missed.
     */
    private static final int[] PAGES = {
            R.id.login_anisette_container,
            R.id.login_maininfo_container,
            R.id.login_2fa_choice,
            R.id.login_2fa_container,
            R.id.login_terms_container,
    };

    /**
     * The half-signed-in account waiting on terms, and the documents it is waiting on.
     *
     * <p>Held on the activity rather than passed around because a {@code PyObject} cannot go in
     * an {@code Intent} - which is also why this is a step of this screen and not a screen of
     * its own. Cleared once signing in completes, so nothing outlives the flow that needed it.
     */
    private PyObject termsAccount = null;

    private List<TermsDocument> termsDocuments = List.of();

    private int termsIndex = 0;

    /**
     * Set when the user did not choose to be here - their stored session stopped working.
     *
     * <p>Worth distinguishing from an ordinary first run. Being dropped on a login screen with
     * no explanation reads as the app having lost everything, and the reasonable response to
     * that is to go and tidy up whatever unfamiliar entry is in your Apple device list - which
     * is the one action that makes it worse.
     */
    public static final String EXTRA_SESSION_EXPIRED = "sessionExpired";

    /**
     * The address to put in the field, for somebody signing in again rather than for the first
     * time. Only ever their own, read from the account being discarded.
     */
    public static final String EXTRA_PREFILL_EMAIL = "prefillEmail";

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

    /**
     * Anisette produced here, which is what a sign-in normally uses.
     *
     * <p>A field rather than a local, so that the screen asks the same object every time and
     * so that a test can put something else in its place - the states this screen has to
     * handle otherwise require Apple to have shipped a new build, or the network to be down.
     */
    private AnisetteSource localAnisette;

    /** Signing in to Apple. Behind an interface so a test can drive this screen at all. */
    private AppleAuthService authService;

    /**
     * What it last said. Starts as "nothing tried yet", which is the only honest thing to say
     * before the first check and is rendered as such.
     */
    private AnisetteStatus localAnisetteStatus = AnisetteStatus.pending();

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
                this::updateMapProvider,
                this::testAndSaveAnisetteUrl,
                github,
                this.getUserSettings(),
                this::onAnisetteUrlInputTyped
        );

        this.anisetteServerTesterService = AppDependencies.serverTester(cronet);

        this.authService = AppDependencies.authService();

        // Nobody is signed in on this screen, so an unchosen mode means local: there is no
        // existing session whose machine identity has to be preserved.
        this.localAnisette = AppDependencies.anisette(this, this.getUserSettings(), false);

        this.twoFactorEntryManager = new Apple2FACodeInputManager(this, this::on2FAAuthCodeFilled);

        this.binding = DataBindingUtil.setContentView(this, R.layout.activity_apple_login);
        // This screen had neither inset applied - so its buttons sat under the navigation bar
        // and its heading under the status bar, on the very first screen anybody sees.
        WindowPaddingUtil.insetForSystemBars(this.binding.getRoot());

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
        this.sharedMainSettingsManager.setupMapProviderField();
        this.sharedMainSettingsManager.setupAnisetteServerUrlField();
        this.twoFactorEntryManager.init();

        model.getUiState().observe(this, this::handleAuth);

        this.emailOrPhoneInput = this.findViewById(R.id.email_or_phone_input_field);
        this.passwordInput = this.findViewById(R.id.password_input_field);
        this.loginButton = this.findViewById(R.id.login_button_main);
        this.twoFactorAuthChoiceBackButton = this.findViewById(R.id.twofactorauthchoice_back_button);

        this.findViewById(R.id.login_terms_agree_button)
                .setOnClickListener(v -> this.onAgreeToTerms());

        // **The terms box has to win the drag, or the contract cannot be read.**
        //
        // This whole screen is inside a vertical ScrollView, and the box holding the document is
        // another one nested in it. Two vertical scrollers on top of each other means the outer
        // one takes the gesture: dragging inside the document scrolls the *page*, and the
        // document itself never moves - so everything past the first boxful is unreachable, on a
        // screen whose entire purpose is reading to the end.
        //
        // Asking the parent not to intercept while a touch is inside the box is the standard fix
        // and the smallest one. Returning false leaves the ScrollView to do its own scrolling.
        //
        // **There were two causes and this is only one of them.** The document TextView also had
        // `textIsSelectable`, which makes it take the drag for a text-selection gesture, and the
        // box did not scroll with that set either. Both had to go; removing either one on its own
        // brings the bug back, which AcceptingTermsFlowTest was used to confirm in both
        // directions. So selectable text cannot come back here without a scrolling story.
        this.findViewById(R.id.login_terms_scroll).setOnTouchListener((view, event) -> {
            view.getParent().requestDisallowInterceptTouchEvent(true);
            return false;
        });
        // Leaving without agreeing. Nothing was sent, and the account is dropped rather than
        // kept around half-signed-in - the next attempt starts a fresh one.
        this.findViewById(R.id.login_terms_back_button).setOnClickListener(v -> {
            this.termsAccount = null;
            this.termsDocuments = List.of();
            this.showPage(R.id.login_maininfo_container, Direction.BACK);
            this.setCurrentStepText(R.string.apple_account, Direction.BACK);
        });

        this.explainWhySignedOutIfSent();
    }

    /**
     * Somebody arriving here because their session stopped working, rather than by choice.
     *
     * <p>Being returned to a login screen with no explanation reads as the app having lost
     * everything - which is exactly the moment somebody goes to their Apple device list, finds
     * an entry they do not recognise, and removes it. So it says what happened, and says that
     * the tags and their history are still here.
     *
     * <p>Shown on this screen rather than by whoever sent them, because this is the screen that
     * stays: the sender is finishing, and a dialog on a finishing activity is a leaked window.
     */
    private void explainWhySignedOutIfSent() {
        final String email = this.getIntent().getStringExtra(EXTRA_PREFILL_EMAIL);
        if (email != null && !email.isEmpty()) {
            // Prefilled whether or not the dialog is shown, and before it, so the field is
            // already right the moment the dialog is dismissed rather than filling in
            // afterwards in front of the user.
            this.emailOrPhoneInput.setText(email);
        }

        if (!this.getIntent().getBooleanExtra(EXTRA_SESSION_EXPIRED, false)) {
            return;
        }

        // Consumed, so that rotating the device - or coming back to this screen later - does
        // not re-announce something the user has already read and acted on.
        this.getIntent().removeExtra(EXTRA_SESSION_EXPIRED);

        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.session_expired_title)
                .setMessage(R.string.session_expired_message)
                .setPositiveButton(R.string.ok, null)
                .show();
    }

    @Override
    protected void onResume() {
        super.onResume();

        this.sharedMainSettingsManager.handleOnResume();
    }

    private void testAndSaveAnisetteUrl(final String newUrl) {
        this.testAndSaveAnisetteUrl(newUrl, null);
    }

    private void testAndSaveAnisetteUrl(final String newUrl, final Runnable onSuccess) {
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
                        if (onSuccess != null) {
                            onSuccess.run();
                        }
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

    /**
     * Move to one step of the flow, animating away whichever one is currently up.
     *
     * @param pageId    one of {@link #PAGES}, or {@link View#NO_ID} to leave the flow showing
     *                  nothing - which is what happens while the spinner is up
     * @param direction the way the user is travelling, which decides which way things slide.
     *                  {@link Direction#NONE} for a render that is not navigation, such as
     *                  the first draw or a restore after rotation
     */
    private void showPage(final int pageId, final Direction direction) {
        View outgoing = null;
        for (final int candidate : PAGES) {
            if (candidate == pageId) {
                continue;
            }
            final View page = this.findViewById(candidate);
            if (outgoing == null && page.getVisibility() == VISIBLE) {
                outgoing = page;
                continue;
            }
            // Either already off screen or a second visible step, which should not happen.
            // Hidden without ceremony either way: only one thing can animate out, and a view
            // left half-faded by an earlier swap would come back that way next time.
            StepTransition.swap(page, null, Direction.NONE);
        }

        StepTransition.swap(
                outgoing,
                pageId == View.NO_ID ? null : this.findViewById(pageId),
                direction);
    }

    /** Leave the current step with nothing to replace it, because the spinner is taking over. */
    private void hideCurrentPage(final Direction direction) {
        this.showPage(View.NO_ID, direction);
    }

    /**
     * Name the step the user is on, travelling with it when it changes.
     *
     * <p>Set before it is animated, so what it says is readable straight away and only the
     * movement is decorative.
     *
     * <p>Unchanged text is left alone rather than re-animated. Choosing a 2FA method and then
     * typing the code are two steps under one heading, so animating on every call would make
     * the heading twitch for a change that did not happen.
     */
    private void setCurrentStepText(final int stringResId, final Direction direction) {
        TextView textView = this.findViewById(R.id.login_current_input_indicator);

        final String next = this.getString(stringResId);
        if (next.contentEquals(textView.getText())) {
            return;
        }

        textView.setText(next);
        StepTransition.enter(textView, direction);
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
        // Let the user proceed after typing a syntactically valid URL.
        // The actual connectivity test happens when the user continues.
        this.binding.setAllowServerConfNext(isValid);
    }

    private void handleAuth(LoginActivityState state) {
        this.setCurrentStepText(R.string.welcome, Direction.NONE);
        this.showLoading(null);

        var sub = this.getAnisetteSetupStatus()
            .observeOn(AndroidSchedulers.mainThread())
            .subscribe(status -> {
                this.hideLoading();

                // Nothing here is navigation - it is the screen being drawn, or restored after
                // a rotation. Animating it would replay steps the user did not just take.
                if (status == SETUP_STATUS.OK && (!state.hasSpecifiedCurrentPage() || state.getCurrentPage() == PAGE.LOGIN)) {
                    this.binding.setAllowServerConfNext(true);
                    this.sharedMainSettingsManager.showAnisetteTestStatus(OK);
                    this.sharedMainSettingsManager.setAnisetteTextFieldError(null);
                    this.showAccountLoginAuthOptions(Direction.NONE);
                } else if (state.getCurrentPage() == PAGE.CHOOSE_2FA) {
                    this.showNextAuthPage(state.getAuthResponse().getLoginState(), Direction.NONE);
                } else if (state.currentPageIs2faEntry()) {
                    this.show2FACodeEntryTextbox(Direction.NONE);
                } else {
                    // show welcome step/server setup step
                    this.showInitialWelcomeConfOptions(status, Direction.NONE);
                }
            });
    }

    public void onClickToLoginAccount(View view) {
        Log.d(TAG, "Clicked onwards to account login!");

        // When Anisette comes from this device the server field is not even on screen, so
        // there is nothing to validate and nothing that could stop somebody getting past here.
        if (this.localAnisetteStatus.state() == AnisetteStatus.State.READY) {
            this.getUiState().setCurrentPage(PAGE.LOGIN);
            this.showAccountLoginAuthOptions(Direction.FORWARD);
            return;
        }

        MaterialAutoCompleteTextView urlTextInput = findViewById(R.id.anisetteServerUrl);
        final String currentInput = Optional.ofNullable(urlTextInput.getText())
                .map(CharSequence::toString)
                .map(String::trim)
                .orElse("");

        if (!this.sharedMainSettingsManager.validateAnisetteUrl(currentInput)) {
            this.binding.setAllowServerConfNext(false);
            return;
        }

        this.testAndSaveAnisetteUrl(currentInput, () -> {
            this.getUiState().setCurrentPage(PAGE.LOGIN);
            this.showAccountLoginAuthOptions(Direction.FORWARD);
        });
    }

    public void onClickBackToAnisetteSettings(View view) {
        Log.d(TAG, "Clicked backwards to language + anisette settings");
        this.showInitialWelcomeConfOptions(SETUP_STATUS.NO_SERVER_CONFIGURED, Direction.BACK);
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
        this.hideCurrentPage(Direction.FORWARD);

        final String emailOrPhone = Objects.requireNonNull(emailOrPhoneInput.getText()).toString();
        final String password = Objects.requireNonNull(passwordInput.getText()).toString();
        final String anisetteServerUrl = this.getAnisetteServerUrlOrDefault();

        // Produces Anisette on this device when it can, so the login is not relayed through a
        // public Anisette server. It decides for itself whether it is usable, and the Python
        // side falls back to anisetteServerUrl when it is not - so this can only improve on
        // the previous behaviour, never break it.
        var async = this.authService.login(
                    emailOrPhone, password, anisetteServerUrl, this.localAnisette)
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

                // **Not the end of the attempt.** Authentication worked and the exchange after
                // it did not, which is what an account with unaccepted terms does - and Apple
                // takes agreement on one of its own devices or on iCloud.com, so this user
                // generally has nowhere else to do it. Offered here rather than reported as a
                // dead end. Falls back to the ordinary error if no documents come back, which
                // is how "it was actually something else" is answered.
                if (error instanceof PythonAccountLoginException
                        && ((PythonAccountLoginException) error).hasTermsToAccept()) {
                    this.handleTermsPending((PythonAccountLoginException) error);
                    return;
                }

                // undo loading and allow user to try again, basically. Backwards, because
                // that is what it is: the step the user just left, handed back to them.
                this.hideLoading();
                this.showPage(R.id.login_maininfo_container, Direction.BACK);
                emailOrPhoneInput.setEnabled(true);
                passwordInput.setEnabled(true);
                loginButton.setClickable(true);

                FrameLayout loginErrorMessage = this.findViewById(R.id.login_error_container);
                loginErrorMessage.setVisibility(VISIBLE);

                TextView loginErrorText = this.findViewById(R.id.login_error_message_text);
                loginErrorText.setText(this.describeLoginFailure(error));
            });
    }

    /**
     * Fetch the terms Apple is waiting on and show the first of them.
     *
     * <p><b>Read-only.</b> Fetching records nothing; only {@link #onAgreeToTerms} writes, and
     * only for a document the user has been shown.
     *
     * <p>An empty list is not an error and not a bug - it is how "the sign-in stopped for some
     * other reason" is answered, since which failure means "terms pending" is not established.
     * That falls through to the ordinary error, whose wording says exactly that.
     */
    private void handleTermsPending(final PythonAccountLoginException termsFailure) {
        this.termsAccount = termsFailure.getAccount();
        this.showLoading(R.string.terms_fetching);

        var async = this.authService.pendingTerms(this.termsAccount)
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(documents -> {
                    if (documents.isEmpty()) {
                        Log.w(TAG, "Nothing was waiting on terms, so this was something else");
                        this.showLoginFailure(termsFailure);
                        return;
                    }

                    this.termsDocuments = documents;
                    this.termsIndex = 0;
                    this.showTermsStep(Direction.FORWARD);
                }, error -> {
                    Log.e(TAG, "Could not fetch the terms of service", error);
                    this.showLoginFailure(termsFailure);
                });
    }

    /** Put the document at {@link #termsIndex} on screen. */
    private void showTermsStep(final Direction direction) {
        final TermsDocument document = this.termsDocuments.get(this.termsIndex);

        this.hideLoading();
        this.setCurrentStepText(R.string.terms_title, direction);

        final TextView counter = this.findViewById(R.id.login_terms_counter);
        counter.setText(this.getString(R.string.terms_document_x_of_y,
                this.termsIndex + 1, this.termsDocuments.size()));
        // Hidden for a single document, where "Document 1 of 1" is noise on a screen that is
        // already asking somebody to read a contract.
        counter.setVisibility(this.termsDocuments.size() > 1 ? VISIBLE : GONE);

        final TextView text = this.findViewById(R.id.login_terms_text);
        text.setText(document.getText());
        // Back to the top for each document. Otherwise the second one opens scrolled to wherever
        // the first was left, which looks like part of it is missing.
        ((ScrollView) this.findViewById(R.id.login_terms_scroll)).scrollTo(0, 0);

        // A document Apple gave no agreement URL for cannot be accepted, and FindMy.py refuses
        // to send one. Said plainly, with the button disabled rather than left to fail.
        this.findViewById(R.id.login_terms_cannot_accept)
                .setVisibility(document.isCanAccept() ? GONE : VISIBLE);
        this.findViewById(R.id.login_terms_agree_button).setEnabled(document.isCanAccept());

        this.showPage(R.id.login_terms_container, direction);
    }

    /**
     * Record agreement to the document currently on screen, and move on.
     *
     * <p>Signing in is finished by the last one, and <b>only stored if it reached
     * {@code LOGGED_IN}</b>. Anything else fails every later fetch inside FindMy.py's own state
     * check, which is issues #43 and #119 - a map that never updates and a session signing out
     * does not repair.
     */
    private void onAgreeToTerms() {
        final TermsDocument document = this.termsDocuments.get(this.termsIndex);
        this.showLoading(R.string.terms_recording_agreement);

        var async = this.authService.acceptTerms(this.termsAccount, document.getPageId())
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(accepted -> {
                    if (accepted.hasMore()) {
                        this.termsIndex++;
                        this.showTermsStep(Direction.FORWARD);
                        return;
                    }

                    if (!accepted.isSignedIn()) {
                        Log.e(TAG, "Terms accepted but signing in ended at "
                                + accepted.getLoginState() + ", so nothing will be stored");
                        this.showLoginFailure(new PythonAccountLoginException(
                                this.getString(R.string.login_failed_terms),
                                PythonAccountLoginException.REASON_TERMS));
                        return;
                    }

                    Log.i(TAG, "Terms accepted and signing in completed");
                    this.getUiState().setAuthResponse(new PythonAuthResponse(
                            this.termsAccount, PythonAuthService.LOGIN_STATE.LOGGED_IN, null));
                    this.termsAccount = null;
                    this.termsDocuments = List.of();
                    this.handleIsAlreadyLoggedIn();
                }, error -> {
                    Log.e(TAG, "Could not record agreement to " + document.getPageId(), error);
                    this.showLoginFailure(error);
                });
    }

    /** Hand the sign-in step back to the user with the failure on it. */
    private void showLoginFailure(final Throwable error) {
        this.hideLoading();
        this.showPage(R.id.login_maininfo_container, Direction.BACK);

        this.findViewById(R.id.email_or_phone_input_field).setEnabled(true);
        this.findViewById(R.id.password_input_field).setEnabled(true);
        this.findViewById(R.id.login_button_main).setClickable(true);

        this.findViewById(R.id.login_error_container).setVisibility(VISIBLE);
        ((TextView) this.findViewById(R.id.login_error_message_text))
                .setText(this.describeLoginFailure(error));
    }

    /**
     * What to put on screen when a sign-in fails.
     *
     * <p><b>Never an empty message.</b> This used to be {@code login_failed_x} with
     * {@code getLocalizedMessage()}, and the most common real failure - a connection timeout -
     * carries no message at all, so the screen showed "Login failed:" and stopped. A person
     * cannot tell from that whether they typed their password wrong, whether Apple is down, or
     * whether the app is broken.
     *
     * <p>A recognised reason gets a translated sentence that says what to do. Anything else
     * falls back to the detail, which at least names the exception - unhelpful, but honest,
     * and better than a guess at a cause we have not established.
     */
    private String describeLoginFailure(final Throwable error) {
        final String reason = error instanceof PythonAccountLoginException
                ? ((PythonAccountLoginException) error).getReason()
                : PythonAccountLoginException.REASON_UNKNOWN;

        if (PythonAccountLoginException.REASON_NETWORK.equals(reason)) {
            return this.getString(R.string.login_failed_network);
        }

        // Reached when the terms path was tried and produced nothing to accept, so the sentence
        // says what Apple said and then that accepting terms will not fix something else -
        // rather than asserting a cause that has not been established.
        if (PythonAccountLoginException.REASON_TERMS.equals(reason)) {
            return this.getString(R.string.login_failed_terms);
        }

        final String detail = error.getLocalizedMessage();
        return detail == null || detail.isBlank()
                ? this.getString(R.string.login_failed_x, error.getClass().getSimpleName())
                : this.getString(R.string.login_failed_x, detail);
    }

    private void handleLoginResponse(PythonAuthResponse authResponse) {
        final PythonAuthService.LOGIN_STATE loginState = authResponse.getLoginState();
        Log.d(TAG, "Login state was " + loginState);
        this.getUiState().setAuthResponse(authResponse);

        this.showNextAuthPage(loginState, Direction.FORWARD);
    }

    private void showNextAuthPage(PythonAuthService.LOGIN_STATE loginState, Direction direction) {
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
                this.show2FAChoiceScreen(direction);
                break;
        }
    }

    private void show2FAChoiceScreen(Direction direction) {
        var state = this.getUiState();
        state.setCurrentPage(PAGE.CHOOSE_2FA);

        PythonAuthResponse authResponse = state.getAuthResponse();
        // determine which options should be shown:
        this.hideLoading();

        this.setCurrentStepText(R.string.two_factor_authentication, direction);
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

        // Shown last, so the options above are in place before it slides in rather than
        // appearing one by one on a view the user is already looking at.
        this.showPage(R.id.login_2fa_choice, direction);
    }

    public void onClick2FAWithTrustedDevice(View view) {
        var chosenAuthMethod = this.getUiState().getAuthResponse().getAuthMethods().stream()
                .filter(method -> method.getType() == TRUSTED_DEVICE)
                .findFirst()
                .orElseThrow();

        this.getUiState().setChosenAuthMethod(chosenAuthMethod);

        this.hideCurrentPage(Direction.FORWARD);
        this.showLoading(R.string.requesting_code);

        var async = this.authService.requestCode(chosenAuthMethod)
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(() -> this.show2FACodeEntryTextbox(Direction.FORWARD),
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
        this.hideCurrentPage(Direction.FORWARD);
        this.showLoading(R.string.requesting_code);

        var async = this.authService.requestCode(phoneAuthMethod)
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(() -> this.show2FACodeEntryTextbox(Direction.FORWARD),
                error -> {
                    Log.e(TAG, "Error occurred when trying to request 2FA code to SMS for phone number " + phoneAuthMethod.getPhoneNumber(), error);
                    this.hideLoading();
                    Toast.makeText(this, this.getString(R.string.failed_to_request_code_please_try_again), LENGTH_LONG)
                            .show();
                });
    }

    private void showInitialWelcomeConfOptions(SETUP_STATUS setupStatus, Direction direction) {
        var state = this.getUiState();
        state.setCurrentPage(PAGE.SETUP);

        final UserSettings userSettings = this.getUserSettings();

        this.showPage(R.id.login_anisette_container, direction);

        MaterialAutoCompleteTextView urlTextInput = findViewById(R.id.anisetteServerUrl);

        final String currentAnisetteServerSelection = this.getAnisetteServerUrlOrDefault();
        urlTextInput.setText(currentAnisetteServerSelection);

        // The server field only exists on this screen for the case where signing in cannot
        // happen without it. Deciding that here, from what local Anisette actually said,
        // rather than leaving it permanently on or permanently off.
        SharedMainSettingsManager.applyLoginAnisetteFallback(
                this.findViewById(android.R.id.content),
                this.localAnisetteStatus,
                userSettings.hasChosenAnisetteMode()
                        && !userSettings.usesLocalAnisette(false));

        if (this.localAnisetteStatus.state() == AnisetteStatus.State.READY) {
            // Nothing is going to ask a server for anything, so testing one would be a network
            // request whose only possible effect is to block the button below it.
            this.setCurrentStepText(R.string.welcome, direction);
            this.binding.setAllowServerConfNext(true);
            return;
        }

        this.testAndSaveAnisetteUrl(currentAnisetteServerSelection);

        if (setupStatus == SETUP_STATUS.NO_SERVER_CONFIGURED) {
            this.setCurrentStepText(R.string.welcome, direction);
        } else {
            this.setCurrentStepText(R.string.choose_your_server, direction);

            this.sharedMainSettingsManager.showAnisetteTestStatus(ERROR);
            this.sharedMainSettingsManager.setAnisetteTextFieldError(
                    R.string.anisette_server_at_x_could_not_be_reached,
                    userSettings.getAnisetteServerUrl()
            );
        }
    }

    private void showAccountLoginAuthOptions(Direction direction) {
        this.showPage(R.id.login_maininfo_container, direction);

        this.setCurrentStepText(R.string.apple_account, direction);

        TextInputEditText emailOrPhoneInput = this.findViewById(R.id.email_or_phone_input_field);
        TextInputEditText passwordInput = this.findViewById(R.id.password_input_field);

        // **Attached once.** This method runs again every time the page is shown - coming back
        // from the 2FA step, from the terms step - and addTextChangedListener appends, so
        // without the guard each keystroke ends up firing a growing pile of identical watchers.
        if (!this.loginFieldWatchersAttached) {
            this.loginFieldWatchersAttached = true;

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

        // **Read what is already in the fields, rather than waiting to be told it changed.**
        //
        // A watcher only hears about edits made after it exists, and the email is prefilled in
        // onCreate - before this page is ever shown - when somebody is sent back here after
        // their session expired. So the watcher never saw it, `validEmailOrPhone` stayed false,
        // and typing the password could not enable the button no matter what was typed: the
        // screen showed a filled-in email beside a dead Sign in button, with nothing to explain
        // it and no way to proceed except retyping an address that was already correct.
        //
        // Seeding from the fields covers every way text can arrive without an edit event -
        // prefill, restored instance state, an autofill service - rather than special-casing
        // the one that was reported.
        this.recheckWhetherLoginIsPossible();
    }

    /** Whether {@link #showAccountLoginAuthOptions} has already added its field watchers. */
    private boolean loginFieldWatchersAttached = false;

    /**
     * Work out from the fields themselves whether signing in is possible, and update the button.
     *
     * <p>The same two rules the watchers apply, asked of the current contents instead of an edit.
     */
    private void recheckWhetherLoginIsPossible() {
        final String email = Optional.ofNullable(this.emailOrPhoneInput.getText())
                .map(CharSequence::toString).orElse("");
        final String password = Optional.ofNullable(this.passwordInput.getText())
                .map(CharSequence::toString).orElse("");

        this.getUiState().setValidEmailOrPhone(isEmailOrPhoneNumber(email));
        this.getUiState().setValidPassword(!password.isEmpty());

        this.updateLoginButtonState();
    }

    private void show2FACodeEntryTextbox(Direction direction) {
        this.getUiState().setCurrentPage(PAGE.ENTER_2FA_CODE);

        this.hideLoading();
        this.setCurrentStepText(R.string.two_factor_authentication, direction);

        this.showPage(R.id.login_2fa_container, direction);

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

        this.showAccountLoginAuthOptions(Direction.BACK);
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
        this.show2FAChoiceScreen(Direction.BACK);
    }

    /**
     * How many times a spent code has been waited out on this screen. Never reset on purpose:
     * two goes at it is the budget for one sign-in, not per code.
     */
    private int spentCodeRecoveries = 0;

    /** Cancelled if the screen goes away mid-wait, so a dead activity is not written to. */
    private final Handler waitingForApple = new Handler(Looper.getMainLooper());

    /**
     * Apple took the code and then failed. Wait, then ask for a new one - without a prompt.
     *
     * <p><b>There is no question worth asking, so none is asked.</b> Re-typing cannot work and
     * waiting is the only option, so a dialog would offer a choice between one real answer and a
     * wrong one. The exporter reached the same conclusion and has a test asserting the user is
     * never prompted.
     *
     * <p><b>The wait is shown counting down.</b> Two minutes of a still screen on a phone is
     * indistinguishable from a hang, and gets force-quit.
     *
     * <p><b>And the new code is requested after the wait, never before.</b> Both orders look
     * right in a diff; Apple's codes expire, so one fetched first is two minutes stale by the
     * time it is typed.
     *
     * <p>The Apple ID and password are not asked for again. The account is still in its
     * second-factor state, so requesting on the chosen method is all that is needed.
     */
    private void recoverFromApppleTakingTheCode(
            final FrameLayout errorBox, final TextView errorText) {

        final long wait = ACodeAppleAlreadyTook.waitBefore(this.spentCodeRecoveries);
        this.spentCodeRecoveries++;

        this.hideLoading();
        this.showPage(R.id.login_2fa_container, Direction.BACK);
        this.twoFactorEntryManager.clear();
        this.twoFactorAuthChoiceBackButton.setEnabled(true);
        errorBox.setVisibility(VISIBLE);

        if (wait < 0) {
            // Out of goes. Say whose fault it is, and warn about the password refusal - it
            // happened in the one observed recovery, and unwarned it reads as a second,
            // unrelated problem.
            Log.w(TAG, "Apple did not recover after waiting it out twice");
            errorText.setText(R.string.twofactor_apple_did_not_recover);
            return;
        }

        Log.i(TAG, "Apple took the code and then failed; waiting " + wait
                + "ms before asking for a new one");
        errorText.setText(R.string.twofactor_apple_took_the_code);
        this.countDownThenAskForANewCode(wait, errorBox, errorText);
    }

    private void countDownThenAskForANewCode(
            final long remaining, final FrameLayout errorBox, final TextView errorText) {
        if (this.isFinishing() || this.isDestroyed()) {
            return;
        }

        if (remaining <= 0) {
            final var chosen = this.getUiState().getChosenAuthMethod();
            if (chosen == null) {
                errorText.setText(R.string.twofactor_apple_did_not_recover);
                return;
            }

            var async = this.authService.requestCode(chosen)
                    .observeOn(AndroidSchedulers.mainThread())
                    .subscribe(
                            () -> {
                                // The box is already empty and the prompt above it still says
                                // where the code was sent, so the error line's job is done.
                                Log.i(TAG, "Asked Apple for a fresh code after the wait");
                                errorBox.setVisibility(GONE);
                            },
                            error -> {
                                Log.e(TAG, "Asking for a fresh code failed too", error);
                                errorText.setText(R.string.twofactor_apple_did_not_recover);
                            });
            return;
        }

        errorText.setText(this.getString(
                R.string.twofactor_waiting_seconds, (int) Math.ceil(remaining / 1000.0)));

        this.waitingForApple.postDelayed(
                () -> this.countDownThenAskForANewCode(remaining - 1000L, errorBox, errorText),
                1000L);
    }

    @Override
    protected void onDestroy() {
        // Or a countdown outlives the screen and writes to views that are gone.
        this.waitingForApple.removeCallbacksAndMessages(null);
        super.onDestroy();
    }

    private void on2FAAuthCodeFilled(final String authCode) {
        if (!REGEX_2FA_CODE.matcher(authCode).matches()) {
            Log.w(TAG, "2FA Auth code from callback was invalid: " + authCode);
            return;
        }

        this.showLoading(R.string.logging_in);
        this.hideCurrentPage(Direction.FORWARD); // for now: on error unhide

        final FrameLayout twoFactorErrorMessage = this.findViewById(R.id.verification_code_error_msg_container);
        final TextView errorMessageText = this.findViewById(R.id.verification_code_error_message);

        var chosenAuthMethod = this.getUiState().getChosenAuthMethod();

        var async = this.authService.submitCode(
                Objects.requireNonNull(chosenAuthMethod),
                authCode
        ).observeOn(AndroidSchedulers.mainThread())
        .subscribe(() -> {

            var nextAsync = this.authService.retrieveAuthData(this.getUiState().getAuthResponse())
                .flatMapCompletable(userAuthRepo::storeUserAuth)
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(() -> {
                    Log.i(TAG, "Retrieved login info after 2FA success and stored it successfully!");
                    this.sendToMapActivity();
                }, error -> {

                    Log.e(TAG, "Error during auth data retrieval and storage after 2FA success", error);
                    this.hideLoading();
                    this.showPage(R.id.login_2fa_container, Direction.BACK);
                    this.twoFactorEntryManager.clear();
                    this.twoFactorAuthChoiceBackButton.setEnabled(true);

                    // I don't think this error should really happen. Maybe there's some issue with the python backend in this case...
                    twoFactorErrorMessage.setVisibility(VISIBLE);
                    errorMessageText.setText(this.getString(R.string.error_occurred_please_retry_submitting_your_2fa_code));
                });

        }, error -> {
            // I really would like to handle this error separately from the one above, hence the nesting above.
            Log.e(TAG, "Failed to authenticate using auth code " + authCode, error);

            // **Apple taking the code and then failing is not a wrong code**, and must be told
            // apart before anything counts it as one. See ACodeAppleAlreadyTook: the submit does
            // two calls, the first succeeded, and the code is spent - so returning to the code
            // box is the one action guaranteed to fail, and the attempt counter would eventually
            // advise changing the Anisette server for a fault Anisette had no part in.
            if (ACodeAppleAlreadyTook.spentIt(error)) {
                this.recoverFromApppleTakingTheCode(twoFactorErrorMessage, errorMessageText);
                return;
            }

            var state = this.getUiState();
            final int failedLoginAttemptCount = state.getFailed2FAAttemptCount() + 1;
            state.setFailed2FAAttemptCount(failedLoginAttemptCount);

            this.hideLoading();
            this.showPage(R.id.login_2fa_container, Direction.BACK);
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
        var async = this.authService.retrieveAuthData(this.getUiState().getAuthResponse())
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

    /**
     * Whether this screen can get somebody signed in, and what it has to ask for first.
     *
     * <p>Local Anisette is asked first, because when it works none of the server questions
     * have a reason to be asked - and that is the normal case. Only when it cannot does this
     * fall back to the old behaviour of testing a server and, if that fails too, sending the
     * user to choose one.
     *
     * <p>The local check downloads and can talk to Apple, so it runs off the main thread. The
     * spinner {@link #handleAuth} shows covers it, and doing it here means the cost lands on
     * the welcome screen rather than on the login button.
     */
    private Observable<SETUP_STATUS> getAnisetteSetupStatus() {
        return Observable.fromCallable(() -> AnisetteStatus.of(this.localAnisette))
                .subscribeOn(Schedulers.io())
                .flatMap(status -> {
                    this.localAnisetteStatus = status;
                    Log.i(TAG, "local Anisette on the sign-in screen: " + status);

                    if (status.state() == AnisetteStatus.State.READY) {
                        return Observable.just(SETUP_STATUS.OK);
                    }
                    return this.getAnisetteServerSetupStatus();
                });
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

    /**
     * The Anisette server to fall back to, which may never have been chosen.
     *
     * <p>Before Anisette was produced on the device, nobody reached the login button without
     * passing a server test first, so the stored URL was always set by then. That is no longer
     * true: a sign-in that needs no server never visits that step, and reading the setting
     * without allowing for null would fail the login of exactly the people for whom everything
     * had gone right.
     */
    private String getAnisetteServerUrlOrDefault() {
        var properties = PropertiesUtil.getProperties(this.getAssets(), "app.properties");
        assert properties != null;

        return Optional.ofNullable(this.getUserSettings().getAnisetteServerUrl())
                .orElse(properties.getProperty("defaultAnisetteUrl"));
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

    private void updateMapProvider(final String newProvider) {
        // AMap needs a key the user supplies themselves, so selecting it here without one
        // would silently save a provider that can only render a blank map. Prompt instead,
        // and only apply the choice once a key exists.
        if ("amap".equals(newProvider) && !this.getUserSettings().hasAmapApiKey()) {
            AmapApiKeyDialog.show(this, this.getUserSettings().getAmapApiKey(), enteredKey -> {
                if (enteredKey == null) {
                    Toast.makeText(this, R.string.amap_key_required, Toast.LENGTH_LONG).show();
                    this.sharedMainSettingsManager.setupMapProviderField();
                    return;
                }
                this.getUserSettings().setAmapApiKey(enteredKey);
                this.getUserSettings().setMapProvider(newProvider);
                this.saveSettings();
                Log.i(TAG, "Updating app settings map provider to " + newProvider);
            });
            return;
        }

        this.getUserSettings().setMapProvider(newProvider);
        this.saveSettings();
        Log.i(TAG, "Updating app settings map provider to " + newProvider);
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
        this.recordHowThisSessionWasEstablished();

        this.model.resetUiState();
        this.finish();
        Intent intent = new Intent(this, MapsActivity.class);
        startActivity(intent);
    }

    /**
     * Write down what actually produced this session, now that there is one.
     *
     * <p>Without this, a brand-new sign-in leaves the mode unchosen - and "signed in with no
     * mode chosen" is exactly how somebody updating from an older version looks. So a person
     * who had just signed in using Anisette from their own phone was offered the chance to
     * switch to Anisette from their own phone, at the price of signing in again. It also left
     * Settings with nothing to show under "Anisette Provider", because the only thing it had
     * to show was a server URL that a local sign-in never sets.
     *
     * <p>This does not defeat the null-is-meaningful rule in {@link UserSettings}: null still
     * means nobody has decided, and someone who updated from a version without any of this
     * never ran this code, so they keep it and still get asked once.
     *
     * <p><b>Read, not guessed.</b> Which kind actually produced the session is decided inside
     * the Python sign-in, which consults local Anisette itself and falls back on its own - so
     * it records the answer, and this only copies it. Inferring it here from the status shown
     * when the screen opened would be wrong in precisely the interesting case: a sign-in that
     * began local and fell back part-way would be filed as local.
     */
    private void recordHowThisSessionWasEstablished() {
        if (this.localAnisette == null) {
            return;
        }

        final boolean establishedLocally = this.localAnisette.wasSessionEstablishedLocally();

        this.getUserSettings().setAnisetteMode(establishedLocally
                ? UserSettings.ANISETTE_LOCAL : UserSettings.ANISETTE_REMOTE);
        this.saveSettings();

        Log.i(TAG, "session established with "
                + (establishedLocally ? "local" : "remote") + " Anisette");
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
