package com.poorgrammera.autotunnel.ui;

import android.Manifest;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.location.Location;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.preference.PreferenceManager;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.SwitchCompat;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import com.poorgrammera.autotunnel.R;
import com.poorgrammera.autotunnel.data.ConfigData;
import com.poorgrammera.autotunnel.model.GeoPoint;
import com.poorgrammera.autotunnel.model.GeoUtils;
import com.poorgrammera.autotunnel.model.OrientedBox;
import com.poorgrammera.autotunnel.model.Tunnel;
import com.poorgrammera.autotunnel.model.TunnelDebugSnapshot;
import com.poorgrammera.autotunnel.model.TunnelDetectionResult;
import com.poorgrammera.autotunnel.model.TunnelStore;
import com.poorgrammera.autotunnel.service.AutoTunnelService;
import com.poorgrammera.autotunnel.vehicle.VehicleController;

import org.osmdroid.config.Configuration;
import org.osmdroid.tileprovider.tilesource.TileSourceFactory;
import org.osmdroid.views.MapView;
import org.osmdroid.views.overlay.Marker;
import org.osmdroid.views.overlay.Polygon;
import org.osmdroid.views.overlay.Polyline;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public class MainActivity extends AppCompatActivity {
    private static final String TAG = "MainActivity";
    private static final int PERMISSION_REQ_CODE = 101;
    private static final long REFRESH_INTERVAL_MS = 1000L;

    private static final int COLOR_CORE_BOX_STROKE = Color.rgb(255, 140, 0);     // Orange (Tunnel Box)
    private static final int COLOR_CORE_BOX_FILL = Color.argb(35, 255, 152, 0);
    private static final int COLOR_ENTRY_BOX_STROKE = Color.rgb(33, 150, 243);   // Blue (Start Bounding Box)
    private static final int COLOR_ENTRY_BOX_FILL = Color.argb(45, 33, 150, 243);

    private final Handler handler = new Handler(Looper.getMainLooper());

    private View layoutStatusHeader;
    private TextView tvStatusEvent;
    private TextView tvStatusDetail;
    private TextView tvTelemetryInfo;
    private SwitchCompat switchServiceEnable;
    private SwitchCompat switchAutoCloseWindows;
    private SwitchCompat switchSafeWindowClose;
    private SwitchCompat switchAlertSound;
    private Button btnFollowVehicle;
    private Button btnOpenTunnelEditor;
    private MapView mapView;

    private boolean initiallyCentered = false;
    private boolean isFollowVehicle = true;
    private float lastKnownBearing = 0.0f;
    private Location lastKnownVehicleLocation = null;

    private final Runnable refreshRunnable = new Runnable() {
        @Override
        public void run() {
            renderSnapshot(AutoTunnelService.getLatestSnapshot());
            handler.postDelayed(this, REFRESH_INTERVAL_MS);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        Configuration.getInstance().setUserAgentValue(getPackageName());
        Configuration.getInstance().load(this, PreferenceManager.getDefaultSharedPreferences(this));

        setContentView(R.layout.activity_main);

        bindViews();
        setupListeners();
        checkPermissionsAndStartService();
    }

    private void bindViews() {
        layoutStatusHeader = findViewById(R.id.layout_status_header);
        tvStatusEvent = findViewById(R.id.tv_tunnel_event_status);
        tvStatusDetail = findViewById(R.id.tv_tunnel_detail_status);
        tvTelemetryInfo = findViewById(R.id.tv_telemetry_info);
        switchServiceEnable = findViewById(R.id.switch_service_enable);
        switchAutoCloseWindows = findViewById(R.id.switch_auto_close_windows);
        switchSafeWindowClose = findViewById(R.id.switch_safe_window_close);
        switchAlertSound = findViewById(R.id.switch_alert_sound);
        btnFollowVehicle = findViewById(R.id.btn_follow_vehicle);
        btnOpenTunnelEditor = findViewById(R.id.btn_open_tunnel_editor);
        mapView = findViewById(R.id.map_view);

        mapView.setTileSource(TileSourceFactory.MAPNIK);
        mapView.setMultiTouchControls(true);
        mapView.getController().setZoom(15.0);

        SharedPreferences prefs = ConfigData.getPrefs(this);
        switchServiceEnable.setChecked(prefs.getBoolean(ConfigData.KEY_SERVICE_ENABLED, ConfigData.DEFAULT_SERVICE_ENABLED));
        boolean autoClose = prefs.getBoolean(ConfigData.KEY_AUTO_CLOSE_WINDOWS, ConfigData.DEFAULT_AUTO_CLOSE_WINDOWS);
        switchAutoCloseWindows.setChecked(autoClose);
        switchSafeWindowClose.setChecked(prefs.getBoolean(ConfigData.KEY_SAFE_WINDOW_CLOSE, ConfigData.DEFAULT_SAFE_WINDOW_CLOSE));
        switchAlertSound.setChecked(prefs.getBoolean(ConfigData.KEY_ALERT_SOUND_ENABLED, ConfigData.DEFAULT_ALERT_SOUND_ENABLED));

        updateSafeWindowCloseState(autoClose);
        updateFollowVehicleButton(true);
    }

    private void updateFollowVehicleButton(boolean follow) {
        this.isFollowVehicle = follow;
        if (btnFollowVehicle != null) {
            btnFollowVehicle.setText(follow ? "📍 추적 중" : "📍 내 위치");
            btnFollowVehicle.setBackgroundTintList(android.content.res.ColorStateList.valueOf(
                    follow ? Color.rgb(2, 136, 209) : Color.rgb(69, 90, 100)
            ));
        }
    }

    private void updateSafeWindowCloseState(boolean autoCloseEnabled) {
        if (switchSafeWindowClose != null) {
            switchSafeWindowClose.setEnabled(autoCloseEnabled);
            switchSafeWindowClose.setAlpha(autoCloseEnabled ? 1.0f : 0.4f);
        }
    }

    private void setupListeners() {
        switchServiceEnable.setOnCheckedChangeListener((btn, isChecked) -> {
            ConfigData.getPrefs(this).edit().putBoolean(ConfigData.KEY_SERVICE_ENABLED, isChecked).apply();
            Toast.makeText(this, isChecked ? "오토터널 서비스가 켜졌습니다." : "오토터널 서비스가 꺼졌습니다 (대기 모드).", Toast.LENGTH_SHORT).show();
            if (!isChecked) {
                renderSnapshot(null);
            }
        });

        switchAutoCloseWindows.setOnCheckedChangeListener((btn, isChecked) -> {
            ConfigData.getPrefs(this).edit().putBoolean(ConfigData.KEY_AUTO_CLOSE_WINDOWS, isChecked).apply();
            updateSafeWindowCloseState(isChecked);
            Toast.makeText(this, isChecked ? "창문 연동이 켜졌습니다." : "창문 연동이 꺼졌습니다.", Toast.LENGTH_SHORT).show();
        });

        switchSafeWindowClose.setOnCheckedChangeListener((btn, isChecked) -> {
            ConfigData.getPrefs(this).edit().putBoolean(ConfigData.KEY_SAFE_WINDOW_CLOSE, isChecked).apply();
            Toast.makeText(this, isChecked
                    ? "안전 창문 닫기 켜짐 (10% 예고 ➔ 틈새 ➔ 완전 닫힘)"
                    : "안전 창문 닫기 꺼짐 (즉시 닫힘)", Toast.LENGTH_SHORT).show();
        });

        switchAlertSound.setOnCheckedChangeListener((btn, isChecked) -> {
            ConfigData.getPrefs(this).edit().putBoolean(ConfigData.KEY_ALERT_SOUND_ENABLED, isChecked).apply();
            Toast.makeText(this, isChecked ? "터널 알림음이 켜졌습니다." : "터널 알림음이 꺼졌습니다.", Toast.LENGTH_SHORT).show();
        });

        btnFollowVehicle.setOnClickListener(v -> {
            updateFollowVehicleButton(!isFollowVehicle);
            if (isFollowVehicle && lastKnownVehicleLocation != null) {
                mapView.getController().animateTo(new org.osmdroid.util.GeoPoint(
                        lastKnownVehicleLocation.getLatitude(), lastKnownVehicleLocation.getLongitude()
                ));
            }
        });

        mapView.setOnTouchListener((v, event) -> {
            if (event.getAction() == android.view.MotionEvent.ACTION_MOVE) {
                if (isFollowVehicle) {
                    updateFollowVehicleButton(false);
                }
            }
            return false;
        });

        btnOpenTunnelEditor.setOnClickListener(v -> {
            Intent intent = new Intent(this, TunnelEditorActivity.class);
            startActivity(intent);
        });

        layoutStatusHeader.setOnClickListener(v -> {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
                    != PackageManager.PERMISSION_GRANTED) {
                checkPermissionsAndStartService();
            }
        });
    }

    private void checkPermissionsAndStartService() {
        List<String> needed = new ArrayList<>();
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
                != PackageManager.PERMISSION_GRANTED) {
            needed.add(Manifest.permission.ACCESS_FINE_LOCATION);
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION)
                != PackageManager.PERMISSION_GRANTED) {
            needed.add(Manifest.permission.ACCESS_COARSE_LOCATION);
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                    != PackageManager.PERMISSION_GRANTED) {
                needed.add(Manifest.permission.POST_NOTIFICATIONS);
            }
        }

        if (!needed.isEmpty()) {
            ActivityCompat.requestPermissions(this, needed.toArray(new String[0]), PERMISSION_REQ_CODE);
        } else {
            startAutoTunnelService();
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == PERMISSION_REQ_CODE) {
            boolean fineLocationGranted = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
                    == PackageManager.PERMISSION_GRANTED;
            if (fineLocationGranted) {
                startAutoTunnelService();
            } else {
                Toast.makeText(this, "터널 자동 감지를 위해 위치 권한(정확한 위치) 허용이 필요합니다.", Toast.LENGTH_LONG).show();
            }
        }
    }

    private void startAutoTunnelService() {
        Intent serviceIntent = new Intent(this, AutoTunnelService.class);
        ContextCompat.startForegroundService(this, serviceIntent);
    }

    @Override
    protected void onResume() {
        super.onResume();
        mapView.onResume();
        handler.post(refreshRunnable);
    }

    @Override
    protected void onPause() {
        handler.removeCallbacks(refreshRunnable);
        mapView.onPause();
        super.onPause();
    }

    private void renderSnapshot(TunnelDebugSnapshot snapshot) {
        boolean isServiceEnabled = ConfigData.getPrefs(this).getBoolean(ConfigData.KEY_SERVICE_ENABLED, ConfigData.DEFAULT_SERVICE_ENABLED);
        if (!isServiceEnabled) {
            tvStatusEvent.setText("오토터널 전원 꺼짐 (대기)");
            layoutStatusHeader.setBackgroundColor(Color.rgb(38, 50, 56));
            tvStatusDetail.setVisibility(View.GONE);
            tvTelemetryInfo.setText("전원이 꺼져 있습니다 | 백그라운드 GPS 비활성화됨");
            drawMapWhenNoLocation();
            return;
        }

        if (snapshot == null || snapshot.location == null) {
            boolean hasPermission = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
                    == PackageManager.PERMISSION_GRANTED;
            if (!hasPermission) {
                tvStatusEvent.setText("위치 권한 필요 (여기를 눌러 허용)");
                layoutStatusHeader.setBackgroundColor(Color.rgb(183, 28, 28));
            } else {
                tvStatusEvent.setText("GPS 수신 대기 중");
                layoutStatusHeader.setBackgroundColor(Color.rgb(55, 71, 79));
            }
            tvStatusDetail.setVisibility(View.GONE);
            drawMapWhenNoLocation();
            return;
        }

        lastKnownVehicleLocation = snapshot.location;

        TunnelDetectionResult res = snapshot.result;
        Tunnel tunnel = res.tunnel;
        String tName = tunnel != null ? tunnel.getDisplayName() : "미확인 터널";
        String winStr = snapshot.isWindowsClosedByTunnel
                ? " | 창문 " + snapshot.savedWindowsCount + "개 닫힘"
                : "";

        switch (res.state) {
            case APPROACHING:
                layoutStatusHeader.setBackgroundColor(Color.rgb(230, 81, 0)); // Orange
                tvStatusEvent.setText("전방 터널 접근 중: " + tName);
                tvStatusDetail.setVisibility(View.VISIBLE);
                tvStatusDetail.setText(String.format(Locale.KOREAN, "약 %.0fm 전방 진입 (내기 전환 완료)%s", res.distanceMeters, winStr));
                break;

            case IN_TUNNEL:
                layoutStatusHeader.setBackgroundColor(Color.rgb(46, 125, 50)); // Green
                tvStatusEvent.setText("터널 내부 주행 중: " + tName);
                tvStatusDetail.setVisibility(View.VISIBLE);
                String remainStr = res.timeRemainingSec > 0
                        ? String.format(Locale.KOREAN, "내기 순환 모드 유지 중 (예상 잔여 %d초)%s", res.timeRemainingSec, winStr)
                        : "내기 순환 모드 유지 중" + winStr;
                tvStatusDetail.setText(remainStr);
                break;

            case EXITING:
                layoutStatusHeader.setBackgroundColor(Color.rgb(0, 131, 143)); // Cyan/Teal
                tvStatusEvent.setText("터널 통과 완료: " + tName);
                tvStatusDetail.setVisibility(View.VISIBLE);
                tvStatusDetail.setText(String.format(Locale.KOREAN, "출구 매연 차단 쿨다운 (%d초 후 외기 복원)%s", res.timeRemainingSec, winStr));
                break;

            case NORMAL:
            default:
                layoutStatusHeader.setBackgroundColor(Color.rgb(38, 50, 56)); // Dark Slate
                String acModeStr = (snapshot.currentAcMode == VehicleController.CYCLE_INLOOP_RECYCLE) ? "내기" : "외기";
                tvStatusEvent.setText("정상 주행 중 (" + acModeStr + " 순환)");
                tvStatusDetail.setVisibility(View.GONE);
                tvStatusDetail.setText("");
                break;
        }

        // Telemetry
        double speed = snapshot.vehicleSpeedKmh >= 0 ? snapshot.vehicleSpeedKmh : (snapshot.location.getSpeed() * 3.6);
        tvTelemetryInfo.setText(String.format(Locale.KOREAN,
                "속도: %.0f km/h | 후보 터널: %d개 | GPS 정밀도: %.0fm",
                speed, snapshot.nearbyTunnels.size(), snapshot.location.getAccuracy()
        ));

        drawMap(snapshot);
    }

    private void drawMapWhenNoLocation() {
        mapView.getOverlays().clear();
        org.osmdroid.api.IGeoPoint center = mapView.getMapCenter();
        List<Tunnel> viewTunnels = TunnelStore.getInstance(this).queryNearby(center.getLatitude(), center.getLongitude(), 3000.0);
        for (Tunnel t : viewTunnels) {
            addTunnelPolyline(t, Color.rgb(144, 164, 174), 4f);
            addTunnelBoxes(t, false);
        }
        mapView.invalidate();
    }

    private void drawMap(TunnelDebugSnapshot snapshot) {
        mapView.getOverlays().clear();
        Tunnel selected = snapshot.result.tunnel;
        boolean isDetected = snapshot.result.shouldRecirculate;
        java.util.Set<Long> drawnTunnelIds = new java.util.HashSet<>();

        // 1. Draw candidate tunnels near vehicle
        for (Tunnel tunnel : snapshot.nearbyTunnels) {
            boolean isSelected = (selected != null && tunnel.id == selected.id);
            int color = isSelected
                    ? (isDetected ? Color.rgb(0, 230, 118) : Color.rgb(255, 171, 0))
                    : Color.rgb(144, 164, 174);
            addTunnelPolyline(tunnel, color, isSelected ? 8f : 4f);
            addTunnelBoxes(tunnel, isSelected);
            drawnTunnelIds.add(tunnel.id);
        }

        // 2. Also draw tunnels around visible map center if user moved the map away from vehicle
        if (!isFollowVehicle) {
            org.osmdroid.api.IGeoPoint center = mapView.getMapCenter();
            List<Tunnel> viewTunnels = TunnelStore.getInstance(this).queryNearby(center.getLatitude(), center.getLongitude(), 3000.0);
            for (Tunnel t : viewTunnels) {
                if (!drawnTunnelIds.contains(t.id)) {
                    addTunnelPolyline(t, Color.rgb(144, 164, 174), 4f);
                    addTunnelBoxes(t, false);
                    drawnTunnelIds.add(t.id);
                }
            }
        }

        // Vehicle Marker & Range circle
        org.osmdroid.util.GeoPoint vehiclePoint = new org.osmdroid.util.GeoPoint(
                snapshot.location.getLatitude(), snapshot.location.getLongitude()
        );
        addRangeCircle(vehiclePoint);

        if (snapshot.location.hasBearing()) {
            lastKnownBearing = snapshot.location.getBearing();
        }

        Marker vehicleMarker = UiUtils.createVehicleMarker(mapView, snapshot.location, lastKnownBearing);
        if (vehicleMarker != null) {
            mapView.getOverlays().add(vehicleMarker);
        }

        if (!initiallyCentered) {
            initiallyCentered = true;
            mapView.getController().setCenter(vehiclePoint);
        } else if (isFollowVehicle) {
            mapView.getController().setCenter(vehiclePoint);
        }

        mapView.invalidate();
    }
    private void addRangeCircle(org.osmdroid.util.GeoPoint center) {
        Polygon circle = new Polygon(mapView);
        circle.setPoints(Polygon.pointsAsCircle(center, 2000.0));
        circle.getOutlinePaint().setColor(Color.rgb(3, 169, 244));
        circle.getOutlinePaint().setStrokeWidth(2f);
        circle.getFillPaint().setColor(Color.argb(20, 3, 169, 244));
        mapView.getOverlays().add(circle);
    }

    private void addTunnelPolyline(Tunnel tunnel, int color, float width) {
        List<org.osmdroid.util.GeoPoint> points = new ArrayList<>();
        for (GeoPoint p : tunnel.getPoints()) {
            points.add(new org.osmdroid.util.GeoPoint(p.lat, p.lng));
        }
        if (points.size() < 2) return;

        Polyline line = new Polyline(mapView);
        line.setPoints(points);
        line.getOutlinePaint().setColor(color);
        line.getOutlinePaint().setStrokeWidth(width);
        line.setTitle(tunnel.getDisplayName());
        mapView.getOverlays().add(line);
    }

    /**
     * Renders user sketch-based bounding boxes:
     * 1. Orange Core Box covering the full tunnel
     * 2. Blue Start Bounding Box (~200m ahead of entrance) for collision trigger
     */
    private void addTunnelBoxes(Tunnel tunnel, boolean isSelected) {
        float strokeWidth = isSelected ? 3.5f : 2.0f;

        // 1. Orange Tunnel Box (Core)
        OrientedBox coreBox = tunnel.getCoreBox();
        if (coreBox != null) {
            addOrientedBoxPolygon(coreBox, COLOR_CORE_BOX_STROKE, COLOR_CORE_BOX_FILL, strokeWidth,
                    tunnel.getDisplayName() + " [Tunnel Box]");
        }

        // 2. Blue Start Bounding Box (Entry trigger)
        OrientedBox entryBox = tunnel.getEntryBox();
        if (entryBox != null) {
            addOrientedBoxPolygon(entryBox, COLOR_ENTRY_BOX_STROKE, COLOR_ENTRY_BOX_FILL, strokeWidth,
                    tunnel.getDisplayName() + " [Start Bounding Box]");
        }

        // 3. Blue Start Bounding Box for reverse entrance (if two-way tunnel)
        if (!tunnel.oneway) {
            OrientedBox revBox = tunnel.getReverseEntryBox();
            if (revBox != null) {
                addOrientedBoxPolygon(revBox, COLOR_ENTRY_BOX_STROKE, COLOR_ENTRY_BOX_FILL, strokeWidth,
                        tunnel.getDisplayName() + " [Reverse Start Box]");
            }
        }
    }

    private void addOrientedBoxPolygon(OrientedBox box, int strokeColor, int fillColor, float strokeWidth, String title) {
        List<GeoPoint> corners = box.getCorners();
        List<org.osmdroid.util.GeoPoint> osmdroidCorners = new ArrayList<>(corners.size());
        for (GeoPoint pt : corners) {
            osmdroidCorners.add(new org.osmdroid.util.GeoPoint(pt.lat, pt.lng));
        }

        Polygon polygon = new Polygon(mapView);
        polygon.setPoints(osmdroidCorners);
        polygon.getOutlinePaint().setColor(strokeColor);
        polygon.getOutlinePaint().setStrokeWidth(strokeWidth);
        polygon.getFillPaint().setColor(fillColor);
        polygon.setTitle(title);
        mapView.getOverlays().add(polygon);
    }
}
