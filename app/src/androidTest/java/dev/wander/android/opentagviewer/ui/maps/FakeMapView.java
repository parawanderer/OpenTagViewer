package dev.wander.android.opentagviewer.ui.maps;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.util.TypedValue;
import android.view.View;

import java.util.List;
import java.util.Locale;

/**
 * Draws what {@link FakeMapProvider} was asked to place, so a screenshot shows something.
 *
 * <p><b>Why this exists.</b> The fake recorded markers and drew nothing, which is right for an
 * assertion and useless for an eye. Every screenshot of the map screen was a white rectangle
 * with the tag cards floating over it, and a run in slow motion - the thing somebody watches
 * when they want to see the app work - showed cards sliding across a void. The pin is the whole
 * point of that screen and it was the one thing not on it.
 *
 * <p><b>It is not a map, and it says so.</b> There is no tile source and there never will be:
 * the managed device has no network worth relying on and no Play Services, and a fake that
 * looked convincing would be worse than one that does not - somebody would eventually read a
 * screenshot from a test as evidence about a real place. So: flat ground, a graticule, and a
 * caption naming it as a fake along with the camera it was drawn from. A reader can tell at a
 * glance both that this is synthetic and where the app thought it was looking.
 *
 * <p><b>The projection is honest about being crude.</b> Equirectangular around the camera,
 * scaled the way web maps scale - 256 pixels per tile, doubling per zoom level. It is wrong in
 * the way every flat projection is wrong, and at the zooms this app uses the error is far below
 * a pixel. What it gets right is the part that matters here: two reports a hundred metres apart
 * are drawn a hundred metres apart, so a pin in the wrong place looks wrong.
 *
 * <p><b>Nothing asserts on what this paints.</b> It is decoration for the human, and the
 * assertions still read {@link FakeMapProvider#markers()}. That separation is deliberate - see
 * the note in {@code AGENTS.md} about a screenshot not being an assertion - and it is why this
 * class can be as rough as it likes without any test becoming a test of the fake.
 */
final class FakeMapView extends View {

    /** Web-map convention: one 256px tile spans the world at zoom 0. */
    private static final double TILE_SIZE = 256.0;

    private static final float GRID_SPACING_DP = 48f;

    private final FakeMapProvider provider;

    private final Paint ground = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint grid = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint line = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint defaultPin = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint caption = new Paint(Paint.ANTI_ALIAS_FLAG);

    FakeMapView(final Context context, final FakeMapProvider provider) {
        super(context);
        this.provider = provider;

        // Resolved from the drawing context, not from fixed colours, so this follows the theme
        // into dark mode and into a dynamic-colour scheme exactly as the pins themselves do.
        final int ink = MarkerPalette.icon(context);

        this.ground.setColor(MarkerPalette.fill(context));
        this.ground.setStyle(Paint.Style.FILL);

        this.grid.setColor(withAlpha(ink, 0x22));
        this.grid.setStrokeWidth(dp(1));
        this.grid.setStyle(Paint.Style.STROKE);

        this.line.setStyle(Paint.Style.STROKE);
        this.line.setStrokeCap(Paint.Cap.ROUND);
        this.line.setStrokeJoin(Paint.Join.ROUND);

        // The on-surface colour, which Material guarantees contrasts with the ground this is
        // painted on - in either theme. The marker's own colour is deliberately not consulted.
        this.defaultPin.setColor(ink);
        this.defaultPin.setStyle(Paint.Style.FILL);

        this.caption.setColor(withAlpha(ink, 0x99));
        this.caption.setTextSize(dp(11));
    }

    @Override
    protected void onDraw(final Canvas canvas) {
        super.onDraw(canvas);

        final IMapProvider.CameraPosition camera = this.provider.whereTheCameraIs();
        final double pixelsPerDegree = TILE_SIZE * Math.pow(2, camera.getZoom()) / 360.0;

        final float centreX = this.getWidth() / 2f;
        final float centreY = this.getHeight() / 2f;

        canvas.drawRect(0, 0, this.getWidth(), this.getHeight(), this.ground);
        this.drawGraticule(canvas);
        this.drawLines(canvas, camera, pixelsPerDegree, centreX, centreY);
        this.drawPins(canvas, camera, pixelsPerDegree, centreX, centreY);
        this.drawCaption(canvas, camera);
    }

    private void drawGraticule(final Canvas canvas) {
        final float step = dp(GRID_SPACING_DP);

        for (float x = 0; x < this.getWidth(); x += step) {
            canvas.drawLine(x, 0, x, this.getHeight(), this.grid);
        }
        for (float y = 0; y < this.getHeight(); y += step) {
            canvas.drawLine(0, y, this.getWidth(), y, this.grid);
        }
    }

    private void drawLines(
            final Canvas canvas, final IMapProvider.CameraPosition camera,
            final double pixelsPerDegree, final float centreX, final float centreY) {

        for (final MapPolyline polyline : this.provider.linesToDraw()) {
            final List<MapPolyline.LatLng> points = polyline.getPoints();
            if (points == null || points.size() < 2) {
                continue;
            }

            this.line.setColor(polyline.getColor());
            this.line.setStrokeWidth(Math.max(dp(2), polyline.getWidth()));

            final Path path = new Path();
            for (int i = 0; i < points.size(); i++) {
                final float x = x(points.get(i).getLongitude(), camera, pixelsPerDegree, centreX);
                final float y = y(points.get(i).getLatitude(), camera, pixelsPerDegree, centreY);

                if (i == 0) {
                    path.moveTo(x, y);
                } else {
                    path.lineTo(x, y);
                }
            }
            canvas.drawPath(path, this.line);
        }
    }

    private void drawPins(
            final Canvas canvas, final IMapProvider.CameraPosition camera,
            final double pixelsPerDegree, final float centreX, final float centreY) {

        // Lowest first, so a raised marker lands on top - which is what the screen raises it for.
        for (final FakeMapProvider.PlacedMarker placed : this.provider.inDrawOrder()) {
            final MapMarker marker = placed.marker;
            if (!marker.isVisible()) {
                continue;
            }

            final float x = x(marker.getLongitude(), camera, pixelsPerDegree, centreX);
            final float y = y(marker.getLatitude(), camera, pixelsPerDegree, centreY);

            final Bitmap icon = marker.getIconBitmap();
            if (icon != null) {
                // Bottom-centre anchored, which is where a pin's point is - the same anchor the
                // real providers use, so a pin drawn here sits over the same spot.
                canvas.drawBitmap(icon, x - icon.getWidth() / 2f, y - icon.getHeight(), null);
            } else {
                this.drawDefaultPin(canvas, x, y);
            }
        }
    }

    /**
     * A pin for a marker that brought no bitmap of its own.
     *
     * <p><b>Drawn as a pin rather than a dot, and never in the marker's own colour.</b> This used
     * to be {@code drawCircle} in {@code marker.getMarkerColor()}, which reads as reasonable and
     * is wrong twice over.
     *
     * <p>Wrong in shape: the app builds these with {@code useDefaultIcon(true)} - the history
     * screen's {@code single_coord_marker} is the only one - and both real providers answer that
     * with their own teardrop pin. A flat dot is not what the user sees, so a screenshot showing
     * one is a screenshot of the fake rather than of the app.
     *
     * <p>And wrong in colour: {@code MapMarker}'s default {@code markerColor} is opaque black,
     * which nothing ever sets for these markers, so every default pin came out black - on a dark
     * theme, a black dot on a near-black ground. Present, correct, invisible, which is the exact
     * failure mode {@code AGENTS.md} rule 12 lists.
     */
    private void drawDefaultPin(final Canvas canvas, final float x, final float y) {
        final float radius = dp(7);
        final float height = dp(22);

        // A teardrop: a circle at the top, tapering to the point that sits on the coordinate.
        final Path pin = new Path();
        pin.addCircle(x, y - height + radius, radius, Path.Direction.CW);
        pin.moveTo(x - radius * 0.8f, y - height + radius * 1.5f);
        pin.lineTo(x, y);
        pin.lineTo(x + radius * 0.8f, y - height + radius * 1.5f);
        pin.close();

        canvas.drawPath(pin, this.defaultPin);
    }

    /**
     * Says what this is and where it is pointed.
     *
     * <p>Both halves earn their place. The first stops a screenshot being mistaken for evidence
     * about a real location; the second is the thing that is otherwise invisible - a pin missing
     * because the camera is over the wrong continent looks exactly like a pin that was never
     * drawn, and this is the difference.
     */
    private void drawCaption(final Canvas canvas, final IMapProvider.CameraPosition camera) {
        final float pad = dp(8);

        canvas.drawText("FAKE MAP - nothing here is a real place", pad,
                this.getHeight() - pad - this.caption.getTextSize() * 1.4f, this.caption);
        canvas.drawText(String.format(Locale.ROOT, "camera %.5f, %.5f  z%.1f",
                        camera.getLatitude(), camera.getLongitude(), camera.getZoom()),
                pad, this.getHeight() - pad, this.caption);
    }

    private static float x(final double longitude, final IMapProvider.CameraPosition camera,
                           final double pixelsPerDegree, final float centreX) {
        return (float) (centreX + (longitude - camera.getLongitude()) * pixelsPerDegree);
    }

    private static float y(final double latitude, final IMapProvider.CameraPosition camera,
                           final double pixelsPerDegree, final float centreY) {
        // Minus, because latitude grows northward and screen y grows downward.
        return (float) (centreY - (latitude - camera.getLatitude()) * pixelsPerDegree);
    }

    private static int withAlpha(final int colour, final int alpha) {
        return Color.argb(alpha, Color.red(colour), Color.green(colour), Color.blue(colour));
    }

    private float dp(final float value) {
        return TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value,
                this.getResources().getDisplayMetrics());
    }
}
