package com.poorgrammera.autotunnel.receiver;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;
import androidx.core.content.ContextCompat;

import com.poorgrammera.autotunnel.service.AutoTunnelService;

/**
 * BroadcastReceiver listening for device boot completion and BYD vehicle ACC/IGN ON events
 * to automatically start the AutoTunnelService daemon.
 */
public class BootReceiver extends BroadcastReceiver {
    private static final String TAG = "BootReceiver";

    @Override
    public void onReceive(Context context, Intent intent) {
        String action = intent != null ? intent.getAction() : null;
        Log.i(TAG, "Boot/Power broadcast received: " + action);

        try {
            Intent serviceIntent = new Intent(context, AutoTunnelService.class);
            ContextCompat.startForegroundService(context, serviceIntent);
            Log.i(TAG, "AutoTunnelService started successfully from BootReceiver.");
        } catch (Exception e) {
            Log.e(TAG, "Failed to start AutoTunnelService from BootReceiver: " + e.getMessage(), e);
        }
    }
}
