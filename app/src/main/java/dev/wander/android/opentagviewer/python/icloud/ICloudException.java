package dev.wander.android.opentagviewer.python.icloud;

import lombok.Getter;

/**
 * A step of the iCloud flow reporting why it did not work.
 *
 * <p>An exception rather than a result type because these travel back through RxJava, where a
 * failure belongs in {@code onError} - a screen that has to unwrap a success value to find out
 * it failed is a screen that will forget to.
 *
 * <p>Both halves are carried on purpose. {@link #getFailure()} is what the screen branches on,
 * so its wording stays in {@code strings.xml}. {@link #getDetail()} is what Python said, for the
 * log and for {@link ICloudFailure#UNKNOWN}, where there is nothing better to show - <b>and it is
 * never empty</b>, which is the whole reason the bridge returns failures as values instead of
 * letting exceptions cross the boundary with {@code str(e)} of nothing.
 */
@Getter
public class ICloudException extends RuntimeException {
    private final ICloudFailure failure;
    private final String detail;

    public ICloudException(final ICloudFailure failure, final String detail) {
        super(failure + ": " + detail);
        this.failure = failure;
        this.detail = detail;
    }
}
