package dev.wander.android.airtagforall.ui.history;

import android.content.res.Resources;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import java.util.List;
import java.util.Locale;

import dev.wander.android.airtagforall.R;
import dev.wander.android.airtagforall.data.model.BeaconLocationReport;
import lombok.Getter;

public class HistoryItemsAdapter extends RecyclerView.Adapter<HistoryItemsAdapter.ViewHolder> {

    private final List<BeaconLocationReport> locations;
    private final Resources resources;

    public HistoryItemsAdapter(@lombok.NonNull Resources resources, @lombok.NonNull List<BeaconLocationReport> locations) {
        this.locations = locations;
        this.resources = resources;
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

        viewHolder.getLocationDetail().setText(item.getDescription());

        viewHolder.getLocationTime().setText(Long.toString(item.getTimestamp()));
    }

    // Return the size of your dataset (invoked by the layout manager)
    @Override
    public int getItemCount() {
        return this.locations.size();
    }
}
