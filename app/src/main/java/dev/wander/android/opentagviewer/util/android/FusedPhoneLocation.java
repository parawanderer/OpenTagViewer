package dev.wander.android.opentagviewer.util.android;

import android.Manifest;
import android.annotation.SuppressLint;
import android.content.Context;
import android.content.pm.PackageManager;
import android.location.Location;
import android.util.Log;

import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;

import com.google.android.gms.location.FusedLocationProviderClient;
import com.google.android.gms.location.LocationServices;
import com.google.android.gms.tasks.Tasks;

import java.util.concurrent.TimeUnit;

/**
 * The real {@link PhoneLocation}: the cached fix Play services already holds.
 *
 * <p>Same client the map uses for its own blue dot, so this asks for nothing the app was not
 * already granted and starts no new location request.
 */
public class FusedPhoneLocation implements PhoneLocation {
    private static final String TAG = FusedPhoneLocation.class.getSimpleName();

    /**
     * How long to wait for a cached fix before giving up.
     *
     * <p>Short on purpose. {@code getLastLocation} answers from memory when there is anything to
     * answer with, so a wait longer than this means something is wrong rather than slow - and
     * this blocks a thread on the sighting path while it waits.
     */
    private static final long WAIT_MS = 2_000L;

    private final Context context;
    private final FusedLocationProviderClient client;

    public FusedPhoneLocation(final Context context) {
        this.context = context.getApplicationContext();
        this.client = LocationServices.getFusedLocationProviderClient(this.context);
    }

    @Nullable
    @Override
    @SuppressLint("MissingPermission")
    public Fix lastKnown() {
        if (!this.locationGranted()) {
            // Not an error: the map asks for this permission, and somebody who declined it has
            // said they do not want their position recorded. The sighting still records what it
            // heard, minus the position.
            return null;
        }

        try {
            final Location location =
                    Tasks.await(this.client.getLastLocation(), WAIT_MS, TimeUnit.MILLISECONDS);

            if (location == null) {
                return null;
            }

            // hasAccuracy() is false on a fix from a provider that does not report one. Zero
            // would then be written as "accurate to the metre", which is a stronger claim than
            // anything here can make - so it becomes the width of Bluetooth range instead, which
            // is what hearing the tag actually established.
            final long accuracy = location.hasAccuracy()
                    ? Math.round(location.getAccuracy())
                    : BLUETOOTH_RANGE_M;

            return new Fix(location.getLatitude(), location.getLongitude(), accuracy);
        } catch (final Exception e) {
            Log.d(TAG, "No cached location available for this sighting", e);
            return null;
        }
    }

    /**
     * The accuracy claimed when the system reports none of its own.
     *
     * <p>Hearing a Find My advertisement is itself a distance measurement of sorts: the tag was
     * in Bluetooth range. That is the weakest true statement available, so it is the right
     * fallback - see {@code NearbyTagLabel} on why RSSI cannot narrow it further.
     */
    private static final long BLUETOOTH_RANGE_M = 10L;

    private boolean locationGranted() {
        return ContextCompat.checkSelfPermission(this.context, Manifest.permission.ACCESS_FINE_LOCATION)
                == PackageManager.PERMISSION_GRANTED
                || ContextCompat.checkSelfPermission(this.context, Manifest.permission.ACCESS_COARSE_LOCATION)
                == PackageManager.PERMISSION_GRANTED;
    }
}
