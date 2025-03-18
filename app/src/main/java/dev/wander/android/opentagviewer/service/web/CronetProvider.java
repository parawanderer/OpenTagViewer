package dev.wander.android.opentagviewer.service.web;

import android.content.Context;

import org.chromium.net.CronetEngine;

public class CronetProvider {

    private static CronetEngine ENGINE;

    public static CronetEngine getInstance(Context context) {
        if (ENGINE == null) {
            ENGINE = new CronetEngine.Builder(context).build();
        }
        return ENGINE;
    }
}
