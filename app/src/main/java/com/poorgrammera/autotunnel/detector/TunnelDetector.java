package com.poorgrammera.autotunnel.detector;

import android.content.Context;
import android.location.Location;
import android.util.Log;

import com.poorgrammera.autotunnel.data.ConfigData;
import com.poorgrammera.autotunnel.model.GeoPoint;
import com.poorgrammera.autotunnel.model.GeoUtils;
import com.poorgrammera.autotunnel.model.OrientedBox;
import com.poorgrammera.autotunnel.model.Tunnel;
import com.poorgrammera.autotunnel.model.TunnelDetectionResult;
import com.poorgrammera.autotunnel.model.TunnelStore;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Robust State-Machine Tunnel Detector.
 *
 * Solves real-world vehicle GPS tunnel issues:
 * 1. GPS signal blackout / severe accuracy degradation inside tunnels (does not prematurely cancel).
 * 2. Directional heading validation to ignore tunnels on perpendicular or opposite-side roads.
 * 3. Dead-reckoning duration fallback based on vehicle speed and tunnel length when GPS freezes.
 * 4. Post-exit delay (hysteresis) to prevent exhaust air from rushing into the cabin at the tunnel exit.
 */
public class TunnelDetector {
    private static final String TAG = "TunnelDetector";

    // Geometry thresholds
    private static final double MAX_HEADING_DIFF_DEGREES = 50.0;
    private static final double IN_TUNNEL_POLYLINE_DISTANCE_M = 50.0; // Distance to center line
    private static final double AT_ENTRANCE_DISTANCE_M = 40.0;
    private static final double DEFAULT_SPEED_MPS = 16.6; // ~60 km/h fallback
    private static final double QUERY_RADIUS_METERS = 2000.0; // 2 km range

    private final TunnelStore tunnelStore;

    // Detection State Machine
    private TunnelDetectionResult.State currentState = TunnelDetectionResult.State.NORMAL;
    private Tunnel activeTunnel = null;
    private long stateEnterTimestampMs = 0L;
    private long estimatedTunnelDurationMs = 0L;
    private long exitCooldownDurationMs = 5000L;

    private Location lastValidLocation = null;
    private Double lastKnownBearing = null;
    private long lastBearingTimestampMs = 0L;
    private double lastVehicleSpeedMps = DEFAULT_SPEED_MPS;

    private volatile List<Tunnel> lastNearbyCandidates = Collections.emptyList();

    public TunnelDetector(Context context) {
        this.tunnelStore = TunnelStore.getInstance(context);
    }

    /**
     * Periodic time tick (called every 1s from the background service).
     * Handles tunnel hold & exit timeouts even if GPS location updates stop completely inside the tunnel.
     */
    public synchronized TunnelDetectionResult tick(long nowMs, int exitDelaySec) {
        this.exitCooldownDurationMs = Math.max(1, exitDelaySec) * 1000L;

        if (currentState == TunnelDetectionResult.State.IN_TUNNEL && activeTunnel != null) {
            long elapsedInsideMs = nowMs - stateEnterTimestampMs;
            long maxTunnelHoldMs = estimatedTunnelDurationMs + 20_000L; // Estimated time + 20s margin

            // If GPS is completely dark and time has comfortably exceeded estimated tunnel length
            if (elapsedInsideMs >= maxTunnelHoldMs) {
                Log.i(TAG, "IN_TUNNEL timed out via dead-reckoning fallback (" + (elapsedInsideMs / 1000)
                        + "s elapsed for " + activeTunnel.getDisplayName() + "). Transitioning to EXITING.");
                currentState = TunnelDetectionResult.State.EXITING;
                stateEnterTimestampMs = nowMs;
                return TunnelDetectionResult.exiting(activeTunnel, exitCooldownDurationMs / 1000);
            }

            long remainingSec = Math.max(0, (estimatedTunnelDurationMs - elapsedInsideMs) / 1000);
            return TunnelDetectionResult.inTunnel(activeTunnel, 0.0, remainingSec);
        }

        if (currentState == TunnelDetectionResult.State.EXITING && activeTunnel != null) {
            long elapsedCooldownMs = nowMs - stateEnterTimestampMs;
            if (elapsedCooldownMs >= exitCooldownDurationMs) {
                Log.i(TAG, "Exit cooldown complete (" + (elapsedCooldownMs / 1000) + "s). Returning to NORMAL.");
                Tunnel finishedTunnel = activeTunnel;
                currentState = TunnelDetectionResult.State.NORMAL;
                activeTunnel = null;
                return TunnelDetectionResult.normal("TUNNEL_COMPLETED_" + finishedTunnel.getDisplayName());
            }

            long delayRemainingSec = Math.max(0, (exitCooldownDurationMs - elapsedCooldownMs) / 1000);
            return TunnelDetectionResult.exiting(activeTunnel, delayRemainingSec);
        }

        return null; // No state change needed during tick
    }

    /**
     * Evaluates a new GPS Location update.
     */
    public synchronized TunnelDetectionResult update(
            Location location,
            int approachDistanceM,
            int exitDelaySec,
            int minTunnelLengthM,
            double obdSpeedKmh
    ) {
        long now = System.currentTimeMillis();
        this.exitCooldownDurationMs = Math.max(1, exitDelaySec) * 1000L;

        if (location == null) {
            return TunnelDetectionResult.noLocation();
        }

        // Determine current vehicle speed (prefer OBD vehicle speed if positive, then GPS speed)
        if (obdSpeedKmh > 5.0) {
            lastVehicleSpeedMps = (obdSpeedKmh * 1000.0) / 3600.0;
        } else if (location.hasSpeed() && location.getSpeed() > 2.0f) {
            lastVehicleSpeedMps = location.getSpeed();
        } else if (lastVehicleSpeedMps <= 1.0) {
            lastVehicleSpeedMps = DEFAULT_SPEED_MPS;
        }

        // Determine vehicle bearing
        Double currentBearing = resolveBearing(location, now);

        // Fetch candidate tunnels around vehicle
        List<Tunnel> candidates = tunnelStore.queryNearby(
                location.getLatitude(), location.getLongitude(), QUERY_RADIUS_METERS
        );
        lastNearbyCandidates = Collections.unmodifiableList(new ArrayList<>(candidates));

        // State Machine evaluation
        switch (currentState) {
            case IN_TUNNEL:
                return handleInTunnelUpdate(location, now);

            case EXITING:
                return handleExitingUpdate(location, now);

            case APPROACHING:
                return handleApproachingUpdate(location, currentBearing, approachDistanceM, minTunnelLengthM, now);

            case NORMAL:
            case NO_LOCATION:
            default:
                return handleNormalUpdate(location, currentBearing, candidates, approachDistanceM, minTunnelLengthM, now);
        }
    }

    private TunnelDetectionResult handleInTunnelUpdate(Location location, long now) {
        if (activeTunnel == null) {
            currentState = TunnelDetectionResult.State.NORMAL;
            return TunnelDetectionResult.normal("INVALID_ACTIVE_TUNNEL");
        }

        // If GPS is degraded (poor accuracy inside tunnel), trust the tunnel state and keep holding!
        if (location.hasAccuracy() && location.getAccuracy() > 100.0f) {
            long remainingSec = Math.max(0, (estimatedTunnelDurationMs - (now - stateEnterTimestampMs)) / 1000);
            return TunnelDetectionResult.inTunnel(activeTunnel, 0.0, remainingSec);
        }

        OrientedBox coreBox = activeTunnel.getCoreBox();
        boolean insideCore = (coreBox != null && coreBox.contains(location.getLatitude(), location.getLongitude()));

        if (insideCore) {
            long remainingSec = Math.max(0, (estimatedTunnelDurationMs - (now - stateEnterTimestampMs)) / 1000);
            return TunnelDetectionResult.inTunnel(activeTunnel, 0.0, remainingSec);
        }

        // Vehicle has exited the core tunnel box
        Log.i(TAG, "Vehicle left core bounding box of tunnel: " + activeTunnel.getDisplayName()
                + ". Starting exit cooldown (" + (exitCooldownDurationMs / 1000) + "s).");
        currentState = TunnelDetectionResult.State.EXITING;
        stateEnterTimestampMs = now;
        return TunnelDetectionResult.exiting(activeTunnel, exitCooldownDurationMs / 1000);
    }

    private TunnelDetectionResult handleExitingUpdate(Location location, long now) {
        long elapsed = now - stateEnterTimestampMs;
        if (elapsed >= exitCooldownDurationMs) {
            Tunnel finished = activeTunnel;
            currentState = TunnelDetectionResult.State.NORMAL;
            activeTunnel = null;
            return TunnelDetectionResult.normal("EXIT_COOLDOWN_FINISHED_" + (finished != null ? finished.getDisplayName() : ""));
        }

        long remSec = Math.max(0, (exitCooldownDurationMs - elapsed) / 1000);
        return TunnelDetectionResult.exiting(activeTunnel, remSec);
    }

    private TunnelDetectionResult handleApproachingUpdate(
            Location location,
            Double bearing,
            int approachDistanceM,
            int minTunnelLengthM,
            long now
    ) {
        if (activeTunnel == null) {
            currentState = TunnelDetectionResult.State.NORMAL;
            return TunnelDetectionResult.normal("NO_ACTIVE_APPROACH");
        }

        // 1. Vehicle entered the core tunnel box
        OrientedBox coreBox = activeTunnel.getCoreBox();
        if (coreBox != null && coreBox.contains(location.getLatitude(), location.getLongitude())) {
            Log.i(TAG, "Vehicle collided with core tunnel box: " + activeTunnel.getDisplayName() + ". Switching to IN_TUNNEL.");
            currentState = TunnelDetectionResult.State.IN_TUNNEL;
            stateEnterTimestampMs = now;
            calculateEstimatedDuration(activeTunnel);
            return TunnelDetectionResult.inTunnel(activeTunnel, 0.0, estimatedTunnelDurationMs / 1000);
        }

        // 2. Check if vehicle is still within entry trigger boxes
        OrientedBox entryBox = activeTunnel.getEntryBox();
        OrientedBox revBox = activeTunnel.getReverseEntryBox();
        boolean insideTrigger = (entryBox != null && entryBox.contains(location.getLatitude(), location.getLongitude()))
                || (revBox != null && revBox.contains(location.getLatitude(), location.getLongitude()));

        if (insideTrigger) {
            GeoPoint entrance = activeTunnel.getStartPoint();
            double distToEntrance = entrance != null ? GeoUtils.distanceMeters(
                    location.getLatitude(), location.getLongitude(), entrance.lat, entrance.lng) : 100.0;
            return TunnelDetectionResult.approaching(activeTunnel, distToEntrance, 0.0);
        }

        // 3. Vehicle left entry trigger box without entering core box (aborted / turned off)
        Log.i(TAG, "Vehicle left entry trigger box without entering core box. Cancelling approach for: "
                + activeTunnel.getDisplayName());
        currentState = TunnelDetectionResult.State.NORMAL;
        activeTunnel = null;
        return TunnelDetectionResult.normal("APPROACH_ABORTED_BOX_EXIT");
    }

    private TunnelDetectionResult handleNormalUpdate(
            Location location,
            Double bearing,
            List<Tunnel> candidates,
            int approachDistanceM,
            int minTunnelLengthM,
            long now
    ) {
        Tunnel bestApproachTunnel = null;
        double minEntranceDist = Double.MAX_VALUE;

        for (Tunnel tunnel : candidates) {
            if (minTunnelLengthM > 0 && tunnel.lengthMeters < minTunnelLengthM) continue;

            // 1. Direct inside-tunnel check (in case app starts while already inside a tunnel)
            OrientedBox coreBox = tunnel.getCoreBox();
            if (coreBox != null && coreBox.contains(location.getLatitude(), location.getLongitude())) {
                activeTunnel = tunnel;
                currentState = TunnelDetectionResult.State.IN_TUNNEL;
                stateEnterTimestampMs = now;
                calculateEstimatedDuration(tunnel);
                Log.i(TAG, "Started directly inside tunnel core box: " + tunnel.getDisplayName());
                return TunnelDetectionResult.inTunnel(tunnel, 0.0, estimatedTunnelDurationMs / 1000);
            }

            // 2. Primary entry trigger box collision check
            OrientedBox entryBox = tunnel.getEntryBox();
            if (entryBox != null && entryBox.contains(location.getLatitude(), location.getLongitude())) {
                boolean directionOk = true;
                if (bearing != null) {
                    double diff = GeoUtils.angleDiffDegrees(bearing, entryBox.bearingDeg);
                    if (diff > 70.0) {
                        directionOk = false;
                    }
                }
                if (directionOk) {
                    GeoPoint startPt = tunnel.getStartPoint();
                    double dist = startPt != null ? GeoUtils.distanceMeters(
                            location.getLatitude(), location.getLongitude(), startPt.lat, startPt.lng) : 100.0;
                    if (dist < minEntranceDist) {
                        minEntranceDist = dist;
                        bestApproachTunnel = tunnel;
                    }
                }
            }

            // 3. Reverse entry trigger box collision check (two-way tunnel)
            if (!tunnel.oneway) {
                OrientedBox revBox = tunnel.getReverseEntryBox();
                if (revBox != null && revBox.contains(location.getLatitude(), location.getLongitude())) {
                    boolean directionOk = true;
                    if (bearing != null) {
                        double diff = GeoUtils.angleDiffDegrees(bearing, revBox.bearingDeg);
                        if (diff > 70.0) {
                            directionOk = false;
                        }
                    }
                    if (directionOk) {
                        GeoPoint endPt = tunnel.getEndPoint();
                        double dist = endPt != null ? GeoUtils.distanceMeters(
                                location.getLatitude(), location.getLongitude(), endPt.lat, endPt.lng) : 100.0;
                        if (dist < minEntranceDist) {
                            minEntranceDist = dist;
                            bestApproachTunnel = tunnel;
                        }
                    }
                }
            }
        }

        if (bestApproachTunnel != null) {
            activeTunnel = bestApproachTunnel;
            currentState = TunnelDetectionResult.State.APPROACHING;
            stateEnterTimestampMs = now;
            Log.i(TAG, "Approaching tunnel detected via OBB trigger box: " + bestApproachTunnel.getDisplayName()
                    + " (~" + Math.round(minEntranceDist) + "m ahead)");
            return TunnelDetectionResult.approaching(bestApproachTunnel, minEntranceDist, 0.0);
        }

        return TunnelDetectionResult.normal("NO_TUNNEL_AHEAD");
    }

    private void calculateEstimatedDuration(Tunnel tunnel) {
        double speed = Math.max(5.0, lastVehicleSpeedMps); // Minimum 18 km/h assumption
        double durationSec = (tunnel.lengthMeters / speed);
        this.estimatedTunnelDurationMs = (long) (durationSec * 1000.0);
    }


    private Double resolveBearing(Location location, long now) {
        if (location.hasBearing() && location.getBearing() != 0.0f) {
            lastKnownBearing = (double) location.getBearing();
            lastBearingTimestampMs = now;
            lastValidLocation = new Location(location);
            return lastKnownBearing;
        }

        if (lastValidLocation != null) {
            double distance = GeoUtils.distanceMeters(lastValidLocation, location);
            if (distance >= 5.0) {
                lastKnownBearing = GeoUtils.bearingDegrees(lastValidLocation, location);
                lastBearingTimestampMs = now;
                lastValidLocation = new Location(location);
                return lastKnownBearing;
            }
        } else {
            lastValidLocation = new Location(location);
        }

        // If bearing is fresh within 10 seconds, reuse it
        if (lastKnownBearing != null && (now - lastBearingTimestampMs) < 10_000L) {
            return lastKnownBearing;
        }

        return null;
    }

    public List<Tunnel> getLastCandidates() {
        return lastNearbyCandidates;
    }

    public Tunnel getActiveTunnel() {
        return activeTunnel;
    }

    public TunnelDetectionResult.State getCurrentState() {
        return currentState;
    }

    public void reset() {
        currentState = TunnelDetectionResult.State.NORMAL;
        activeTunnel = null;
        stateEnterTimestampMs = 0L;
    }
}
