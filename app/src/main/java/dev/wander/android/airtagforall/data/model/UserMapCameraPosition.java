package dev.wander.android.airtagforall.data.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.ToString;

@Builder
@AllArgsConstructor
@NoArgsConstructor
@Data
@ToString
public class UserMapCameraPosition {
    private float zoom;
    private double lat;
    private double lon;
}
