package com.poorgrammera.autotunnel.model;

/**
 * Immutable geographic coordinate (latitude, longitude).
 */
public final class GeoPoint {
    public final double lat;
    public final double lng;

    public GeoPoint(double lat, double lng) {
        this.lat = lat;
        this.lng = lng;
    }

    @Override
    public String toString() {
        return "(" + lat + ", " + lng + ")";
    }
}
