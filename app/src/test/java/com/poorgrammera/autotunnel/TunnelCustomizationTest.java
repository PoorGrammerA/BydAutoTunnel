package com.poorgrammera.autotunnel;

import com.poorgrammera.autotunnel.data.UserCustomTunnel;
import com.poorgrammera.autotunnel.data.UserTunnelOverride;
import com.poorgrammera.autotunnel.model.GeoPoint;
import com.poorgrammera.autotunnel.model.OrientedBox;
import com.poorgrammera.autotunnel.model.Tunnel;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * Unit tests verifying user customization data models, JSON serialization, and bounding box overrides.
 */
public class TunnelCustomizationTest {

    @Test
    public void testTunnelOverrideApplication() {
        List<GeoPoint> points = Arrays.asList(
                new GeoPoint(37.500, 127.000),
                new GeoPoint(37.510, 127.000)
        );
        Tunnel base = new Tunnel(10L, 100010L, "기본터널", 0, false, 1000.0f, points);

        // Verify base default values
        assertEquals(0.0, base.getEffectiveStartBearing(), 0.001);
        assertEquals(200, base.getEffectiveStartBoxDist());
        assertFalse(base.isUserModified);
        assertFalse(base.isUserDeleted);

        // Apply override: change bearing to 45°, start box dist to 150m, and not deleted
        UserTunnelOverride override = new UserTunnelOverride(10L, false, 45.0, 150, 80, System.currentTimeMillis());
        Tunnel overridden = base.withOverride(override);

        assertTrue(overridden.isUserModified);
        assertFalse(overridden.isUserDeleted);
        assertEquals(45.0, overridden.getEffectiveStartBearing(), 0.001);
        assertEquals(150, overridden.getEffectiveStartBoxDist());
        assertEquals(80, overridden.getEffectiveBoxWidth());

        // Check if EntryBox reflects the overridden bearing and length
        OrientedBox entryBox = overridden.getEntryBox();
        assertNotNull(entryBox);
        assertEquals(45.0, entryBox.bearingDeg, 0.001);
        // length for 150m dist: Math.max(60.0, 150 - 30) = 120.0m
        assertEquals(120.0, entryBox.lengthMeters, 0.001);

        // Test deletion override
        UserTunnelOverride deleteOverride = new UserTunnelOverride(10L, true, null, null, null, System.currentTimeMillis());
        Tunnel deleted = base.withOverride(deleteOverride);
        assertTrue(deleted.isUserDeleted);
    }

    @Test
    public void testCustomTunnelCreation() {
        UserCustomTunnel custom = new UserCustomTunnel(
                -1001L,
                "새로운 지하차도",
                37.5000, 127.0000,
                37.5050, 127.0050,
                35.0,
                650.0f,
                true, // oneway
                250,  // startBoxDist
                70,   // boxWidth
                System.currentTimeMillis()
        );

        Tunnel tunnel = Tunnel.fromCustom(custom);
        assertTrue(tunnel.isUserCustom);
        assertEquals(-1001L, tunnel.id);
        assertEquals("새로운 지하차도", tunnel.name);
        assertTrue(tunnel.oneway);
        assertEquals(35.0, tunnel.getEffectiveStartBearing(), 0.001);
        assertEquals(250, tunnel.getEffectiveStartBoxDist());

        // Since oneway is true, reverse entry box must be null
        OrientedBox revBox = tunnel.getReverseEntryBox();
        assertTrue("One-way tunnel should not have reverse entry box", revBox == null);

        // Check entry box
        OrientedBox entryBox = tunnel.getEntryBox();
        assertNotNull(entryBox);
        assertEquals(35.0, entryBox.bearingDeg, 0.001);
        // length for 250m dist: 250 - 30 = 220m
        assertEquals(220.0, entryBox.lengthMeters, 0.001);
    }

    @Test
    public void testJsonSerializationContract() throws Exception {
        // Construct simulated JSON export
        JSONObject root = new JSONObject();
        root.put("version", 1);
        root.put("exportedAt", 1728604800000L);

        JSONArray overridesArr = new JSONArray();
        JSONObject o1 = new JSONObject();
        o1.put("baseTunnelId", 501L);
        o1.put("isDeleted", false);
        o1.put("overrideBearing", 12.5);
        o1.put("overrideStartBoxDist", 250);
        o1.put("overrideBoxWidth", 70);
        o1.put("updatedAt", 1728604800000L);
        overridesArr.put(o1);
        root.put("overrides", overridesArr);

        JSONArray customArr = new JSONArray();
        JSONObject c1 = new JSONObject();
        c1.put("customId", -1005L);
        c1.put("name", "테스트 커스텀 터널");
        c1.put("startLat", 37.4900);
        c1.put("startLng", 126.9900);
        c1.put("endLat", 37.4950);
        c1.put("endLng", 126.9950);
        c1.put("bearing", 40.0);
        c1.put("lengthMeters", 700.0);
        c1.put("oneway", false);
        c1.put("startBoxDist", 200);
        c1.put("boxWidth", 60);
        c1.put("createdAt", 1728604800000L);
        customArr.put(c1);
        root.put("customTunnels", customArr);

        String jsonString = root.toString(2);
        assertNotNull(jsonString);

        // Deserialize and verify
        JSONObject parsed = new JSONObject(jsonString);
        assertEquals(1, parsed.getInt("version"));
        JSONArray parsedOverrides = parsed.getJSONArray("overrides");
        assertEquals(1, parsedOverrides.length());
        JSONObject parsedO1 = parsedOverrides.getJSONObject(0);
        assertEquals(501L, parsedO1.getLong("baseTunnelId"));
        assertEquals(12.5, parsedO1.getDouble("overrideBearing"), 0.001);
        assertEquals(250, parsedO1.getInt("overrideStartBoxDist"));

        JSONArray parsedCustom = parsed.getJSONArray("customTunnels");
        assertEquals(1, parsedCustom.length());
        JSONObject parsedC1 = parsedCustom.getJSONObject(0);
        assertEquals(-1005L, parsedC1.getLong("customId"));
        assertEquals("테스트 커스텀 터널", parsedC1.getString("name"));
        assertEquals(700.0, parsedC1.getDouble("lengthMeters"), 0.001);
    }
}
