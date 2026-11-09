package com.poorgrammera.autotunnel.data;

/**
 * Model representing user customizations or deletion of an existing base tunnel.
 */
public final class UserTunnelOverride {
    public final long baseTunnelId;
    public final boolean isDeleted;
    public final Double overrideBearing;       // null if unchanged
    public final Integer overrideStartBoxDist; // null if default (e.g. 200m)
    public final Integer overrideBoxWidth;     // null if default (e.g. 60m)
    public final long updatedAt;

    public UserTunnelOverride(
            long baseTunnelId,
            boolean isDeleted,
            Double overrideBearing,
            Integer overrideStartBoxDist,
            Integer overrideBoxWidth,
            long updatedAt
    ) {
        this.baseTunnelId = baseTunnelId;
        this.isDeleted = isDeleted;
        this.overrideBearing = overrideBearing;
        this.overrideStartBoxDist = overrideStartBoxDist;
        this.overrideBoxWidth = overrideBoxWidth;
        this.updatedAt = updatedAt;
    }
}
