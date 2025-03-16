package dev.wander.android.airtagforall.ui.history;

import android.location.Address;
import android.location.Geocoder;
import android.text.format.DateFormat;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import dev.wander.android.airtagforall.R;
import dev.wander.android.airtagforall.data.model.BeaconLocationReport;
import io.reactivex.rxjava3.android.schedulers.AndroidSchedulers;
import io.reactivex.rxjava3.core.Observable;
import io.reactivex.rxjava3.schedulers.Schedulers;
import lombok.Getter;
import lombok.Setter;

public class HistoryItemsAdapter extends RecyclerView.Adapter<HistoryItemsAdapter.ViewHolder> {
    private static final String TAG = HistoryItemsAdapter.class.getSimpleName();

    private final List<BeaconLocationReport> locations;
    private final Geocoder geocoder;
    private static final Map<String, List<Address>> GEOCODING_CACHE = new ConcurrentHashMap<>();

    @Setter
    private int daysBack = 0;

    public HistoryItemsAdapter(@lombok.NonNull Geocoder geocoder, @lombok.NonNull List<BeaconLocationReport> locations) {
        this.locations = locations;
        this.geocoder = geocoder;
    }

    @Getter
    public static class ViewHolder extends RecyclerView.ViewHolder {
        private final TextView locationName;
        private final TextView locationDetail;
        private final TextView locationTime;

        public ViewHolder(@NonNull View itemView) {
            super(itemView);
            this.locationName = itemView.findViewById(R.id.history_item_location_name);
            this.locationDetail = itemView.findViewById(R.id.history_item_location_detail);
            this.locationTime = itemView.findViewById(R.id.history_item_location_time);
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

        // TODO:
        final BeaconLocationReport item = this.locations.get(position);
        viewHolder.getLocationName().setText(
                String.format(Locale.ROOT, "%.6f, %.6f", item.getLatitude(), item.getLongitude()));

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

        var format = DateFormat.getBestDateTimePattern(Locale.getDefault(), "hh:mm:ss");
        var timestampFormat = new SimpleDateFormat(format, Locale.getDefault());

        viewHolder.getLocationTime().setText(timestampFormat.format(new Date(item.getTimestamp())));

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

    private Observable<List<Address>> reverseGeocode(double latitude, double longitude) {
        return Observable.fromCallable(() -> {
                    final String key = String.format(Locale.ROOT, "%.4f,%.4f", latitude, longitude);
                    var cached = GEOCODING_CACHE.get(key);
                    if (cached != null) {
                        Log.d(TAG, "Got geocoding data for " + key + " (rounded) from cache!");
                        return cached;
                    }

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
}
