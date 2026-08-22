package dev.wander.android.opentagviewer.ui.settings;

import static android.view.View.inflate;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.view.View;
import android.widget.TextView;
import android.widget.Toast;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.textfield.TextInputEditText;

import java.util.Optional;
import java.util.function.Consumer;

import dev.wander.android.opentagviewer.R;
import dev.wander.android.opentagviewer.util.android.PropertiesUtil;
import dev.wander.android.opentagviewer.util.android.SigningInfoUtil;
import dev.wander.android.opentagviewer.util.android.WebLink;

/**
 * Prompt for the user's own AMap API key.
 * <br>
 * Shared because AMap can be selected from two places - the first-run screen and Settings -
 * and both need the same gate. No key ships with the app: AMap issues them per developer
 * account, bound to a package name and signing fingerprint, and expects the key holder to
 * be the app's operator.
 */
public final class AmapApiKeyDialog {

    private AmapApiKeyDialog() {}

    /**
     * @param currentKey the key already stored, or null
     * @param onResult   receives the entered key, or null if the user cleared it. Not called
     *                   if the dialog is cancelled, so a cancelled dialog leaves the current
     *                   provider alone.
     */
    public static void show(
            final Activity activity,
            final String currentKey,
            final Consumer<String> onResult) {

        View view = inflate(activity, R.layout.amap_api_key_input_dialog, null);

        final TextInputEditText keyInput = view.findViewById(R.id.amapApiKey);
        final TextView details = view.findViewById(R.id.amap_registration_details);
        final MaterialButton copyButton = view.findViewById(R.id.amap_copy_registration_details);
        final MaterialButton guideButton = view.findViewById(R.id.amap_open_guide);

        keyInput.setText(Optional.ofNullable(currentKey).orElse(""));

        // Read from the installed package, so these stay correct for whichever build is in
        // the user's hand rather than a documented value that goes stale when signing changes.
        final String registrationDetails = SigningInfoUtil.getRegistrationDetails(activity);
        details.setText(registrationDetails);

        copyButton.setOnClickListener(v -> {
            var clipboard = (ClipboardManager) activity.getSystemService(Context.CLIPBOARD_SERVICE);
            clipboard.setPrimaryClip(ClipData.newPlainText("AMap registration details", registrationDetails));
            Toast.makeText(activity, R.string.amap_registration_details_copied, Toast.LENGTH_SHORT).show();
        });

        // Registering a key means working through a Chinese-language console and knowing to
        // supply the package name and fingerprint, so link the walkthrough.
        guideButton.setOnClickListener(v -> {
            var properties = PropertiesUtil.getProperties(activity.getAssets(), "app.properties");
            if (properties == null) {
                return;
            }
            // Through WebLink, which guards. This threw ActivityNotFoundException on a phone
            // with no browser - the aosp-atd emulator the instrumented suite runs on is one.
            WebLink.open(activity, properties.getProperty("amapWikiPage"));
        });

        new MaterialAlertDialogBuilder(activity)
                .setTitle(R.string.amap_api_key)
                .setView(view)
                .setPositiveButton(R.string.accept, (dialog, which) -> {
                    final String entered = Optional.ofNullable(keyInput.getText())
                            .map(CharSequence::toString)
                            .map(String::trim)
                            .orElse("");
                    onResult.accept(entered.isEmpty() ? null : entered);
                })
                .setNegativeButton(R.string.cancel, null)
                .show();
    }
}
