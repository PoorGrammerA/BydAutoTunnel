package com.poorgrammera.autotunnel.model;

import android.content.Context;
import android.util.Log;

import java.io.BufferedInputStream;
import java.io.DataInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * High-performance spatial store that loads compressed binary tunnel data (tunnels_kr.bin)
 * from assets and indexes them into a geographic grid for sub-millisecond range queries.
 */
public final class TunnelStore {
    private static final String TAG = "TunnelStore";
    private static final String ASSET_NAME = "tunnels_kr.bin";
    private static final double CELL_SIZE_DEG = 0.05; // ~5.5km cell bucket

    private static volatile TunnelStore sInstance;

    private final List<Tunnel> rawBaseTunnels = new ArrayList<>();
    private final List<Tunnel> allTunnels = new ArrayList<>();
    private final Map<Long, List<Tunnel>> spatialGrid = new HashMap<>();
    private boolean isBaseLoaded = false;

    public static TunnelStore getInstance(Context context) {
        if (sInstance == null) {
            synchronized (TunnelStore.class) {
                if (sInstance == null) {
                    sInstance = new TunnelStore();
                    sInstance.load(context.getApplicationContext());
                }
            }
        }
        return sInstance;
    }

    private TunnelStore() {}

    public synchronized void load(Context context) {
        loadBaseAsset(context);
        rebuildActiveIndex(context);
    }

    public synchronized void reload(Context context) {
        rebuildActiveIndex(context);
    }

    private void loadBaseAsset(Context context) {
        if (isBaseLoaded) return;
        long startTime = System.currentTimeMillis();

        try (InputStream is = context.getAssets().open(ASSET_NAME);
             BufferedInputStream bis = new BufferedInputStream(is, 65536);
             DataInputStream dis = new DataInputStream(bis)) {

            byte[] magic = new byte[4];
            dis.readFully(magic);
            String magicStr = new String(magic, StandardCharsets.US_ASCII);
            if (!"ATNL".equals(magicStr)) {
                throw new IllegalStateException("Invalid tunnel asset format magic: " + magicStr);
            }

            int version = dis.readInt();
            long timestamp = dis.readLong();
            int count = dis.readInt();

            Log.i(TAG, "Loading " + count + " base tunnels (asset version=" + version + ", date=" + timestamp + ")...");
            rawBaseTunnels.clear();

            for (int i = 0; i < count; i++) {
                long osmId = dis.readLong();
                int nameLen = dis.readUnsignedShort();
                String name = "";
                if (nameLen > 0) {
                    byte[] nameBytes = new byte[nameLen];
                    dis.readFully(nameBytes);
                    name = new String(nameBytes, StandardCharsets.UTF_8);
                }

                int highwayCode = dis.readUnsignedByte();
                boolean oneway = dis.readUnsignedByte() == 1;
                float lengthM = dis.readFloat();
                int pointCount = dis.readInt();

                List<GeoPoint> points = new ArrayList<>(pointCount);
                for (int p = 0; p < pointCount; p++) {
                    int latScaled = dis.readInt();
                    int lngScaled = dis.readInt();
                    points.add(new GeoPoint(latScaled / 1_000_000.0, lngScaled / 1_000_000.0));
                }

                Tunnel tunnel = new Tunnel(i + 1, osmId, name, highwayCode, oneway, lengthM, points);
                rawBaseTunnels.add(tunnel);
            }

            isBaseLoaded = true;
            long elapsed = System.currentTimeMillis() - startTime;
            Log.i(TAG, "Base tunnels loaded successfully: " + rawBaseTunnels.size() + " tunnels in " + elapsed + "ms");

        } catch (Exception e) {
            Log.e(TAG, "Failed to load tunnel asset: " + e.getMessage(), e);
        }
    }

    private void rebuildActiveIndex(Context context) {
        long startTime = System.currentTimeMillis();
        com.poorgrammera.autotunnel.data.UserTunnelDbHelper db =
                com.poorgrammera.autotunnel.data.UserTunnelDbHelper.getInstance(context);

        Map<Long, com.poorgrammera.autotunnel.data.UserTunnelOverride> overrides = db.getAllOverrides();
        List<com.poorgrammera.autotunnel.data.UserCustomTunnel> customTunnels = db.getAllCustomTunnels();

        allTunnels.clear();
        spatialGrid.clear();

        // 1. Process base tunnels with user overrides
        for (Tunnel base : rawBaseTunnels) {
            com.poorgrammera.autotunnel.data.UserTunnelOverride override = overrides.get(base.id);
            if (override != null) {
                if (override.isDeleted) {
                    continue; // Skip deleted tunnels from detection
                }
                Tunnel modified = base.withOverride(override);
                allTunnels.add(modified);
                indexTunnel(modified);
            } else {
                allTunnels.add(base);
                indexTunnel(base);
            }
        }

        // 2. Process user custom tunnels
        for (com.poorgrammera.autotunnel.data.UserCustomTunnel custom : customTunnels) {
            Tunnel customTunnel = Tunnel.fromCustom(custom);
            allTunnels.add(customTunnel);
            indexTunnel(customTunnel);
        }

        long elapsed = System.currentTimeMillis() - startTime;
        Log.i(TAG, "Rebuilt active index with " + allTunnels.size() + " tunnels ("
                + overrides.size() + " overrides, " + customTunnels.size() + " custom) in " + elapsed + "ms");
    }

    private void indexTunnel(Tunnel tunnel) {
        int minX = (int) Math.floor(tunnel.bounds.minLng / CELL_SIZE_DEG);
        int maxX = (int) Math.floor(tunnel.bounds.maxLng / CELL_SIZE_DEG);
        int minY = (int) Math.floor(tunnel.bounds.minLat / CELL_SIZE_DEG);
        int maxY = (int) Math.floor(tunnel.bounds.maxLat / CELL_SIZE_DEG);

        for (int x = minX; x <= maxX; x++) {
            for (int y = minY; y <= maxY; y++) {
                long cellKey = (((long) x) << 32) | (y & 0xFFFFFFFFL);
                List<Tunnel> cellList = spatialGrid.computeIfAbsent(cellKey, k -> new ArrayList<>());
                cellList.add(tunnel);
            }
        }
    }

    public List<Tunnel> getAllTunnels() {
        return Collections.unmodifiableList(allTunnels);
    }

    public List<Tunnel> getRawBaseTunnels() {
        return Collections.unmodifiableList(rawBaseTunnels);
    }

    public Tunnel getTunnelById(long id) {
        for (Tunnel t : allTunnels) {
            if (t.id == id) return t;
        }
        for (Tunnel t : rawBaseTunnels) {
            if (t.id == id) return t;
        }
        return null;
    }

    /**
     * Fast spatial query for tunnels located within radiusMeters of (lat, lng).
     */
    public List<Tunnel> queryNearby(double lat, double lng, double radiusMeters) {
        if (!isBaseLoaded || allTunnels.isEmpty()) return Collections.emptyList();

        GeoBounds queryBounds = GeoUtils.createBounds(lat, lng, radiusMeters);

        int minX = (int) Math.floor(queryBounds.minLng / CELL_SIZE_DEG);
        int maxX = (int) Math.floor(queryBounds.maxLng / CELL_SIZE_DEG);
        int minY = (int) Math.floor(queryBounds.minLat / CELL_SIZE_DEG);
        int maxY = (int) Math.floor(queryBounds.maxLat / CELL_SIZE_DEG);

        Set<Tunnel> candidates = new HashSet<>();

        for (int x = minX; x <= maxX; x++) {
            for (int y = minY; y <= maxY; y++) {
                long cellKey = (((long) x) << 32) | (y & 0xFFFFFFFFL);
                List<Tunnel> cellList = spatialGrid.get(cellKey);
                if (cellList != null) {
                    for (Tunnel t : cellList) {
                        if (t.bounds.intersects(queryBounds)) {
                            candidates.add(t);
                        }
                    }
                }
            }
        }

        return new ArrayList<>(candidates);
    }
}
