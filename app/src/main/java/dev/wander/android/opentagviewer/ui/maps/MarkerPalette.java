package dev.wander.android.opentagviewer.ui.maps;

import android.content.Context;
import android.util.TypedValue;

import androidx.annotation.ColorInt;
import androidx.annotation.NonNull;

import lombok.AccessLevel;
import lombok.NoArgsConstructor;

/**
 * What colour a map pin is drawn in.
 *
 * <p><b>The same colours the tag card uses, resolved the same way.</b> A pin and the card that
 * describes it sit on screen together, so a theme that tints one and not the other reads as an
 * oversight - which is what it was.
 *
 * <p>The pins used to be filled with {@code getColor(R.color.md_theme_background)}: the raw
 * colour resource rather than the theme attribute the card uses. Those agree exactly, including
 * in dark mode, right up until somebody turns on system colours. {@code DynamicColors} rewrites
 * the <i>theme</i> so {@code android:colorBackground} becomes a wallpaper-derived tint; it does
 * not - and cannot - rewrite {@code @color/md_theme_background}, which is a fixed value in a
 * resource file. So the cards took the wallpaper's colour and the pins stayed on the app's own
 * palette, and the two drifted apart on exactly the setting somebody enables because they want
 * the app to match their phone.
 *
 * <p>Resolving the attribute instead is a no-op wherever the two agreed before, and follows the
 * card everywhere they did not. It also means anything that themes this app later - a third
 * party's palette, a future dynamic scheme - gets the pins for free rather than needing to know
 * this file exists.
 *
 * <p><b>Read from the context that is drawing.</b> Not from the application context: a themed or
 * night-forced context resolves these differently, which is what lets both be rendered and
 * measured in a test without touching the device's settings.
 */
@NoArgsConstructor(access = AccessLevel.PRIVATE)
public final class MarkerPalette {

    /**
     * The pin's body, matching the tag card's own background.
     *
     * <p>{@code android:colorBackground} rather than a surface colour, because that is what
     * {@code maps_tag_card.xml} tints itself with - the point is that they agree, so the answer
     * is "whatever the card asked for" rather than a judgement about which is prettier.
     */
    @ColorInt
    public static int fill(@NonNull final Context context) {
        return resolve(context, android.R.attr.colorBackground);
    }

    /**
     * The icon drawn on the pin, for a tag with no emoji of its own.
     *
     * <p>A muted on-surface colour rather than the flat grey it used to be. Material guarantees
     * this contrasts with the surface colours around it, which a fixed grey cannot once the fill
     * is free to become anything the wallpaper suggests - and an icon that disappears into its
     * own pin is the failure this whole change could otherwise introduce. Held to 3:1 by
     * {@code MarkerFollowsTheThemeTest}.
     */
    @ColorInt
    public static int icon(@NonNull final Context context) {
        return resolve(context, com.google.android.material.R.attr.colorOnSurfaceVariant);
    }

    @ColorInt
    private static int resolve(@NonNull final Context context, final int attribute) {
        final TypedValue found = new TypedValue();

        // resolveRefs = true, so a theme pointing the attribute at a colour resource gives the
        // colour rather than a reference nobody can paint with.
        context.getTheme().resolveAttribute(attribute, found, true);

        return found.data;
    }
}
