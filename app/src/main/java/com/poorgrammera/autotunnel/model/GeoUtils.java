package com.poorgrammera.autotunnel.model;

import android.location.Location;
import java.util.List;

/**
 * Geometric calculation utility for spatial distances, bearings, and segment projections.
 */
public final class GeoUtils {
    public static final double EARTH_RADIUS_METERS = 6_371_000.0;

    private GeoUtils() {}

    /**
     * Creates a bounding box around (lat, lng) with a given radius in meters.
     */
    public static GeoBounds createBounds(double lat, double lng, double radiusMeters) {
        double deltaLat = radiusMeters / 111_000.0;
        double cosLat = Math.cos(Math.toRadians(lat));
        double deltaLng = (Math.abs(cosLat) < 0.000001) ? deltaLat : radiusMeters / (111_000.0 * cosLat);

        return new GeoBounds(
                lat - deltaLat,
                lat + deltaLat,
                lng - deltaLng,
                lng + deltaLng
        );
    }

    /**
     * Great-circle distance between two coordinates using the Haversine formula.
     */
    public static double distanceMeters(double lat1, double lng1, double lat2, double lng2) {
        double p1 = Math.toRadians(lat1);
        double p2 = Math.toRadians(lat2);
        double dp = Math.toRadians(lat2 - lat1);
        double dl = Math.toRadians(lng2 - lng1);

        double a = Math.sin(dp / 2.0) * Math.sin(dp / 2.0)
                + Math.cos(p1) * Math.cos(p2)
                * Math.sin(dl / 2.0) * Math.sin(dl / 2.0);

        return 2.0 * EARTH_RADIUS_METERS * Math.atan2(Math.sqrt(a), Math.sqrt(Math.max(0.0, 1.0 - a)));
    }

    public static double distanceMeters(Location a, Location b) {
        if (a == null || b == null) return Double.MAX_VALUE;
        return distanceMeters(a.getLatitude(), a.getLongitude(), b.getLatitude(), b.getLongitude());
    }

    /**
     * Initial bearing (forward azimuth) from point 1 to point 2 in degrees [0, 360).
     */
    public static double bearingDegrees(double fromLat, double fromLng, double toLat, double toLng) {
        double lat1 = Math.toRadians(fromLat);
        double lat2 = Math.toRadians(toLat);
        double dLng = Math.toRadians(toLng - fromLng);

        double y = Math.sin(dLng) * Math.cos(lat2);
        double x = Math.cos(lat1) * Math.sin(lat2)
                - Math.sin(lat1) * Math.cos(lat2) * Math.cos(dLng);

        double bearing = Math.toDegrees(Math.atan2(y, x));
        return normalizeDegrees(bearing);
    }

    public static double bearingDegrees(Location from, Location to) {
        if (from == null || to == null) return 0.0;
        return bearingDegrees(from.getLatitude(), from.getLongitude(), to.getLatitude(), to.getLongitude());
    }

    public static double normalizeDegrees(double degrees) {
        double value = degrees % 360.0;
        if (value < 0.0) {
            value += 360.0;
        }
        return value;
    }

    /**
     * Smallest difference between two angles in degrees [0, 180].
     */
    public static double angleDiffDegrees(double a, double b) {
        double diff = Math.abs(normalizeDegrees(a) - normalizeDegrees(b));
        return Math.min(diff, 360.0 - diff);
    }

    /**
     * Calculates the minimum perpendicular distance in meters from (lat, lng) to a polyline.
     */
    public static double distancePointToPolylineMeters(double lat, double lng, List<GeoPoint> polyline) {
        if (polyline == null || polyline.size() < 2) {
            return Double.MAX_VALUE;
        }

        double min = Double.MAX_VALUE;
        int count = polyline.size();
        for (int i = 0; i < count - 1; i++) {
            GeoPoint a = polyline.get(i);
            GeoPoint b = polyline.get(i + 1);

            double d = distancePointToSegmentMeters(lat, lng, a.lat, a.lng, b.lat, b.lng);
            if (d < min) {
                min = d;
            }
        }
        return min;
    }

    /**
     * Minimum distance from a point to a line segment in meters using equirectangular projection.
     */
    public static double distancePointToSegmentMeters(
            double pLat, double pLng,
            double aLat, double aLng,
            double bLat, double bLng
    ) {
        double lat0 = Math.toRadians(pLat);
        double cosLat = Math.cos(lat0);

        double px = Math.toRadians(pLng) * cosLat * EARTH_RADIUS_METERS;
        double py = Math.toRadians(pLat) * EARTH_RADIUS_METERS;

        double ax = Math.toRadians(aLng) * cosLat * EARTH_RADIUS_METERS;
        double ay = Math.toRadians(aLat) * EARTH_RADIUS_METERS;

        double bx = Math.toRadians(bLng) * cosLat * EARTH_RADIUS_METERS;
        double by = Math.toRadians(bLat) * EARTH_RADIUS_METERS;

        double dx = bx - ax;
        double dy = by - ay;

        if (dx == 0.0 && dy == 0.0) {
            return Math.sqrt((px - ax) * (px - ax) + (py - ay) * (py - ay));
        }

        double t = ((px - ax) * dx + (py - ay) * dy) / (dx * dx + dy * dy);
        t = Math.max(0.0, Math.min(1.0, t));

        double cx = ax + t * dx;
        double cy = ay + t * dy;

        return Math.sqrt((px - cx) * (px - cx) + (py - cy) * (py - cy));
    }
}
