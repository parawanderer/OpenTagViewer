package dev.wander.android.airtagforall.data.model;

import lombok.Builder;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.ToString;

/**
 * Main reference for this file is the python
 * class {@code LocationReport} from the {@code findmy.reports.reports} module.
 * <br><br>
 * See pip package <a href="https://pypi.org/project/FindMy/">FindMy</a> & Github project
 * <a href="https://github.com/malmeloo/FindMy.py">malmeloo/FindMy.py</a>
 *
 * <ul>
 *     <li>
 *         Current source link of {@code LocationReport}
 *         <a href="https://github.com/malmeloo/FindMy.py/blob/main/findmy/reports/reports.py#L28C7-L28C21">here</a></a>
 *          (this link may break in the future)
 *     </li>
 *     <li>
 *         Permalink to referenced version {@code 0.7.6} when implementing this
 *         <a href="https://github.com/malmeloo/FindMy.py/blob/bd6cea4b79b036d32893e0132ca4d551fb5fdb1b/findmy/reports/reports.py#L28C7-L28C21">here</a>
 *     </li>
 * </ul>
 */
@Builder
@Getter
@ToString
@EqualsAndHashCode
public class BeaconLocationReport {
    /**
     * The datetime when this report was published by a device.
     * UNIX timestamp MS.
     */
    private long publishedAt;
    /**
     * Description of the location report as published by Apple.
     */
    private String description;
    /**
     * The datetime when this report was recorded by a device.
     * UNIX timestamp MS.
     */
    private long timestamp;
    /**
     * Confidence of the location of this report. Int between 1 and 3.
     * <br><br>
     * <b>TODO: May be incorrect/broken!!! To be fixed</b>
     */
    private long confidence;
    /**
     * Latitude of the location of this report.
     */
    private double latitude;
    /**
     * Longitude of the location of this report.
     */
    private double longitude;
    /**
     * Horizontal accuracy of the location of this report.
     */
    private long horizontalAccuracy;
    /**
     * Status byte of the accessory as recorded by a device, as an integer.
     */
    private long status;
}
