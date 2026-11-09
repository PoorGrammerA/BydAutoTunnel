package com.poorgrammera.autotunnel.data;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * Shared preferences keys and default configuration values for AutoTunnel.
 */
public final class ConfigData {
    public static final String PREF_NAME = "autotunnel_prefs";

    public static final String KEY_SERVICE_ENABLED = "pref_service_enabled";
    public static final boolean DEFAULT_SERVICE_ENABLED = true;

    public static final String KEY_APPROACH_DISTANCE_M = "pref_approach_distance_m";
    public static final int DEFAULT_APPROACH_DISTANCE_M = 200; // 200 meters ahead

    public static final String KEY_EXIT_DELAY_SEC = "pref_exit_delay_sec";
    public static final int DEFAULT_EXIT_DELAY_SEC = 5; // 5 seconds cooldown after exit

    public static final String KEY_MIN_TUNNEL_LENGTH_M = "pref_min_tunnel_length_m";
    public static final int DEFAULT_MIN_TUNNEL_LENGTH_M = 0; // 0 = No filter (include all short tunnels/underpasses)

    public static final String KEY_ALERT_SOUND_ENABLED = "pref_alert_sound_enabled";
    public static final boolean DEFAULT_ALERT_SOUND_ENABLED = true;

    public static final String KEY_AUTO_CLOSE_WINDOWS = "pref_auto_close_windows";
    public static final boolean DEFAULT_AUTO_CLOSE_WINDOWS = true;

    public static final String KEY_SAFE_WINDOW_CLOSE = "pref_safe_window_close";
    public static final boolean DEFAULT_SAFE_WINDOW_CLOSE = true; // 10% 틈새 후 3초 대기 뒤 완전 닫기

    public static SharedPreferences getPrefs(Context context) {
        return context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
    }

    private ConfigData() {}
}
