package com.poorgrammera.autotunnel.ui;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.location.Location;

import androidx.core.content.ContextCompat;

import com.poorgrammera.autotunnel.R;

import org.osmdroid.views.MapView;
import org.osmdroid.views.overlay.Marker;

import java.util.Locale;

public final class UiUtils {
    private static BitmapDrawable cachedVehicleIcon = null;

    private UiUtils() {}

    /**
     * Renders the directional vector marker into a BitmapDrawable for Osmdroid.
     */
    public static synchronized BitmapDrawable getVehicleMarkerDrawable(Context context) {
        if (cachedVehicleIcon != null) return cachedVehicleIcon;

        Drawable drawable = ContextCompat.getDrawable(context, R.drawable.ic_vehicle_marker);
        if (drawable == null) return null;

        int sizePx = (int) (44 * context.getResources().getDisplayMetrics().density);
        Bitmap bitmap = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bitmap);
        drawable.setBounds(0, 0, sizePx, sizePx);
        drawable.draw(canvas);

        cachedVehicleIcon = new BitmapDrawable(context.getResources(), bitmap);
        return cachedVehicleIcon;
    }

    /**
     * Creates a directional navigation arrow Marker on the MapView aligned with the vehicle heading.
     *
     * @param mapView MapView instance
     * @param location Current vehicle GPS location
     * @param fallbackBearing Bearing to use if location.hasBearing() is false (e.g. while stopped)
     * @return Osmdroid Marker configured with center anchor, flat rotation, and heading
     */
    public static Marker createVehicleMarker(MapView mapView, Location location, float fallbackBearing) {
        if (location == null) return null;

        Marker marker = new Marker(mapView);
        marker.setPosition(new org.osmdroid.util.GeoPoint(location.getLatitude(), location.getLongitude()));
        marker.setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER);
        marker.setFlat(true);

        BitmapDrawable icon = getVehicleMarkerDrawable(mapView.getContext());
        if (icon != null) {
            marker.setIcon(icon);
        }

        float bearing = location.hasBearing() ? location.getBearing() : fallbackBearing;
        // Osmdroid Marker.drawAt rotates by -mBearing on screen, so -bearing achieves clockwise rotation
        marker.setRotation(-bearing);

        marker.setTitle("현재 차량 위치");
        if (location.hasAccuracy()) {
            marker.setSnippet(String.format(Locale.KOREAN, "GPS 오차 ±%.0fm | 진행 방향: %.0f°", location.getAccuracy(), bearing));
        }

        return marker;
    }
}
