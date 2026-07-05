package com.ava.mods.bleadv;

import android.content.Context;
import android.util.Log;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

/**
 * Reads this mod's installed manifest.json (ble_adv_standalone, etc.).
 * Defaults to standalone=true when the field is absent — matches Ava host policy.
 */
final class BleAdvManifestReader {
    private static final String TAG = "BleAdvManifest";
    private static final String MOD_ID = "ble-adv-proxy";

    private static volatile Boolean cachedStandalone;

    private BleAdvManifestReader() {
    }

    static boolean isStandalone(Context context) {
        Boolean cached = cachedStandalone;
        if (cached != null) {
            return cached;
        }
        boolean standalone = readStandaloneFlag(context);
        cachedStandalone = standalone;
        return standalone;
    }

    static void invalidateCache() {
        cachedStandalone = null;
    }

    private static boolean readStandaloneFlag(Context context) {
        File manifest = new File(context.getFilesDir(), "mods/" + MOD_ID + "/manifest.json");
        if (!manifest.isFile()) {
            Log.d(TAG, "manifest missing, default standalone=true");
            return true;
        }
        try {
            String json = readUtf8(manifest);
            JSONObject root = new JSONObject(json);
            if (!root.has("ble_adv_standalone") || root.isNull("ble_adv_standalone")) {
                return true;
            }
            return root.optBoolean("ble_adv_standalone", true);
        } catch (Exception e) {
            Log.w(TAG, "manifest parse failed, default standalone=true", e);
            return true;
        }
    }

    private static String readUtf8(File file) throws Exception {
        StringBuilder sb = new StringBuilder();
        BufferedReader reader = new BufferedReader(
                new InputStreamReader(new FileInputStream(file), StandardCharsets.UTF_8));
        try {
            String line;
            while ((line = reader.readLine()) != null) {
                sb.append(line).append('\n');
            }
        } finally {
            reader.close();
        }
        return sb.toString();
    }
}
