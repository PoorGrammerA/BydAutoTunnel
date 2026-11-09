package com.poorgrammera.autotunnel.ui;

import android.content.DialogInterface;
import android.graphics.Color;
import android.location.Location;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.SwitchCompat;

import com.poorgrammera.autotunnel.R;
import com.poorgrammera.autotunnel.data.TunnelJsonExporter;
import com.poorgrammera.autotunnel.data.UserCustomTunnel;
import com.poorgrammera.autotunnel.data.UserTunnelDbHelper;
import com.poorgrammera.autotunnel.data.UserTunnelOverride;
import com.poorgrammera.autotunnel.model.GeoPoint;
import com.poorgrammera.autotunnel.model.GeoUtils;
import com.poorgrammera.autotunnel.model.OrientedBox;
import com.poorgrammera.autotunnel.model.Tunnel;
import com.poorgrammera.autotunnel.model.TunnelStore;
import com.poorgrammera.autotunnel.service.AutoTunnelService;

import org.osmdroid.events.MapEventsReceiver;
import org.osmdroid.events.MapListener;
import org.osmdroid.events.ScrollEvent;
import org.osmdroid.events.ZoomEvent;
import org.osmdroid.tileprovider.tilesource.TileSourceFactory;
import org.osmdroid.views.MapView;
import org.osmdroid.views.overlay.MapEventsOverlay;
import org.osmdroid.views.overlay.Marker;
import org.osmdroid.views.overlay.Polygon;
import org.osmdroid.views.overlay.Polyline;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Dedicated visual editor Activity for customizing, creating, deleting, and backing up tunnel DB.
 */
public class TunnelEditorActivity extends AppCompatActivity {

    private enum AddModeState {
        OFF,
        WAITING_START,
        WAITING_END
    }

    private MapView mapView;
    private View layoutGuideBanner;
    private TextView tvGuideText;
    private Button btnUseCurrentLoc;
    private Button btnCancelAddMode;

    private Button btnStartAddTunnel;
    private Button btnCenterCurrentLocation;
    private Button btnJsonBackup;
    private Button btnBack;

    // Bottom Panel
    private LinearLayout layoutBottomPanel;
    private EditText etTunnelName;
    private TextView tvTunnelBadge;
    private TextView tvTunnelLengthInfo;
    private TextView tvCurrentBearing;
    private Button btnBearingMinus5, btnBearingMinus1, btnBearingPlus1, btnBearingPlus5;
    private SwitchCompat switchTunnelOneway;
    private Button btnStartDist150, btnStartDist200, btnStartDist250;
    private Button btnDeleteOrRestore, btnResetToDefault, btnClosePanel, btnSaveChanges;

    // State
    private AddModeState addModeState = AddModeState.OFF;
    private GeoPoint newTunnelStart = null;
    private GeoPoint newTunnelEnd = null;

    private Tunnel selectedTunnel = null;
    private double currentEditBearing = 0.0;
    private int currentEditStartDist = 200;
    private boolean currentEditOneway = false;
    private boolean currentEditIsDeleted = false;
    private float lastKnownVehicleBearing = 0.0f;

    private TunnelStore tunnelStore;
    private UserTunnelDbHelper dbHelper;

    private final Handler mapRefreshHandler = new Handler(Looper.getMainLooper());
    private final Runnable mapRefreshRunnable = this::refreshMap;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_tunnel_editor);

        tunnelStore = TunnelStore.getInstance(this);
        dbHelper = UserTunnelDbHelper.getInstance(this);

        bindViews();
        setupMap();
        setupListeners();

        refreshMap();
    }

    private void bindViews() {
        mapView = findViewById(R.id.editor_map_view);
        layoutGuideBanner = findViewById(R.id.layout_guide_banner);
        tvGuideText = findViewById(R.id.tv_guide_text);
        btnUseCurrentLoc = findViewById(R.id.btn_use_current_loc_as_entrance);
        btnCancelAddMode = findViewById(R.id.btn_cancel_add_mode);

        btnStartAddTunnel = findViewById(R.id.btn_start_add_tunnel);
        btnCenterCurrentLocation = findViewById(R.id.btn_center_current_location);
        btnJsonBackup = findViewById(R.id.btn_json_backup);
        btnBack = findViewById(R.id.btn_back);

        layoutBottomPanel = findViewById(R.id.layout_bottom_panel);
        etTunnelName = findViewById(R.id.et_tunnel_name);
        tvTunnelBadge = findViewById(R.id.tv_tunnel_badge);
        tvTunnelLengthInfo = findViewById(R.id.tv_tunnel_length_info);
        tvCurrentBearing = findViewById(R.id.tv_current_bearing);
        btnBearingMinus5 = findViewById(R.id.btn_bearing_minus_5);
        btnBearingMinus1 = findViewById(R.id.btn_bearing_minus_1);
        btnBearingPlus1 = findViewById(R.id.btn_bearing_plus_1);
        btnBearingPlus5 = findViewById(R.id.btn_bearing_plus_5);
        switchTunnelOneway = findViewById(R.id.switch_tunnel_oneway);
        btnStartDist150 = findViewById(R.id.btn_start_dist_150);
        btnStartDist200 = findViewById(R.id.btn_start_dist_200);
        btnStartDist250 = findViewById(R.id.btn_start_dist_250);
        btnDeleteOrRestore = findViewById(R.id.btn_delete_or_restore);
        btnResetToDefault = findViewById(R.id.btn_reset_to_default);
        btnClosePanel = findViewById(R.id.btn_close_panel);
        btnSaveChanges = findViewById(R.id.btn_save_changes);
    }

    private void setupMap() {
        mapView.setTileSource(TileSourceFactory.MAPNIK);
        mapView.setMultiTouchControls(true);
        mapView.getController().setZoom(15.5);

        // Center on last vehicle location if available
        Location lastLoc = AutoTunnelService.getLatestSnapshot() != null
                ? AutoTunnelService.getLatestSnapshot().location : null;
        if (lastLoc != null) {
            mapView.getController().setCenter(new org.osmdroid.util.GeoPoint(lastLoc.getLatitude(), lastLoc.getLongitude()));
        } else {
            // Default center around Seoul
            mapView.getController().setCenter(new org.osmdroid.util.GeoPoint(37.5665, 126.9780));
        }

        // Tap event overlay
        MapEventsOverlay mapEventsOverlay = new MapEventsOverlay(new MapEventsReceiver() {
            @Override
            public boolean singleTapConfirmedHelper(org.osmdroid.util.GeoPoint p) {
                handleMapTap(new GeoPoint(p.getLatitude(), p.getLongitude()));
                return true;
            }

            @Override
            public boolean longPressHelper(org.osmdroid.util.GeoPoint p) {
                return false;
            }
        });
        mapView.getOverlays().add(0, mapEventsOverlay);

        mapView.addMapListener(new MapListener() {
            @Override
            public boolean onScroll(ScrollEvent event) {
                scheduleMapRefresh();
                return false;
            }

            @Override
            public boolean onZoom(ZoomEvent event) {
                scheduleMapRefresh();
                return false;
            }
        });
    }

    private void scheduleMapRefresh() {
        mapRefreshHandler.removeCallbacks(mapRefreshRunnable);
        mapRefreshHandler.postDelayed(mapRefreshRunnable, 250);
    }

    private void setupListeners() {
        btnBack.setOnClickListener(v -> finish());

        btnCenterCurrentLocation.setOnClickListener(v -> {
            Location loc = AutoTunnelService.getLatestSnapshot() != null ? AutoTunnelService.getLatestSnapshot().location : null;
            if (loc != null) {
                mapView.getController().animateTo(new org.osmdroid.util.GeoPoint(loc.getLatitude(), loc.getLongitude()));
            } else {
                Toast.makeText(this, "수신된 차량 GPS 위치가 없습니다.", Toast.LENGTH_SHORT).show();
            }
        });

        btnStartAddTunnel.setOnClickListener(v -> enterAddMode());

        btnCancelAddMode.setOnClickListener(v -> exitAddMode());

        btnUseCurrentLoc.setOnClickListener(v -> {
            Location loc = AutoTunnelService.getLatestSnapshot() != null ? AutoTunnelService.getLatestSnapshot().location : null;
            if (loc != null) {
                handleMapTap(new GeoPoint(loc.getLatitude(), loc.getLongitude()));
            } else {
                Toast.makeText(this, "수신된 차량 GPS 위치가 없습니다.", Toast.LENGTH_SHORT).show();
            }
        });

        btnJsonBackup.setOnClickListener(v -> showJsonBackupDialog());

        // Bearing adjustment buttons
        btnBearingMinus5.setOnClickListener(v -> adjustBearing(-5.0));
        btnBearingMinus1.setOnClickListener(v -> adjustBearing(-1.0));
        btnBearingPlus1.setOnClickListener(v -> adjustBearing(1.0));
        btnBearingPlus5.setOnClickListener(v -> adjustBearing(5.0));

        // Start box distance options
        btnStartDist150.setOnClickListener(v -> selectStartDist(150));
        btnStartDist200.setOnClickListener(v -> selectStartDist(200));
        btnStartDist250.setOnClickListener(v -> selectStartDist(250));

        switchTunnelOneway.setOnCheckedChangeListener((b, isChecked) -> {
            currentEditOneway = isChecked;
            refreshMap();
        });

        btnDeleteOrRestore.setOnClickListener(v -> toggleDeleteStatus());

        btnResetToDefault.setOnClickListener(v -> resetSelectedTunnelToDefault());

        btnClosePanel.setOnClickListener(v -> closeEditPanel());

        btnSaveChanges.setOnClickListener(v -> saveCurrentEdits());
    }

    // ==================== Map Interaction & Add Mode ====================

    private void enterAddMode() {
        addModeState = AddModeState.WAITING_START;
        newTunnelStart = null;
        newTunnelEnd = null;
        selectedTunnel = null;
        layoutBottomPanel.setVisibility(View.GONE);

        layoutGuideBanner.setVisibility(View.VISIBLE);
        tvGuideText.setText("지도를 탭하여 터널 입구를 지정하세요 (1/2)");
        btnUseCurrentLoc.setVisibility(View.VISIBLE);
        refreshMap();
    }

    private void exitAddMode() {
        addModeState = AddModeState.OFF;
        newTunnelStart = null;
        newTunnelEnd = null;
        layoutGuideBanner.setVisibility(View.GONE);
        layoutBottomPanel.setVisibility(View.GONE);
        refreshMap();
    }

    private void handleMapTap(GeoPoint tapPoint) {
        if (addModeState == AddModeState.WAITING_START) {
            newTunnelStart = tapPoint;
            addModeState = AddModeState.WAITING_END;
            tvGuideText.setText("지도를 탭하여 터널 출구를 지정하세요 (2/2)");
            btnUseCurrentLoc.setVisibility(View.GONE);
            refreshMap();
            return;
        }

        if (addModeState == AddModeState.WAITING_END) {
            newTunnelEnd = tapPoint;
            addModeState = AddModeState.OFF;
            layoutGuideBanner.setVisibility(View.GONE);

            // Compute distance and bearing
            float lengthM = (float) GeoUtils.distanceMeters(newTunnelStart.lat, newTunnelStart.lng, newTunnelEnd.lat, newTunnelEnd.lng);
            double bearing = GeoUtils.bearingDegrees(newTunnelStart.lat, newTunnelStart.lng, newTunnelEnd.lat, newTunnelEnd.lng);

            List<GeoPoint> pts = new ArrayList<>();
            pts.add(newTunnelStart);
            pts.add(newTunnelEnd);

            long customId = dbHelper.generateNextCustomId();
            selectedTunnel = new Tunnel(customId, customId, "새 터널 #" + Math.abs(customId), 0,
                    false, Math.max(50.0f, lengthM), pts, true, false, false, bearing, 200, 60);

            openEditPanel(selectedTunnel, true);
            refreshMap();
            return;
        }

        // Normal mode: select nearest tunnel
        Tunnel nearest = findNearestTunnel(tapPoint, 400.0);
        if (nearest != null) {
            selectedTunnel = nearest;
            openEditPanel(selectedTunnel, false);
            refreshMap();
        } else {
            // Tapped empty space -> dismiss selection
            if (layoutBottomPanel.getVisibility() == View.VISIBLE) {
                closeEditPanel();
            }
        }
    }

    private Tunnel findNearestTunnel(GeoPoint pt, double maxDistMeters) {
        List<Tunnel> all = tunnelStore.getAllTunnels();
        Tunnel best = null;
        double bestDist = maxDistMeters;

        for (Tunnel t : all) {
            double dist = GeoUtils.distancePointToPolylineMeters(pt.lat, pt.lng, t.getPoints());
            if (dist < bestDist) {
                bestDist = dist;
                best = t;
            }
        }

        // Also check if tap is near raw base tunnels (even if marked deleted)
        Map<Long, UserTunnelOverride> overrides = dbHelper.getAllOverrides();
        for (Tunnel raw : tunnelStore.getRawBaseTunnels()) {
            UserTunnelOverride o = overrides.get(raw.id);
            if (o != null && o.isDeleted) {
                double dist = GeoUtils.distancePointToPolylineMeters(pt.lat, pt.lng, raw.getPoints());
                if (dist < bestDist) {
                    bestDist = dist;
                    best = raw.withOverride(o);
                }
            }
        }

        return best;
    }

    // ==================== Edit Panel ====================

    private void openEditPanel(Tunnel tunnel, boolean isNewCustom) {
        layoutBottomPanel.setVisibility(View.VISIBLE);
        etTunnelName.setText(tunnel.name);
        etTunnelName.setEnabled(isNewCustom);

        currentEditBearing = tunnel.getEffectiveStartBearing();
        currentEditStartDist = tunnel.getEffectiveStartBoxDist();
        currentEditOneway = tunnel.oneway;
        currentEditIsDeleted = tunnel.isUserDeleted;

        tvTunnelLengthInfo.setText(String.format(Locale.KOREAN, "길이: %.0fm", tunnel.lengthMeters));
        tvCurrentBearing.setText(String.format(Locale.KOREAN, "%.1f°", currentEditBearing));
        switchTunnelOneway.setChecked(currentEditOneway);

        updateBadgeAndButtons(tunnel, isNewCustom);
        selectStartDist(currentEditStartDist);
    }

    private void updateBadgeAndButtons(Tunnel tunnel, boolean isNewCustom) {
        if (isNewCustom) {
            tvTunnelBadge.setText("[신규 터널 생성]");
            tvTunnelBadge.setTextColor(Color.rgb(206, 147, 216)); // Purple
            btnDeleteOrRestore.setVisibility(View.GONE);
            btnResetToDefault.setVisibility(View.GONE);
        } else if (tunnel.isUserCustom) {
            tvTunnelBadge.setText("[사용자 생성 터널]");
            tvTunnelBadge.setTextColor(Color.rgb(179, 157, 219));
            btnDeleteOrRestore.setText("삭제하기");
            btnDeleteOrRestore.setVisibility(View.VISIBLE);
            btnResetToDefault.setVisibility(View.GONE);
        } else if (currentEditIsDeleted) {
            tvTunnelBadge.setText("[감지 제외됨]");
            tvTunnelBadge.setTextColor(Color.rgb(239, 83, 80)); // Red
            btnDeleteOrRestore.setText("감지 복구");
            btnDeleteOrRestore.setVisibility(View.VISIBLE);
            btnResetToDefault.setVisibility(View.VISIBLE);
        } else if (tunnel.isUserModified) {
            tvTunnelBadge.setText("[사용자 수정됨]");
            tvTunnelBadge.setTextColor(Color.rgb(129, 199, 132)); // Green
            btnDeleteOrRestore.setText("감지 제외 (삭제)");
            btnDeleteOrRestore.setVisibility(View.VISIBLE);
            btnResetToDefault.setVisibility(View.VISIBLE);
        } else {
            tvTunnelBadge.setText("[기본 터널]");
            tvTunnelBadge.setTextColor(Color.rgb(128, 216, 255)); // Blue
            btnDeleteOrRestore.setText("감지 제외 (삭제)");
            btnDeleteOrRestore.setVisibility(View.VISIBLE);
            btnResetToDefault.setVisibility(View.GONE);
        }
    }

    private void adjustBearing(double delta) {
        currentEditBearing = GeoUtils.normalizeDegrees(currentEditBearing + delta);
        tvCurrentBearing.setText(String.format(Locale.KOREAN, "%.1f°", currentEditBearing));
        refreshMap();
    }

    private void selectStartDist(int dist) {
        currentEditStartDist = dist;
        btnStartDist150.setBackgroundColor(dist == 150 ? Color.rgb(2, 136, 209) : Color.rgb(55, 71, 79));
        btnStartDist200.setBackgroundColor(dist == 200 ? Color.rgb(2, 136, 209) : Color.rgb(55, 71, 79));
        btnStartDist250.setBackgroundColor(dist == 250 ? Color.rgb(2, 136, 209) : Color.rgb(55, 71, 79));
        refreshMap();
    }

    private void toggleDeleteStatus() {
        if (selectedTunnel == null) return;

        if (selectedTunnel.isUserCustom) {
            // Delete custom tunnel permanently
            new AlertDialog.Builder(this)
                    .setTitle("사용자 터널 삭제")
                    .setMessage("이 터널을 완전히 삭제하시겠습니까?")
                    .setPositiveButton("삭제", (d, w) -> {
                        dbHelper.deleteCustomTunnel(selectedTunnel.id);
                        tunnelStore.reload(this);
                        closeEditPanel();
                        Toast.makeText(this, "터널이 삭제되었습니다.", Toast.LENGTH_SHORT).show();
                        refreshMap();
                    })
                    .setNegativeButton("취소", null)
                    .show();
        } else {
            // Toggle delete/restore on base tunnel
            currentEditIsDeleted = !currentEditIsDeleted;
            updateBadgeAndButtons(selectedTunnel, false);
            Toast.makeText(this, currentEditIsDeleted ? "터널이 감지 대상에서 제외되었습니다." : "터널 감지가 다시 활성화되었습니다.", Toast.LENGTH_SHORT).show();
            saveCurrentEdits();
        }
    }

    private void resetSelectedTunnelToDefault() {
        if (selectedTunnel == null || selectedTunnel.isUserCustom) return;

        new AlertDialog.Builder(this)
                .setTitle("기본값 복원")
                .setMessage("이 터널의 모든 수정 사항을 지우고 기본값으로 되돌리시겠습니까?")
                .setPositiveButton("복원", (d, w) -> {
                    dbHelper.deleteOverride(selectedTunnel.id);
                    tunnelStore.reload(this);
                    selectedTunnel = tunnelStore.getTunnelById(selectedTunnel.id);
                    if (selectedTunnel != null) {
                        openEditPanel(selectedTunnel, false);
                    } else {
                        closeEditPanel();
                    }
                    Toast.makeText(this, "기본값으로 복원되었습니다.", Toast.LENGTH_SHORT).show();
                    refreshMap();
                })
                .setNegativeButton("취소", null)
                .show();
    }

    private void saveCurrentEdits() {
        if (selectedTunnel == null) return;

        String name = etTunnelName.getText().toString().trim();
        if (name.isEmpty()) name = selectedTunnel.name;

        if (selectedTunnel.isUserCustom) {
            GeoPoint p0 = selectedTunnel.getStartPoint();
            GeoPoint pEnd = selectedTunnel.getEndPoint();
            UserCustomTunnel custom = new UserCustomTunnel(
                    selectedTunnel.id,
                    name,
                    p0.lat,
                    p0.lng,
                    pEnd.lat,
                    pEnd.lng,
                    currentEditBearing,
                    selectedTunnel.lengthMeters,
                    currentEditOneway,
                    currentEditStartDist,
                    60,
                    System.currentTimeMillis()
            );
            dbHelper.saveCustomTunnel(custom);
            Toast.makeText(this, "새 터널이 저장되었습니다: " + name, Toast.LENGTH_SHORT).show();
        } else {
            UserTunnelOverride override = new UserTunnelOverride(
                    selectedTunnel.id,
                    currentEditIsDeleted,
                    currentEditBearing,
                    currentEditStartDist,
                    60,
                    System.currentTimeMillis()
            );
            dbHelper.saveOverride(override);
            Toast.makeText(this, "터널 설정이 저장되었습니다.", Toast.LENGTH_SHORT).show();
        }

        tunnelStore.reload(this);
        selectedTunnel = tunnelStore.getTunnelById(selectedTunnel.id);
        if (selectedTunnel != null) {
            openEditPanel(selectedTunnel, false);
        }
        refreshMap();
    }

    private void closeEditPanel() {
        selectedTunnel = null;
        layoutBottomPanel.setVisibility(View.GONE);
        refreshMap();
    }

    // ==================== Map Rendering ====================

    private void refreshMap() {
        // Keep index 0 (MapEventsOverlay) and clear remaining overlays
        while (mapView.getOverlays().size() > 1) {
            mapView.getOverlays().remove(mapView.getOverlays().size() - 1);
        }

        Map<Long, UserTunnelOverride> overrides = dbHelper.getAllOverrides();

        org.osmdroid.api.IGeoPoint mapCenter = mapView.getMapCenter();
        double centerLat = mapCenter != null ? mapCenter.getLatitude() : 37.5665;
        double centerLng = mapCenter != null ? mapCenter.getLongitude() : 126.9780;
        double maxRenderDistMeters = 8000.0; // Render within 8km of center for high performance

        // 1. Draw raw base tunnels within viewport (or if selected)
        for (Tunnel base : tunnelStore.getRawBaseTunnels()) {
            boolean isSelected = (selectedTunnel != null && selectedTunnel.id == base.id);
            if (!isSelected) {
                GeoPoint mid = base.getMidpoint();
                if (GeoUtils.distanceMeters(centerLat, centerLng, mid.lat, mid.lng) > maxRenderDistMeters) {
                    continue;
                }
            }

            UserTunnelOverride o = overrides.get(base.id);
            Tunnel toDraw = (o != null) ? base.withOverride(o) : base;

            if (isSelected) {
                // Apply ongoing preview adjustments
                toDraw = applyPreviewAdjustments(toDraw);
            }

            drawTunnelOnMap(toDraw, isSelected);
        }

        // 2. Draw user custom tunnels within viewport (or if selected)
        for (UserCustomTunnel custom : dbHelper.getAllCustomTunnels()) {
            boolean isSelected = (selectedTunnel != null && selectedTunnel.id == custom.customId);
            if (!isSelected) {
                double midLat = (custom.startLat + custom.endLat) / 2.0;
                double midLng = (custom.startLng + custom.endLng) / 2.0;
                if (GeoUtils.distanceMeters(centerLat, centerLng, midLat, midLng) > maxRenderDistMeters) {
                    continue;
                }
            }

            Tunnel toDraw = Tunnel.fromCustom(custom);
            if (isSelected) {
                toDraw = applyPreviewAdjustments(toDraw);
            }
            drawTunnelOnMap(toDraw, isSelected);
        }

        // 3. Draw newly created tunnel preview if in add mode
        if (selectedTunnel != null && selectedTunnel.id < 0 && dbHelper.getAllCustomTunnels().stream().noneMatch(c -> c.customId == selectedTunnel.id)) {
            Tunnel preview = applyPreviewAdjustments(selectedTunnel);
            drawTunnelOnMap(preview, true);
        }

        // 4. Draw add mode pins
        if (newTunnelStart != null) {
            Marker mStart = new Marker(mapView);
            mStart.setPosition(new org.osmdroid.util.GeoPoint(newTunnelStart.lat, newTunnelStart.lng));
            mStart.setTitle("터널 입구 (지정됨)");
            mStart.setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM);
            mapView.getOverlays().add(mStart);
        }
        if (newTunnelEnd != null) {
            Marker mEnd = new Marker(mapView);
            mEnd.setPosition(new org.osmdroid.util.GeoPoint(newTunnelEnd.lat, newTunnelEnd.lng));
            mEnd.setTitle("터널 출구 (지정됨)");
            mEnd.setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM);
            mapView.getOverlays().add(mEnd);
        }

        // 5. Vehicle location marker
        Location loc = AutoTunnelService.getLatestSnapshot() != null ? AutoTunnelService.getLatestSnapshot().location : null;
        if (loc != null) {
            if (loc.hasBearing()) {
                lastKnownVehicleBearing = loc.getBearing();
            }
            Marker vehicleMarker = UiUtils.createVehicleMarker(mapView, loc, lastKnownVehicleBearing);
            if (vehicleMarker != null) {
                mapView.getOverlays().add(vehicleMarker);
            }
        }

        mapView.invalidate();
    }

    private Tunnel applyPreviewAdjustments(Tunnel original) {
        return new Tunnel(
                original.id,
                original.osmId,
                original.name,
                original.highwayCode,
                currentEditOneway,
                original.lengthMeters,
                original.points,
                original.isUserCustom,
                original.isUserModified,
                currentEditIsDeleted,
                currentEditBearing,
                currentEditStartDist,
                60
        );
    }

    private void drawTunnelOnMap(Tunnel tunnel, boolean isSelected) {
        int lineColor;
        int coreStrokeColor;
        int coreFillColor;

        if (tunnel.isUserDeleted) {
            // Deleted / Excluded: Translucent dark grey
            lineColor = Color.rgb(120, 144, 156);
            coreStrokeColor = Color.rgb(90, 100, 110);
            coreFillColor = Color.argb(20, 90, 100, 110);
        } else if (tunnel.isUserCustom) {
            // User Custom: Purple
            lineColor = Color.rgb(186, 104, 200);
            coreStrokeColor = isSelected ? Color.rgb(255, 235, 59) : Color.rgb(171, 71, 188);
            coreFillColor = Color.argb(40, 171, 71, 188);
        } else if (tunnel.isUserModified) {
            // User Modified: Green / Gold
            lineColor = Color.rgb(102, 187, 106);
            coreStrokeColor = isSelected ? Color.rgb(255, 235, 59) : Color.rgb(76, 175, 80);
            coreFillColor = Color.argb(40, 76, 175, 80);
        } else {
            // Base Tunnel: Standard Orange
            lineColor = Color.rgb(144, 164, 174);
            coreStrokeColor = isSelected ? Color.rgb(255, 235, 59) : Color.rgb(255, 140, 0);
            coreFillColor = Color.argb(35, 255, 152, 0);
        }

        // Polyline
        List<org.osmdroid.util.GeoPoint> osmPoints = new ArrayList<>();
        for (GeoPoint p : tunnel.getPoints()) {
            osmPoints.add(new org.osmdroid.util.GeoPoint(p.lat, p.lng));
        }
        if (osmPoints.size() >= 2) {
            Polyline line = new Polyline(mapView);
            line.setPoints(osmPoints);
            line.getOutlinePaint().setColor(lineColor);
            line.getOutlinePaint().setStrokeWidth(isSelected ? 6f : 3.5f);
            line.setTitle(tunnel.getDisplayName());
            line.setInfoWindow(null);
            line.setOnClickListener((pl, mv, pt) -> {
                onTunnelClicked(tunnel, new GeoPoint(pt.getLatitude(), pt.getLongitude()));
                return true;
            });
            mapView.getOverlays().add(line);
        }

        float strokeWidth = isSelected ? 3.5f : 1.8f;

        // Core Box
        OrientedBox coreBox = tunnel.getCoreBox();
        if (coreBox != null) {
            addBoxOverlay(tunnel, coreBox, coreStrokeColor, coreFillColor, strokeWidth, tunnel.getDisplayName());
        }

        // Start Bounding Box (Only if not deleted)
        if (!tunnel.isUserDeleted) {
            OrientedBox entryBox = tunnel.getEntryBox();
            if (entryBox != null) {
                int blueStroke = isSelected ? Color.rgb(0, 229, 255) : Color.rgb(33, 150, 243);
                addBoxOverlay(tunnel, entryBox, blueStroke, Color.argb(40, 33, 150, 243), strokeWidth, tunnel.getDisplayName() + " [Start Box]");
            }

            if (!tunnel.oneway) {
                OrientedBox revBox = tunnel.getReverseEntryBox();
                if (revBox != null) {
                    int blueStroke = isSelected ? Color.rgb(0, 229, 255) : Color.rgb(33, 150, 243);
                    addBoxOverlay(tunnel, revBox, blueStroke, Color.argb(40, 33, 150, 243), strokeWidth, tunnel.getDisplayName() + " [Rev Start Box]");
                }
            }
        }
    }

    private void onTunnelClicked(Tunnel tunnel, GeoPoint tapPoint) {
        if (addModeState != AddModeState.OFF) {
            handleMapTap(tapPoint);
            return;
        }

        selectedTunnel = tunnel;
        openEditPanel(selectedTunnel, false);
        refreshMap();
    }

    private void addBoxOverlay(Tunnel tunnel, OrientedBox box, int strokeColor, int fillColor, float strokeWidth, String title) {
        List<GeoPoint> corners = box.getCorners();
        List<org.osmdroid.util.GeoPoint> pts = new ArrayList<>(corners.size());
        for (GeoPoint pt : corners) {
            pts.add(new org.osmdroid.util.GeoPoint(pt.lat, pt.lng));
        }

        Polygon polygon = new Polygon(mapView);
        polygon.setPoints(pts);
        polygon.getOutlinePaint().setColor(strokeColor);
        polygon.getOutlinePaint().setStrokeWidth(strokeWidth);
        polygon.getFillPaint().setColor(fillColor);
        polygon.setTitle(title);
        polygon.setInfoWindow(null);
        polygon.setOnClickListener((pg, mv, pt) -> {
            onTunnelClicked(tunnel, new GeoPoint(pt.getLatitude(), pt.getLongitude()));
            return true;
        });
        mapView.getOverlays().add(polygon);
    }

    // ==================== JSON Backup & Restore ====================

    private void showJsonBackupDialog() {
        String[] options = {
                "JSON 백업 내보내기 (Download 폴더 저장)",
                "JSON 백업 불러오기 (기존 데이터에 병합)",
                "JSON 백업 불러오기 (완전 덮어쓰기)",
                "사용자 커스텀 데이터 전체 초기화"
        };

        new AlertDialog.Builder(this)
                .setTitle("터널 DB JSON 백업 및 복원")
                .setItems(options, (d, which) -> {
                    File downloadDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
                    if (!downloadDir.exists()) downloadDir = getExternalFilesDir(null);
                    File backupFile = new File(downloadDir, TunnelJsonExporter.DEFAULT_FILE_NAME);

                    switch (which) {
                        case 0:
                            boolean exported = TunnelJsonExporter.exportToFile(this, backupFile);
                            if (exported) {
                                Toast.makeText(this, "백업 완료:\n" + backupFile.getAbsolutePath(), Toast.LENGTH_LONG).show();
                            } else {
                                Toast.makeText(this, "백업 파일 저장 실패", Toast.LENGTH_SHORT).show();
                            }
                            break;

                        case 1:
                            if (!backupFile.exists()) {
                                Toast.makeText(this, "백업 파일을 찾을 수 없습니다:\n" + backupFile.getAbsolutePath(), Toast.LENGTH_LONG).show();
                                return;
                            }
                            boolean importedMerge = TunnelJsonExporter.importFromFile(this, backupFile, true);
                            if (importedMerge) {
                                tunnelStore.reload(this);
                                refreshMap();
                                Toast.makeText(this, "JSON 데이터를 성공적으로 병합했습니다.", Toast.LENGTH_SHORT).show();
                            } else {
                                Toast.makeText(this, "JSON 파일 읽기 실패", Toast.LENGTH_SHORT).show();
                            }
                            break;

                        case 2:
                            if (!backupFile.exists()) {
                                Toast.makeText(this, "백업 파일을 찾을 수 없습니다:\n" + backupFile.getAbsolutePath(), Toast.LENGTH_LONG).show();
                                return;
                            }
                            new AlertDialog.Builder(this)
                                    .setTitle("완전 덮어쓰기 확인")
                                    .setMessage("현재 저장된 모든 수정 사항이 백업 파일의 내용으로 완전히 교체됩니다. 계속하시겠습니까?")
                                    .setPositiveButton("덮어쓰기", (dw, w) -> {
                                        boolean imported = TunnelJsonExporter.importFromFile(this, backupFile, false);
                                        if (imported) {
                                            tunnelStore.reload(this);
                                            refreshMap();
                                            Toast.makeText(this, "JSON 데이터를 성공적으로 복원했습니다.", Toast.LENGTH_SHORT).show();
                                        }
                                    })
                                    .setNegativeButton("취소", null)
                                    .show();
                            break;

                        case 3:
                            new AlertDialog.Builder(this)
                                    .setTitle("전체 데이터 초기화")
                                    .setMessage("사용자가 수정한 모든 설정과 새로 만든 터널을 완전히 삭제하고 공장 기본값으로 리셋하시겠습니까?")
                                    .setPositiveButton("전체 초기화", (dw, w) -> {
                                        dbHelper.clearAll();
                                        tunnelStore.reload(this);
                                        closeEditPanel();
                                        refreshMap();
                                        Toast.makeText(this, "모든 커스텀 데이터가 초기화되었습니다.", Toast.LENGTH_SHORT).show();
                                    })
                                    .setNegativeButton("취소", null)
                                    .show();
                            break;
                    }
                })
                .setNegativeButton("닫기", null)
                .show();
    }

    @Override
    protected void onResume() {
        super.onResume();
        mapView.onResume();
    }

    @Override
    protected void onPause() {
        mapView.onPause();
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        mapRefreshHandler.removeCallbacksAndMessages(null);
        super.onDestroy();
    }
}
