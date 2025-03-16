package dev.wander.android.airtagforall.ui.maps;

import android.content.res.Configuration;
import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.Canvas;
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

import androidx.annotation.ColorInt;
import androidx.annotation.DrawableRes;
import androidx.annotation.RequiresApi;
import androidx.core.content.res.ResourcesCompat;
import androidx.core.graphics.drawable.DrawableCompat;

import com.google.android.gms.maps.model.BitmapDescriptor;
import com.google.android.gms.maps.model.BitmapDescriptorFactory;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

import dev.wander.android.airtagforall.R;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;
import lombok.NonNull;

/**
 * Primary reference: https://developer.android.com/guide/topics/renderscript/migrate#intrinsics
 */
@NoArgsConstructor(access = AccessLevel.PRIVATE)
public final class VectorImageGeneratorUtil {
    private static final String TAG = VectorImageGeneratorUtil.class.getSimpleName();

    private static final Map<String, BitmapDescriptor> BITMAP_CACHE = new HashMap<>();

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

    public static BitmapDescriptor vectorToBitmap(@NonNull Resources resources, @DrawableRes int id) {
        Drawable vectorDrawable = Objects.requireNonNull(ResourcesCompat.getDrawable(resources, id, null));
        Bitmap bitmap = Bitmap.createBitmap(vectorDrawable.getIntrinsicWidth(), vectorDrawable.getIntrinsicHeight(), Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bitmap);
        vectorDrawable.setBounds(0, 0, canvas.getWidth(), canvas.getHeight());
        vectorDrawable.draw(canvas);
        return BitmapDescriptorFactory.fromBitmap(bitmap);
    }

    /**
     * Create marker with emoji centred in the middle of it (cached)
     */
    public static BitmapDescriptor makeMarker(@NonNull Resources resources, final String emoji, @ColorInt int markerColor) {
        final String key = String.format(Locale.ROOT, "%s-%d", emoji, markerColor);
        if (BITMAP_CACHE.containsKey(key)) {
            return BITMAP_CACHE.get(key);
        }

        Drawable markerDrawable = Objects.requireNonNull(ResourcesCompat.getDrawable(resources, R.drawable.location_on_56px, null));
        final int unit = (int)((float)markerDrawable.getIntrinsicWidth() / 3.5f);

        Bitmap bitmap = drawBackgroundForMarker(markerDrawable);
        Canvas canvas = new Canvas(bitmap);

        drawMarker(canvas, markerDrawable, markerColor);

        // draw emoji on marker (e.g. an emoji like 😁 would be placed in the middle of the marker)
        Paint paint = new Paint();
        paint.setColor(Color.BLACK);
        paint.setTextSize(EMOJI_TEXT_SIZE);
        final float emojiX = (markerDrawable.getIntrinsicWidth()/2.f) - (EMOJI_TEXT_SIZE/1.75f);
        final float emojiY = (markerDrawable.getIntrinsicHeight()/2.f) + (EMOJI_TEXT_SIZE/8f);
        canvas.drawText(emoji, emojiX, emojiY, paint);

        var desc = BitmapDescriptorFactory.fromBitmap(bitmap);
        BITMAP_CACHE.put(key, desc);
        return desc;
    }

    /**
     * Create marker with some drawable resource in the middle of it (cached)
     */
    public static BitmapDescriptor makeMarker(@NonNull Resources resources, @DrawableRes int innerIcon, @ColorInt int markerColor, @ColorInt int iconColor) {
        final String key = String.format(Locale.ROOT, "%d-%d-%d", innerIcon, markerColor, iconColor);
        if (BITMAP_CACHE.containsKey(key)) {
            return BITMAP_CACHE.get(key);
        }

        Drawable markerDrawable = Objects.requireNonNull(ResourcesCompat.getDrawable(resources, R.drawable.location_on_56px, null));
        final int unit = (int)((float)markerDrawable.getIntrinsicWidth() / 3.5f);
        final int half = unit / 2;

        Bitmap bitmap = drawBackgroundForMarker(markerDrawable);
        Canvas canvas = new Canvas(bitmap);

        drawMarker(canvas, markerDrawable, markerColor);

        // draw secondary icon on marker (e.g. apple icon)
        Drawable iconOnMarkerDrawable = Objects.requireNonNull(ResourcesCompat.getDrawable(resources, innerIcon, null));
        iconOnMarkerDrawable.setBounds(unit, half + INNER_ICON_OFFSET_TOP, canvas.getWidth() - unit, canvas.getHeight() - (unit + half) + INNER_ICON_OFFSET_TOP);
        DrawableCompat.setTint(iconOnMarkerDrawable, iconColor);
        iconOnMarkerDrawable.draw(canvas);

        var desc = BitmapDescriptorFactory.fromBitmap(bitmap);
        BITMAP_CACHE.put(key, desc);
        return desc;
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
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ? drawShadow(markerDrawable) : fromOutline(markerDrawable);
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

        hardwareBuffer.close();
        image.close();
        imageReader.close();
        renderNode.discardDisplayList();
        hardwareRenderer.destroy();

        return blurredBitmap.copy(Bitmap.Config.ARGB_8888,true);
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
