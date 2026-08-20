package dev.wander.android.opentagviewer.ui;

import java.util.Locale;

import dev.wander.android.opentagviewer.R;
import dev.wander.android.opentagviewer.python.icloud.RecoverableDevice;

/**
 * Which icon stands for one of the user's Apple devices.
 *
 * <p>Chosen from {@code device_model_class} - the record's own word for what kind of thing it is
 * - rather than parsed out of the model string. A person picking between two records is choosing
 * between physical objects they own, and a phone that looks like a phone is most of that job.
 *
 * <p>Matched loosely and case-insensitively on purpose: this is a string from Apple by way of a
 * property list, and an unrecognised one should fall back to something sensible rather than draw
 * nothing.
 */
public final class RecoverableDeviceIcon {

    private RecoverableDeviceIcon() {
    }

    public static int forDevice(final RecoverableDevice device) {
        final String kind = (device.getModelClass() + " " + device.getModel())
                .toLowerCase(Locale.ROOT);

        if (kind.contains("ipad")) {
            return R.drawable.tablet_24px;
        }
        if (kind.contains("iphone") || kind.contains("ipod")) {
            return R.drawable.smartphone_24px;
        }
        if (kind.contains("mac") || kind.contains("book")) {
            return R.drawable.laptop_24px;
        }

        // Anything else - a Watch, an Apple TV, something Apple ships next year. The generic
        // devices icon says "a device" honestly rather than guessing wrong.
        return R.drawable.devices_24px;
    }
}
