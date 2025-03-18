package dev.wander.android.opentagviewer.ui.mydevices;

import static android.view.View.GONE;
import static android.view.View.VISIBLE;

import android.content.res.Resources;
import android.text.format.DateUtils;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Consumer;

import dev.wander.android.opentagviewer.R;
import dev.wander.android.opentagviewer.data.model.BeaconInformation;
import dev.wander.android.opentagviewer.data.model.BeaconLocationReport;
import lombok.Getter;

public class DeviceListAdaptor extends RecyclerView.Adapter<DeviceListAdaptor.ViewHolder> {
    private final List<BeaconInformation> beaconInfo;
    private final Map<String, BeaconLocationReport> locations;
    private final Resources resources;
    private final Consumer<BeaconInformation> onDeviceClickCallback;

    @Getter
    public static class ViewHolder extends RecyclerView.ViewHolder {
        private final FrameLayout container;
        private final TextView deviceName;
        private final TextView lastUpdated;
        private final TextView itemEmoji;
        private final ImageView itemImage;
        private final ImageView warningIcon;

        public ViewHolder(@NonNull View itemView) {
            super(itemView);
            this.container = itemView.findViewById(R.id.device_item_container);
            this.deviceName = itemView.findViewById(R.id.list_item_device_name);
            this.lastUpdated = itemView.findViewById(R.id.list_item_last_update);
            this.itemEmoji = itemView.findViewById(R.id.list_item_emoji);
            this.itemImage = itemView.findViewById(R.id.list_item_image);
            this.warningIcon = itemView.findViewById(R.id.warning_icon);
        }
    }

    public DeviceListAdaptor(
            @lombok.NonNull Resources resources,
            @lombok.NonNull List<BeaconInformation> beaconInfo,
            @lombok.NonNull Map<String, BeaconLocationReport> locations,
            @lombok.NonNull Consumer<BeaconInformation> onDeviceClickCallback) {
        this.resources = resources;
        this.beaconInfo = beaconInfo;
        this.locations = locations;
        this.onDeviceClickCallback = onDeviceClickCallback;
    }

    // Create new views (invoked by the layout manager)
    @Override
    public ViewHolder onCreateViewHolder(ViewGroup viewGroup, int viewType) {
        // Create a new view, which defines the UI of the list item
        View view = LayoutInflater.from(viewGroup.getContext())
                .inflate(R.layout.my_device_list_item, viewGroup, false);

        return new ViewHolder(view);
    }

    // Replace the contents of a view (invoked by the layout manager)
    @Override
    public void onBindViewHolder(ViewHolder viewHolder, final int position) {

        // Get element from your dataset at this position and replace the
        // contents of the view with that element
        // viewHolder.getDeviceNameView().setText(localDataSet[position]);

        final BeaconInformation beacon = this.beaconInfo.get(position);
        final String beaconId = beacon.getBeaconId();

        viewHolder.getDeviceName().setText(beacon.getName());
        if (beacon.getEmoji() != null && !beacon.getEmoji().isBlank()) {
            viewHolder.getItemEmoji().setText(beacon.getEmoji());
            viewHolder.getItemEmoji().setVisibility(VISIBLE);
            viewHolder.getItemImage().setVisibility(GONE);
        }

        // locations?
        final long now = System.currentTimeMillis();
        if (this.locations.containsKey(beaconId)) {
            var lastLocation = Objects.requireNonNull(this.locations.get(beaconId));

            final var timeAgo = DateUtils.getRelativeTimeSpanString(
                    lastLocation.getTimestamp(),
                    now,
                    DateUtils.MINUTE_IN_MILLIS
            ).toString();

            viewHolder.getLastUpdated().setText(this.resources.getString(R.string.last_updated_x, timeAgo));

        } else {
            viewHolder.getLastUpdated().setText(R.string.no_last_location_known);
            viewHolder.getWarningIcon().setVisibility(VISIBLE);
        }

        viewHolder.getContainer().setOnClickListener(v -> {
            this.onDeviceClickCallback.accept(beacon);
        });
    }

    // Return the size of your dataset (invoked by the layout manager)
    @Override
    public int getItemCount() {
        return this.beaconInfo.size();
    }
}
