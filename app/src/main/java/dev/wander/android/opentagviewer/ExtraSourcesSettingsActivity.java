package dev.wander.android.opentagviewer;

import android.os.Bundle;
import android.view.View;
import android.widget.EditText;

import androidx.appcompat.app.AppCompatActivity;
import androidx.databinding.DataBindingUtil;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import dev.wander.android.opentagviewer.databinding.ActivityExtraSourcesBinding;
import dev.wander.android.opentagviewer.source.ExtraSourcesApi;
import dev.wander.android.opentagviewer.source.ExtraSourcesSettings;
import dev.wander.android.opentagviewer.ui.compat.WindowPaddingUtil;

/**
 * Settings for the extra location source: googlefind (Google Find Hub trackers). Values are
 * saved when the screen is left, like the rest of Settings.
 */
public class ExtraSourcesSettingsActivity extends AppCompatActivity {
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private ActivityExtraSourcesBinding binding;
    private ExtraSourcesSettings settings;

    private static final String[] KEYS = {
            ExtraSourcesSettings.GOOGLE_URL, ExtraSourcesSettings.GOOGLE_TOKEN,
            ExtraSourcesSettings.GOOGLE_USER, ExtraSourcesSettings.GOOGLE_PASSWORD,
    };

    private EditText[] fields() {
        return new EditText[]{
                binding.extraSourcesGoogleUrl, binding.extraSourcesGoogleToken,
                binding.extraSourcesGoogleUser, binding.extraSourcesGooglePassword,
        };
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        this.settings = new ExtraSourcesSettings(this);

        this.binding = DataBindingUtil.setContentView(this, R.layout.activity_extra_sources);
        WindowPaddingUtil.insetForSystemBarsAndKeyboard(this.binding.getRoot());
        this.binding.setHandleClickBack(this::finish);
        if (this.getSupportActionBar() != null) {
            this.getSupportActionBar().hide();
        }

        final EditText[] fields = this.fields();
        for (int i = 0; i < KEYS.length; i++) fields[i].setText(this.settings.get(KEYS[i]));

        this.binding.extraSourcesTest.setOnClickListener(v -> this.test());
    }

    private void save() {
        final EditText[] fields = this.fields();
        for (int i = 0; i < KEYS.length; i++) this.settings.put(KEYS[i], fields[i].getText().toString());
    }

    @Override
    protected void onPause() {
        this.save();
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        this.io.shutdownNow();
        super.onDestroy();
    }

    /** Asks the service something cheap and says whether it answered. */
    private void test() {
        this.save();
        this.binding.extraSourcesTest.setEnabled(false);
        this.binding.extraSourcesTestProgress.setVisibility(View.VISIBLE);
        this.binding.extraSourcesTestResult.setText("");
        this.io.execute(() -> {
            final ExtraSourcesApi api = new ExtraSourcesApi(this.settings);
            final StringBuilder result = new StringBuilder();
            if (this.settings.isGoogleConfigured()) {
                try {
                    final int count = api.googleDevices().size();
                    result.append(getString(R.string.test_google_ok, count));
                } catch (final Exception e) {
                    result.append(getString(R.string.test_google_failed, e.getMessage()));
                }
            }
            final String text = result.toString().trim();
            runOnUiThread(() -> {
                this.binding.extraSourcesTest.setEnabled(true);
                this.binding.extraSourcesTestProgress.setVisibility(View.GONE);
                this.binding.extraSourcesTestResult.setText(
                        text.isEmpty() ? getString(R.string.test_nothing_configured) : text);
            });
        });
    }
}
