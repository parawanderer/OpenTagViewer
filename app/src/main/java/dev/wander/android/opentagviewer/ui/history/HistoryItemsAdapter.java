package dev.wander.android.opentagviewer.ui.history;

import static android.view.View.GONE;
import static android.view.View.VISIBLE;

import android.content.res.Resources;
import android.graphics.drawable.Drawable;
import android.location.Address;
import android.location.Geocoder;
import android.text.format.DateFormat;
import android.util.Log;
import android.util.Pair;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.core.content.res.ResourcesCompat;
import androidx.recyclerview.widget.RecyclerView;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

import dev.wander.android.opentagviewer.R;
import dev.wander.android.opentagviewer.data.model.BeaconLocationReport;
import dev.wander.android.opentagviewer.db.repo.model.UserSettings;
import io.reactivex.rxjava3.android.schedulers.AndroidSchedulers;
import io.reactivex.rxjava3.core.Observable;
import io.reactivex.rxjava3.schedulers.Schedulers;
import lombok.AllArgsConstructor;
import lombok.Getter;

public class HistoryItemsAdapter extends RecyclerView.Adapter<HistoryItemsAdapter.ViewHolder> {
    private static final String TAG = HistoryItemsAdapter.class.getSimpleName();

    private static final Map<String, List<Address>> GEOCODING_CACHE = new ConcurrentHashMap<>();

    private final List<BeaconLocationReport> locations;

    private final Set<Integer> selectedItems;
    private final Geocoder geocoder;
    private final @lombok.NonNull UserSettings userSettings;
    private final Consumer<ClickedItemInfo> onClickCallback;
    private final @lombok.NonNull Resources resources;


    public HistoryItemsAdapter(
            @lombok.NonNull Resources resources,
            @lombok.NonNull Geocoder geocoder,
            @lombok.NonNull List<BeaconLocationReport> locations,
            @lombok.NonNull UserSettings userSettings,
            @lombok.NonNull Set<Integer> selectedItems,
            @lombok.NonNull Consumer<ClickedItemInfo> onClickCallback
    ) {
        this.resources = resources;
        this.locations = locations;
        this.geocoder = geocoder;
        this.selectedItems = selectedItems;
        this.userSettings = userSettings;
        this.onClickCallback = onClickCallback;
    }

    @Getter
    public static class ViewHolder extends RecyclerView.ViewHolder {
        private final LinearLayout container;
        private final TextView locationName;
        private final TextView locationDetail;
        private final TextView locationTime;
        private final ImageView locationHistoryTile;

        public ViewHolder(@NonNull View itemView) {
            super(itemView);
            this.container = itemView.findViewById(R.id.history_item_clickable_container);
            this.locationName = itemView.findViewById(R.id.history_item_location_name);
            this.locationDetail = itemView.findViewById(R.id.history_item_location_detail);
            this.locationTime = itemView.findViewById(R.id.history_item_location_time);
            this.locationHistoryTile = itemView.findViewById(R.id.location_history_tile);
        }
    }

    // Create new views (invoked by the layout manager)
    @Override
    public ViewHolder onCreateViewHolder(ViewGroup viewGroup, int viewType) {
        // Create a new view, which defines the UI of the list item
        View view = LayoutInflater.from(viewGroup.getContext())
                .inflate(R.layout.history_list_item, viewGroup, false);

        return new ViewHolder(view);
    }

    // Replace the contents of a view (invoked by the layout manager)
    @Override
    public void onBindViewHolder(ViewHolder viewHolder, final int position) {

        // Get element from your dataset at this position and replace the
        // contents of the view with that element

        final BeaconLocationReport item = this.locations.get(position);

        if (this.userSettings.getEnableDebugData() == Boolean.TRUE) {
            viewHolder.getLocationDetail().setVisibility(VISIBLE);
            viewHolder.getLocationDetail().setText(String.format(
                    Locale.ROOT,
                    "coords:%.6f,%.6f, desc:%s, status:%d, conf:%d, acc:%d",
                    item.getLatitude(),
                    item.getLongitude(),
                    item.getDescription(),
                    item.getStatus(),
                    item.getConfidence(),
                    item.getHorizontalAccuracy()
            ));
        } else {
            viewHolder.getLocationDetail().setVisibility(GONE);
        }

        Drawable tile;
        if (position < this.locations.size() - 1) {
            tile = ResourcesCompat.getDrawable(resources,
                    this.selectedItems.contains(position) ? R.drawable.pin_drop_tile_pin_filled
                        : R.drawable.pin_drop_tile_empty_filled,
                    null);
        } else {
            // last item gets special icon tile
            tile = ResourcesCompat.getDrawable(resources,
                    this.selectedItems.contains(position) ? R.drawable.pin_drop_tile_pin_filled_bottom
                            : R.drawable.pin_drop_tile_empty_filled_bottom,
                    null);
        }
        viewHolder.getLocationHistoryTile().setImageDrawable(tile);

        var format = DateFormat.getBestDateTimePattern(Locale.getDefault(), "hh:mm:ss");
        var timestampFormat = new SimpleDateFormat(format, Locale.getDefault());

        viewHolder.getLocationTime().setText(timestampFormat.format(new Date(item.getTimestamp())));

        // setup onclick handling for this item
        viewHolder.getContainer().setOnClickListener(v -> {
            this.onClickCallback.accept(new ClickedItemInfo(item, viewHolder.getLocationName().getText().toString(), position));
        });



        var cachedLocation = this.getCachedGeocode(item.getLatitude(), item.getLongitude());
        if (cachedLocation != null) {
            viewHolder.getLocationName().setText(convertLocationToUIName(cachedLocation));
        } else {
            viewHolder.getLocationName().setText(
                    String.format(Locale.ROOT, "%.6f, %.6f", item.getLatitude(), item.getLongitude()));

            var async = this.reverseGeocode(item.getLatitude(), item.getLongitude())
                    .observeOn(AndroidSchedulers.mainThread())
                    .subscribe(geocodingResults -> {
                        try {
                            if (geocodingResults.isEmpty()) return;

                            var geocodingLocation = geocodingResults.get(0);
                            var locationAddr = geocodingLocation.getAddressLine(0);

                            viewHolder.getLocationName().setText(locationAddr);

                        } catch (Exception e) {
                            Log.e(TAG, "Error updating reverse geocoded location", e);
                        }
                    }, error -> Log.e(TAG, "Error reverse geocoding location: " + item.getLatitude() + ", " + item.getLongitude(), error));
        }
    }

    private List<Address> getCachedGeocode(double latitude, double longitude) {
        final String key = String.format(Locale.ROOT, "%.4f,%.4f", latitude, longitude);
        var cached = GEOCODING_CACHE.get(key);
        if (cached != null) {
            Log.d(TAG, "Got geocoding data for " + key + " (rounded) from cache!");
            return cached;
        }
        return null;
    }

    private static String convertLocationToUIName(@lombok.NonNull List<Address> geocodeData) {
        var geocodingLocation = geocodeData.get(0);
        return geocodingLocation.getAddressLine(0);
    }

    private Observable<List<Address>> reverseGeocode(double latitude, double longitude) {
        return Observable.fromCallable(() -> {
                    final String key = String.format(Locale.ROOT, "%.4f,%.4f", latitude, longitude);
                    Log.d(TAG, "Fetching geocoding data for " + key + " (rounded)...");
                    var result = this.geocoder.getFromLocation(latitude, longitude, 1);
                    GEOCODING_CACHE.put(key, result);
                    return result;
                })
                .subscribeOn(Schedulers.io());
    }

    // Return the size of your dataset (invoked by the layout manager)
    @Override
    public int getItemCount() {
        return this.locations.size();
    }

    @Getter
    @AllArgsConstructor
    public static class ClickedItemInfo {
        private final BeaconLocationReport beaconLocationReport;
        private final String geocodedLocationName;
        private final int index;
    }
}
