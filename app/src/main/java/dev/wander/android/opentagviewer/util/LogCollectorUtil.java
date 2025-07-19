package dev.wander.android.opentagviewer.util;

import android.util.Log;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;

public class LogCollectorUtil {
    private static final String TAG = LogCollectorUtil.class.getSimpleName();

    private static final int NUM_LINES_UP = 500;

    public static String getLastLogs() {
        try {
            Log.d(TAG, String.format("Reading last %d log lines from logcat...", NUM_LINES_UP));
            Process process = Runtime.getRuntime().exec(new String[]{"logcat", "-t", String.valueOf(NUM_LINES_UP)});

            var reader = new BufferedReader(new InputStreamReader(process.getInputStream()));
            var sb = new StringBuilder();

            String line;
            while ((line = reader.readLine()) != null) {
                sb.append(line).append("\n");
            }

            return sb.toString();
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }
}
