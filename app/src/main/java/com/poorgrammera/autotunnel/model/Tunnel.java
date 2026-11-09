package com.poorgrammera.autotunnel.model;

import java.util.Collections;
import java.util.List;

/**
 * Representation of a road tunnel with path coordinates and geometry properties.
 */
public final class Tunnel {
    public final long id;
    public final long osmId;
    public final String name;
    public final int highwayCode;
    public final boolean oneway;
    public final float lengthMeters;
    public final List<GeoPoint> points;
    public final GeoBounds bounds;

    private final double startBearing;
    private final double endBearing;

    // User customization fields
    public final boolean isUserCustom;
    public final boolean isUserModified;
    public final boolean isUserDeleted;
    public final Double overrideBearing;
    public final Integer overrideStartBoxDist;
    public final Integer overrideBoxWidth;

    public Tunnel(
            long id,
            long osmId,
            String name,
            int highwayCode,
            boolean oneway,
            float lengthMeters,
            List<GeoPoint> points
    ) {
        this(id, osmId, name, highwayCode, oneway, lengthMeters, points,
                false, false, false, null, null, null);
    }

    public Tunnel(
            long id,
            long osmId,
            String name,
            int highwayCode,
            boolean oneway,
            float lengthMeters,
            List<GeoPoint> points,
            boolean isUserCustom,
            boolean isUserModified,
            boolean isUserDeleted,
            Double overrideBearing,
            Integer overrideStartBoxDist,
            Integer overrideBoxWidth
    ) {
        this.id = id;
        this.osmId = osmId;
        this.name = name;
        this.highwayCode = highwayCode;
        this.oneway = oneway;
        this.lengthMeters = lengthMeters;
        this.points = Collections.unmodifiableList(points);

        this.isUserCustom = isUserCustom;
        this.isUserModified = isUserModified;
        this.isUserDeleted = isUserDeleted;
        this.overrideBearing = overrideBearing;
        this.overrideStartBoxDist = overrideStartBoxDist;
        this.overrideBoxWidth = overrideBoxWidth;

        double minLat = Double.MAX_VALUE;
        double maxLat = -Double.MAX_VALUE;
        double minLng = Double.MAX_VALUE;
        double maxLng = -Double.MAX_VALUE;

        for (GeoPoint p : points) {
            if (p.lat < minLat) minLat = p.lat;
            if (p.lat > maxLat) maxLat = p.lat;
            if (p.lng < minLng) minLng = p.lng;
            if (p.lng > maxLng) maxLng = p.lng;
        }
        this.bounds = new GeoBounds(minLat, maxLat, minLng, maxLng);

        if (points.size() >= 2) {
            GeoPoint p0 = points.get(0);
            GeoPoint p1 = points.get(1);
            this.startBearing = GeoUtils.bearingDegrees(p0.lat, p0.lng, p1.lat, p1.lng);

            GeoPoint pEnd = points.get(points.size() - 1);
            GeoPoint pPrev = points.get(points.size() - 2);
            this.endBearing = GeoUtils.bearingDegrees(pPrev.lat, pPrev.lng, pEnd.lat, pEnd.lng);
        } else {
            this.startBearing = 0.0;
            this.endBearing = 0.0;
        }
    }

    private OrientedBox cachedEntryBox = null;
    private OrientedBox cachedReverseEntryBox = null;
    private OrientedBox cachedCoreBox = null;

    public double getEffectiveStartBearing() {
        return overrideBearing != null ? overrideBearing : startBearing;
    }

    public double getEffectiveEndBearing() {
        return overrideBearing != null ? overrideBearing : endBearing;
    }

    public int getEffectiveStartBoxDist() {
        return overrideStartBoxDist != null ? overrideStartBoxDist : 200;
    }

    public int getEffectiveBoxWidth() {
        return overrideBoxWidth != null ? overrideBoxWidth : 60;
    }

    /**
     * Entry trigger box positioned ahead of the tunnel entrance based on effective settings.
     */
    public synchronized OrientedBox getEntryBox() {
        if (cachedEntryBox != null) return cachedEntryBox;
        GeoPoint p0 = getStartPoint();
        if (p0 == null) return null;

        double effBearing = getEffectiveStartBearing();
        int targetDist = getEffectiveStartBoxDist(); // e.g. 200m
        double boxLength = Math.max(60.0, targetDist - 30.0); // e.g. 170m
        double centerOffsetMeters = 30.0 + (boxLength / 2.0); // e.g. 115m

        // Center is centerOffsetMeters back from entrance along the opposite of effBearing
        double backBearing = GeoUtils.normalizeDegrees(effBearing + 180.0);
        double rad = Math.toRadians(backBearing);
        double centerLat = p0.lat + (centerOffsetMeters * Math.cos(rad) / 111_320.0);
        double centerLng = p0.lng + (centerOffsetMeters * Math.sin(rad) / (111_320.0 * Math.cos(Math.toRadians(p0.lat))));

        double boxWidth = Math.max(35.0, getEffectiveBoxWidth() * 0.7);
        cachedEntryBox = new OrientedBox(centerLat, centerLng, boxLength, boxWidth, effBearing);
        return cachedEntryBox;
    }

    /**
     * Entry trigger box for two-way tunnels positioned ahead of the reverse entrance.
     */
    public synchronized OrientedBox getReverseEntryBox() {
        if (oneway) return null;
        if (cachedReverseEntryBox != null) return cachedReverseEntryBox;
        GeoPoint pEnd = getEndPoint();
        if (pEnd == null) return null;

        double effEndBearing = getEffectiveEndBearing();
        int targetDist = getEffectiveStartBoxDist();
        double boxLength = Math.max(60.0, targetDist - 30.0);
        double centerOffsetMeters = 30.0 + (boxLength / 2.0);

        // Heading into tunnel from reverse end
        double inBearing = GeoUtils.normalizeDegrees(effEndBearing + 180.0);
        // Center is centerOffsetMeters back from pEnd along effEndBearing (outward)
        double rad = Math.toRadians(effEndBearing);
        double centerLat = pEnd.lat + (centerOffsetMeters * Math.cos(rad) / 111_320.0);
        double centerLng = pEnd.lng + (centerOffsetMeters * Math.sin(rad) / (111_320.0 * Math.cos(Math.toRadians(pEnd.lat))));

        double boxWidth = Math.max(35.0, getEffectiveBoxWidth() * 0.7);
        cachedReverseEntryBox = new OrientedBox(centerLat, centerLng, boxLength, boxWidth, inBearing);
        return cachedReverseEntryBox;
    }

    /**
     * Core bounding box covering the entire tunnel bore plus margins on both ends.
     */
    public synchronized OrientedBox getCoreBox() {
        if (cachedCoreBox != null) return cachedCoreBox;
        GeoPoint p0 = getStartPoint();
        GeoPoint pEnd = getEndPoint();
        if (p0 == null || pEnd == null) return null;

        double centerLat = (p0.lat + pEnd.lat) / 2.0;
        double centerLng = (p0.lng + pEnd.lng) / 2.0;
        double mainBearing = overrideBearing != null ? overrideBearing
                : GeoUtils.bearingDegrees(p0.lat, p0.lng, pEnd.lat, pEnd.lng);

        double rad = Math.toRadians(mainBearing);
        double sinB = Math.sin(rad);
        double cosB = Math.cos(rad);
        double cosCenterLat = Math.cos(Math.toRadians(centerLat));

        double maxAbsU = 0.0;
        double maxAbsV = 0.0;

        for (GeoPoint p : points) {
            double dy = (p.lat - centerLat) * 111_320.0;
            double dx = (p.lng - centerLng) * 111_320.0 * cosCenterLat;
            double u = dx * sinB + dy * cosB; // longitudinal
            double v = dx * cosB - dy * sinB; // lateral
            if (Math.abs(u) > maxAbsU) maxAbsU = Math.abs(u);
            if (Math.abs(v) > maxAbsV) maxAbsV = Math.abs(v);
        }

        double boxLength = Math.max((double) lengthMeters + 60.0, (maxAbsU * 2.0) + 60.0);
        double boxWidth = Math.max((double) getEffectiveBoxWidth(), (maxAbsV * 2.0) + 20.0);

        cachedCoreBox = new OrientedBox(centerLat, centerLng, boxLength, boxWidth, mainBearing);
        return cachedCoreBox;
    }

    /**
     * Creates a new copy of this Tunnel with user override modifications applied.
     */
    public Tunnel withOverride(com.poorgrammera.autotunnel.data.UserTunnelOverride override) {
        if (override == null) return this;
        boolean modified = !override.isDeleted && (override.overrideBearing != null
                || override.overrideStartBoxDist != null
                || override.overrideBoxWidth != null);
        return new Tunnel(
                this.id,
                this.osmId,
                this.name,
                this.highwayCode,
                this.oneway,
                this.lengthMeters,
                this.points,
                this.isUserCustom,
                modified,
                override.isDeleted,
                override.overrideBearing,
                override.overrideStartBoxDist,
                override.overrideBoxWidth
        );
    }

    /**
     * Creates a Tunnel instance from a UserCustomTunnel.
     */
    public static Tunnel fromCustom(com.poorgrammera.autotunnel.data.UserCustomTunnel custom) {
        List<GeoPoint> pts = new java.util.ArrayList<>(2);
        pts.add(new GeoPoint(custom.startLat, custom.startLng));
        pts.add(new GeoPoint(custom.endLat, custom.endLng));
        return new Tunnel(
                custom.customId,
                custom.customId,
                custom.name,
                0,
                custom.oneway,
                custom.lengthMeters,
                pts,
                true,
                false,
                false,
                custom.bearing,
                custom.startBoxDist,
                custom.boxWidth
        );
    }

    public List<GeoPoint> getPoints() {
        return points;
    }

    public GeoPoint getStartPoint() {
        return points.isEmpty() ? null : points.get(0);
    }

    public GeoPoint getEndPoint() {
        return points.isEmpty() ? null : points.get(points.size() - 1);
    }

    public GeoPoint getMidpoint() {
        if (points.isEmpty()) return new GeoPoint(0, 0);
        GeoPoint p0 = points.get(0);
        GeoPoint pEnd = points.get(points.size() - 1);
        return new GeoPoint((p0.lat + pEnd.lat) / 2.0, (p0.lng + pEnd.lng) / 2.0);
    }

    public double getStartBearing() {
        return startBearing;
    }

    public double getEndBearing() {
        return endBearing;
    }

    public String getDisplayName() {
        if (name != null && !name.trim().isEmpty()) {
            return name.trim();
        }
        return "터널 #" + osmId;
    }

    @Override
    public String toString() {
        return "Tunnel{" + getDisplayName() + ", length=" + lengthMeters + "m}";
    }
}
