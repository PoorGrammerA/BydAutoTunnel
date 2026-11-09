package com.poorgrammera.autotunnel.vehicle;

import android.content.Context;
import android.util.Log;

import java.lang.reflect.Method;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Controller bridging to BYD Automotive hardware interfaces via reflection.
 * Interacts with BYDAutoAcDevice and BYDAutoSpeedDevice without hard compilation dependencies.
 */
public class VehicleController {
    private static final String TAG = "VehicleController";

    public static final int CYCLE_OUTLOOP_FRESH = 0;  // 외기 순환
    public static final int CYCLE_INLOOP_RECYCLE = 1; // 내기 순환
    public static final int CYCLE_UNKNOWN = -1;

    private final Context context;
    private final ExecutorService executor = Executors.newSingleThreadExecutor(r ->
            new Thread(r, "VehicleController-Worker")
    );

    private Object acDevice;
    private int acDevType;
    private Object speedDevice;
    private Object bodyworkDevice;
    private int bodyworkDevType;

    private Method baseSetMethod;
    private Method baseArraySetMethod;
    private Class<?> featureIdsClass;

    private volatile boolean isInitialized = false;

    public VehicleController(Context context) {
        this.context = context.getApplicationContext();
        initReflectionAsync();
    }

    private void initReflectionAsync() {
        executor.submit(this::initReflectionSync);
    }

    public synchronized boolean initReflectionSync() {
        if (isInitialized && acDevice != null) return true;

        try {
            Log.d(TAG, "Initializing BYD Automotive framework reflection...");
            VehicleContextWrapper wrappedCtx = new VehicleContextWrapper(context);

            // 1. Initialize AC Device
            try {
                Class<?> acClass = Class.forName("android.hardware.bydauto.ac.BYDAutoAcDevice");
                Method acGetInstance = acClass.getMethod("getInstance", Context.class);
                acDevice = acGetInstance.invoke(null, wrappedCtx);

                Method getAcType = acClass.getMethod("getDevicetype");
                acDevType = (int) getAcType.invoke(acDevice);

                Class<?> absDeviceClass = acClass.getSuperclass();
                if (absDeviceClass != null) {
                    try {
                        baseSetMethod = absDeviceClass.getDeclaredMethod("set", int.class, int.class, int.class);
                        baseSetMethod.setAccessible(true);
                    } catch (Exception ignored) {}

                    try {
                        baseArraySetMethod = absDeviceClass.getDeclaredMethod("set", int.class, int[].class, int[].class);
                        baseArraySetMethod.setAccessible(true);
                    } catch (Exception ignored) {}
                }
                Log.i(TAG, "BYDAutoAcDevice initialized successfully.");
            } catch (Exception e) {
                Log.w(TAG, "BYDAutoAcDevice not available: " + e.getMessage());
            }

            // 2. Initialize Feature IDs
            try {
                featureIdsClass = Class.forName("android.hardware.bydauto.BYDAutoFeatureIds");
            } catch (Exception ignored) {}

            // 3. Initialize Speed Device
            try {
                Class<?> speedClass = Class.forName("android.hardware.bydauto.speed.BYDAutoSpeedDevice");
                Method speedGetInstance = speedClass.getMethod("getInstance", Context.class);
                speedDevice = speedGetInstance.invoke(null, wrappedCtx);
                Log.i(TAG, "BYDAutoSpeedDevice initialized successfully.");
            } catch (Exception e) {
                Log.w(TAG, "BYDAutoSpeedDevice not available: " + e.getMessage());
            }

            // 4. Initialize Bodywork Device for Windows & Sunroof
            try {
                Class<?> bodyworkClass = Class.forName("android.hardware.bydauto.bodywork.BYDAutoBodyworkDevice");
                Method bodyworkGetInstance = bodyworkClass.getMethod("getInstance", Context.class);
                bodyworkDevice = bodyworkGetInstance.invoke(null, wrappedCtx);
                Method getBodyworkType = bodyworkClass.getMethod("getDevicetype");
                bodyworkDevType = (int) getBodyworkType.invoke(bodyworkDevice);
                Log.i(TAG, "BYDAutoBodyworkDevice initialized successfully.");
            } catch (Exception e) {
                Log.w(TAG, "BYDAutoBodyworkDevice not available: " + e.getMessage());
            }

            isInitialized = (acDevice != null);
            return isInitialized;

        } catch (Exception e) {
            Log.e(TAG, "Failed to initialize BYD reflection: " + e.getMessage(), e);
            return false;
        }
    }

    /**
     * Reads the current A/C air circulation mode.
     * @return 1 for Recirculation (내기), 0 for Fresh (외기), -1 if unknown / unavailable.
     */
    public int getAirConditionerCycleState() {
        if (!ensureAcDevice()) return CYCLE_UNKNOWN;

        try {
            // Priority 1: Correct BYD official API getAcCycleMode()
            Method m = acDevice.getClass().getMethod("getAcCycleMode");
            Object result = m.invoke(acDevice);
            if (result instanceof Integer) {
                int mode = (Integer) result;
                Log.d(TAG, "getAirConditionerCycleState: getAcCycleMode() returned " + mode);
                return mode; // 1: Inloop, 0: Outloop
            }
        } catch (Exception e) {
            Log.w(TAG, "getAcCycleMode failed, attempting getAcVentilationState: " + e.getMessage());
        }

        try {
            // Priority 2: Fallback getAcVentilationState()
            Method m = acDevice.getClass().getMethod("getAcVentilationState");
            Object result = m.invoke(acDevice);
            if (result instanceof Integer) {
                return (Integer) result;
            }
        } catch (Exception ignored) {}

        return CYCLE_UNKNOWN;
    }

    /**
     * Sets the A/C air circulation mode.
     * @param mode 1 for Recirculation (내기), 0 for Fresh (외기)
     */
    public boolean setAirConditionerCycleState(int mode) {
        if (!ensureAcDevice()) return false;

        Log.i(TAG, "setAirConditionerCycleState -> " + (mode == 1 ? "RECIRCULATION (내기)" : "FRESH AIR (외기)"));

        // Attempt 1: setAcCycleMode(int setSource, int mode) with setSource = 0 (UI_KEY)
        try {
            Method m = acDevice.getClass().getMethod("setAcCycleMode", int.class, int.class);
            Object ret = m.invoke(acDevice, 0, mode);
            if (ret instanceof Integer && (Integer) ret == 0) {
                Log.d(TAG, "setAcCycleMode(0, " + mode + ") succeeded.");
                return true;
            }
        } catch (Exception e) {
            Log.w(TAG, "setAcCycleMode(0, mode) failed: " + e.getMessage());
        }

        // Attempt 2: setAcCycleMode(int setSource, int mode) with setSource = 1 (VOICE)
        try {
            Method m = acDevice.getClass().getMethod("setAcCycleMode", int.class, int.class);
            Object ret = m.invoke(acDevice, 1, mode);
            if (ret instanceof Integer && (Integer) ret == 0) {
                Log.d(TAG, "setAcCycleMode(1, " + mode + ") succeeded.");
                return true;
            }
        } catch (Exception e) {
            Log.w(TAG, "setAcCycleMode(1, mode) failed: " + e.getMessage());
        }

        // Attempt 3: Feature ID command via base set()
        try {
            if (featureIdsClass != null && baseArraySetMethod != null) {
                int cycleSetId = featureIdsClass.getField("AC_CYCLE_MODE_SET").getInt(null);
                int ctrlSourceSetId = featureIdsClass.getField("AC_CTRL_SOURCE_SET").getInt(null);
                int[] area = {cycleSetId, ctrlSourceSetId};
                int[] param = {mode, 0};
                int ret = (int) baseArraySetMethod.invoke(acDevice, acDevType, area, param);
                Log.d(TAG, "baseArraySetMethod cycle returned " + ret);
                return ret == 0;
            }
        } catch (Exception e) {
            Log.w(TAG, "Array set method for cycle failed: " + e.getMessage());
        }

        // Attempt 4: Direct single feature ID set (501219355 legacy fallback)
        try {
            if (baseSetMethod != null) {
                int ret = (int) baseSetMethod.invoke(acDevice, acDevType, 501219355, mode);
                Log.d(TAG, "Direct single set(501219355) returned " + ret);
                return ret == 0;
            }
        } catch (Exception e) {
            Log.w(TAG, "Direct single set failed: " + e.getMessage());
        }

        return false;
    }

    /**
     * Checks if the vehicle air conditioner power is currently ON.
     * @return 1 if ON, 0 if OFF, -1 if unavailable
     */
    public int getAirConditionerPowerState() {
        if (!ensureAcDevice()) return -1;
        try {
            Method m = acDevice.getClass().getMethod("getAcStartState");
            Object res = m.invoke(acDevice);
            return res instanceof Integer ? (Integer) res : -1;
        } catch (Exception e) {
            return -1;
        }
    }

    /**
     * Reads the current vehicle speed in km/h from BYDAutoSpeedDevice.
     * @return speed in km/h, or -1.0 if not available.
     */
    public double getVehicleSpeedKmh() {
        if (speedDevice == null) return -1.0;
        try {
            Method m = speedDevice.getClass().getMethod("getCurrentSpeed");
            Object res = m.invoke(speedDevice);
            if (res instanceof Number) {
                return ((Number) res).doubleValue();
            }
        } catch (Exception ignored) {}
        return -1.0;
    }

    private boolean ensureAcDevice() {
        if (acDevice != null) return true;
        return initReflectionSync();
    }

    // --- Windows & Sunroof Control & State Reading ---

    /**
     * Reads the raw window open percent for windows 1-4, or raw moonroof percent for area 5.
     */
    public int getRawWindowOpenPercent(int area) {
        if (bodyworkDevice == null) return -1;
        try {
            Method m = bodyworkDevice.getClass().getMethod("getWindowOpenPercent", int.class);
            Object res = m.invoke(bodyworkDevice, area);
            if (res instanceof Number) {
                int pct = ((Number) res).intValue();
                Log.d(TAG, "getRawWindowOpenPercent(area=" + area + ") -> " + pct + "%");
                return pct;
            }
        } catch (Exception e) {
            Log.w(TAG, "Failed to getRawWindowOpenPercent for area " + area + ": " + e.getMessage());
        }
        return -1;
    }

    /**
     * Reads the discrete sunroof position code from BYDAutoBodyworkDevice.
     * Constants: 1: FULL_OPEN, 2: FULL_CLOSE, 3: HALF_OPEN, 4: STOP, 5: TILTUP, 6: COMFORTABLE
     */
    public int getSunroofPosition() {
        if (bodyworkDevice == null) return -1;
        try {
            Method m = bodyworkDevice.getClass().getMethod("getSunroofPosition");
            Object res = m.invoke(bodyworkDevice);
            if (res instanceof Number) {
                int pos = ((Number) res).intValue();
                Log.d(TAG, "getSunroofPosition() -> " + pos);
                return pos;
            }
        } catch (Exception ignored) {}
        return -1;
    }

    /**
     * Reads the binary sunroof state code from BYDAutoBodyworkDevice.
     * 1: Open, 0/2: Closed / Invalid
     */
    public int getSunroofState() {
        if (bodyworkDevice == null) return -1;
        try {
            Method m = bodyworkDevice.getClass().getMethod("getSunroofState");
            Object res = m.invoke(bodyworkDevice);
            if (res instanceof Number) {
                int state = ((Number) res).intValue();
                Log.d(TAG, "getSunroofState() -> " + state);
                return state;
            }
        } catch (Exception ignored) {}
        return -1;
    }

    /**
     * Accurately determines if the sunroof is open or tilted, returning estimated open percentage.
     * Overcomes vehicle firmware limitations where getWindowOpenPercent(5) returns 0 even when tilted/open.
     * @return 0 if completely closed, 10-100 if open or tilted, or -1 if unavailable.
     */
    public int getSunroofOpenPercent() {
        // 1. Check getRawWindowOpenPercent(5)
        int rawPct = getRawWindowOpenPercent(5);
        if (rawPct > 0 && rawPct <= 100) {
            Log.i(TAG, "Sunroof open percent measured via getWindowOpenPercent(5): " + rawPct + "%");
            return rawPct;
        }

        // 2. Check getSunroofPosition()
        // Constants: 1: FULL_OPEN, 2: FULL_CLOSE, 3: HALF_OPEN, 4: STOP, 5: TILTUP, 6: COMFORTABLE
        int pos = getSunroofPosition();
        if (pos == 2) {
            Log.d(TAG, "Sunroof is confirmed FULL_CLOSE (position=2).");
            return 0; // Completely closed
        }
        if (pos == 1) {
            Log.i(TAG, "Sunroof is confirmed FULL_OPEN (position=1).");
            return 100;
        }
        if (pos == 3) {
            Log.i(TAG, "Sunroof is confirmed HALF_OPEN (position=3).");
            return 50;
        }
        if (pos == 5) {
            Log.i(TAG, "Sunroof is confirmed TILTUP / ventilation (position=5).");
            return 15;
        }
        if (pos == 6) {
            Log.i(TAG, "Sunroof is confirmed COMFORTABLE (position=6).");
            return 80;
        }
        if (pos > 0) {
            Log.i(TAG, "Sunroof position is non-zero (position=" + pos + "), treating as open.");
            return 50;
        }

        // 3. Check getSunroofState()
        int state = getSunroofState();
        if (state == 1) {
            Log.i(TAG, "Sunroof is open according to getSunroofState() == 1.");
            return 100;
        }

        return 0;
    }

    /**
     * Reads the current open percentage (0 - 100) of a specific window.
     * @param area 1: Left Front, 2: Right Front, 3: Left Rear, 4: Right Rear, 5: Sunroof
     * @return percentage [0, 100], or -1 if unavailable.
     */
    public int getWindowOpenPercent(int area) {
        if (area == 5) {
            return getSunroofOpenPercent();
        }
        return getRawWindowOpenPercent(area);
    }

    /**
     * Returns a map of all currently open windows and their percentages.
     * Only windows with percent > 0 are included.
     */
    public java.util.Map<Integer, Integer> getOpenWindows() {
        java.util.Map<Integer, Integer> openMap = new java.util.HashMap<>();
        int[] areas = {1, 2, 3, 4, 5}; // LF, RF, LR, RR, Sunroof
        for (int area : areas) {
            int pct = getWindowOpenPercent(area);
            if (pct > 0 && pct <= 100) {
                openMap.put(area, pct);
            }
        }
        return openMap;
    }

    /**
     * Closes the specified windows.
     * @param areas Set of window IDs (1:LF, 2:RF, 3:LR, 4:RR, 5:Sunroof)
     * @param initialPercentages Map of initial open percentages before closing (can be null)
     * @param safeClose If true, executes 3-stage safe sequence:
     *                  Stage 1: Closes 10% more from current position (notice movement).
     *                  Stage 2: Pauses 3s for passenger awareness.
     *                  Stage 3: Closes until 10% remains (90% closed, pinch prevention).
     *                  Stage 4: Pauses 3s for pinch safety.
     *                  Stage 5: Completely closes all windows (100% closed).
     */
    public void closeWindows(java.util.Set<Integer> areas, java.util.Map<Integer, Integer> initialPercentages, boolean safeClose) {
        if (areas == null || areas.isEmpty()) return;

        executor.submit(() -> {
            try {
                if (safeClose) {
                    boolean hasLargeOpenWindow = false;

                    // --- Stage 1: Notice Movement ---
                    // If window is barely open (<=15%, e.g. 10% gap):
                    //   Option C: Open +5% more (e.g. 10% -> 15%) as an active physical notice signal, pause 3s, then full close!
                    // If window is largely open (>15%):
                    //   Close 10% more from current position (e.g. 50% -> 40%), pause 3s, then go to 10% gap stage!
                    Log.i(TAG, "Safe Close Stage 1: Initiating notice movement: " + areas);
                    for (int w : areas) {
                        int curPct = 100;
                        if (initialPercentages != null && initialPercentages.containsKey(w)) {
                            curPct = initialPercentages.get(w);
                        } else {
                            int measured = getWindowOpenPercent(w);
                            if (measured > 0) curPct = measured;
                        }

                        if (curPct > 15) {
                            hasLargeOpenWindow = true;
                            // Close 10% more (e.g. 80% open -> 70% open)
                            int target = Math.max(10, curPct - 10);
                            Log.d(TAG, "Window area " + w + " (" + curPct + "%): Closing 10% more -> target " + target + "%");
                            moveWindowToTargetPercent(w, target);
                        } else {
                            // Option C: Open +5% more (e.g. 10% -> 15%) so motor moves and alerts passenger
                            int target = Math.min(25, curPct + 5);
                            Log.d(TAG, "Window area " + w + " (" + curPct + "% <= 15%): Opening +5% more -> target " + target + "%");
                            moveWindowToTargetPercent(w, target);
                        }
                        Thread.sleep(150);
                    }

                    Log.i(TAG, "Safe Close Stage 1 complete. Pausing 3 seconds for awareness delay...");
                    Thread.sleep(3000L); // 3 seconds awareness delay

                    // --- Stage 2: Close until 10% remains (Pinch Prevention) ---
                    // Only executed if there were windows open > 15%
                    if (hasLargeOpenWindow) {
                        Log.i(TAG, "Safe Close Stage 2: Closing until only 10% gap remains (Pinch Prevention): " + areas);
                        for (int w : areas) {
                            if (w >= 1 && w <= 4) {
                                callBodyworkMethod("setBodyWindowCtrlState",
                                        new Class<?>[]{int.class, int.class}, w, 5); // 5: 10% ventilation gap mode
                                int fid = getWindowTargetFeatureId(w);
                                if (fid != 0 && baseSetMethod != null && bodyworkDevice != null) {
                                    try {
                                        baseSetMethod.invoke(bodyworkDevice, bodyworkDevType, fid, 10);
                                    } catch (Exception ignored) {}
                                }
                                Thread.sleep(150);
                            } else if (w == 5) {
                                callBodyworkMethod("setMoonRoofState", new Class<?>[]{int.class}, 253); // 253: MOONROOF_BREATH (tilt mode)
                                callBodyworkMethod("voiceCtlMoonRoof", new Class<?>[]{int.class}, 2); // 2: SUNROOF_TILTUP
                                Thread.sleep(150);
                            }
                        }

                        Log.i(TAG, "Safe Close Stage 2 complete. Pausing 3 seconds at 10% safety gap...");
                        Thread.sleep(3000L); // 3 seconds pinch prevention delay
                    }

                    Log.i(TAG, "Safe Close Final Stage: Completely closing all windows: " + areas);
                } else {
                    Log.i(TAG, "Direct Window Close: Closing open windows immediately: " + areas);
                }

                // --- Final Complete Close (0% open / 100% closed) ---
                for (int w : areas) {
                    if (w >= 1 && w <= 4) {
                        int lf = (w == 1) ? 2 : 0;
                        int rf = (w == 2) ? 2 : 0;
                        int lr = (w == 3) ? 2 : 0;
                        int rr = (w == 4) ? 2 : 0;
                        callBodyworkMethod("setAllWindowState",
                                new Class<?>[]{int.class, int.class, int.class, int.class},
                                lf, rf, lr, rr);
                        Thread.sleep(150);
                    } else if (w == 5) {
                        closeSunroofDirect();
                        Thread.sleep(150);
                    }
                }
            } catch (Exception e) {
                Log.e(TAG, "Failed to close windows in safe sequence", e);
            }
        });
    }

    private void moveWindowToTargetPercent(int area, int percent) {
        if (area >= 1 && area <= 4) {
            int fid = getWindowTargetFeatureId(area);
            if (fid != 0 && baseSetMethod != null && bodyworkDevice != null) {
                try {
                    baseSetMethod.invoke(bodyworkDevice, bodyworkDevType, fid, percent);
                    return;
                } catch (Exception ignored) {}
            }
            if (percent <= 20) {
                callBodyworkMethod("setBodyWindowCtrlState", new Class<?>[]{int.class, int.class}, area, 5); // 10%
            } else if (percent <= 60) {
                callBodyworkMethod("setBodyWindowCtrlState", new Class<?>[]{int.class, int.class}, area, 4); // 50%
            }
        } else if (area == 5) {
            if (percent <= 0) {
                closeSunroofDirect();
            } else if (percent <= 15) {
                callBodyworkMethod("setMoonRoofState", new Class<?>[]{int.class}, 253); // MOONROOF_BREATH
                callBodyworkMethod("voiceCtlMoonRoof", new Class<?>[]{int.class}, 2); // SUNROOF_TILTUP
            } else {
                callBodyworkMethod("setMoonRoofState", new Class<?>[]{int.class}, Math.max(21, percent));
            }
        }
    }

    public void closeWindows(java.util.Set<Integer> areas, boolean safeClose) {
        closeWindows(areas, null, safeClose);
    }

    public void closeWindows(java.util.Set<Integer> areas) {
        closeWindows(areas, null, false);
    }

    /**
     * Restores windows to their previously saved open percentages.
     */
    public void restoreWindows(java.util.Map<Integer, Integer> savedPercentages) {
        if (savedPercentages == null || savedPercentages.isEmpty()) return;

        executor.submit(() -> {
            try {
                Log.i(TAG, "Restoring windows to original percentages: " + savedPercentages);
                for (java.util.Map.Entry<Integer, Integer> entry : savedPercentages.entrySet()) {
                    int area = entry.getKey();
                    int percent = entry.getValue();
                    if (percent <= 0) continue;

                    if (area >= 1 && area <= 4) {
                        if (percent >= 90) {
                            int lf = (area == 1) ? 1 : 0;
                            int rf = (area == 2) ? 1 : 0;
                            int lr = (area == 3) ? 1 : 0;
                            int rr = (area == 4) ? 1 : 0;
                            callBodyworkMethod("setAllWindowState",
                                    new Class<?>[]{int.class, int.class, int.class, int.class},
                                    lf, rf, lr, rr);
                        } else if (percent >= 40 && percent <= 60) {
                            callBodyworkMethod("setBodyWindowCtrlState",
                                    new Class<?>[]{int.class, int.class}, area, 4); // 50% half open
                        } else if (percent <= 20) {
                            callBodyworkMethod("setBodyWindowCtrlState",
                                    new Class<?>[]{int.class, int.class}, area, 5); // 10% ventilation
                        } else {
                            int fid = getWindowTargetFeatureId(area);
                            if (fid != 0 && baseSetMethod != null && bodyworkDevice != null) {
                                baseSetMethod.invoke(bodyworkDevice, bodyworkDevType, fid, percent);
                            } else {
                                callBodyworkMethod("setBodyWindowCtrlState",
                                        new Class<?>[]{int.class, int.class}, area, percent < 40 ? 5 : 4);
                            }
                        }
                        Thread.sleep(200);
                    } else if (area == 5) {
                        // Sunroof restoration (supports tilt or 21-100% sliding open)
                        Log.i(TAG, "Restoring sunroof to previous state: " + percent + "%");
                        if (percent <= 20) {
                            callBodyworkMethod("setMoonRoofState", new Class<?>[]{int.class}, 253); // 253: MOONROOF_BREATH
                            callBodyworkMethod("voiceCtlMoonRoof", new Class<?>[]{int.class}, 2); // 2: SUNROOF_TILTUP
                        } else {
                            int targetState = Math.max(21, Math.min(100, percent));
                            callBodyworkMethod("setMoonRoofState", new Class<?>[]{int.class}, targetState);
                            callBodyworkMethod("voiceCtlMoonRoof", new Class<?>[]{int.class}, 3); // 3: SUNROOF_OPEN
                        }
                        Thread.sleep(200);
                    }
                }
            } catch (Exception e) {
                Log.e(TAG, "Failed to restore windows", e);
            }
        });
    }

    /**
     * Executes multi-backup close commands specifically for the BYD sunroof / moonroof.
     */
    public void closeSunroofDirect() {
        Log.i(TAG, "Executing multi-backup close for sunroof...");

        // 1. Priority 1: Official BYD standard setMoonRoofState(0) [MOONROOF_CLOSED = 0]
        try {
            Method m = bodyworkDevice.getClass().getMethod("setMoonRoofState", int.class);
            Object ret = m.invoke(bodyworkDevice, 0);
            Log.d(TAG, "setMoonRoofState(0) returned " + ret);
        } catch (Exception e) {
            Log.w(TAG, "setMoonRoofState(0) invocation failed: " + e.getMessage());
        }

        // 2. Priority 2: voiceCtlMoonRoof(4) [SUNROOF_CLOSE = 4]
        try {
            Method m = bodyworkDevice.getClass().getMethod("voiceCtlMoonRoof", int.class);
            Object ret = m.invoke(bodyworkDevice, 4);
            Log.d(TAG, "voiceCtlMoonRoof(4) returned " + ret);
        } catch (Exception e) {
            Log.w(TAG, "voiceCtlMoonRoof(4) invocation failed: " + e.getMessage());
        }

        // 3. Priority 3: Direct Feature ID transmission via baseSetMethod
        if (baseSetMethod != null && bodyworkDevice != null) {
            try {
                // BODYWORK_MOON_ROOF_OPEN_PERCENT_SET: 1330642976 (0x4F500020)
                int fidPercent = getFeatureIdSafely("BODYWORK_MOON_ROOF_OPEN_PERCENT_SET", 1330642976);
                baseSetMethod.invoke(bodyworkDevice, bodyworkDevType, fidPercent, 0);
            } catch (Exception ignored) {}

            try {
                // BODYWORK_MOON_ROOF_CTL_SET: 1125122056 (0x43100008)
                int fidCtl = getFeatureIdSafely("BODYWORK_MOON_ROOF_CTL_SET", 1125122056);
                baseSetMethod.invoke(bodyworkDevice, bodyworkDevType, fidCtl, 4);
            } catch (Exception ignored) {}
        }
    }

    private int getFeatureIdSafely(String fieldName, int defaultId) {
        if (featureIdsClass != null) {
            try {
                return featureIdsClass.getField(fieldName).getInt(null);
            } catch (Exception ignored) {}
        }
        return defaultId;
    }

    private int getWindowTargetFeatureId(int area) {
        if (featureIdsClass == null) return 0;
        try {
            switch (area) {
                case 1: return featureIdsClass.getField("BODYWORK_LF_WINDOW_TARGET_POSITION_SET").getInt(null);
                case 2: return featureIdsClass.getField("BODYWORK_RF_WINDOW_TARGET_POSITION_SET").getInt(null);
                case 3: return featureIdsClass.getField("BODYWORK_LR_WINDOW_TARGET_POSITION_SET").getInt(null);
                case 4: return featureIdsClass.getField("BODYWORK_RR_WINDOW_TARGET_POSITION_SET").getInt(null);
            }
        } catch (Exception ignored) {}
        return 0;
    }

    private void callBodyworkMethod(String name, Class<?>[] paramTypes, Object... args) {
        if (bodyworkDevice == null) return;
        try {
            Method m = bodyworkDevice.getClass().getMethod(name, paramTypes);
            m.invoke(bodyworkDevice, args);
        } catch (Exception e) {
            Log.w(TAG, "callBodyworkMethod " + name + " failed: " + e.getMessage());
        }
    }

    public void shutdown() {
        executor.shutdown();
    }
}
