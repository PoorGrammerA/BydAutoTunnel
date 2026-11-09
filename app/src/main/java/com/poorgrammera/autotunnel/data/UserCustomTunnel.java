package com.poorgrammera.autotunnel.data;

/**
 * Model representing a new tunnel created by the user from scratch.
 */
public final class UserCustomTunnel {
    public final long customId;         // Negative integer unique ID
    public final String name;
    public final double startLat;
    public final double startLng;
    public final double endLat;
    public final double endLng;
    public final double bearing;
    public final float lengthMeters;
    public final boolean oneway;
    public final int startBoxDist;       // e.g. 200m
    public final int boxWidth;           // e.g. 60m
    public final long createdAt;

    public UserCustomTunnel(
            long customId,
            String name,
            double startLat,
            double startLng,
            double endLat,
            double endLng,
            double bearing,
            float lengthMeters,
            boolean oneway,
            int startBoxDist,
            int boxWidth,
            long createdAt
    ) {
        this.customId = customId;
        this.name = name;
        this.startLat = startLat;
        this.startLng = startLng;
        this.endLat = endLat;
        this.endLng = endLng;
        this.bearing = bearing;
        this.lengthMeters = lengthMeters;
        this.oneway = oneway;
        this.startBoxDist = startBoxDist;
        this.boxWidth = boxWidth;
        this.createdAt = createdAt;
    }
}
