package dev.wander.android.opentagviewer.util.android;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.util.Log;
import android.widget.Toast;

import androidx.annotation.Nullable;

import dev.wander.android.opentagviewer.R;

/**
 * Opening a web page, and saying so when nothing can.
 *
 * <p><b>Nine places used to do this by hand, and eight of them failed silently.</b> The shape
 * they all had was {@code if (intent.resolveActivity(...) != null) startActivity(intent)} - so on
 * a phone with no browser the user tapped a link and <i>nothing happened at all</i>. No error, no
 * toast, no change of screen. That reads as a broken button, and it is the same failure the
 * "navigate to" button had before it was fixed for exactly this reason.
 *
 * <p>The ninth - the AMap key guide - had the opposite bug and no guard at all, so instead of
 * doing nothing it threw {@code ActivityNotFoundException}.
 *
 * <p>It is not a hypothetical device, either: the emulator image the instrumented suite runs on
 * ({@code aosp-atd}) has no browser, so this is the behaviour every UI test sees.
 *
 * <p><b>{@code https} is declared in {@code <queries>}</b>, without which {@code resolveActivity}
 * returns null on Android 11 and up whatever is installed - the same package-visibility trap
 * that hid every maps app. Do not remove it.
 */
public final class WebLink {

    private static final String TAG = WebLink.class.getSimpleName();

    private WebLink() {
    }

    /**
     * Open a URL in whatever the phone uses for the web.
     *
     * @param url the address. A null or blank one is a build configuration problem rather than
     *            anything the user did - it means a property is missing from
     *            {@code app.properties} - so it is logged and nothing is shown. Telling somebody
     *            "no app can open a web link" would be a lie in that case.
     * @return whether anything was actually opened, so a caller that wants to do something else
     *         instead can. Most do not care.
     */
    public static boolean open(final Context context, @Nullable final String url) {
        if (url == null || url.isBlank()) {
            Log.w(TAG, "Asked to open a link but there was no URL to open");
            return false;
        }

        final Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(url));

        if (intent.resolveActivity(context.getPackageManager()) == null) {
            Log.w(TAG, "Nothing on this device can open " + url);
            Toast.makeText(context, R.string.no_app_to_open_a_link_with, Toast.LENGTH_SHORT)
                    .show();
            return false;
        }

        context.startActivity(intent);
        return true;
    }
}
