package dev.wander.android.opentagviewer.ui.nearbydevices;

import android.content.res.Resources;
import android.text.format.DateUtils;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import java.util.ArrayList;
import java.util.List;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

import dev.wander.android.opentagviewer.R;
import dev.wander.android.opentagviewer.ble.FindMyAdvertisement;
import dev.wander.android.opentagviewer.ble.NearbyDeviceBook;
import dev.wander.android.opentagviewer.ble.NearbyDeviceSighting;
import dev.wander.android.opentagviewer.util.nearby.ProximityDistance;
import lombok.Getter;
import java.util.function.Function;

/**
 * The rows of the nearby-devices list: one per key prefix, name-or-prefix on top.
 *
 * <p>Backed by a snapshot list the activity swaps wholesale rather than by the book itself -
 * RecyclerView wants positions stable within a bind pass, and the book mutates on the scan
 * thread, so the activity hands over an immutable copy and only then notifies.
 */
public class NearbyDeviceListAdaptor
        extends RecyclerView.Adapter<NearbyDeviceListAdaptor.ViewHolder> {

    /**
     * Opacity for a row whose device stopped advertising: dimmed but still readable -
     * {@code colorControlHighlight}-like greys would fail contrast, so the whole row
     * including icon and text fades to roughly half instead.
     */
    private static final float GONE_ROW_ALPHA = 0.5f;

    /** Click: select the device (future radar). Long click: rename - carries the row view as anchor. */
    private final Consumer<NearbyDeviceSighting> onClickCallback;
    private final BiConsumer<View, NearbyDeviceSighting> onLongClickCallback;

    /** prefix → user-given name; consulted on every bind so a rename shows on the next pass. */
    private final Function<String, String> nameResolver;

    private final Resources resources;

    private final List<NearbyDeviceSighting> rows = new ArrayList<>();

    @Getter
    public static class ViewHolder extends RecyclerView.ViewHolder {
        private final FrameLayout container;
        private final TextView deviceName;
        private final TextView details;
        private final TextView mac;

        public ViewHolder(@NonNull final View itemView) {
            super(itemView);
            this.container = itemView.findViewById(R.id.nearby_device_item_container);
            this.deviceName = itemView.findViewById(R.id.nearby_item_device_name);
            this.details = itemView.findViewById(R.id.nearby_item_details);
            this.mac = itemView.findViewById(R.id.nearby_item_mac);
        }
    }

    public NearbyDeviceListAdaptor(
            @lombok.NonNull final Resources resources,
            @lombok.NonNull final Function<String, String> nameResolver,
            @lombok.NonNull final Consumer<NearbyDeviceSighting> onClickCallback,
            @lombok.NonNull final BiConsumer<View, NearbyDeviceSighting> onLongClickCallback) {
        this.resources = resources;
        this.nameResolver = nameResolver;
        this.onClickCallback = onClickCallback;
        this.onLongClickCallback = onLongClickCallback;
    }

    /** Replaces the whole snapshot; the caller notifies. */
    public void setRows(final List<NearbyDeviceSighting> sightings) {
        this.rows.clear();
        this.rows.addAll(sightings);
    }

    @NonNull
    @Override
    public ViewHolder onCreateViewHolder(@NonNull final ViewGroup parent, final int viewType) {
        final View view = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.nearby_device_list_item, parent, false);
        return new ViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull final ViewHolder holder, final int position) {
        final NearbyDeviceSighting sighting = this.rows.get(position);
        final boolean gone = NearbyDeviceBook.isGone(sighting, System.currentTimeMillis());

        final String givenName = this.nameResolver.apply(sighting.getKeyPrefix());
        final String title = givenName != null
                ? givenName
                : this.resources.getString(R.string.nearby_device_unnamed,
                        sighting.getKeyPrefix().substring(0, 4));
        holder.getDeviceName().setText(title);

        final List<String> parts = new ArrayList<>();
        if (gone) {
            // Distance, battery and owner-state would claim conditions from before the tag
            // left; the row reports only that it went quiet and when it was last heard.
            parts.add(this.resources.getString(R.string.nearby_device_offline));
        } else {
            // The details line is assembled from localized pieces: distance, battery,
            // owner-state, then how stale the newest hearing is.
            parts.add(this.resources.getString(R.string.nearby_device_distance_format,
                    ProximityDistance.describe(sighting.getRssi())));
            parts.add(this.resources.getString(batteryString(sighting.getBatteryLevel())));
            parts.add(this.resources.getString(sighting.getState() == FindMyAdvertisement.State.SEPARATED
                    ? R.string.nearby_device_separated
                    : R.string.nearby_device_with_owner));
        }
        parts.add(DateUtils.getRelativeTimeSpanString(
                sighting.getSeenAtMs(), System.currentTimeMillis(),
                DateUtils.SECOND_IN_MILLIS).toString());
        holder.getDetails().setText(String.join(" · ", parts));

        holder.getMac().setText(sighting.getMac());

        // Rows are recycled, so the dimming has to be set unconditionally on every bind.
        holder.getContainer().setAlpha(gone ? GONE_ROW_ALPHA : 1.0f);

        holder.getContainer().setOnClickListener(v -> this.onClickCallback.accept(sighting));
        holder.getContainer().setOnLongClickListener(v -> {
            this.onLongClickCallback.accept(v, sighting);
            return true;
        });
    }

    private static int batteryString(final FindMyAdvertisement.BatteryLevel level) {
        switch (level) {
            case MEDIUM: return R.string.battery_short_medium;
            case LOW: return R.string.battery_short_low;
            case VERY_LOW: return R.string.battery_short_very_low;
            case FULL:
            default: return R.string.battery_short_full;
        }
    }

    @Override
    public int getItemCount() {
        return this.rows.size();
    }
}
