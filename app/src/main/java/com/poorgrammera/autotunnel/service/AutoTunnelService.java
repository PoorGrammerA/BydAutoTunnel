package com.poorgrammera.autotunnel.service;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.media.AudioManager;
import android.media.MediaPlayer;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.util.Log;
import androidx.core.app.NotificationCompat;
import androidx.core.content.ContextCompat;

import com.poorgrammera.autotunnel.R;
import com.poorgrammera.autotunnel.data.ConfigData;
import com.poorgrammera.autotunnel.detector.TunnelDetector;
import com.poorgrammera.autotunnel.model.Tunnel;
import com.poorgrammera.autotunnel.model.TunnelDebugSnapshot;
import com.poorgrammera.autotunnel.model.TunnelDetectionResult;
import com.poorgrammera.autotunnel.ui.MainActivity;
import com.poorgrammera.autotunnel.vehicle.VehicleController;

public class AutoTunnelService extends Service {
    private static final String TAG = "AutoTunnelService";
    private static final String CHANNEL_ID = "AutoTunnelChannel";
    private static final int NOTIFICATION_ID = 1001;

    public static final String ACTION_REFRESH_TRACKING = "com.poorgrammera.autotunnel.ACTION_REFRESH_TRACKING";
    public static final String ACTION_SIMULATE_TUNNEL = "com.poorgrammera.autotunnel.ACTION_SIMULATE_TUNNEL";

    public static volatile boolean isRunning = false;
    private static volatile TunnelDebugSnapshot latestSnapshot;

    public static TunnelDebugSnapshot getLatestSnapshot() {
        return latestSnapshot;
    }

    private final Handler handler = new Handler(Looper.getMainLooper());

    private TunnelDetector detector;
    private VehicleController vehicleController;

    private LocationManager locationManager;
    private LocationListener locationListener;
    private Location lastKnownLocation;

    private boolean isRecirculationForcedByTunnel = false;
    private int originalCycleModeBeforeTunnel = -1; // -1: unknown, 0: fresh, 1: recycle
    private boolean isWindowsClosedByTunnel = false;
    private final java.util.Map<Integer, Integer> savedWindowPercentagesBeforeTunnel = new java.util.HashMap<>();
    private MediaPlayer mediaPlayer;

    private final SharedPreferences.OnSharedPreferenceChangeListener prefListener = (prefs, key) -> {
        if (ConfigData.KEY_SERVICE_ENABLED.equals(key)) {
            boolean enabled = prefs.getBoolean(ConfigData.KEY_SERVICE_ENABLED, ConfigData.DEFAULT_SERVICE_ENABLED);
            if (enabled) {
                Log.i(TAG, "Service powered ON. Starting GPS tracking.");
                startGpsTracking();
                updateNotification("AutoTunnel 대기 중...", "GPS 신호를 검색하고 있습니다.");
            } else {
                Log.i(TAG, "Service powered OFF. Stopping GPS tracking & restoring vehicle state.");
                stopGpsTracking();
                if (isRecirculationForcedByTunnel) {
                    restoreOriginalAirCycle();
                }
                if (isWindowsClosedByTunnel) {
                    restoreOriginalWindows();
                }
                detector.reset();
                updateSnapshot(lastKnownLocation, TunnelDetectionResult.normal("SERVICE_DISABLED"));
                updateNotification("AutoTunnel 전원 꺼짐", "오토터널 서비스가 비활성화되었습니다.");
            }
        }
    };

    private final Runnable tickRunnable = new Runnable() {
        @Override
        public void run() {
            onPeriodicTick();
            handler.postDelayed(this, 1000L); // 1s tick
        }
    };

    @Override
    public void onCreate() {
        super.onCreate();
        isRunning = true;
        Log.i(TAG, "Starting AutoTunnelService...");

        createNotificationChannel();

        SharedPreferences prefs = ConfigData.getPrefs(this);
        prefs.registerOnSharedPreferenceChangeListener(prefListener);
        boolean enabled = prefs.getBoolean(ConfigData.KEY_SERVICE_ENABLED, ConfigData.DEFAULT_SERVICE_ENABLED);

        startForeground(NOTIFICATION_ID, buildNotification(
                enabled ? "AutoTunnel 대기 중..." : "AutoTunnel 전원 꺼짐",
                enabled ? "GPS 신호를 검색하고 있습니다." : "오토터널 서비스가 비활성화되었습니다."
        ));

        detector = new TunnelDetector(this);
        vehicleController = new VehicleController(this);

        if (enabled) {
            startGpsTracking();
        }
        handler.post(tickRunnable);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null) {
            String action = intent.getAction();
            if (ACTION_REFRESH_TRACKING.equals(action)) {
                Log.i(TAG, "Refresh tracking action received.");
                startGpsTracking();
            } else if (ACTION_SIMULATE_TUNNEL.equals(action)) {
                simulateTunnelToggle();
            }
        }
        return START_STICKY;
    }

    private void simulateTunnelToggle() {
        SharedPreferences prefs = ConfigData.getPrefs(this);
        boolean autoCloseWin = prefs.getBoolean(ConfigData.KEY_AUTO_CLOSE_WINDOWS, ConfigData.DEFAULT_AUTO_CLOSE_WINDOWS);
        boolean safeClose = prefs.getBoolean(ConfigData.KEY_SAFE_WINDOW_CLOSE, ConfigData.DEFAULT_SAFE_WINDOW_CLOSE);

        if (!isRecirculationForcedByTunnel && !isWindowsClosedByTunnel) {
            Log.i(TAG, "Simulating tunnel entry -> Force Recirculation & Close Windows (safeClose=" + safeClose + ")");
            int cur = vehicleController.getAirConditionerCycleState();
            originalCycleModeBeforeTunnel = cur;
            vehicleController.setAirConditionerCycleState(VehicleController.CYCLE_INLOOP_RECYCLE);
            isRecirculationForcedByTunnel = true;

            if (autoCloseWin) {
                java.util.Map<Integer, Integer> openWindows = vehicleController.getOpenWindows();
                if (!openWindows.isEmpty()) {
                    savedWindowPercentagesBeforeTunnel.clear();
                    savedWindowPercentagesBeforeTunnel.putAll(openWindows);
                    vehicleController.closeWindows(openWindows.keySet(), savedWindowPercentagesBeforeTunnel, safeClose);
                    isWindowsClosedByTunnel = true;
                    Log.i(TAG, "Simulate: Closed open windows (safeClose=" + safeClose + ") and saved: " + openWindows);
                }
            }
            playTone(true);
        } else {
            Log.i(TAG, "Simulating tunnel exit -> Restore Original");
            restoreOriginalAirCycle();
            if (isWindowsClosedByTunnel) {
                restoreOriginalWindows();
            }
            playTone(false);
        }
    }

    private void onPeriodicTick() {
        SharedPreferences prefs = ConfigData.getPrefs(this);
        boolean enabled = prefs.getBoolean(ConfigData.KEY_SERVICE_ENABLED, ConfigData.DEFAULT_SERVICE_ENABLED);
        int exitDelaySec = prefs.getInt(ConfigData.KEY_EXIT_DELAY_SEC, ConfigData.DEFAULT_EXIT_DELAY_SEC);

        if (!enabled) {
            if (isRecirculationForcedByTunnel) {
                restoreOriginalAirCycle();
            }
            if (isWindowsClosedByTunnel) {
                restoreOriginalWindows();
            }
            detector.reset();
            updateSnapshot(lastKnownLocation, TunnelDetectionResult.normal("SERVICE_DISABLED"));
            return;
        }

        // Run detector tick (manages dead-reckoning inside tunnel & exit cooldown)
        TunnelDetectionResult tickResult = detector.tick(System.currentTimeMillis(), exitDelaySec);
        if (tickResult != null) {
            processDetectionResult(tickResult);
        }
    }

    private void startGpsTracking() {
        if (ContextCompat.checkSelfPermission(this, android.Manifest.permission.ACCESS_FINE_LOCATION)
                != PackageManager.PERMISSION_GRANTED) {
            Log.w(TAG, "ACCESS_FINE_LOCATION not granted yet.");
            return;
        }

        try {
            if (locationManager == null) {
                locationManager = (LocationManager) getSystemService(Context.LOCATION_SERVICE);
            }
            if (locationManager == null) return;

            if (locationListener != null) {
                locationManager.removeUpdates(locationListener);
            }

            locationListener = new LocationListener() {
                @Override
                public void onLocationChanged(Location location) {
                    if (location != null) {
                        lastKnownLocation = location;
                        handleLocationUpdate(location);
                    }
                }
                @Override public void onStatusChanged(String provider, int status, Bundle extras) {}
                @Override public void onProviderEnabled(String provider) {}
                @Override public void onProviderDisabled(String provider) {}
            };

            // Request updates every 1000ms, 0 meters
            if (locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
                locationManager.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1000L, 0f, locationListener);
                Log.i(TAG, "LocationListener registered with GPS_PROVIDER (1000ms / 0m)");
            }
            if (locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) {
                locationManager.requestLocationUpdates(LocationManager.NETWORK_PROVIDER, 2000L, 10f, locationListener);
                Log.i(TAG, "LocationListener registered with NETWORK_PROVIDER");
            }

            // Immediately check last known location if fresh
            Location lastKnown = locationManager.getLastKnownLocation(LocationManager.GPS_PROVIDER);
            if (lastKnown != null && (System.currentTimeMillis() - lastKnown.getTime()) < 60_000L) {
                lastKnownLocation = lastKnown;
                handleLocationUpdate(lastKnown);
            }

        } catch (Exception e) {
            Log.e(TAG, "Failed to start GPS tracking: " + e.getMessage(), e);
        }
    }

    private void handleLocationUpdate(Location location) {
        SharedPreferences prefs = ConfigData.getPrefs(this);
        boolean enabled = prefs.getBoolean(ConfigData.KEY_SERVICE_ENABLED, ConfigData.DEFAULT_SERVICE_ENABLED);
        if (!enabled) return;

        int approachDist = prefs.getInt(ConfigData.KEY_APPROACH_DISTANCE_M, ConfigData.DEFAULT_APPROACH_DISTANCE_M);
        int exitDelaySec = prefs.getInt(ConfigData.KEY_EXIT_DELAY_SEC, ConfigData.DEFAULT_EXIT_DELAY_SEC);
        int minLengthM = prefs.getInt(ConfigData.KEY_MIN_TUNNEL_LENGTH_M, ConfigData.DEFAULT_MIN_TUNNEL_LENGTH_M);

        double obdSpeed = vehicleController.getVehicleSpeedKmh();

        TunnelDetectionResult result = detector.update(location, approachDist, exitDelaySec, minLengthM, obdSpeed);
        processDetectionResult(result);
    }

    private void processDetectionResult(TunnelDetectionResult result) {
        if (result == null) return;

        SharedPreferences prefs = ConfigData.getPrefs(this);
        boolean autoCloseWin = prefs.getBoolean(ConfigData.KEY_AUTO_CLOSE_WINDOWS, ConfigData.DEFAULT_AUTO_CLOSE_WINDOWS);
        boolean safeClose = prefs.getBoolean(ConfigData.KEY_SAFE_WINDOW_CLOSE, ConfigData.DEFAULT_SAFE_WINDOW_CLOSE);

        if (result.shouldRecirculate) {
            // 1. 공조기 제어
            if (!isRecirculationForcedByTunnel) {
                int currentCycle = vehicleController.getAirConditionerCycleState();
                originalCycleModeBeforeTunnel = currentCycle;
                Log.i(TAG, "Tunnel active! Original A/C cycle state before tunnel: " + originalCycleModeBeforeTunnel
                        + " (1:Recycle, 0:Fresh, -1:Unknown). Reason: " + result.reason);

                if (currentCycle != VehicleController.CYCLE_INLOOP_RECYCLE) {
                    // Switch to recirculation
                    Log.i(TAG, "Overriding A/C to RECIRCULATION (내기).");
                    vehicleController.setAirConditionerCycleState(VehicleController.CYCLE_INLOOP_RECYCLE);
                    isRecirculationForcedByTunnel = true;
                    playTone(true);
                } else {
                    Log.i(TAG, "A/C was already in RECIRCULATION. Skipping override.");
                    isRecirculationForcedByTunnel = false;
                }
            }

            // 2. 창문 연동 제어: 현재 열려있는 창문 수치를 기억하고 닫기 (안전 닫기 지원)
            if (autoCloseWin && !isWindowsClosedByTunnel) {
                java.util.Map<Integer, Integer> openWindows = vehicleController.getOpenWindows();
                if (!openWindows.isEmpty()) {
                    savedWindowPercentagesBeforeTunnel.clear();
                    savedWindowPercentagesBeforeTunnel.putAll(openWindows);
                    vehicleController.closeWindows(openWindows.keySet(), savedWindowPercentagesBeforeTunnel, safeClose);
                    isWindowsClosedByTunnel = true;
                    Log.i(TAG, "Tunnel active: Closed open windows (safeClose=" + safeClose + ") and saved original percentages: " + openWindows);
                }
            }
        } else {
            // Normal area
            if (isRecirculationForcedByTunnel) {
                Log.i(TAG, "Tunnel area cleared. Restoring original air cycle mode.");
                restoreOriginalAirCycle();
                playTone(false);
            }
            if (isWindowsClosedByTunnel) {
                Log.i(TAG, "Tunnel area cleared. Restoring original window positions: " + savedWindowPercentagesBeforeTunnel);
                restoreOriginalWindows();
            }
        }

        updateSnapshot(lastKnownLocation, result);
        updateNotification(result);
    }

    private void restoreOriginalAirCycle() {
        // If it was fresh (0) or unknown (-1), restore to fresh (0)
        int target = (originalCycleModeBeforeTunnel == 1) ? 1 : 0;
        Log.i(TAG, "Restoring A/C cycle to: " + (target == 1 ? "RECIRCULATION" : "FRESH AIR"));
        vehicleController.setAirConditionerCycleState(target);
        isRecirculationForcedByTunnel = false;
        originalCycleModeBeforeTunnel = -1;
    }

    private void restoreOriginalWindows() {
        if (!savedWindowPercentagesBeforeTunnel.isEmpty()) {
            vehicleController.restoreWindows(new java.util.HashMap<>(savedWindowPercentagesBeforeTunnel));
            savedWindowPercentagesBeforeTunnel.clear();
        }
        isWindowsClosedByTunnel = false;
    }

    private void playTone(boolean enter) {
        SharedPreferences prefs = ConfigData.getPrefs(this);
        if (!prefs.getBoolean(ConfigData.KEY_ALERT_SOUND_ENABLED, ConfigData.DEFAULT_ALERT_SOUND_ENABLED)) return;

        int resId = enter ? R.raw.tunnel_start : R.raw.tunnel_end;
        try {
            if (mediaPlayer != null) {
                try {
                    if (mediaPlayer.isPlaying()) {
                        mediaPlayer.stop();
                    }
                    mediaPlayer.release();
                } catch (Exception ignored) {}
                mediaPlayer = null;
            }

            mediaPlayer = MediaPlayer.create(this, resId);
            if (mediaPlayer != null) {
                mediaPlayer.setOnCompletionListener(mp -> {
                    try {
                        mp.release();
                    } catch (Exception ignored) {}
                    if (mediaPlayer == mp) {
                        mediaPlayer = null;
                    }
                });
                mediaPlayer.start();
            }
        } catch (Exception e) {
            Log.w(TAG, "Failed to play tunnel sound: " + e.getMessage());
        }
    }

    private void updateSnapshot(Location loc, TunnelDetectionResult res) {
        int curAc = vehicleController.getAirConditionerCycleState();
        double speed = vehicleController.getVehicleSpeedKmh();
        latestSnapshot = new TunnelDebugSnapshot(
                loc,
                res,
                detector.getLastCandidates(),
                isRecirculationForcedByTunnel,
                originalCycleModeBeforeTunnel,
                curAc,
                speed,
                isWindowsClosedByTunnel,
                savedWindowPercentagesBeforeTunnel.size()
        );
    }

    private void stopGpsTracking() {
        if (locationManager != null && locationListener != null) {
            try {
                locationManager.removeUpdates(locationListener);
                Log.i(TAG, "LocationListener removed (GPS tracking paused).");
            } catch (Exception e) {
                Log.w(TAG, "Failed to remove location updates: " + e.getMessage());
            }
        }
    }

    private void updateNotification(String title, String content) {
        NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm != null) {
            nm.notify(NOTIFICATION_ID, buildNotification(title, content));
        }
    }

    private void updateNotification(TunnelDetectionResult result) {
        String title;
        String content;
        Tunnel t = result.tunnel;
        String name = (t != null) ? t.getDisplayName() : "";

        switch (result.state) {
            case APPROACHING:
                title = "터널 접근 중: " + name;
                content = String.format("약 %.0fm 전방 진입 예정 (내기 모드 전환됨)", result.distanceMeters);
                break;
            case IN_TUNNEL:
                title = "터널 주행 중: " + name;
                content = result.timeRemainingSec > 0
                        ? String.format("내기 순환 유지 (예상 잔여 %d초)", result.timeRemainingSec)
                        : "내기 순환 유지 중";
                break;
            case EXITING:
                title = "터널 탈출 완료: " + name;
                content = String.format("출구 매연 차단 쿨다운 (%d초 후 외기 복원)", result.timeRemainingSec);
                break;
            case NORMAL:
            default:
                title = "AutoTunnel 정상 감시 중";
                content = isRecirculationForcedByTunnel
                        ? "공조기 제어 중"
                        : "터널 진입 시 자동으로 내기로 전환합니다.";
                break;
        }

        updateNotification(title, content);
    }

    private Notification buildNotification(String title, String content) {
        Intent openUiIntent = new Intent(this, MainActivity.class);
        PendingIntent pi = PendingIntent.getActivity(
                this, 0, openUiIntent, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
        );

        return new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle(title)
                .setContentText(content)
                .setSmallIcon(android.R.drawable.ic_dialog_map)
                .setContentIntent(pi)
                .setOngoing(true)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .build();
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID,
                    "AutoTunnel Service",
                    NotificationManager.IMPORTANCE_LOW
            );
            channel.setDescription("터널 감지 및 자동 공조기 제어 백그라운드 서비스");
            NotificationManager nm = getSystemService(NotificationManager.class);
            if (nm != null) {
                nm.createNotificationChannel(channel);
            }
        }
    }

    @Override
    public void onDestroy() {
        Log.i(TAG, "onDestroy: Stopping AutoTunnelService");
        isRunning = false;
        handler.removeCallbacks(tickRunnable);

        SharedPreferences prefs = ConfigData.getPrefs(this);
        try {
            prefs.unregisterOnSharedPreferenceChangeListener(prefListener);
        } catch (Exception ignored) {}

        stopGpsTracking();

        if (isRecirculationForcedByTunnel) {
            restoreOriginalAirCycle();
        }

        if (isWindowsClosedByTunnel) {
            restoreOriginalWindows();
        }

        if (vehicleController != null) {
            vehicleController.shutdown();
        }

        if (mediaPlayer != null) {
            try {
                if (mediaPlayer.isPlaying()) {
                    mediaPlayer.stop();
                }
                mediaPlayer.release();
            } catch (Exception ignored) {}
            mediaPlayer = null;
        }

        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
