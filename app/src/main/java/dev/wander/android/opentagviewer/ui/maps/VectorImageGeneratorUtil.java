package dev.wander.android.opentagviewer.ui.maps;

import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Rect;
import android.graphics.Color;
import android.graphics.HardwareRenderer;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.RenderEffect;
import android.graphics.RenderNode;
import android.graphics.Shader;
import android.graphics.drawable.Drawable;
import android.hardware.HardwareBuffer;
import android.media.ImageReader;
import android.os.Build;
import android.util.Log;

import androidx.annotation.ColorInt;
import androidx.annotation.DrawableRes;
import androidx.annotation.RequiresApi;
import androidx.core.content.res.ResourcesCompat;
import androidx.core.graphics.drawable.DrawableCompat;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

import dev.wander.android.opentagviewer.R;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;
import lombok.NonNull;

/**
 * Primary reference: https://developer.android.com/guide/topics/renderscript/migrate#intrinsics
 */
@NoArgsConstructor(access = AccessLevel.PRIVATE)
public final class VectorImageGeneratorUtil {
    private static final String TAG = VectorImageGeneratorUtil.class.getSimpleName();

    private static final Map<String, Bitmap> BITMAP_CACHE = new HashMap<>();

    @ColorInt private static final int COLOR_BLACK = 0xFF000000;
    @ColorInt private static final int COLOR_BLACK_ALPHA_100 = 0xD1000000;
    @ColorInt private static final int COLOR_BLACK_ALPHA_200 = 0xB3000000;
    @ColorInt private static final int COLOR_BLACK_ALPHA_300 = 0xA8000000;
    @ColorInt private static final int COLOR_BLACK_ALPHA_400 = 0x87000000;
    @ColorInt private static final int COLOR_BLACK_ALPHA_500 = 0x6E000000;
    @ColorInt private static final int COLOR_BLACK_ALPHA_600 = 0x4A000000;
    @ColorInt private static final int COLOR_BLACK_ALPHA_700 = 0x29000000;

    private static final float BLUR_RADIUS = 10.0f;

    private static final int INNER_ICON_OFFSET_TOP = 5;

    private static final float EMOJI_TEXT_SIZE = 60;

    public static Bitmap vectorToBitmap(@NonNull Resources resources, @DrawableRes int id) {
        Drawable vectorDrawable = Objects.requireNonNull(// TINTED_AFTERWARDS: setTint below replaces every fill, so no theme is needed here.
        ResourcesCompat.getDrawable(resources, id, null));
        Bitmap bitmap = Bitmap.createBitmap(vectorDrawable.getIntrinsicWidth(), vectorDrawable.getIntrinsicHeight(), Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bitmap);
        vectorDrawable.setBounds(0, 0, canvas.getWidth(), canvas.getHeight());
        vectorDrawable.draw(canvas);
        return bitmap;
    }

    /**
     * Create marker with emoji centred in the middle of it (cached)
     */
    public static Bitmap makeMarker(@NonNull Resources resources, final String emoji, @ColorInt int markerColor) {
        final String key = String.format(Locale.ROOT, "%s-%d", emoji, markerColor);
        if (BITMAP_CACHE.containsKey(key)) {
            return BITMAP_CACHE.get(key);
        }

        Drawable markerDrawable = Objects.requireNonNull(// TINTED_AFTERWARDS: setTint below replaces every fill, so no theme is needed here.
        ResourcesCompat.getDrawable(resources, R.drawable.location_on_56px, null));
        final int unit = (int)((float)markerDrawable.getIntrinsicWidth() / 3.5f);

        Bitmap bitmap = drawBackgroundForMarker(markerDrawable);
        Canvas canvas = new Canvas(bitmap);

        drawMarker(canvas, markerDrawable, markerColor);

        // **Centred on the pin's head, not on the pin.** A location pin is a circle with a point
        // hanging off the bottom, so the middle of its bounding box is below the middle of the
        // head - and the emoji used to be placed by halving the drawable's height and nudging it
        // with two magic divisors, which put it low and slightly left. It was only visible next
        // to a non-emoji pin, where the icon is positioned by setBounds and lands correctly.
        //
        // So both now use the same box, and Paint measures the glyph instead of a constant
        // guessing at its width: ALIGN.CENTER handles x, and the font metrics put the glyph's
        // visual middle on the box's middle rather than its baseline.
        final Rect head = innerIconBounds(canvas, markerDrawable);

        Paint paint = new Paint();
        paint.setColor(Color.BLACK);
        paint.setTextSize(EMOJI_TEXT_SIZE);
        paint.setTextAlign(Paint.Align.CENTER);

        final Paint.FontMetrics metrics = paint.getFontMetrics();
        final float baseline = head.centerY() - (metrics.ascent + metrics.descent) / 2f;

        canvas.drawText(emoji, head.centerX(), baseline, paint);

        BITMAP_CACHE.put(key, bitmap);
        return bitmap;
    }

    /**
     * Create marker with some drawable resource in the middle of it (cached)
     */
    public static Bitmap makeMarker(@NonNull Resources resources, @DrawableRes int innerIcon, @ColorInt int markerColor, @ColorInt int iconColor) {
        final String key = String.format(Locale.ROOT, "%d-%d-%d", innerIcon, markerColor, iconColor);
        if (BITMAP_CACHE.containsKey(key)) {
            return BITMAP_CACHE.get(key);
        }

        Drawable markerDrawable = Objects.requireNonNull(// TINTED_AFTERWARDS: setTint below replaces every fill, so no theme is needed here.
        ResourcesCompat.getDrawable(resources, R.drawable.location_on_56px, null));
        final int unit = (int)((float)markerDrawable.getIntrinsicWidth() / 3.5f);
        final int half = unit / 2;

        Bitmap bitmap = drawBackgroundForMarker(markerDrawable);
        Canvas canvas = new Canvas(bitmap);

        drawMarker(canvas, markerDrawable, markerColor);

        // draw secondary icon on marker (e.g. apple icon)
        Drawable iconOnMarkerDrawable = Objects.requireNonNull(// TINTED_AFTERWARDS: setTint below replaces every fill, so no theme is needed here.
        ResourcesCompat.getDrawable(resources, innerIcon, null));
        iconOnMarkerDrawable.setBounds(innerIconBounds(canvas, markerDrawable));
        DrawableCompat.setTint(iconOnMarkerDrawable, iconColor);
        iconOnMarkerDrawable.draw(canvas);

        BITMAP_CACHE.put(key, bitmap);
        return bitmap;
    }

    /**
     * The square inside the pin's head, which is where anything drawn on a pin belongs.
     *
     * <p><b>One definition, used by both kinds of pin.</b> The emoji and the icon are drawn by
     * completely different means - {@code drawText} against {@code setBounds} - and while each
     * had its own idea of where the middle was, they disagreed: the icon sat in the head and the
     * emoji sat low and left of it. Sharing the box is what makes them land in the same place,
     * and makes "is it centred" a question with one answer rather than two.
     *
     * <p>The numbers are the ones the icon has always used, since that was the one that looked
     * right. {@code INNER_ICON_OFFSET_TOP} nudges it up off the point of the pin.
     */
    private static Rect innerIconBounds(Canvas canvas, Drawable markerDrawable) {
        final int unit = (int) ((float) markerDrawable.getIntrinsicWidth() / 3.5f);
        final int half = unit / 2;

        return new Rect(
                unit,
                half + INNER_ICON_OFFSET_TOP,
                canvas.getWidth() - unit,
                canvas.getHeight() - (unit + half) + INNER_ICON_OFFSET_TOP);
    }

    private static void drawMarker(Canvas canvas, Drawable markerDrawable, @ColorInt int markerColor) {
        // draws the marker itself (in color), on top of the shadow
        markerDrawable.setBounds(1, 1, canvas.getWidth()-1, canvas.getHeight()-1);
        DrawableCompat.setTint(markerDrawable, markerColor);
        markerDrawable.draw(canvas);
    }

    private static Bitmap drawBackgroundForMarker(Drawable markerDrawable) {
        // no shadow when android version too low :(
        // (I am too lazy to try and implement it for these lower versions.
        // I think a viable way to make it work would be to use:
        // https://developer.android.com/guide/topics/renderscript/compute
        // however this was explicitly deprecated for Android 12 and up
        // so meh. No blur shadow, just an outline for older versions)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            try {
                return drawShadow(markerDrawable);
            } catch (final RuntimeException cannotBlur) {
                // **A missing shadow is not worth a missing pin.** The blur goes through
                // HardwareRenderer and an ImageReader, which needs a working GPU render path -
                // and where there is not one, acquireNextImage returns null and this used to
                // throw all the way out of showBeaconOnMap. The tag then simply did not appear
                // on the map, with an exception in the log about an image, which is a long way
                // from "this device cannot blur".
                //
                // Found on the headless emulator the instrumented tests run on, which has no
                // GPU. That is not a real user's phone - but "the device cannot do the fancy
                // version" is exactly the case the older-Android branch below already handles,
                // and losing the drop shadow is a far better outcome than losing the marker.
                Log.w(TAG, "Could not render the marker's blurred shadow on this device;"
                        + " drawing a plain outline instead", cannotBlur);
            }
        }

        return fromOutline(markerDrawable);
    }

    /**
     * Main reference for this here: https://developer.android.com/guide/topics/renderscript/migrate#image_blur_on_android_12_rendered_into_a_bitmap
     */
    @RequiresApi(api = Build.VERSION_CODES.S)
    private static Bitmap drawShadow(Drawable markerDrawable) {
        Bitmap bitmap = Bitmap.createBitmap(markerDrawable.getIntrinsicWidth(), markerDrawable.getIntrinsicHeight(), Bitmap.Config.ARGB_8888);
        Canvas canvas1 = new Canvas(bitmap);

        markerDrawable.setBounds(0, 0, canvas1.getWidth(), canvas1.getHeight());
        DrawableCompat.setTint(markerDrawable, COLOR_BLACK_ALPHA_600);
        markerDrawable.draw(canvas1);

        var imageReader = ImageReader.newInstance(bitmap.getWidth(), bitmap.getHeight(), PixelFormat.RGBA_8888, 1, HardwareBuffer.USAGE_GPU_SAMPLED_IMAGE | HardwareBuffer.USAGE_GPU_COLOR_OUTPUT);
        var renderNode = new RenderNode("BlurEffect");
        var hardwareRenderer = new HardwareRenderer();

        hardwareRenderer.setSurface(imageReader.getSurface());
        hardwareRenderer.setContentRoot(renderNode);
        renderNode.setPosition(0, 0, imageReader.getWidth(), imageReader.getHeight());
        var blurRenderEffect = RenderEffect.createBlurEffect(BLUR_RADIUS, BLUR_RADIUS, Shader.TileMode.MIRROR);
        renderNode.setRenderEffect(blurRenderEffect);


        var renderCanvas = renderNode.beginRecording();
        renderCanvas.drawBitmap(bitmap, 0.0f, 0.0f, null);
        renderNode.endRecording();
        hardwareRenderer.createRenderRequest()
                .setWaitForPresent(true)
                .syncAndDraw();

        var image = Optional.ofNullable(imageReader.acquireNextImage()).orElseThrow(() -> new RuntimeException("No Image"));
        var hardwareBuffer = Optional.ofNullable(image.getHardwareBuffer()).orElseThrow(() -> new RuntimeException("No HardwareBuffer"));
        var blurredBitmap = Optional.ofNullable(Bitmap.wrapHardwareBuffer(hardwareBuffer, null)).orElseThrow(() -> new RuntimeException("Create Bitmap failed"));

        // 立即创建可变的 ARGB_8888 副本，避免 Hardware Bitmap 在跨线程使用时出现 BufferQueue 问题
        Bitmap softwareBitmap = blurredBitmap.copy(Bitmap.Config.ARGB_8888, true);
        
        // 清理 Hardware 资源
        hardwareBuffer.close();
        image.close();
        imageReader.close();
        renderNode.discardDisplayList();
        hardwareRenderer.destroy();

        return softwareBitmap;
    }

    /**
     * This will just draw an outline
     */
    private static Bitmap fromOutline(Drawable markerDrawable) {
        Bitmap bitmap = Bitmap.createBitmap(markerDrawable.getIntrinsicWidth(), markerDrawable.getIntrinsicHeight(), Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bitmap);
        markerDrawable.setBounds(0, 0, canvas.getWidth(), canvas.getHeight());
        DrawableCompat.setTint(markerDrawable, COLOR_BLACK_ALPHA_600);
        markerDrawable.draw(canvas);
        return bitmap;
    }
}
