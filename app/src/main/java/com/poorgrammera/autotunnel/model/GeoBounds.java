package com.poorgrammera.autotunnel.model;

/**
 * Axis-aligned geographic bounding box.
 */
public final class GeoBounds {
    public final double minLat;
    public final double maxLat;
    public final double minLng;
    public final double maxLng;

    public GeoBounds(double minLat, double maxLat, double minLng, double maxLng) {
        this.minLat = minLat;
        this.maxLat = maxLat;
        this.minLng = minLng;
        this.maxLng = maxLng;
    }

    public boolean intersects(GeoBounds other) {
        return this.minLat <= other.maxLat && this.maxLat >= other.minLat
                && this.minLng <= other.maxLng && this.maxLng >= other.minLng;
    }

    public boolean contains(double lat, double lng) {
        return lat >= minLat && lat <= maxLat && lng >= minLng && lng <= maxLng;
    }
}
