package com.poorgrammera.autotunnel.model;

import android.location.Location;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Snapshot of the detector's state for the UI map and diagnostic dashboard.
 */
public final class TunnelDebugSnapshot {
    public final Location location;
    public final TunnelDetectionResult result;
    public final List<Tunnel> nearbyTunnels;
    public final boolean isRecirculationForced;
    public final int originalAcMode; // -1: unknown, 0: fresh, 1: recycle
    public final int currentAcMode;  // -1: unknown, 0: fresh, 1: recycle
    public final double vehicleSpeedKmh;
    public final boolean isWindowsClosedByTunnel;
    public final int savedWindowsCount;
    public final long timestampMs;

    public TunnelDebugSnapshot(
            Location location,
            TunnelDetectionResult result,
            List<Tunnel> nearbyTunnels,
            boolean isRecirculationForced,
            int originalAcMode,
            int currentAcMode,
            double vehicleSpeedKmh
    ) {
        this(location, result, nearbyTunnels, isRecirculationForced, originalAcMode, currentAcMode, vehicleSpeedKmh, false, 0);
    }

    public TunnelDebugSnapshot(
            Location location,
            TunnelDetectionResult result,
            List<Tunnel> nearbyTunnels,
            boolean isRecirculationForced,
            int originalAcMode,
            int currentAcMode,
            double vehicleSpeedKmh,
            boolean isWindowsClosedByTunnel,
            int savedWindowsCount
    ) {
        this.location = location == null ? null : new Location(location);
        this.result = result == null ? TunnelDetectionResult.noLocation() : result;
        this.nearbyTunnels = nearbyTunnels == null
                ? Collections.emptyList()
                : Collections.unmodifiableList(new ArrayList<>(nearbyTunnels));
        this.isRecirculationForced = isRecirculationForced;
        this.originalAcMode = originalAcMode;
        this.currentAcMode = currentAcMode;
        this.vehicleSpeedKmh = vehicleSpeedKmh;
        this.isWindowsClosedByTunnel = isWindowsClosedByTunnel;
        this.savedWindowsCount = savedWindowsCount;
        this.timestampMs = System.currentTimeMillis();
    }
}
