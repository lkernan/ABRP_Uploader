package com.leonkernan.abrp_uploader;

import android.content.ComponentName;
import android.content.Context;
import android.content.ServiceConnection;
import android.os.IBinder;
import android.util.Log;

import java.lang.reflect.Method;

/**
 * Connects to Android Automotive's CarPropertyManager via reflection.
 *
 * Targets AAOS 9 (API 28) where android.car is a platform library not in the
 * compile-time SDK — direct imports are unavailable, so all access is via reflection.
 *
 * Usage:
 * <pre>
 *     CarPropertyAdapter adapter = new CarPropertyAdapter(listener);
 *     adapter.connect(context);
 *
 *     // After onConnected():
 *     float speed = adapter.getFloatProperty(VehiclePropertyIds.PERF_VEHICLE_SPEED, 0);
 * </pre>
 *
 * Property constants confirmed on this vehicle:
 *   OUTSIDE_TEMP           = 0x15602511, area 117  — ambient °C (matches HMI display)
 *   PERF_VEHICLE_SPEED     = 0x11600207, area 0    — speed m/s (standard AAOS)
 */
public class CarPropertyAdapter {

    private static final String TAG = "CarPropertyAdapter";

    public interface Listener {
        void onConnected();
        void onDisconnected();
    }

    // Confirmed property IDs for this vehicle (AAOS 9, SAIC platform)
    public static final int PROP_OUTSIDE_TEMP   = 0x15602511; // float °C, area 117
    public static final int PROP_VEHICLE_SPEED  = 0x11600207; // float km/h, area 0
    public static final int PROP_GEAR_SELECTION = 0x11400400; // int (VehicleGear), area 0
    // Vendor EV cluster — matched against the SAIC stack's BatteryPercent / CurrentRange:
    public static final int PROP_EV_BATTERY_PCT = 0x2160F404; // float % (e.g. 90.5)
    public static final int PROP_EV_RANGE_KM    = 0x2140F41C; // int km
    public static final int PROP_AREA_GLOBAL    = 0;
    public static final int PROP_AREA_HVAC      = 117;

    // VehicleGear bitfield values (standard AAOS) — these are flags, not ordinals.
    public static final int GEAR_UNKNOWN  = 0x0000;
    public static final int GEAR_NEUTRAL  = 0x0001;
    public static final int GEAR_REVERSE  = 0x0002;
    public static final int GEAR_PARK     = 0x0004;
    public static final int GEAR_DRIVE    = 0x0008;

    private final Listener listener;
    private Context        appContext;
    private Object         car;       // android.car.Car
    private Object         cpm;       // android.car.hardware.property.CarPropertyManager
    private Class<?>       carClass;
    private Class<?>       cpmClass;

    public CarPropertyAdapter(Listener listener) {
        this.listener = listener;
    }

    public void connect(Context context) {
        appContext = context.getApplicationContext();

        try {
            carClass = Class.forName("android.car.Car");
            cpmClass = Class.forName("android.car.hardware.property.CarPropertyManager");
        } catch (ClassNotFoundException e) {
            Log.e(TAG, "android.car not available — not running on AAOS", e);
            return;
        }

        ServiceConnection sc = new ServiceConnection() {
            @Override
            public void onServiceConnected(ComponentName name, IBinder binder) {
                try {
                    String svcName = (String) carClass.getField("PROPERTY_SERVICE").get(null);
                    Method getManager = carClass.getMethod("getCarManager", String.class);
                    cpm = getManager.invoke(car, svcName);
                    if (cpm != null) {
                        Log.i(TAG, "CarPropertyManager ready");
                        listener.onConnected();
                    } else {
                        Log.e(TAG, "getCarManager returned null for: " + svcName);
                    }
                } catch (Exception e) {
                    Log.e(TAG, "Failed to get CarPropertyManager", e);
                }
            }

            @Override
            public void onServiceDisconnected(ComponentName name) {
                cpm = null;
                car = null;
                Log.w(TAG, "Car service disconnected");
                listener.onDisconnected();
            }
        };

        try {
            Method createCar = carClass.getMethod("createCar", Context.class, ServiceConnection.class);
            car = createCar.invoke(null, appContext, sc);
            carClass.getMethod("connect").invoke(car);
            Log.d(TAG, "Connecting to Car service...");
        } catch (Exception e) {
            Log.e(TAG, "Failed to create/connect Car", e);
        }
    }

    public void disconnect() {
        if (car == null) return;
        try {
            carClass.getMethod("disconnect").invoke(car);
        } catch (Exception e) {
            Log.e(TAG, "disconnect error", e);
        }
        car = null;
        cpm = null;
    }

    public boolean isConnected() {
        return cpm != null;
    }

    /**
     * Reads every vendor Float property visible in the car_service dump and logs non-zero values.
     * Use this to identify which vendor property ID maps to a given sensor.
     */
    public void probeVendorFloatProperties() {
        // Full Float-type property ID list from `dumpsys car_service` on this vehicle.
        int[] globalIds = {
            // Standard AAOS
            291504647, 291504901, 291518465, 291518466, 291518467, 291518468,
            // Vendor 0x2160xxxx global
            559945057, 559945059, 559945060, 559945125, 559945126,
            559971102, 559971105,
            559980820, 559980821, 559980822, 559980824, 559980825, 559980826, 559980827,
            559980833, 559980834, 559980835, 559980837, 559980838, 559980839, 559980840,
            559980844, 559980845, 559980846, 559980847, 559980848,
            559980850, 559980851, 559980852, 559980853, 559980854,
            559980857, 559980858, 559980859, 559980860, 559980861,
            559980864, 559980865, 559980866, 559980867, 559980868,
            559980872, 559980873, 559980875, 559980876, 559980877, 559980878,
            559980880, 559980881, 559980882, 559980883, 559980891,
            559980933, 559980951, 559980952, 559980953, 559980954, 559980955,
            559980961, 559980962, 559980963, 559980964, 559980968,
            559980987, 559980988, 559980989, 559980990, 559980991, 559980992, 559980993, 559980994,
            559981004, 559981005, 559981006, 559981007,
            559987029,
            559990304, 559990308,
            560002052, 560002053, 560002054, 560002055, 560002058,
            560002075, 560002077, 560002081,
            560002108, 560002109, 560002114,
        };
        // HVAC / climate properties use zone area ID 117
        int[] hvacIds = {
            358622475, 358622476, 358622481, 358622494, 358622495,
            358622496, 358622497, 358622506, 358622518,
        };

        Log.d(TAG, "probeVendorFloatProperties: scanning " + (globalIds.length + hvacIds.length) + " IDs");
        for (int id : globalIds) {
            float val = getFloatProperty(id, 0);
            if (val != 0f) Log.i(TAG, String.format("  GLOBAL 0x%08X = %.4f", id, val));
        }
        for (int id : hvacIds) {
            float val = getFloatProperty(id, 117);
            if (val != 0f) Log.i(TAG, String.format("  HVAC[117] 0x%08X = %.4f", id, val));
            float val0 = getFloatProperty(id, 0);
            if (val0 != 0f) Log.i(TAG, String.format("  HVAC[0] 0x%08X = %.4f", id, val0));
        }
        Log.d(TAG, "probeVendorFloatProperties: done");
    }

    /**
     * Enumerates every supported property and logs its current value. Used for
     * discovery: match logged values against the dashboard to identify which
     * vendor property ID maps to a given sensor (battery %, range, etc).
     */
    public void logAllProperties() {
        if (cpm == null) { Log.w(TAG, "logAllProperties: not connected"); return; }
        Log.d(TAG, "logAllProperties: starting");
        try {
            java.lang.reflect.Method listMethod = cpmClass.getMethod("getPropertyList");
            java.util.List<?> configs = (java.util.List<?>) listMethod.invoke(cpm);
            Log.d(TAG, "getPropertyList returned: " + (configs == null ? "null" : configs.size() + " entries"));
            if (configs == null || configs.isEmpty()) return;
            Class<?> configClass = Class.forName("android.car.hardware.CarPropertyConfig", true, cpmClass.getClassLoader());
            java.lang.reflect.Method getPropId   = configClass.getMethod("getPropertyId");
            java.lang.reflect.Method getPropType = configClass.getMethod("getPropertyType");
            java.lang.reflect.Method getAreaIds  = configClass.getMethod("getAreaIds");
            for (Object cfg : configs) {
                int      propId = (int)      getPropId.invoke(cfg);
                Class<?> type   = (Class<?>) getPropType.invoke(cfg);
                int[]    areas  = (int[])    getAreaIds.invoke(cfg);
                int      area   = (areas != null && areas.length > 0) ? areas[0] : 0;
                try {
                    if (type == Float.class) {
                        float val = getFloatProperty(propId, area);
                        Log.i(TAG, String.format("FLOAT  0x%08X area=%d = %.4f", propId, area, val));
                    } else if (type == Integer.class) {
                        int val = getIntProperty(propId, area);
                        Log.i(TAG, String.format("INT    0x%08X area=%d = %d", propId, area, val));
                    } else if (type == Boolean.class) {
                        boolean val = getBooleanProperty(propId, area);
                        Log.i(TAG, String.format("BOOL   0x%08X area=%d = %s", propId, area, val));
                    } else {
                        Log.i(TAG, String.format("OTHER  0x%08X area=%d type=%s", propId, area, type));
                    }
                } catch (Throwable t) {
                    Log.w(TAG, String.format("read 0x%08X area=%d failed: %s",
                            propId, area, t.getClass().getSimpleName()));
                }
            }
            Log.d(TAG, "logAllProperties: done");
        } catch (Exception e) {
            Log.e(TAG, "logAllProperties failed", e);
        }
    }

    public interface PropertyCallback {
        void onValue(int propId, int areaId, Object value);
    }

    /**
     * Polls a float property on the given handler at the specified interval.
     * Stops automatically when disconnect() is called.
     * This is the preferred mechanism on AAOS 9 where CarPropertyValue is not
     * accessible via any standard classloader path, making Proxy-based listeners fail.
     */
    public void startFloatPolling(int propId, int areaId, long intervalMs,
                                  android.os.Handler handler, PropertyCallback cb) {
        if (cpm == null) { Log.w(TAG, "startFloatPolling: not connected"); return; }
        Runnable[] r = {null};
        r[0] = () -> {
            if (cpm == null) return; // stopped by disconnect
            float val = getFloatProperty(propId, areaId);
            cb.onValue(propId, areaId, val);
            handler.postDelayed(r[0], intervalMs);
        };
        handler.post(r[0]);
        Log.d(TAG, String.format("started polling 0x%08X every %dms", propId, intervalMs));
    }

    /**
     * Subscribes to updates for any car property (float, int, bool — type-agnostic).
     * The callback receives the raw {@link Object} value; cast to the expected type.
     *
     * AAOS 9 quirks handled here:
     *  - Method is named registerListener (not registerCallback as in later versions).
     *  - The event callback interface and CarPropertyValue class are discovered from
     *    the register method's signature — on this SAIC build CarPropertyValue lives
     *    at android.car.hardware.CarPropertyValue (NOT android.car.hardware.property.*).
     *  - The Proxy is defined with this class's loader so the framework can verify it.
     *
     * @param rateHz  for CONTINUOUS properties, the requested rate in Hz.
     *                For ON_CHANGE properties (e.g. gear), this is ignored — pass 0f.
     */
    public void registerPropertyCallback(int propId, int areaId, float rateHz, PropertyCallback cb) {
        if (cpm == null) { Log.w(TAG, "registerPropertyCallback: not connected"); return; }
        try {
            // Find a 3-arg register* method on CarPropertyManager: (Listener, propId, rateHz).
            java.lang.reflect.Method registerMethod = null;
            for (java.lang.reflect.Method m : cpmClass.getMethods()) {
                String n = m.getName();
                if ((n.startsWith("register") || n.startsWith("Register"))
                        && m.getParameterCount() == 3) {
                    Log.d(TAG, "register candidate: " + m.toGenericString());
                    registerMethod = m;
                    break;
                }
            }
            if (registerMethod == null) {
                Log.e(TAG, "no register method found on CarPropertyManager");
                return;
            }

            Class<?> cbInterface = registerMethod.getParameterTypes()[0];
            Log.d(TAG, "callback interface: " + cbInterface.getName());

            // Discover CarPropertyValue directly from the interface's onChangeEvent
            // parameter type. This sidesteps hardcoding a package path that differs
            // between AAOS builds.
            Class<?> cpvClass = null;
            for (java.lang.reflect.Method m : cbInterface.getMethods()) {
                Log.d(TAG, "  interface method: " + m.toGenericString());
                if ("onChangeEvent".equals(m.getName()) && m.getParameterCount() == 1) {
                    cpvClass = m.getParameterTypes()[0];
                }
            }
            if (cpvClass == null) {
                Log.e(TAG, "no onChangeEvent(CarPropertyValue) method on " + cbInterface.getName());
                return;
            }
            Log.d(TAG, "CarPropertyValue resolved to: " + cpvClass.getName());

            java.lang.reflect.Method getPropId = cpvClass.getMethod("getPropertyId");
            java.lang.reflect.Method getAreaId = cpvClass.getMethod("getAreaId");
            java.lang.reflect.Method getValue  = cpvClass.getMethod("getValue");

            // Use the app classloader (delegates to boot for android.car.* classes)
            // so the generated Proxy class can actually be defined.
            ClassLoader proxyLoader = CarPropertyAdapter.class.getClassLoader();

            Object proxy = java.lang.reflect.Proxy.newProxyInstance(
                    proxyLoader,
                    new Class[]{cbInterface},
                    (proxyObj, method, args) -> {
                        String mname = method.getName();
                        if ("onChangeEvent".equals(mname) && args != null && args.length == 1) {
                            int pId = (int) getPropId.invoke(args[0]);
                            int aId = (int) getAreaId.invoke(args[0]);
                            Object val = getValue.invoke(args[0]);
                            cb.onValue(pId, aId, val);
                        } else if ("onErrorEvent".equals(mname)) {
                            Log.e(TAG, "onErrorEvent propId=" + args[0] + " area=" + args[1]);
                        } else if ("equals".equals(mname)) {
                            return proxyObj == args[0];
                        } else if ("hashCode".equals(mname)) {
                            return System.identityHashCode(proxyObj);
                        } else if ("toString".equals(mname)) {
                            return "CarPropertyProxy@" + Integer.toHexString(System.identityHashCode(proxyObj));
                        }
                        return null;
                    });

            registerMethod.invoke(cpm, proxy, propId, rateHz);
            Log.i(TAG, String.format("registered callback for 0x%08X area=%d rate=%.1fHz",
                    propId, areaId, rateHz));
        } catch (Exception e) {
            Log.e(TAG, "registerPropertyCallback failed", e);
        }
    }

    /** Area 0 covers global/non-zoned properties. */
    public int getIntProperty(int propId, int areaId) {
        if (cpm == null) return 0;
        try {
            return (Integer) cpmClass.getMethod("getIntProperty", int.class, int.class)
                    .invoke(cpm, propId, areaId);
        } catch (Exception e) {
            Log.e(TAG, "getIntProperty(" + Integer.toHexString(propId) + ") failed", e);
            return 0;
        }
    }

    public float getFloatProperty(int propId, int areaId) {
        if (cpm == null) return 0f;
        try {
            return (float) cpmClass.getMethod("getFloatProperty", int.class, int.class)
                    .invoke(cpm, propId, areaId);
        } catch (Exception e) {
            Log.e(TAG, "getFloatProperty(" + Integer.toHexString(propId) + ") failed", e);
            return 0f;
        }
    }

    public boolean getBooleanProperty(int propId, int areaId) {
        if (cpm == null) return false;
        try {
            return (boolean) cpmClass.getMethod("getBooleanProperty", int.class, int.class)
                    .invoke(cpm, propId, areaId);
        } catch (Exception e) {
            Log.e(TAG, "getBooleanProperty(" + Integer.toHexString(propId) + ") failed", e);
            return false;
        }
    }
}
