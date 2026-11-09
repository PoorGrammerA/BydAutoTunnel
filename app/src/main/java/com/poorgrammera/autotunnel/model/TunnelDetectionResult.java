package com.poorgrammera.autotunnel.model;

/**
 * Result of a tunnel detection evaluation.
 */
public final class TunnelDetectionResult {
    public enum State {
        NO_LOCATION,
        NORMAL,
        APPROACHING,
        IN_TUNNEL,
        EXITING
    }

    public final State state;
    public final boolean shouldRecirculate;
    public final Tunnel tunnel;
    public final double distanceMeters;
    public final double headingDiffDegrees;
    public final String reason;
    public final long timeRemainingSec;

    public TunnelDetectionResult(
            State state,
            boolean shouldRecirculate,
            Tunnel tunnel,
            double distanceMeters,
            double headingDiffDegrees,
            String reason,
            long timeRemainingSec
    ) {
        this.state = state;
        this.shouldRecirculate = shouldRecirculate;
        this.tunnel = tunnel;
        this.distanceMeters = distanceMeters;
        this.headingDiffDegrees = headingDiffDegrees;
        this.reason = reason;
        this.timeRemainingSec = timeRemainingSec;
    }

    public static TunnelDetectionResult noLocation() {
        return new TunnelDetectionResult(
                State.NO_LOCATION, false, null, Double.MAX_VALUE, Double.MAX_VALUE, "NO_LOCATION", 0
        );
    }

    public static TunnelDetectionResult normal(String reason) {
        return new TunnelDetectionResult(
                State.NORMAL, false, null, Double.MAX_VALUE, Double.MAX_VALUE, reason, 0
        );
    }

    public static TunnelDetectionResult approaching(Tunnel tunnel, double distanceM, double headingDiff) {
        return new TunnelDetectionResult(
                State.APPROACHING, true, tunnel, distanceM, headingDiff, "APPROACHING", 0
        );
    }

    public static TunnelDetectionResult inTunnel(Tunnel tunnel, double distToPolyline, long estRemainingSec) {
        return new TunnelDetectionResult(
                State.IN_TUNNEL, true, tunnel, distToPolyline, 0.0, "IN_TUNNEL", estRemainingSec
        );
    }

    public static TunnelDetectionResult exiting(Tunnel tunnel, long delayRemainingSec) {
        return new TunnelDetectionResult(
                State.EXITING, true, tunnel, 0.0, 0.0, "EXITING_COOLDOWN", delayRemainingSec
        );
    }

    @Override
    public String toString() {
        return "TunnelResult{" + state + ", tunnel=" + (tunnel != null ? tunnel.getDisplayName() : "none")
                + ", recirc=" + shouldRecirculate + ", reason=" + reason + "}";
    }
}
