package com.poorgrammera.autotunnel;

import android.location.Location;

import com.poorgrammera.autotunnel.detector.TunnelDetector;
import com.poorgrammera.autotunnel.model.GeoPoint;
import com.poorgrammera.autotunnel.model.GeoUtils;
import com.poorgrammera.autotunnel.model.Tunnel;
import com.poorgrammera.autotunnel.model.TunnelDetectionResult;

import org.junit.Before;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

public class TunnelDetectorTest {

    @Test
    public void testDistanceCalculation() {
        // 서울시청 ~ 광화문 거리 (~550m)
        double dist = GeoUtils.distanceMeters(37.5665, 126.9780, 37.5714, 126.9768);
        assertTrue("Distance should be around 550m", dist > 500 && dist < 650);
    }

    @Test
    public void testBearingCalculation() {
        // 정북 방향
        double north = GeoUtils.bearingDegrees(37.0, 127.0, 38.0, 127.0);
        assertEquals(0.0, north, 1.0);

        // 정동 방향
        double east = GeoUtils.bearingDegrees(37.0, 127.0, 37.0, 128.0);
        assertEquals(90.0, east, 1.0);
    }

    @Test
    public void testAngleDiff() {
        assertEquals(10.0, GeoUtils.angleDiffDegrees(355.0, 5.0), 0.001);
        assertEquals(90.0, GeoUtils.angleDiffDegrees(0.0, 90.0), 0.001);
        assertEquals(180.0, GeoUtils.angleDiffDegrees(0.0, 180.0), 0.001);
        assertEquals(30.0, GeoUtils.angleDiffDegrees(10.0, 340.0), 0.001);
    }

    @Test
    public void testPointToPolylineDistance() {
        List<GeoPoint> line = Arrays.asList(
                new GeoPoint(37.000, 127.000),
                new GeoPoint(37.010, 127.000)
        );
        // 선상에 있는 점
        double distOnLine = GeoUtils.distancePointToPolylineMeters(37.005, 127.000, line);
        assertEquals(0.0, distOnLine, 2.0);

        // 선에서 약 111m 동쪽으로 떨어진 점
        double distEast = GeoUtils.distancePointToPolylineMeters(37.005, 127.001, line);
        assertTrue(distEast > 70 && distEast < 100);
    }

    @Test
    public void testOrientedBoxCollisionNorth() {
        // Oriented box centered at (37.5, 127.0), heading 0° (North), length=200m, width=50m
        com.poorgrammera.autotunnel.model.OrientedBox box =
                new com.poorgrammera.autotunnel.model.OrientedBox(37.5, 127.0, 200.0, 50.0, 0.0);

        // Center should be inside
        assertTrue(box.contains(37.5, 127.0));

        // 50m North (inside 200m length => half is 100m)
        double lat50mNorth = 37.5 + (50.0 / 111_320.0);
        assertTrue(box.contains(lat50mNorth, 127.0));

        // 15m East (inside 50m width => half is 25m)
        double cosLat = Math.cos(Math.toRadians(37.5));
        double lng15mEast = 127.0 + (15.0 / (111_320.0 * cosLat));
        assertTrue(box.contains(37.5, lng15mEast));

        // 35m East (outside 50m width => half is 25m)
        double lng35mEast = 127.0 + (35.0 / (111_320.0 * cosLat));
        assertFalse(box.contains(37.5, lng35mEast));

        // 120m North (outside 200m length => half is 100m)
        double lat120mNorth = 37.5 + (120.0 / 111_320.0);
        assertFalse(box.contains(lat120mNorth, 127.0));
    }

    @Test
    public void testOrientedBoxCorners() {
        com.poorgrammera.autotunnel.model.OrientedBox box =
                new com.poorgrammera.autotunnel.model.OrientedBox(37.5, 127.0, 100.0, 40.0, 90.0);
        List<GeoPoint> corners = box.getCorners();
        assertEquals("Polygon should have 5 points (closed loop)", 5, corners.size());
        assertEquals("Start and end point must match to close polygon", corners.get(0).lat, corners.get(4).lat, 0.000001);
        assertEquals("Start and end point must match to close polygon", corners.get(0).lng, corners.get(4).lng, 0.000001);
    }

    @Test
    public void testTunnelBoxGenerators() {
        // Create a 1km straight North-bound tunnel
        List<GeoPoint> points = Arrays.asList(
                new GeoPoint(37.5000, 127.0000),
                new GeoPoint(37.5045, 127.0000),
                new GeoPoint(37.5090, 127.0000)
        );
        Tunnel tunnel = new Tunnel(1L, 1001L, "테스트터널", 0, false, 1000.0f, points);

        // 1. Core Box check
        com.poorgrammera.autotunnel.model.OrientedBox coreBox = tunnel.getCoreBox();
        assertNotNull(coreBox);
        // Middle point of tunnel should be inside core box
        assertTrue("Tunnel midpoint should be inside core box", coreBox.contains(37.5045, 127.0000));
        // Entrance should be inside core box (has 30m margin)
        assertTrue("Entrance should be inside core box", coreBox.contains(37.5000, 127.0000));

        // 2. Start Bounding Box (Entry trigger) check
        com.poorgrammera.autotunnel.model.OrientedBox entryBox = tunnel.getEntryBox();
        assertNotNull(entryBox);

        // 100m ahead of entrance (South of 37.5000)
        double lat100mAhead = 37.5000 - (100.0 / 111_320.0);
        assertTrue("100m ahead of entrance should collide with entry trigger box",
                entryBox.contains(lat100mAhead, 127.0000));

        // 250m ahead of entrance (Outside 200m range)
        double lat250mAhead = 37.5000 - (250.0 / 111_320.0);
        assertFalse("250m ahead of entrance should NOT collide with entry trigger box",
                entryBox.contains(lat250mAhead, 127.0000));

        // Inside tunnel should NOT collide with entry trigger box
        assertFalse("Inside tunnel should NOT be inside entry trigger box",
                entryBox.contains(37.5020, 127.0000));

        // 3. Reverse entry box (since oneway=false)
        com.poorgrammera.autotunnel.model.OrientedBox revBox = tunnel.getReverseEntryBox();
        assertNotNull(revBox);
        // 100m ahead of reverse entrance (North of 37.5090)
        double lat100mRevAhead = 37.5090 + (100.0 / 111_320.0);
        assertTrue("100m ahead of reverse entrance should collide with reverse trigger box",
                revBox.contains(lat100mRevAhead, 127.0000));
    }
}
