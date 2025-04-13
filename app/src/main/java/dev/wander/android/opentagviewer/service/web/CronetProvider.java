package dev.wander.android.opentagviewer.service.web;

import android.content.Context;

import org.chromium.net.CronetEngine;

public class CronetProvider {

    private static CronetEngine ENGINE;

    public static CronetEngine getInstance(Context context) {
        if (ENGINE == null) {
            ENGINE = new CronetEngine.Builder(context)
                    // not technically secure but app users can use this at their own risk
                    .enableHttp2(true)
                    .build();
        }
        return ENGINE;
    }
}
