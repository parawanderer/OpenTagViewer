package dev.wander.android.opentagviewer;

import android.Manifest;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.activity.OnBackPressedCallback;
import androidx.annotation.NonNull;
import androidx.annotation.VisibleForTesting;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.color.MaterialColors;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import dev.wander.android.opentagviewer.ble.BlePermissions;
import dev.wander.android.opentagviewer.db.datastore.UserAuthDataStore;
import dev.wander.android.opentagviewer.db.datastore.UserSettingsDataStore;
import dev.wander.android.opentagviewer.db.repo.KeychainMembershipRepository;
import dev.wander.android.opentagviewer.db.repo.UserSettingsRepository;
import dev.wander.android.opentagviewer.db.repo.model.UserSettings;
import dev.wander.android.opentagviewer.onboarding.OnboardingRecord;
import dev.wander.android.opentagviewer.ui.compat.WindowPaddingUtil;
import dev.wander.android.opentagviewer.ui.login.StepTransition;
import dev.wander.android.opentagviewer.update.UpdateSettings;
import dev.wander.android.opentagviewer.util.android.AppCryptographyUtil;
import io.reactivex.rxjava3.android.schedulers.AndroidSchedulers;
import io.reactivex.rxjava3.core.Completable;
import io.reactivex.rxjava3.disposables.CompositeDisposable;
import io.reactivex.rxjava3.schedulers.Schedulers;

/**
 * The steps after a first sign-in, before the map: get your tags, nearby tags and location,
 * and update checks. @parawanderer asked for these in place of popups on the map.
 *
 * <p><b>Its own screen, opened by the sign-in screen as its last step.</b> AppleLoginActivity's
 * steps all serve getting a session, and its state model has no place for "signed in, still
 * here"; to the user this reads as the sign-in carrying on.
 *
 * <p>Every step is skippable, and a step with nothing to ask is left out: an account that is
 * already linked, permissions already granted, an update choice already made. Shown once per
 * install ({@link OnboardingRecord}).
 */
public class OnboardingActivity extends AppCompatActivity {
    private static final String TAG = OnboardingActivity.class.getSimpleName();
    private static final int PERMISSIONS_REQUEST_CODE = 41;

    @VisibleForTesting
    enum Step { TAGS, NEARBY, UPDATES }

    /**
     * Tests only: these steps instead of the ones decided from the device. The test runner
     * grants every permission up front, so without this the nearby step never shows there.
     */
    @VisibleForTesting
    static List<Step> forcedSteps = null;

    private final CompositeDisposable inFlight = new CompositeDisposable();
    private final List<Step> steps = new ArrayList<>();
    private int current = -1;

    /** Whether to open this rather than the map after a sign-in. */
    public static boolean isDue(final Context context) {
        return !new OnboardingRecord(context).done();
    }

    @Override
    protected void onCreate(final Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        this.setContentView(R.layout.activity_onboarding);
        WindowPaddingUtil.insetForSystemBars(this.findViewById(R.id.onboarding_root));
        if (this.getSupportActionBar() != null) {
            this.getSupportActionBar().hide();
        }
        new OnboardingRecord(this).markShown();
        // Back skips the rest rather than leaving the app at a blank step.
        this.getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                OnboardingActivity.this.toTheMap(false);
            }
        });
        this.decideTheSteps();
    }

    private void decideTheSteps() {
        // Read off the main thread: the membership store is decrypted on read.
        this.inFlight.add(this.memberships().get()
                .firstOrError()
                .map(held -> held.isPresent())
                .onErrorReturnItem(false)
                .subscribeOn(Schedulers.io())
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(linked -> {
                    if (forcedSteps != null) {
                        this.steps.addAll(forcedSteps);
                        this.next();
                        return;
                    }
                    if (!this.hasEverything(this.permissionsToAsk())) {
                        this.steps.add(Step.NEARBY);
                    }
                    if (new UpdateSettings(this).enabled() == null) {
                        this.steps.add(Step.UPDATES);
                    }
                    // **Last, because connecting ends Setup.** The iCloud flow needs the signed-in
                    // account restored, and only the map does that: started from here it found
                    // no account and closed at once. So "Connect" opens the map and asks it to
                    // start the flow (MapsActivity.EXTRA_CONNECT_ICLOUD).
                    if (!linked) {
                        this.steps.add(Step.TAGS);
                    }
                    this.next();
                }));
    }

    private void next() {
        this.current++;
        if (this.current >= this.steps.size()) {
            this.toTheMap(false);
            return;
        }
        this.show(this.steps.get(this.current));
    }

    private void show(final Step step) {
        final MaterialButton primary = this.findViewById(R.id.onboarding_primary_button);
        final MaterialButton skip = this.findViewById(R.id.onboarding_skip_button);
        final ImageView icon = this.findViewById(R.id.onboarding_icon);
        final TextView title = this.findViewById(R.id.onboarding_title);
        final TextView body = this.findViewById(R.id.onboarding_body);

        this.drawStepper();

        switch (step) {
            case TAGS:
                icon.setImageResource(R.drawable.devices_24px);
                title.setText(R.string.onboarding_tags_title);
                body.setText(R.string.onboarding_tags_body);
                primary.setText(R.string.icloud_link_connect_button);
                primary.setOnClickListener(v -> this.toTheMap(true));
                skip.setText(R.string.onboarding_later);
                break;
            case NEARBY:
                icon.setImageResource(R.drawable.bluetooth_24px);
                title.setText(R.string.onboarding_nearby_title);
                body.setText(R.string.onboarding_nearby_body);
                primary.setText(R.string.onboarding_allow);
                primary.setOnClickListener(v -> ActivityCompat.requestPermissions(
                        this, this.permissionsToAsk(), PERMISSIONS_REQUEST_CODE));
                skip.setText(R.string.onboarding_later);
                break;
            case UPDATES:
            default:
                icon.setImageResource(R.drawable.system_update_24px);
                title.setText(R.string.onboarding_updates_title);
                body.setText(R.string.updates_explained);
                primary.setText(R.string.onboarding_updates_yes);
                primary.setOnClickListener(v -> {
                    new UpdateSettings(this).setEnabled(true);
                    this.next();
                });
                skip.setText(R.string.onboarding_updates_no);
                break;
        }
        skip.setOnClickListener(v -> {
            if (step == Step.UPDATES) {
                // An answer, so Settings says "off" rather than offering it again.
                new UpdateSettings(this).setEnabled(false);
            }
            this.next();
        });

        StepTransition.enter(this.findViewById(R.id.onboarding_step),
                this.current == 0 ? StepTransition.Direction.NONE
                        : StepTransition.Direction.FORWARD);
    }

    /**
     * Numbered circles joined by lines: done ones filled with a check, the current one filled,
     * the rest outlined. A line is filled once the step before it is done, so the row reads as
     * progress. Drawn in code from the theme's colours, so it follows dark mode and wallpaper
     * colours like everything else. Read out as "Step 1 of 3".
     */
    private void drawStepper() {
        final LinearLayout stepper = this.findViewById(R.id.onboarding_stepper);
        stepper.removeAllViews();
        stepper.setContentDescription(this.getString(
                R.string.onboarding_step, this.current + 1, this.steps.size()));

        final int primary = MaterialColors.getColor(stepper, androidx.appcompat.R.attr.colorPrimary);
        final int onPrimary = MaterialColors.getColor(stepper,
                com.google.android.material.R.attr.colorOnPrimary);
        final int outline = MaterialColors.getColor(stepper,
                com.google.android.material.R.attr.colorOutline);
        final int quiet = MaterialColors.getColor(stepper,
                com.google.android.material.R.attr.colorOnSurfaceVariant);
        final float dp = this.getResources().getDisplayMetrics().density;

        for (int i = 0; i < this.steps.size(); i++) {
            if (i > 0) {
                final View line = new View(this);
                line.setBackgroundColor(i <= this.current ? primary : outline);
                stepper.addView(line, new LinearLayout.LayoutParams(
                        Math.round(40 * dp), Math.round(2 * dp)));
            }
            final boolean done = i < this.current;
            final boolean reached = i <= this.current;

            final GradientDrawable circle = new GradientDrawable();
            circle.setShape(GradientDrawable.OVAL);
            if (reached) {
                circle.setColor(primary);
            } else {
                circle.setColor(Color.TRANSPARENT);
                circle.setStroke(Math.round(2 * dp), outline);
            }

            final TextView number = new TextView(this);
            number.setBackground(circle);
            number.setGravity(Gravity.CENTER);
            number.setTextColor(reached ? onPrimary : quiet);
            number.setTextSize(14);
            number.setTypeface(number.getTypeface(), Typeface.BOLD);
            number.setText(done ? "✓" : String.valueOf(i + 1));
            number.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
            final int size = Math.round(28 * dp);
            stepper.addView(number, new LinearLayout.LayoutParams(size, size));
        }
    }

    /** Bluetooth for nearby tags and ringing, location for the blue dot. Asked together. */
    private String[] permissionsToAsk() {
        final List<String> all = new ArrayList<>(Arrays.asList(BlePermissions.required()));
        all.add(Manifest.permission.ACCESS_FINE_LOCATION);
        all.add(Manifest.permission.ACCESS_COARSE_LOCATION);
        return all.stream().distinct().toArray(String[]::new);
    }

    private boolean hasEverything(final String[] permissions) {
        for (final String permission : permissions) {
            if (ContextCompat.checkSelfPermission(this, permission)
                    != PackageManager.PERMISSION_GRANTED) {
                return false;
            }
        }
        return true;
    }

    @Override
    public void onRequestPermissionsResult(final int requestCode,
                                           @NonNull final String[] permissions,
                                           @NonNull final int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == PERMISSIONS_REQUEST_CODE) {
            // Granted or not: the answer was Android's to collect, and the map copes with both.
            this.next();
        }
    }

    /**
     * Off to the map, with its iCloud popup marked as made: the tags step was that offer.
     * Written before the map starts, or it reads the old value and offers again.
     *
     * @param connectICloud whether the map should start the iCloud flow once it has the account
     */
    private void toTheMap(final boolean connectICloud) {
        final UserSettingsRepository settings =
                new UserSettingsRepository(UserSettingsDataStore.getInstance(this.getApplicationContext()));
        this.inFlight.add(Completable.defer(() -> {
                    final UserSettings stored = settings.getUserSettings();
                    stored.setIcloudOfferMade(true);
                    return settings.storeUserSettings(stored);
                })
                .subscribeOn(Schedulers.io())
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(() -> this.openTheMap(connectICloud), error -> {
                    Log.w(TAG, "Could not mark the iCloud offer as made; the map may offer it", error);
                    this.openTheMap(connectICloud);
                }));
    }

    private void openTheMap(final boolean connectICloud) {
        this.startActivity(new Intent(this, MapsActivity.class)
                .putExtra(MapsActivity.EXTRA_CONNECT_ICLOUD, connectICloud));
        this.finish();
    }

    private KeychainMembershipRepository memberships() {
        return new KeychainMembershipRepository(
                UserAuthDataStore.getInstance(this.getApplicationContext()),
                new AppCryptographyUtil());
    }

    @Override
    protected void onDestroy() {
        this.inFlight.dispose();
        super.onDestroy();
    }
}
