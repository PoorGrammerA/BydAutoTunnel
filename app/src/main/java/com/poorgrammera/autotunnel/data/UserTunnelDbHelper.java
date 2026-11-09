package com.poorgrammera.autotunnel.data;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import android.util.Log;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * SQLite Database Helper for storing user overrides and user-created custom tunnels.
 * Ensures user modifications survive base tunnel database updates.
 */
public class UserTunnelDbHelper extends SQLiteOpenHelper {
    private static final String TAG = "UserTunnelDbHelper";
    private static final String DATABASE_NAME = "user_tunnels.db";
    private static final int DATABASE_VERSION = 1;

    // Table 1: Overrides for base tunnels
    private static final String TABLE_OVERRIDES = "user_tunnel_overrides";
    private static final String COL_BASE_TUNNEL_ID = "base_tunnel_id";
    private static final String COL_IS_DELETED = "is_deleted";
    private static final String COL_OVERRIDE_BEARING = "override_bearing";
    private static final String COL_OVERRIDE_START_BOX_DIST = "override_start_box_dist";
    private static final String COL_OVERRIDE_BOX_WIDTH = "override_box_width";
    private static final String COL_UPDATED_AT = "updated_at";

    // Table 2: Custom tunnels created by user
    private static final String TABLE_CUSTOM_TUNNELS = "user_custom_tunnels";
    private static final String COL_CUSTOM_ID = "custom_id";
    private static final String COL_NAME = "name";
    private static final String COL_START_LAT = "start_lat";
    private static final String COL_START_LNG = "start_lng";
    private static final String COL_END_LAT = "end_lat";
    private static final String COL_END_LNG = "end_lng";
    private static final String COL_BEARING = "bearing";
    private static final String COL_LENGTH_METERS = "length_meters";
    private static final String COL_ONEWAY = "oneway";
    private static final String COL_START_BOX_DIST = "start_box_dist";
    private static final String COL_BOX_WIDTH = "box_width";
    private static final String COL_CREATED_AT = "created_at";

    private static volatile UserTunnelDbHelper instance;

    public static synchronized UserTunnelDbHelper getInstance(Context context) {
        if (instance == null) {
            instance = new UserTunnelDbHelper(context.getApplicationContext());
        }
        return instance;
    }

    private UserTunnelDbHelper(Context context) {
        super(context, DATABASE_NAME, null, DATABASE_VERSION);
    }

    @Override
    public void onCreate(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE " + TABLE_OVERRIDES + " ("
                + COL_BASE_TUNNEL_ID + " INTEGER PRIMARY KEY, "
                + COL_IS_DELETED + " INTEGER NOT NULL DEFAULT 0, "
                + COL_OVERRIDE_BEARING + " REAL, "
                + COL_OVERRIDE_START_BOX_DIST + " INTEGER, "
                + COL_OVERRIDE_BOX_WIDTH + " INTEGER, "
                + COL_UPDATED_AT + " INTEGER NOT NULL"
                + ");");

        db.execSQL("CREATE TABLE " + TABLE_CUSTOM_TUNNELS + " ("
                + COL_CUSTOM_ID + " INTEGER PRIMARY KEY, "
                + COL_NAME + " TEXT NOT NULL, "
                + COL_START_LAT + " REAL NOT NULL, "
                + COL_START_LNG + " REAL NOT NULL, "
                + COL_END_LAT + " REAL NOT NULL, "
                + COL_END_LNG + " REAL NOT NULL, "
                + COL_BEARING + " REAL NOT NULL, "
                + COL_LENGTH_METERS + " REAL NOT NULL, "
                + COL_ONEWAY + " INTEGER NOT NULL DEFAULT 0, "
                + COL_START_BOX_DIST + " INTEGER NOT NULL DEFAULT 200, "
                + COL_BOX_WIDTH + " INTEGER NOT NULL DEFAULT 60, "
                + COL_CREATED_AT + " INTEGER NOT NULL"
                + ");");

        Log.i(TAG, "Created user tunnel tables successfully.");
    }

    @Override
    public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        // Future migration logic
    }

    // ==================== Overrides CRUD ====================

    public synchronized Map<Long, UserTunnelOverride> getAllOverrides() {
        Map<Long, UserTunnelOverride> map = new HashMap<>();
        SQLiteDatabase db = getReadableDatabase();
        try (Cursor cursor = db.query(TABLE_OVERRIDES, null, null, null, null, null, null)) {
            while (cursor.moveToNext()) {
                long baseId = cursor.getLong(cursor.getColumnIndexOrThrow(COL_BASE_TUNNEL_ID));
                boolean isDeleted = cursor.getInt(cursor.getColumnIndexOrThrow(COL_IS_DELETED)) == 1;

                Double bearing = cursor.isNull(cursor.getColumnIndexOrThrow(COL_OVERRIDE_BEARING))
                        ? null : cursor.getDouble(cursor.getColumnIndexOrThrow(COL_OVERRIDE_BEARING));
                Integer startBoxDist = cursor.isNull(cursor.getColumnIndexOrThrow(COL_OVERRIDE_START_BOX_DIST))
                        ? null : cursor.getInt(cursor.getColumnIndexOrThrow(COL_OVERRIDE_START_BOX_DIST));
                Integer boxWidth = cursor.isNull(cursor.getColumnIndexOrThrow(COL_OVERRIDE_BOX_WIDTH))
                        ? null : cursor.getInt(cursor.getColumnIndexOrThrow(COL_OVERRIDE_BOX_WIDTH));
                long updatedAt = cursor.getLong(cursor.getColumnIndexOrThrow(COL_UPDATED_AT));

                map.put(baseId, new UserTunnelOverride(baseId, isDeleted, bearing, startBoxDist, boxWidth, updatedAt));
            }
        } catch (Exception e) {
            Log.e(TAG, "Failed to load overrides", e);
        }
        return map;
    }

    public synchronized UserTunnelOverride getOverride(long baseTunnelId) {
        SQLiteDatabase db = getReadableDatabase();
        try (Cursor cursor = db.query(TABLE_OVERRIDES, null, COL_BASE_TUNNEL_ID + "=?",
                new String[]{String.valueOf(baseTunnelId)}, null, null, null)) {
            if (cursor.moveToFirst()) {
                boolean isDeleted = cursor.getInt(cursor.getColumnIndexOrThrow(COL_IS_DELETED)) == 1;
                Double bearing = cursor.isNull(cursor.getColumnIndexOrThrow(COL_OVERRIDE_BEARING))
                        ? null : cursor.getDouble(cursor.getColumnIndexOrThrow(COL_OVERRIDE_BEARING));
                Integer startBoxDist = cursor.isNull(cursor.getColumnIndexOrThrow(COL_OVERRIDE_START_BOX_DIST))
                        ? null : cursor.getInt(cursor.getColumnIndexOrThrow(COL_OVERRIDE_START_BOX_DIST));
                Integer boxWidth = cursor.isNull(cursor.getColumnIndexOrThrow(COL_OVERRIDE_BOX_WIDTH))
                        ? null : cursor.getInt(cursor.getColumnIndexOrThrow(COL_OVERRIDE_BOX_WIDTH));
                long updatedAt = cursor.getLong(cursor.getColumnIndexOrThrow(COL_UPDATED_AT));
                return new UserTunnelOverride(baseTunnelId, isDeleted, bearing, startBoxDist, boxWidth, updatedAt);
            }
        } catch (Exception e) {
            Log.e(TAG, "Failed to get override for id=" + baseTunnelId, e);
        }
        return null;
    }

    public synchronized void saveOverride(UserTunnelOverride override) {
        SQLiteDatabase db = getWritableDatabase();
        ContentValues values = new ContentValues();
        values.put(COL_BASE_TUNNEL_ID, override.baseTunnelId);
        values.put(COL_IS_DELETED, override.isDeleted ? 1 : 0);
        if (override.overrideBearing != null) values.put(COL_OVERRIDE_BEARING, override.overrideBearing);
        else values.putNull(COL_OVERRIDE_BEARING);
        if (override.overrideStartBoxDist != null) values.put(COL_OVERRIDE_START_BOX_DIST, override.overrideStartBoxDist);
        else values.putNull(COL_OVERRIDE_START_BOX_DIST);
        if (override.overrideBoxWidth != null) values.put(COL_OVERRIDE_BOX_WIDTH, override.overrideBoxWidth);
        else values.putNull(COL_OVERRIDE_BOX_WIDTH);
        values.put(COL_UPDATED_AT, override.updatedAt);

        db.insertWithOnConflict(TABLE_OVERRIDES, null, values, SQLiteDatabase.CONFLICT_REPLACE);
    }

    public synchronized void deleteOverride(long baseTunnelId) {
        SQLiteDatabase db = getWritableDatabase();
        db.delete(TABLE_OVERRIDES, COL_BASE_TUNNEL_ID + "=?", new String[]{String.valueOf(baseTunnelId)});
    }

    // ==================== Custom Tunnels CRUD ====================

    public synchronized List<UserCustomTunnel> getAllCustomTunnels() {
        List<UserCustomTunnel> list = new ArrayList<>();
        SQLiteDatabase db = getReadableDatabase();
        try (Cursor cursor = db.query(TABLE_CUSTOM_TUNNELS, null, null, null, null, null, COL_CREATED_AT + " DESC")) {
            while (cursor.moveToNext()) {
                long customId = cursor.getLong(cursor.getColumnIndexOrThrow(COL_CUSTOM_ID));
                String name = cursor.getString(cursor.getColumnIndexOrThrow(COL_NAME));
                double startLat = cursor.getDouble(cursor.getColumnIndexOrThrow(COL_START_LAT));
                double startLng = cursor.getDouble(cursor.getColumnIndexOrThrow(COL_START_LNG));
                double endLat = cursor.getDouble(cursor.getColumnIndexOrThrow(COL_END_LAT));
                double endLng = cursor.getDouble(cursor.getColumnIndexOrThrow(COL_END_LNG));
                double bearing = cursor.getDouble(cursor.getColumnIndexOrThrow(COL_BEARING));
                float lengthMeters = cursor.getFloat(cursor.getColumnIndexOrThrow(COL_LENGTH_METERS));
                boolean oneway = cursor.getInt(cursor.getColumnIndexOrThrow(COL_ONEWAY)) == 1;
                int startBoxDist = cursor.getInt(cursor.getColumnIndexOrThrow(COL_START_BOX_DIST));
                int boxWidth = cursor.getInt(cursor.getColumnIndexOrThrow(COL_BOX_WIDTH));
                long createdAt = cursor.getLong(cursor.getColumnIndexOrThrow(COL_CREATED_AT));

                list.add(new UserCustomTunnel(customId, name, startLat, startLng, endLat, endLng,
                        bearing, lengthMeters, oneway, startBoxDist, boxWidth, createdAt));
            }
        } catch (Exception e) {
            Log.e(TAG, "Failed to load custom tunnels", e);
        }
        return list;
    }

    public synchronized void saveCustomTunnel(UserCustomTunnel tunnel) {
        SQLiteDatabase db = getWritableDatabase();
        ContentValues values = new ContentValues();
        values.put(COL_CUSTOM_ID, tunnel.customId);
        values.put(COL_NAME, tunnel.name);
        values.put(COL_START_LAT, tunnel.startLat);
        values.put(COL_START_LNG, tunnel.startLng);
        values.put(COL_END_LAT, tunnel.endLat);
        values.put(COL_END_LNG, tunnel.endLng);
        values.put(COL_BEARING, tunnel.bearing);
        values.put(COL_LENGTH_METERS, tunnel.lengthMeters);
        values.put(COL_ONEWAY, tunnel.oneway ? 1 : 0);
        values.put(COL_START_BOX_DIST, tunnel.startBoxDist);
        values.put(COL_BOX_WIDTH, tunnel.boxWidth);
        values.put(COL_CREATED_AT, tunnel.createdAt);

        db.insertWithOnConflict(TABLE_CUSTOM_TUNNELS, null, values, SQLiteDatabase.CONFLICT_REPLACE);
    }

    public synchronized void deleteCustomTunnel(long customId) {
        SQLiteDatabase db = getWritableDatabase();
        db.delete(TABLE_CUSTOM_TUNNELS, COL_CUSTOM_ID + "=?", new String[]{String.valueOf(customId)});
    }

    public synchronized long generateNextCustomId() {
        SQLiteDatabase db = getReadableDatabase();
        long minId = -1000L;
        try (Cursor cursor = db.rawQuery("SELECT MIN(" + COL_CUSTOM_ID + ") FROM " + TABLE_CUSTOM_TUNNELS, null)) {
            if (cursor.moveToFirst() && !cursor.isNull(0)) {
                long currentMin = cursor.getLong(0);
                if (currentMin <= -1000L) {
                    minId = currentMin - 1;
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "Failed to generate custom id", e);
        }
        return minId;
    }

    public synchronized void clearAll() {
        SQLiteDatabase db = getWritableDatabase();
        db.delete(TABLE_OVERRIDES, null, null);
        db.delete(TABLE_CUSTOM_TUNNELS, null, null);
    }
}
