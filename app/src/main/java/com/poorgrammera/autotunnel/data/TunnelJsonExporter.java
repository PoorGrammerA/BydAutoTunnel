package com.poorgrammera.autotunnel.data;

import android.content.Context;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

/**
 * Handles exporting and importing user-customized tunnel data to and from JSON.
 */
public final class TunnelJsonExporter {
    private static final String TAG = "TunnelJsonExporter";
    public static final String DEFAULT_FILE_NAME = "autotunnel_backup.json";

    public static String exportToJsonString(Context context) {
        try {
            UserTunnelDbHelper db = UserTunnelDbHelper.getInstance(context);
            Map<Long, UserTunnelOverride> overrides = db.getAllOverrides();
            List<UserCustomTunnel> customTunnels = db.getAllCustomTunnels();

            JSONObject root = new JSONObject();
            root.put("version", 1);
            root.put("exportedAt", System.currentTimeMillis());

            // Overrides array
            JSONArray overridesArr = new JSONArray();
            for (UserTunnelOverride o : overrides.values()) {
                JSONObject obj = new JSONObject();
                obj.put("baseTunnelId", o.baseTunnelId);
                obj.put("isDeleted", o.isDeleted);
                if (o.overrideBearing != null) obj.put("overrideBearing", o.overrideBearing);
                if (o.overrideStartBoxDist != null) obj.put("overrideStartBoxDist", o.overrideStartBoxDist);
                if (o.overrideBoxWidth != null) obj.put("overrideBoxWidth", o.overrideBoxWidth);
                obj.put("updatedAt", o.updatedAt);
                overridesArr.put(obj);
            }
            root.put("overrides", overridesArr);

            // Custom tunnels array
            JSONArray customArr = new JSONArray();
            for (UserCustomTunnel c : customTunnels) {
                JSONObject obj = new JSONObject();
                obj.put("customId", c.customId);
                obj.put("name", c.name);
                obj.put("startLat", c.startLat);
                obj.put("startLng", c.startLng);
                obj.put("endLat", c.endLat);
                obj.put("endLng", c.endLng);
                obj.put("bearing", c.bearing);
                obj.put("lengthMeters", (double) c.lengthMeters);
                obj.put("oneway", c.oneway);
                obj.put("startBoxDist", c.startBoxDist);
                obj.put("boxWidth", c.boxWidth);
                obj.put("createdAt", c.createdAt);
                customArr.put(obj);
            }
            root.put("customTunnels", customArr);

            return root.toString(2);
        } catch (Exception e) {
            Log.e(TAG, "Failed to export JSON string", e);
            return null;
        }
    }

    public static boolean exportToFile(Context context, File targetFile) {
        String jsonStr = exportToJsonString(context);
        if (jsonStr == null) return false;

        try {
            if (targetFile.getParentFile() != null && !targetFile.getParentFile().exists()) {
                targetFile.getParentFile().mkdirs();
            }
            try (FileOutputStream fos = new FileOutputStream(targetFile)) {
                fos.write(jsonStr.getBytes(StandardCharsets.UTF_8));
                fos.flush();
            }
            Log.i(TAG, "Successfully exported user tunnel data to: " + targetFile.getAbsolutePath());
            return true;
        } catch (Exception e) {
            Log.e(TAG, "Failed to write JSON file: " + targetFile.getAbsolutePath(), e);
            return false;
        }
    }

    public static boolean importFromJsonString(Context context, String jsonStr, boolean merge) {
        try {
            JSONObject root = new JSONObject(jsonStr);
            UserTunnelDbHelper db = UserTunnelDbHelper.getInstance(context);

            if (!merge) {
                db.clearAll();
            }

            // Read overrides
            if (root.has("overrides")) {
                JSONArray overridesArr = root.getJSONArray("overrides");
                for (int i = 0; i < overridesArr.length(); i++) {
                    JSONObject obj = overridesArr.getJSONObject(i);
                    long baseId = obj.getLong("baseTunnelId");
                    boolean isDeleted = obj.optBoolean("isDeleted", false);
                    Double bearing = obj.has("overrideBearing") ? obj.getDouble("overrideBearing") : null;
                    Integer startBoxDist = obj.has("overrideStartBoxDist") ? obj.getInt("overrideStartBoxDist") : null;
                    Integer boxWidth = obj.has("overrideBoxWidth") ? obj.getInt("overrideBoxWidth") : null;
                    long updatedAt = obj.optLong("updatedAt", System.currentTimeMillis());

                    db.saveOverride(new UserTunnelOverride(baseId, isDeleted, bearing, startBoxDist, boxWidth, updatedAt));
                }
            }

            // Read custom tunnels
            if (root.has("customTunnels")) {
                JSONArray customArr = root.getJSONArray("customTunnels");
                for (int i = 0; i < customArr.length(); i++) {
                    JSONObject obj = customArr.getJSONObject(i);
                    long customId = obj.getLong("customId");
                    String name = obj.getString("name");
                    double startLat = obj.getDouble("startLat");
                    double startLng = obj.getDouble("startLng");
                    double endLat = obj.getDouble("endLat");
                    double endLng = obj.getDouble("endLng");
                    double bearing = obj.getDouble("bearing");
                    float lengthMeters = (float) obj.getDouble("lengthMeters");
                    boolean oneway = obj.optBoolean("oneway", false);
                    int startBoxDist = obj.optInt("startBoxDist", 200);
                    int boxWidth = obj.optInt("boxWidth", 60);
                    long createdAt = obj.optLong("createdAt", System.currentTimeMillis());

                    db.saveCustomTunnel(new UserCustomTunnel(customId, name, startLat, startLng, endLat, endLng,
                            bearing, lengthMeters, oneway, startBoxDist, boxWidth, createdAt));
                }
            }

            Log.i(TAG, "Successfully imported user tunnel JSON data (merge=" + merge + ")");
            return true;
        } catch (Exception e) {
            Log.e(TAG, "Failed to import JSON data", e);
            return false;
        }
    }

    public static boolean importFromFile(Context context, File sourceFile, boolean merge) {
        if (!sourceFile.exists()) {
            Log.e(TAG, "Source file does not exist: " + sourceFile.getAbsolutePath());
            return false;
        }

        try (BufferedReader reader = new BufferedReader(new InputStreamReader(new FileInputStream(sourceFile), StandardCharsets.UTF_8))) {
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                sb.append(line).append('\n');
            }
            return importFromJsonString(context, sb.toString(), merge);
        } catch (Exception e) {
            Log.e(TAG, "Failed to read JSON file: " + sourceFile.getAbsolutePath(), e);
            return false;
        }
    }

    private TunnelJsonExporter() {}
}
