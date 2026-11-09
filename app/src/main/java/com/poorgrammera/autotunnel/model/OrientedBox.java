package com.poorgrammera.autotunnel.model;

import java.util.ArrayList;
import java.util.List;

/**
 * 2D Oriented Bounding Box (OBB) on Earth coordinates.
 * Evaluates whether a geographic point (lat, lng) is inside a rotated rectangle.
 */
public final class OrientedBox {
    public final double centerLat;
    public final double centerLng;
    public final double lengthMeters; // along bearing axis
    public final double widthMeters;  // perpendicular to bearing axis
    public final double bearingDeg;

    private final double sinBearing;
    private final double cosBearing;
    private final double cosCenterLat;

    public OrientedBox(double centerLat, double centerLng, double lengthMeters, double widthMeters, double bearingDeg) {
        this.centerLat = centerLat;
        this.centerLng = centerLng;
        this.lengthMeters = lengthMeters;
        this.widthMeters = widthMeters;
        this.bearingDeg = GeoUtils.normalizeDegrees(bearingDeg);

        double rad = Math.toRadians(this.bearingDeg);
        this.sinBearing = Math.sin(rad);
        this.cosBearing = Math.cos(rad);
        this.cosCenterLat = Math.cos(Math.toRadians(centerLat));
    }

    /**
     * Checks if the given coordinate is inside this oriented bounding box.
     */
    public boolean contains(double lat, double lng) {
        // Delta in meters from box center
        double dy = (lat - centerLat) * 111_320.0;
        double dx = (lng - centerLng) * 111_320.0 * cosCenterLat;

        // Project onto box local axes
        // Longitudinal axis: direction of bearing
        double longitudinal = dx * sinBearing + dy * cosBearing;
        // Lateral axis: perpendicular to bearing (to the right)
        double lateral = dx * cosBearing - dy * sinBearing;

        return Math.abs(longitudinal) <= (lengthMeters / 2.0)
                && Math.abs(lateral) <= (widthMeters / 2.0);
    }

    /**
     * Returns the 4 corner points of this box in polygon order (closed loop of 5 points).
     */
    public List<GeoPoint> getCorners() {
        List<GeoPoint> corners = new ArrayList<>(5);
        double halfL = lengthMeters / 2.0;
        double halfW = widthMeters / 2.0;

        // 4 local corners (longitudinal, lateral): (+L, -W), (+L, +W), (-L, +W), (-L, -W)
        double[][] localOffsets = {
                {halfL, -halfW},
                {halfL, halfW},
                {-halfL, halfW},
                {-halfL, -halfW}
        };

        for (double[] offset : localOffsets) {
            double u = offset[0]; // along bearing
            double v = offset[1]; // lateral

            // Transform back to (dx, dy)
            double dx = u * sinBearing + v * cosBearing;
            double dy = u * cosBearing - v * sinBearing;

            double lat = centerLat + (dy / 111_320.0);
            double lng = centerLng + (dx / (111_320.0 * cosCenterLat));
            corners.add(new GeoPoint(lat, lng));
        }
        corners.add(corners.get(0)); // close loop
        return corners;
    }
}
