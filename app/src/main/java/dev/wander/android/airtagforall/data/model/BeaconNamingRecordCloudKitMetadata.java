package dev.wander.android.airtagforall.data.model;

import lombok.AllArgsConstructor;
import lombok.Data;

@AllArgsConstructor
@Data
public final class BeaconNamingRecordCloudKitMetadata {
    /**
     * Unix timestamp (MS)
     */
    private Long creationTime;
    /**
     * Unix timestamp (MS)
     */
    private Long modifiedTime;
    /**
     * Device name
     */
    private String modifiedByDevice;

    public Long getLastTime() {
        if (this.modifiedTime != null) {
            return this.modifiedTime;
        }
        return this.creationTime;
    }
}
