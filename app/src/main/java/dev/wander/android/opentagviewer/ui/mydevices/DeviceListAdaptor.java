package dev.wander.android.opentagviewer.ui.mydevices;

import static android.view.View.GONE;
import static android.view.View.VISIBLE;

import android.content.Context;
import android.content.res.Resources;
import android.text.format.DateUtils;
import android.util.TypedValue;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.stream.Collectors;

import dev.wander.android.opentagviewer.R;
import dev.wander.android.opentagviewer.data.model.BeaconInformation;
import dev.wander.android.opentagviewer.ui.BeaconIcon;
import dev.wander.android.opentagviewer.data.model.BeaconLocationReport;
import lombok.Getter;

public class DeviceListAdaptor extends RecyclerView.Adapter<DeviceListAdaptor.ViewHolder> {
    private final List<BeaconInformation> beaconInfo;
    private final Map<String, BeaconLocationReport> locations;
    private final Resources resources;
    private final Consumer<BeaconInformation> onDeviceClickCallback;

    /**
     * Receives the pressed row along with its device, because the caller shows a menu anchored
     * to that row and therefore needs the view, not just the data.
     */
    private final BiConsumer<View, BeaconInformation> onDeviceLongClickCallback;

    /** Told how many rows are selected, so the contextual bar can say so. */
    private final Consumer<Integer> onSelectionChangedCallback;

    /**
     * The theme's own row background, resolved once.
     *
     * <p>Looked up rather than hard-coded because {@code ?attr/selectableItemBackground} cannot
     * be passed to {@code setBackgroundResource}, and re-resolving it per bind would mean a
     * theme lookup for every row on every scroll.
     */
    private final int selectableItemBackground;

    /**
     * Which rows are selected, by beacon id.
     *
     * <p>Keyed by id rather than by position because the list is mutated while a selection is
     * live - removing a device shifts every position after it, and a position-keyed selection
     * would silently start pointing at the wrong rows.
     */
    @Getter
    private final Set<String> selectedBeaconIds = new HashSet<>();

    /**
     * Whether the list is in selection mode.
     *
     * <p>Kept separate from {@code selectedBeaconIds.isEmpty()} so that deselecting the last
     * row leaves the contextual bar up rather than dropping the user out of the mode they are
     * plainly still using.
     */
    @Getter
    private boolean selectionMode = false;

    @Getter
    public static class ViewHolder extends RecyclerView.ViewHolder {
        private final FrameLayout container;
        private final TextView deviceName;
        private final TextView lastUpdated;
        private final TextView itemEmoji;
        private final ImageView itemImage;
        private final ImageView warningIcon;
        private final ImageView selectedCheck;

        public ViewHolder(@NonNull View itemView) {
            super(itemView);
            this.container = itemView.findViewById(R.id.device_item_container);
            this.deviceName = itemView.findViewById(R.id.list_item_device_name);
            this.lastUpdated = itemView.findViewById(R.id.list_item_last_update);
            this.itemEmoji = itemView.findViewById(R.id.list_item_emoji);
            this.itemImage = itemView.findViewById(R.id.list_item_image);
            this.warningIcon = itemView.findViewById(R.id.warning_icon);
            this.selectedCheck = itemView.findViewById(R.id.list_item_selected_check);
        }
    }

    public DeviceListAdaptor(
            @lombok.NonNull Context context,
            @lombok.NonNull Resources resources,
            @lombok.NonNull List<BeaconInformation> beaconInfo,
            @lombok.NonNull Map<String, BeaconLocationReport> locations,
            @lombok.NonNull Consumer<BeaconInformation> onDeviceClickCallback,
            @lombok.NonNull BiConsumer<View, BeaconInformation> onDeviceLongClickCallback,
            @lombok.NonNull Consumer<Integer> onSelectionChangedCallback) {
        this.resources = resources;
        this.beaconInfo = beaconInfo;
        this.locations = locations;
        this.onDeviceClickCallback = onDeviceClickCallback;
        this.onDeviceLongClickCallback = onDeviceLongClickCallback;
        this.onSelectionChangedCallback = onSelectionChangedCallback;

        TypedValue background = new TypedValue();
        context.getTheme().resolveAttribute(
                android.R.attr.selectableItemBackground, background, true);
        this.selectableItemBackground = background.resourceId;
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
        // **Both branches set both views, because these are recycled.** With only the emoji
        // branch, a row reused from a tag that had one kept showing that tag's emoji.
        if (beacon.isEmojiFilled()) {
            viewHolder.getItemEmoji().setText(beacon.getEmoji());
            viewHolder.getItemEmoji().setVisibility(VISIBLE);
            viewHolder.getItemImage().setVisibility(GONE);
        } else {
            BeaconIcon.applyTo(viewHolder.getItemImage(), beacon);
            viewHolder.getItemImage().setVisibility(VISIBLE);
            viewHolder.getItemEmoji().setVisibility(GONE);
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

        } else if (beacon.isIgnored()) {
            // **Not the same as "no last location known", though it looks identical.** That
            // line describes a tag nobody has walked past this week; this one has been searched
            // across months of history and answered nothing, so the app has stopped asking. A
            // user staring at the generic line has no way to tell those apart, and the second
            // one is the one they can act on - the tag page offers to look again.
            viewHolder.getLastUpdated().setText(R.string.tag_ignored_summary);

        } else {
            viewHolder.getLastUpdated().setText(R.string.no_last_location_known);
        }

        // **No warning triangle on either of these.** Both lines already say exactly what is
        // going on, in words, and the icon added alarm without adding information.
        //
        // It was also permanent for the people most likely to see it. The rows that carry these
        // lines are largely the user's own Apple hardware, read from their account - a MacBook,
        // an iPad - and those never report to the Find My network the way a tag does. So the
        // list showed a column of warnings about a situation that is not a fault, cannot be
        // fixed, and will still be there tomorrow. A warning that is always on is not a warning.
        viewHolder.getWarningIcon().setVisibility(GONE);

        final boolean isSelected = this.selectedBeaconIds.contains(beaconId);

        viewHolder.getSelectedCheck().setVisibility(
                this.selectionMode && isSelected ? VISIBLE : GONE);

        // Tinted only while selected. Setting a colour unconditionally would replace the
        // ripple that ?attr/selectableItemBackground provides, so the row would stop
        // responding to touch the way every other row in the app does.
        viewHolder.getContainer().setBackgroundResource(
                this.selectionMode && isSelected
                        ? R.drawable.device_list_item_selected
                        : this.selectableItemBackground);

        viewHolder.getContainer().setOnClickListener(v -> {
            if (this.selectionMode) {
                this.toggleSelection(beaconId);
                return;
            }
            this.onDeviceClickCallback.accept(beacon);
        });

        viewHolder.getContainer().setOnLongClickListener(v -> {
            this.onDeviceLongClickCallback.accept(v, beacon);
            // Consumed, so the row does not also fire its normal click.
            return true;
        });
    }

    /** Enters selection mode with one row already chosen, which is what a long press means. */
    public void startSelectionWith(final String beaconId) {
        this.selectionMode = true;
        this.selectedBeaconIds.clear();
        this.selectedBeaconIds.add(beaconId);
        this.notifyItemRangeChanged(0, this.getItemCount());
    }

    public void toggleSelection(final String beaconId) {
        if (!this.selectedBeaconIds.remove(beaconId)) {
            this.selectedBeaconIds.add(beaconId);
        }
        this.onSelectionChangedCallback.accept(this.selectedBeaconIds.size());

        final int position = this.positionOf(beaconId);
        if (position >= 0) {
            this.notifyItemChanged(position);
        }
    }

    public void clearSelection() {
        this.selectionMode = false;
        this.selectedBeaconIds.clear();
        this.notifyItemRangeChanged(0, this.getItemCount());
    }

    /** The selected beacons themselves, in the order they appear on screen. */
    public List<BeaconInformation> getSelectedBeacons() {
        return this.beaconInfo.stream()
                .filter(beacon -> this.selectedBeaconIds.contains(beacon.getBeaconId()))
                .collect(Collectors.toList());
    }

    private int positionOf(final String beaconId) {
        for (int i = 0; i < this.beaconInfo.size(); i++) {
            if (this.beaconInfo.get(i).getBeaconId().equals(beaconId)) {
                return i;
            }
        }
        return -1;
    }

    // Return the size of your dataset (invoked by the layout manager)
    @Override
    public int getItemCount() {
        return this.beaconInfo.size();
    }
}
