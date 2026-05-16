package com.leonkernan.abrp_uploader;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.Binder;
import android.os.IBinder;
import android.os.Parcel;
import android.provider.Settings;
import android.util.Log;

import java.util.HashMap;

/**
 * Pure-Java equivalent of SaicCarAdapter.bas (B4A v1.3).
 *
 * Connects to the SAIC Motor CarAdapterService via Android IPC (IBinder.transact).
 * Fires callbacks through the {@link Listener} interface instead of B4A events.
 *
 * Tested on: AUTUS SAIC Android head unit (MG4 / ZS EV platform).
 *
 * Usage:
 * <pre>
 *     SaicCarAdapter adapter = new SaicCarAdapter(listener);
 *     adapter.connect(context);
 *
 *     // After onConnected(true):
 *     adapter.registerAllListeners();
 *     float speed = adapter.getSpeed();
 * </pre>
 */
public class SaicCarAdapter {

    private static final String TAG = "SaicCarAdapter";

    // -------------------------------------------------------------------------
    // IPC plumbing
    // -------------------------------------------------------------------------
    private static final String SERVICE_PACKAGE   = "com.saicmotor.caradapter";
    private static final String SERVICE_CLASS     = "com.saicmotor.caradapter.service.CarAdapterService";
    private static final String DESCRIPTOR        = "com.saicmotor.carapi.ICarAdapterService";
    private static final int    TRANSACTION_queryClient = 1;

    // -------------------------------------------------------------------------
    // Helper codes (from LocalBinder.queryClient) — all verified correct
    // -------------------------------------------------------------------------
    public static final int HELPER_GENERAL         = 1;
    public static final int HELPER_CONFIG          = 2;
    public static final int HELPER_EVS             = 3;
    public static final int HELPER_EV              = 4;
    public static final int HELPER_IPK             = 5;
    public static final int HELPER_EOL             = 6;
    public static final int HELPER_HVAC            = 7;
    public static final int HELPER_VEHICLE_SETTING = 8;
    public static final int HELPER_FEATURE         = 9;
    public static final int HELPER_AUDIO           = 10;
    public static final int HELPER_STATE           = 11;
    public static final int HELPER_TIME            = 12;
    public static final int HELPER_LAMP            = 13;
    public static final int HELPER_LOCK            = 14;
    public static final int HELPER_COMFORT         = 15;

    // -------------------------------------------------------------------------
    // Listener interface  (replaces B4A ba.raiseEventFromDifferentThread)
    // -------------------------------------------------------------------------

    /**
     * All callbacks are delivered on whichever Binder thread the car service
     * uses — post to the main thread before touching UI.
     *
     * The {@code value} argument is always one of:
     * <ul>
     *   <li>{@link Integer}</li>
     *   <li>{@link Float}</li>
     *   <li>{@link Boolean}</li>
     *   <li>{@code int[]} — for two-parameter callbacks (SeatHeat, Volume, etc.)</li>
     *   <li>{@code float[]} — for mixed two-parameter callbacks (TirePressureFloat)</li>
     * </ul>
     */
    public interface Listener {
        /** Called after bindService completes (success=true) or fails (success=false). */
        void onConnected(boolean success);

        /** Called when the car service disconnects unexpectedly. */
        void onDisconnected();

        /** Push data from HVAC helper (key examples: "HvacPower", "DriverTemp"). */
        void onHvac(String key, Object value);

        /** Push data from EV/Battery helper (key examples: "BatteryPercent", "CurrentRange"). */
        void onEv(String key, Object value);

        /** Push data from General helper (key examples: "Speed", "IgnitionState"). */
        void onGeneral(String key, Object value);

        /** Push data from Car State helper (key examples: "GearState", "ServiceReady"). */
        void onState(String key, Object value);

        /** Push data from Audio helper (key examples: "Volume", "Mute"). */
        void onAudio(String key, Object value);

        /** Push data from Vehicle Setting helper (key examples: "DrivingMode", "TirePressure"). */
        void onVehicleSetting(String key, Object value);
    }

    // -------------------------------------------------------------------------
    // Fields
    // -------------------------------------------------------------------------
    private final Listener                 listener;
    private       Context                  appContext;
    private       IBinder                  mServiceBinder;
    private       ServiceConnection        mConnection;
    private final HashMap<Integer, IBinder> helperCache     = new HashMap<>();
    private final HashMap<Integer, String>  descriptorCache = new HashMap<>();

    // Cached callback binders
    private IBinder mHvacCallback;
    private IBinder mEvCallback;
    private IBinder mGeneralCallback;
    private IBinder mStateCallback;
    private IBinder mAudioCallback;
    private IBinder mVsCallback;

    // -------------------------------------------------------------------------
    // Constructor
    // -------------------------------------------------------------------------
    public SaicCarAdapter(Listener listener) {
        this.listener = listener;
    }

    // =========================================================================
    // CONNECTION
    // =========================================================================

    /**
     * Bind to the SAIC CarAdapterService.
     * {@link Listener#onConnected} is called when the result is known.
     */
    public void connect(Context context) {
        appContext = context.getApplicationContext();

        Intent intent = new Intent();
        intent.setComponent(new ComponentName(SERVICE_PACKAGE, SERVICE_CLASS));

        mConnection = new ServiceConnection() {
            @Override
            public void onServiceConnected(ComponentName name, IBinder service) {
                mServiceBinder = service;
                Log.d(TAG, "CarAdapterService connected");
                if (listener != null) listener.onConnected(true);
            }

            @Override
            public void onServiceDisconnected(ComponentName name) {
                mServiceBinder = null;
                helperCache.clear();
                Log.d(TAG, "CarAdapterService disconnected");
                if (listener != null) listener.onDisconnected();
            }
        };

        try {
            boolean bound = appContext.bindService(intent, mConnection, Context.BIND_AUTO_CREATE);
            Log.d(TAG, "bindService returned: " + bound);
            if (!bound) {
                if (listener != null) listener.onConnected(false);
            }
        } catch (Exception e) {
            Log.e(TAG, "Bind error: " + e.getMessage());
            if (listener != null) listener.onConnected(false);
        }
    }

    /** Unbind from the service and release all resources. */
    public void disconnect() {
        if (mConnection != null && appContext != null) {
            try { appContext.unbindService(mConnection); } catch (Exception ignored) {}
        }
        mServiceBinder   = null;
        mConnection      = null;
        mHvacCallback    = null;
        mEvCallback      = null;
        mGeneralCallback = null;
        mStateCallback   = null;
        mAudioCallback   = null;
        mVsCallback      = null;
        helperCache.clear();
        descriptorCache.clear();
    }

    /** Returns true if the service binder is alive. */
    public boolean isConnected() {
        return mServiceBinder != null && mServiceBinder.isBinderAlive();
    }

    // =========================================================================
    // CALLBACK REGISTRATION
    // =========================================================================

    public void registerHvacListener() {
        if (mHvacCallback == null) mHvacCallback = createHvacCallbackBinder();
        doRegister(HELPER_HVAC, mHvacCallback);
    }

    public void unregisterHvacListener() {
        if (mHvacCallback != null) doUnregister(HELPER_HVAC, mHvacCallback);
    }

    public void registerEvListener() {
        if (mEvCallback == null) mEvCallback = createEvCallbackBinder();
        doRegister(HELPER_EV, mEvCallback);
    }

    public void unregisterEvListener() {
        if (mEvCallback != null) doUnregister(HELPER_EV, mEvCallback);
    }

    public void registerGeneralListener() {
        if (mGeneralCallback == null) mGeneralCallback = createGeneralCallbackBinder();
        doRegister(HELPER_GENERAL, mGeneralCallback);
    }

    public void unregisterGeneralListener() {
        if (mGeneralCallback != null) doUnregister(HELPER_GENERAL, mGeneralCallback);
    }

    public void registerStateListener() {
        if (mStateCallback == null) mStateCallback = createStateCallbackBinder();
        doRegister(HELPER_STATE, mStateCallback);
    }

    public void unregisterStateListener() {
        if (mStateCallback != null) doUnregister(HELPER_STATE, mStateCallback);
    }

    public void registerAudioListener() {
        if (mAudioCallback == null) mAudioCallback = createAudioCallbackBinder();
        doRegister(HELPER_AUDIO, mAudioCallback);
    }

    public void unregisterAudioListener() {
        if (mAudioCallback != null) doUnregister(HELPER_AUDIO, mAudioCallback);
    }

    public void registerVsListener() {
        if (mVsCallback == null) mVsCallback = createVsCallbackBinder();
        doRegister(HELPER_VEHICLE_SETTING, mVsCallback);
    }

    public void unregisterVsListener() {
        if (mVsCallback != null) doUnregister(HELPER_VEHICLE_SETTING, mVsCallback);
    }

    /** Convenience: register all six listeners at once. */
    public void registerAllListeners() {
        registerHvacListener();
        registerEvListener();
        registerGeneralListener();
        registerStateListener();
        registerAudioListener();
        registerVsListener();
    }

    /** Convenience: unregister all six listeners at once. */
    public void unregisterAllListeners() {
        unregisterHvacListener();
        unregisterEvListener();
        unregisterGeneralListener();
        unregisterStateListener();
        unregisterAudioListener();
        unregisterVsListener();
    }

    // =========================================================================
    // GENERAL VEHICLE INFO  (Helper 1)
    // =========================================================================
    private static final int GEN_GET_POWER_STATUS   = 3;
    private static final int GEN_SET_DISPLAY_STATE  = 4;
    private static final int GEN_GET_BRIGHTNESS_VALUE= 5;
    private static final int GEN_SET_SCREEN_BRIGHTNESS=6;
    private static final int GEN_GET_BRIGHTNESS_MODE = 7;
    private static final int GEN_GET_VER_HW         = 9;
    private static final int GEN_GET_VER_MPU        = 10;
    private static final int GEN_GET_VER_MCU        = 11;
    private static final int GEN_GET_VIN            = 12;
    private static final int GEN_GET_SN             = 13;
    private static final int GEN_GET_PART_NO        = 14;
    private static final int GEN_GET_PDSN_NO        = 15;
    private static final int GEN_GET_SCREEN_VER     = 16;
    private static final int GEN_GET_SPEED          = 17;
    private static final int GEN_VEHICLE_RESET      = 18;
    private static final int GEN_GET_REVERSE        = 19;
    private static final int GEN_REBOOT             = 20;
    private static final int GEN_GET_VER_DSP        = 21;
    private static final int GEN_GET_IGNITION       = 22;
    private static final int GEN_GET_MILEAGE        = 23;
    private static final int GEN_GET_VER_MCU_BYTE   = 27;

    public float  getSpeed()           { return readFloat(HELPER_GENERAL, GEN_GET_SPEED); }
    public int    getIgnitionState()   { return readInt(HELPER_GENERAL, GEN_GET_IGNITION); }
    public int    getPowerStatus()     { return readInt(HELPER_GENERAL, GEN_GET_POWER_STATUS); }
    public boolean isInReverse()       { return readBool(HELPER_GENERAL, GEN_GET_REVERSE); }
    public int    getTotalMileage()    { return readInt(HELPER_GENERAL, GEN_GET_MILEAGE); }
    public String getVIN()             { return readString(HELPER_GENERAL, GEN_GET_VIN); }
    public String getSerialNumber()    { return readString(HELPER_GENERAL, GEN_GET_SN); }
    public String getPartNumber()      { return readString(HELPER_GENERAL, GEN_GET_PART_NO); }
    public String getPDSNNumber()      { return readString(HELPER_GENERAL, GEN_GET_PDSN_NO); }
    public String getHardwareVersion() { return readString(HELPER_GENERAL, GEN_GET_VER_HW); }
    public String getMPUVersion()      { return readString(HELPER_GENERAL, GEN_GET_VER_MPU); }
    public String getMCUVersion()      { return readString(HELPER_GENERAL, GEN_GET_VER_MCU); }
    public String getMCUVersionBytes() { return readString(HELPER_GENERAL, GEN_GET_VER_MCU_BYTE); }
    public String getDSPVersion()      { return readString(HELPER_GENERAL, GEN_GET_VER_DSP); }
    public String getScreenVersion()   { return readString(HELPER_GENERAL, GEN_GET_SCREEN_VER); }
    public void   vehicleReset()       { callVoid(HELPER_GENERAL, GEN_VEHICLE_RESET); }
    public void   reboot()             { callVoid(HELPER_GENERAL, GEN_REBOOT); }

    // =========================================================================
    // SCREEN BRIGHTNESS  (via Android Settings, bypasses CarAdapter)
    // =========================================================================

    /** Returns current screen brightness 0-255. */
    public int getBrightness() {
        try {
            return Settings.System.getInt(
                    appContext.getContentResolver(), "screen_brightness", 128);
        } catch (Exception e) {
            Log.e(TAG, "getBrightness error: " + e.getMessage());
            return 128;
        }
    }

    /** Sets screen brightness 0-255. */
    public void setBrightness(int value) {
        value = Math.max(0, Math.min(255, value));
        try {
            Settings.System.putInt(
                    appContext.getContentResolver(), "screen_brightness", value);
        } catch (Exception e) {
            Log.e(TAG, "setBrightness error: " + e.getMessage());
        }
    }

    /** Returns true if auto-brightness is enabled (mode=1). */
    public boolean isAutoBrightness() {
        try {
            return Settings.System.getInt(
                    appContext.getContentResolver(), "screen_brightness_mode", 0) == 1;
        } catch (Exception e) {
            return false;
        }
    }

    /** Enables or disables auto-brightness. */
    public void setAutoBrightness(boolean auto) {
        try {
            Settings.System.putInt(appContext.getContentResolver(),
                    "screen_brightness_mode", auto ? 1 : 0);
        } catch (Exception e) {
            Log.e(TAG, "setAutoBrightness error: " + e.getMessage());
        }
    }

    // =========================================================================
    // SCREEN ON/OFF  (SAIC-specific Settings key)
    // =========================================================================

    public boolean isScreenOff() {
        try {
            return Settings.System.getInt(
                    appContext.getContentResolver(), "screen_off_state", 0) == 1;
        } catch (Exception e) {
            return false;
        }
    }

    public void setScreenOff(boolean off) {
        try {
            Settings.System.putInt(appContext.getContentResolver(),
                    "screen_off_state", off ? 1 : 0);
        } catch (Exception e) {
            Log.e(TAG, "setScreenOff error: " + e.getMessage());
        }
    }

    // =========================================================================
    // CAR STATE  (Helper 11)
    // =========================================================================
    private static final int STATE_IS_READY  = 3;
    private static final int STATE_GET_ECALL = 4;
    private static final int STATE_GET_GEAR  = 5;
    private static final int STATE_GET_VCU   = 7;

    public int     getGearState()    { return readInt(HELPER_STATE, STATE_GET_GEAR); }
    public int     getEcallState()   { return readInt(HELPER_STATE, STATE_GET_ECALL); }
    public boolean isCarStateReady() { return readBool(HELPER_STATE, STATE_IS_READY); }
    public int     getVcuState()     { return readInt(HELPER_STATE, STATE_GET_VCU); }

    /** Converts gear integer (0=P, 1=R, 2=N, 3=D) to a human-readable label. */
    public String getGearText() {
        switch (getGearState()) {
            case 0: return "P";
            case 1: return "R";
            case 2: return "N";
            case 3: return "D";
            default: return "?";
        }
    }

    // =========================================================================
    // EV / BATTERY  (Helper 4)
    // =========================================================================
    private static final int EV_GET_BATTERY_PERCENT  = 3;
    private static final int EV_GET_CHARGE_STATUS    = 4;
    private static final int EV_CHARGE_CTRL          = 5;
    private static final int EV_GET_CHARGING_TIME    = 6;
    private static final int EV_GET_DISCHARGING_TIME = 7;
    private static final int EV_DISCHARGE_CTRL       = 8;
    private static final int EV_SET_DISTANCE_UNIT    = 10;
    private static final int EV_GET_DISTANCE_UNIT    = 11;
    private static final int EV_GET_RANGE            = 12;
    private static final int EV_SET_BATTERY_HEAT     = 13;
    private static final int EV_GET_BATTERY_HEAT     = 14;
    private static final int EV_GET_CHARGE_STOP_REASON = 15;
    private static final int EV_GET_ELEC_MGMT        = 18;
    private static final int EV_GET_WIRELESS_CHARGER = 19;

    public int  getBatteryPercentage()          { return readInt(HELPER_EV, EV_GET_BATTERY_PERCENT); }
    public int  getChargeStatus()               { return readInt(HELPER_EV, EV_GET_CHARGE_STATUS); }
    public int  getCurrentRange()               { return readInt(HELPER_EV, EV_GET_RANGE); }
    public int  getChargingTimeRemaining()      { return readInt(HELPER_EV, EV_GET_CHARGING_TIME); }
    public int  getDischargingTimeRemaining()   { return readInt(HELPER_EV, EV_GET_DISCHARGING_TIME); }
    public int  getDistanceUnit()               { return readInt(HELPER_EV, EV_GET_DISTANCE_UNIT); }
    public void setDistanceUnit(int unit)       { writeInt(HELPER_EV, EV_SET_DISTANCE_UNIT, unit); }
    public int  getBatteryHeatStatus()          { return readInt(HELPER_EV, EV_GET_BATTERY_HEAT); }
    public void setBatteryHeatStatus(int s)     { writeInt(HELPER_EV, EV_SET_BATTERY_HEAT, s); }
    public int  getChargeStopReason()           { return readInt(HELPER_EV, EV_GET_CHARGE_STOP_REASON); }
    public int  getElectricManagementStatus()   { return readInt(HELPER_EV, EV_GET_ELEC_MGMT); }
    public int  getWirelessChargerState()       { return readInt(HELPER_EV, EV_GET_WIRELESS_CHARGER); }
    public void chargeControl(int command)      { writeInt(HELPER_EV, EV_CHARGE_CTRL, command); }
    public void dischargeControl(int command)   { writeInt(HELPER_EV, EV_DISCHARGE_CTRL, command); }

    // =========================================================================
    // HVAC – CLIMATE CONTROL  (Helper 7)
    // =========================================================================
    private static final int HVAC_SWITCH_POWER      = 5;
    private static final int HVAC_GET_POWER         = 6;
    private static final int HVAC_SWITCH_AC         = 7;
    private static final int HVAC_GET_AC            = 8;
    private static final int HVAC_SWITCH_AUTO       = 9;
    private static final int HVAC_GET_AUTO          = 10;
    private static final int HVAC_SWITCH_SYNC       = 11;
    private static final int HVAC_GET_SYNC          = 12;
    private static final int HVAC_SWITCH_ECO        = 13;
    private static final int HVAC_GET_ECO           = 14;
    private static final int HVAC_SWITCH_AIR_CIRC   = 15;
    private static final int HVAC_GET_AIR_CIRC      = 16;
    private static final int HVAC_SET_FAN_DIR       = 17;
    private static final int HVAC_GET_FAN_DIR       = 18;
    private static final int HVAC_SET_FAN_SPEED     = 19;
    private static final int HVAC_GET_FAN_SPEED     = 20;
    private static final int HVAC_SET_DRIVER_TEMP   = 21;
    private static final int HVAC_GET_DRIVER_TEMP   = 22;
    private static final int HVAC_SET_PASS_TEMP     = 23;
    private static final int HVAC_GET_PASS_TEMP     = 24;
    private static final int HVAC_GET_OUTSIDE_TEMP  = 25;
    private static final int HVAC_GET_AIR_PURIFY    = 26;
    private static final int HVAC_SWITCH_FRONT_DEFROST = 27;
    private static final int HVAC_GET_FRONT_DEFROST = 28;
    private static final int HVAC_SWITCH_REAR_DEFROST  = 29;
    private static final int HVAC_GET_REAR_DEFROST  = 30;
    private static final int HVAC_SWITCH_SEAT_HEAT  = 31;
    private static final int HVAC_GET_SEAT_HEAT     = 32;
    private static final int HVAC_SWITCH_SEAT_VENT  = 33;
    private static final int HVAC_GET_SEAT_VENT     = 34;
    private static final int HVAC_GET_PM25          = 66;
    private static final int HVAC_SET_STEERING_HEAT = 70;
    private static final int HVAC_GET_STEERING_HEAT = 71;

    public void    switchHvacPower()                 { callVoid(HELPER_HVAC, HVAC_SWITCH_POWER); }
    public boolean getHvacPowerStatus()              { return readBool(HELPER_HVAC, HVAC_GET_POWER); }
    public void    switchAC()                        { callVoid(HELPER_HVAC, HVAC_SWITCH_AC); }
    public boolean getACStatus()                     { return readBool(HELPER_HVAC, HVAC_GET_AC); }
    public void    switchAutoMode()                  { callVoid(HELPER_HVAC, HVAC_SWITCH_AUTO); }
    public boolean getAutoStatus()                   { return readBool(HELPER_HVAC, HVAC_GET_AUTO); }
    public void    switchSync()                      { callVoid(HELPER_HVAC, HVAC_SWITCH_SYNC); }
    public boolean getSyncStatus()                   { return readBool(HELPER_HVAC, HVAC_GET_SYNC); }
    public void    switchEcoMode()                   { callVoid(HELPER_HVAC, HVAC_SWITCH_ECO); }
    public boolean getEcoStatus()                    { return readBool(HELPER_HVAC, HVAC_GET_ECO); }
    public void    switchAirCirculation()            { callVoid(HELPER_HVAC, HVAC_SWITCH_AIR_CIRC); }
    public int     getAirCirculationStatus()         { return readInt(HELPER_HVAC, HVAC_GET_AIR_CIRC); }
    public void    setFanSpeed(int speed)            { writeInt(HELPER_HVAC, HVAC_SET_FAN_SPEED, speed); }
    public int     getFanSpeed()                     { return readInt(HELPER_HVAC, HVAC_GET_FAN_SPEED); }
    public void    setFanDirection(int dir)          { writeInt(HELPER_HVAC, HVAC_SET_FAN_DIR, dir); }
    public int     getFanDirection()                 { return readInt(HELPER_HVAC, HVAC_GET_FAN_DIR); }
    public void    setDriverTemperature(float t)     { writeFloat(HELPER_HVAC, HVAC_SET_DRIVER_TEMP, t); }
    public float   getDriverTemperature()            { return readFloat(HELPER_HVAC, HVAC_GET_DRIVER_TEMP); }
    public void    setPassengerTemperature(float t)  { writeFloat(HELPER_HVAC, HVAC_SET_PASS_TEMP, t); }
    public float   getPassengerTemperature()         { return readFloat(HELPER_HVAC, HVAC_GET_PASS_TEMP); }
    public float   getOutsideTemperature()           { return readFloat(HELPER_HVAC, HVAC_GET_OUTSIDE_TEMP); }
    public void    switchFrontDefrost()              { callVoid(HELPER_HVAC, HVAC_SWITCH_FRONT_DEFROST); }
    public boolean getFrontDefrostStatus()           { return readBool(HELPER_HVAC, HVAC_GET_FRONT_DEFROST); }
    public void    switchRearDefrost()               { callVoid(HELPER_HVAC, HVAC_SWITCH_REAR_DEFROST); }
    public boolean getRearDefrostStatus()            { return readBool(HELPER_HVAC, HVAC_GET_REAR_DEFROST); }
    public void    switchSeatHeat(int side)          { writeInt(HELPER_HVAC, HVAC_SWITCH_SEAT_HEAT, side); }
    public int     getSeatHeatStatus(int side)       { return readIntWithParam(HELPER_HVAC, HVAC_GET_SEAT_HEAT, side); }
    public void    switchSeatVent(int side)          { writeInt(HELPER_HVAC, HVAC_SWITCH_SEAT_VENT, side); }
    public int     getSeatVentStatus(int side)       { return readIntWithParam(HELPER_HVAC, HVAC_GET_SEAT_VENT, side); }
    public void    setSteeringWheelHeat(int level)   { writeInt(HELPER_HVAC, HVAC_SET_STEERING_HEAT, level); }
    public int     getSteeringWheelHeatStatus()      { return readInt(HELPER_HVAC, HVAC_GET_STEERING_HEAT); }
    public int     getPM25Concentration()            { return readInt(HELPER_HVAC, HVAC_GET_PM25); }
    public int     getAirPurifyingStatus()           { return readInt(HELPER_HVAC, HVAC_GET_AIR_PURIFY); }

    // =========================================================================
    // VEHICLE SETTINGS – DRIVING MODE  (Helper 8)
    // =========================================================================
    private static final int VS_GET_DRIVING_MODE         = 63;
    private static final int VS_SET_DRIVING_MODE         = 62;
    private static final int VS_GET_POWERTRAIN_MODE      = 83;
    private static final int VS_SET_POWERTRAIN_MODE      = 82;
    private static final int VS_GET_DRIVING_EPS_MODE     = 85;
    private static final int VS_SET_DRIVING_EPS_MODE     = 84;
    private static final int VS_GET_DRIVING_AMBIENT_MODE = 87;
    private static final int VS_SET_DRIVING_AMBIENT_MODE = 86;
    private static final int VS_GET_DRIVING_AC_MODE      = 89;
    private static final int VS_SET_DRIVING_AC_MODE      = 88;
    private static final int VS_GET_CUSTOM_POWERTRAIN    = 178;
    private static final int VS_SET_CUSTOM_POWERTRAIN    = 177;

    public int  getDrivingMode()              { return readInt(HELPER_VEHICLE_SETTING, VS_GET_DRIVING_MODE); }
    public void setDrivingMode(int mode)      { writeInt(HELPER_VEHICLE_SETTING, VS_SET_DRIVING_MODE, mode); }
    public int  getPowertrainMode()           { return readInt(HELPER_VEHICLE_SETTING, VS_GET_POWERTRAIN_MODE); }
    public void setPowertrainMode(int mode)   { writeInt(HELPER_VEHICLE_SETTING, VS_SET_POWERTRAIN_MODE, mode); }
    public int  getDrivingEpsMode()           { return readInt(HELPER_VEHICLE_SETTING, VS_GET_DRIVING_EPS_MODE); }
    public void setDrivingEpsMode(int mode)   { writeInt(HELPER_VEHICLE_SETTING, VS_SET_DRIVING_EPS_MODE, mode); }
    public int  getDrivingAmbientMode()       { return readInt(HELPER_VEHICLE_SETTING, VS_GET_DRIVING_AMBIENT_MODE); }
    public void setDrivingAmbientMode(int m)  { writeInt(HELPER_VEHICLE_SETTING, VS_SET_DRIVING_AMBIENT_MODE, m); }
    public int  getDrivingAcMode()            { return readInt(HELPER_VEHICLE_SETTING, VS_GET_DRIVING_AC_MODE); }
    public void setDrivingAcMode(int mode)    { writeInt(HELPER_VEHICLE_SETTING, VS_SET_DRIVING_AC_MODE, mode); }
    public int  getCustomPowertrainMode()     { return readInt(HELPER_VEHICLE_SETTING, VS_GET_CUSTOM_POWERTRAIN); }
    public void setCustomPowertrainMode(int m){ writeInt(HELPER_VEHICLE_SETTING, VS_SET_CUSTOM_POWERTRAIN, m); }

    // =========================================================================
    // VEHICLE SETTINGS – CHARGING  (Helper 8)
    // =========================================================================
    private static final int VS_GET_CHARGING_MODE = 61;
    private static final int VS_SET_CHARGING_MODE = 60;

    public int  getChargingMode()           { return readInt(HELPER_VEHICLE_SETTING, VS_GET_CHARGING_MODE); }
    public void setChargingMode(int mode)   { writeInt(HELPER_VEHICLE_SETTING, VS_SET_CHARGING_MODE, mode); }

    // =========================================================================
    // VEHICLE SETTINGS – STEERING / EPS  (Helper 8)
    // =========================================================================
    private static final int VS_GET_STEERING_MODE    = 155;
    private static final int VS_SET_STEERING_MODE    = 154;
    private static final int VS_GET_EPS_MODE         = 16;
    private static final int VS_SET_EPS_MODE         = 17;
    private static final int VS_IS_EPS_ENABLE        = 18;
    private static final int VS_GET_BRAKE_PEDAL_MODE = 157;
    private static final int VS_SET_BRAKE_PEDAL_MODE = 156;

    public int     getSteeringMode()           { return readInt(HELPER_VEHICLE_SETTING, VS_GET_STEERING_MODE); }
    public void    setSteeringMode(int mode)   { writeInt(HELPER_VEHICLE_SETTING, VS_SET_STEERING_MODE, mode); }
    public int     getEpsMode()                { return readInt(HELPER_VEHICLE_SETTING, VS_GET_EPS_MODE); }
    public void    setEpsMode(int mode)        { writeInt(HELPER_VEHICLE_SETTING, VS_SET_EPS_MODE, mode); }
    public boolean isEpsModeEnabled()          { return readBool(HELPER_VEHICLE_SETTING, VS_IS_EPS_ENABLE); }
    public int     getBrakePedalMode()         { return readInt(HELPER_VEHICLE_SETTING, VS_GET_BRAKE_PEDAL_MODE); }
    public void    setBrakePedalMode(int mode) { writeInt(HELPER_VEHICLE_SETTING, VS_SET_BRAKE_PEDAL_MODE, mode); }

    // =========================================================================
    // VEHICLE SETTINGS – REGENERATIVE BRAKING / ONE PEDAL / ENDURANCE  (Helper 8)
    // =========================================================================
    private static final int VS_GET_REGEN_LEVEL       = 160;
    private static final int VS_SET_REGEN_LEVEL       = 159;
    private static final int VS_GET_REGEN_ENABLE      = 161;
    private static final int VS_GET_ONE_PEDAL         = 163;
    private static final int VS_SET_ONE_PEDAL         = 162;
    private static final int VS_GET_ONE_PEDAL_ENABLE  = 164;
    private static final int VS_GET_ENDURANCE_MODE    = 166;
    private static final int VS_SET_ENDURANCE_MODE    = 165;
    private static final int VS_GET_ENDURANCE_ENABLE  = 167;
    private static final int VS_GET_LONG_ENDURANCE    = 171;
    private static final int VS_SET_LONG_ENDURANCE    = 170;
    private static final int VS_GET_ELECTRIC_RANGE_CMD= 174;

    public int  getRegenerativeLevel()          { return readInt(HELPER_VEHICLE_SETTING, VS_GET_REGEN_LEVEL); }
    public void setRegenerativeLevel(int level) { writeInt(HELPER_VEHICLE_SETTING, VS_SET_REGEN_LEVEL, level); }
    public int  isRegenerativeEnabled()         { return readInt(HELPER_VEHICLE_SETTING, VS_GET_REGEN_ENABLE); }
    public int  getOnePedalState()              { return readInt(HELPER_VEHICLE_SETTING, VS_GET_ONE_PEDAL); }
    public void setOnePedalState(int state)     { writeInt(HELPER_VEHICLE_SETTING, VS_SET_ONE_PEDAL, state); }
    public int  isOnePedalEnabled()             { return readInt(HELPER_VEHICLE_SETTING, VS_GET_ONE_PEDAL_ENABLE); }
    public int  getEnduranceMode()              { return readInt(HELPER_VEHICLE_SETTING, VS_GET_ENDURANCE_MODE); }
    public void setEnduranceMode(int state)     { writeInt(HELPER_VEHICLE_SETTING, VS_SET_ENDURANCE_MODE, state); }
    public int  isEnduranceEnabled()            { return readInt(HELPER_VEHICLE_SETTING, VS_GET_ENDURANCE_ENABLE); }
    public int  getLongEndurance()              { return readInt(HELPER_VEHICLE_SETTING, VS_GET_LONG_ENDURANCE); }
    public void setLongEndurance(int state)     { writeInt(HELPER_VEHICLE_SETTING, VS_SET_LONG_ENDURANCE, state); }
    public int  getElectricRangeCmd()           { return readInt(HELPER_VEHICLE_SETTING, VS_GET_ELECTRIC_RANGE_CMD); }

    // =========================================================================
    // VEHICLE SETTINGS – AUTO HOLD  (Helper 8)
    // =========================================================================
    private static final int VS_GET_AUTO_HOLD = 169;
    private static final int VS_SET_AUTO_HOLD = 168;

    public int  getAutoHoldState()           { return readInt(HELPER_VEHICLE_SETTING, VS_GET_AUTO_HOLD); }
    public void setAutoHoldState(int state)  { writeInt(HELPER_VEHICLE_SETTING, VS_SET_AUTO_HOLD, state); }

    // =========================================================================
    // VEHICLE SETTINGS – TPMS  (Helper 8)
    // =========================================================================
    private static final int VS_GET_TIRE_PRESSURE       = 92;
    private static final int VS_GET_TIRE_PRESSURE_FLOAT = 93;
    private static final int VS_GET_TIRE_TEMP           = 94;
    private static final int VS_GET_TIRE_STATUS         = 95;
    private static final int VS_GET_TPMS_STATUS         = 96;
    private static final int VS_GET_TPMS_AUTO_LOCATION  = 97;
    private static final int VS_GET_TPMS_FAULT          = 98;
    private static final int VS_GET_TPMS_LEARNING       = 99;
    private static final int VS_GET_TPMS_TELLTALE       = 100;
    private static final int VS_GET_TPMS_WINTER         = 101;
    private static final int VS_SET_TIRE_PRESSURE_UNIT  = 102;

    public int     getTirePressure(int wheel)      { return readIntWithParam(HELPER_VEHICLE_SETTING, VS_GET_TIRE_PRESSURE, wheel); }
    public float   getTirePressureFloat(int wheel) { return readFloatWithParam(HELPER_VEHICLE_SETTING, VS_GET_TIRE_PRESSURE_FLOAT, wheel); }
    public int     getTireTemperature(int wheel)   { return readIntWithParam(HELPER_VEHICLE_SETTING, VS_GET_TIRE_TEMP, wheel); }
    public int     getTireStatus(int wheel)        { return readIntWithParam(HELPER_VEHICLE_SETTING, VS_GET_TIRE_STATUS, wheel); }
    public int     getTPMSStatus()                 { return readInt(HELPER_VEHICLE_SETTING, VS_GET_TPMS_STATUS); }
    public boolean getTPMSAutoLocation()           { return readBool(HELPER_VEHICLE_SETTING, VS_GET_TPMS_AUTO_LOCATION); }
    public boolean getTPMSFault()                  { return readBool(HELPER_VEHICLE_SETTING, VS_GET_TPMS_FAULT); }
    public int     getTPMSLearning()               { return readInt(HELPER_VEHICLE_SETTING, VS_GET_TPMS_LEARNING); }
    public int     getTPMSTelltale()               { return readInt(HELPER_VEHICLE_SETTING, VS_GET_TPMS_TELLTALE); }
    public int     getTPMSWinterMode()             { return readInt(HELPER_VEHICLE_SETTING, VS_GET_TPMS_WINTER); }
    public void    setTirePressureUnit(int unit)   { writeInt(HELPER_VEHICLE_SETTING, VS_SET_TIRE_PRESSURE_UNIT, unit); }

    // =========================================================================
    // VEHICLE SETTINGS – ADAS FEATURES  (Helper 8)
    // =========================================================================
    // FCW
    private static final int VS_GET_FCW_STATE        = 20;
    private static final int VS_SET_FCW_STATE        = 19;
    private static final int VS_GET_FCW_AUTO_BRAKE   = 22;
    private static final int VS_SET_FCW_AUTO_BRAKE   = 21;
    private static final int VS_GET_FCW_SENSITIVITY  = 24;
    private static final int VS_SET_FCW_SENSITIVITY  = 23;
    private static final int VS_GET_FCW_SYSTEM_STATUS= 29;
    // AEB
    private static final int VS_GET_AEB_STATE        = 26;
    private static final int VS_SET_AEB_STATE        = 25;
    private static final int VS_GET_AEB_PEDESTRIAN   = 28;
    private static final int VS_SET_AEB_PEDESTRIAN   = 27;
    // LAS
    private static final int VS_GET_LAS_MODE         = 31;
    private static final int VS_SET_LAS_MODE         = 30;
    private static final int VS_GET_LAS_SENSITIVITY  = 33;
    private static final int VS_SET_LAS_SENSITIVITY  = 32;
    private static final int VS_GET_LAS_VIBRATION    = 35;
    private static final int VS_SET_LAS_VIBRATION    = 34;
    private static final int VS_GET_LAS_SOUND        = 37;
    private static final int VS_SET_LAS_SOUND        = 36;
    // HDC
    private static final int VS_GET_HDC_STATE        = 39;
    private static final int VS_SET_HDC_STATE        = 38;
    // PA
    private static final int VS_GET_PA_STATE         = 41;
    private static final int VS_SET_PA_STATE         = 40;
    // RDA
    private static final int VS_GET_RDA_STATE        = 43;
    private static final int VS_SET_RDA_STATE        = 42;
    // BSD
    private static final int VS_GET_BSD_STATE        = 45;
    private static final int VS_SET_BSD_STATE        = 44;
    // DOW
    private static final int VS_GET_DOW_STATE        = 47;
    private static final int VS_SET_DOW_STATE        = 46;
    // LCA
    private static final int VS_GET_LCA_STATE        = 49;
    private static final int VS_SET_LCA_STATE        = 48;
    // RCTA
    private static final int VS_GET_RCTA_STATE       = 51;
    private static final int VS_SET_RCTA_STATE       = 50;
    // SLIF
    private static final int VS_GET_SLIF_WARNING     = 53;
    private static final int VS_SET_SLIF_WARNING     = 52;
    // SAS
    private static final int VS_GET_SAS_MODE         = 55;
    private static final int VS_SET_SAS_MODE         = 54;
    // SCS
    private static final int VS_GET_SCS_STATE        = 57;
    private static final int VS_SET_SCS_STATE        = 56;
    // TJA
    private static final int VS_GET_TJA_MODE         = 59;
    private static final int VS_SET_TJA_MODE         = 58;
    // ACC/TJA
    private static final int VS_GET_ACC_TJA          = 153;
    private static final int VS_SET_ACC_TJA          = 152;
    // TSR
    private static final int VS_GET_TSR_STATUS       = 141;
    private static final int VS_SET_TSR_STATUS       = 140;
    // RCW
    private static final int VS_GET_RCW_STATUS       = 143;
    private static final int VS_SET_RCW_STATUS       = 142;
    // PDC
    private static final int VS_GET_PDC_STATUS       = 139;
    private static final int VS_SET_PDC_STATUS       = 138;
    // DMS
    private static final int VS_GET_DMS_STATUS       = 145;
    private static final int VS_SET_DMS_STATUS       = 144;
    private static final int VS_GET_DMS_ALARM        = 147;
    private static final int VS_SET_DMS_ALARM        = 146;
    // DDD
    private static final int VS_GET_DDD_STATUS       = 129;
    private static final int VS_SET_DDD_STATE        = 128;
    // UDW
    private static final int VS_GET_UDW_STATUS       = 149;
    private static final int VS_SET_UDW_STATUS       = 148;
    private static final int VS_GET_UDW_SENSITIVITY  = 151;
    private static final int VS_SET_UDW_SENSITIVITY  = 150;

    public int  getFcwState()                  { return readInt(HELPER_VEHICLE_SETTING, VS_GET_FCW_STATE); }
    public void setFcwState(int state)         { writeInt(HELPER_VEHICLE_SETTING, VS_SET_FCW_STATE, state); }
    public int  getFcwAutoBrakeMode()          { return readInt(HELPER_VEHICLE_SETTING, VS_GET_FCW_AUTO_BRAKE); }
    public void setFcwAutoBrakeMode(int mode)  { writeInt(HELPER_VEHICLE_SETTING, VS_SET_FCW_AUTO_BRAKE, mode); }
    public int  getFcwSensitivity()            { return readInt(HELPER_VEHICLE_SETTING, VS_GET_FCW_SENSITIVITY); }
    public void setFcwSensitivity(int s)       { writeInt(HELPER_VEHICLE_SETTING, VS_SET_FCW_SENSITIVITY, s); }
    public int  getFcwSystemStatus()           { return readInt(HELPER_VEHICLE_SETTING, VS_GET_FCW_SYSTEM_STATUS); }
    public int  getAebState()                  { return readInt(HELPER_VEHICLE_SETTING, VS_GET_AEB_STATE); }
    public void setAebState(int state)         { writeInt(HELPER_VEHICLE_SETTING, VS_SET_AEB_STATE, state); }
    public int  getAebPedestrianState()        { return readInt(HELPER_VEHICLE_SETTING, VS_GET_AEB_PEDESTRIAN); }
    public void setAebPedestrianState(int s)   { writeInt(HELPER_VEHICLE_SETTING, VS_SET_AEB_PEDESTRIAN, s); }
    public int  getLasMode()                   { return readInt(HELPER_VEHICLE_SETTING, VS_GET_LAS_MODE); }
    public void setLasMode(int mode)           { writeInt(HELPER_VEHICLE_SETTING, VS_SET_LAS_MODE, mode); }
    public int  getLasSensitivity()            { return readInt(HELPER_VEHICLE_SETTING, VS_GET_LAS_SENSITIVITY); }
    public void setLasSensitivity(int s)       { writeInt(HELPER_VEHICLE_SETTING, VS_SET_LAS_SENSITIVITY, s); }
    public int  getLasVibration()              { return readInt(HELPER_VEHICLE_SETTING, VS_GET_LAS_VIBRATION); }
    public void setLasVibration(int state)     { writeInt(HELPER_VEHICLE_SETTING, VS_SET_LAS_VIBRATION, state); }
    public int  getLasSound()                  { return readInt(HELPER_VEHICLE_SETTING, VS_GET_LAS_SOUND); }
    public void setLasSound(int state)         { writeInt(HELPER_VEHICLE_SETTING, VS_SET_LAS_SOUND, state); }
    public int  getHdcState()                  { return readInt(HELPER_VEHICLE_SETTING, VS_GET_HDC_STATE); }
    public void setHdcState(int state)         { writeInt(HELPER_VEHICLE_SETTING, VS_SET_HDC_STATE, state); }
    public int  getPaState()                   { return readInt(HELPER_VEHICLE_SETTING, VS_GET_PA_STATE); }
    public void setPaState(int state)          { writeInt(HELPER_VEHICLE_SETTING, VS_SET_PA_STATE, state); }
    public int  getRdaState()                  { return readInt(HELPER_VEHICLE_SETTING, VS_GET_RDA_STATE); }
    public void setRdaState(int state)         { writeInt(HELPER_VEHICLE_SETTING, VS_SET_RDA_STATE, state); }
    public int  getBsdState()                  { return readInt(HELPER_VEHICLE_SETTING, VS_GET_BSD_STATE); }
    public void setBsdState(int state)         { writeInt(HELPER_VEHICLE_SETTING, VS_SET_BSD_STATE, state); }
    public int  getDowState()                  { return readInt(HELPER_VEHICLE_SETTING, VS_GET_DOW_STATE); }
    public void setDowState(int state)         { writeInt(HELPER_VEHICLE_SETTING, VS_SET_DOW_STATE, state); }
    public int  getLcaState()                  { return readInt(HELPER_VEHICLE_SETTING, VS_GET_LCA_STATE); }
    public void setLcaState(int state)         { writeInt(HELPER_VEHICLE_SETTING, VS_SET_LCA_STATE, state); }
    public int  getRctaState()                 { return readInt(HELPER_VEHICLE_SETTING, VS_GET_RCTA_STATE); }
    public void setRctaState(int state)        { writeInt(HELPER_VEHICLE_SETTING, VS_SET_RCTA_STATE, state); }
    public int  getSlifWarning()               { return readInt(HELPER_VEHICLE_SETTING, VS_GET_SLIF_WARNING); }
    public void setSlifWarning(int state)      { writeInt(HELPER_VEHICLE_SETTING, VS_SET_SLIF_WARNING, state); }
    public int  getSasMode()                   { return readInt(HELPER_VEHICLE_SETTING, VS_GET_SAS_MODE); }
    public void setSasMode(int mode)           { writeInt(HELPER_VEHICLE_SETTING, VS_SET_SAS_MODE, mode); }
    public int  getScsState()                  { return readInt(HELPER_VEHICLE_SETTING, VS_GET_SCS_STATE); }
    public void setScsState(int state)         { writeInt(HELPER_VEHICLE_SETTING, VS_SET_SCS_STATE, state); }
    public int  getTjaMode()                   { return readInt(HELPER_VEHICLE_SETTING, VS_GET_TJA_MODE); }
    public void setTjaMode(int mode)           { writeInt(HELPER_VEHICLE_SETTING, VS_SET_TJA_MODE, mode); }
    public int  getAccTjaState()               { return readInt(HELPER_VEHICLE_SETTING, VS_GET_ACC_TJA); }
    public void setAccTjaState(int state)      { writeInt(HELPER_VEHICLE_SETTING, VS_SET_ACC_TJA, state); }
    public int  getTsrStatus()                 { return readInt(HELPER_VEHICLE_SETTING, VS_GET_TSR_STATUS); }
    public void setTsrStatus(int status)       { writeInt(HELPER_VEHICLE_SETTING, VS_SET_TSR_STATUS, status); }
    public int  getRcwStatus()                 { return readInt(HELPER_VEHICLE_SETTING, VS_GET_RCW_STATUS); }
    public void setRcwStatus(int status)       { writeInt(HELPER_VEHICLE_SETTING, VS_SET_RCW_STATUS, status); }
    public int  getPdcStatus()                 { return readInt(HELPER_VEHICLE_SETTING, VS_GET_PDC_STATUS); }
    public void setPdcStatus(int status)       { writeInt(HELPER_VEHICLE_SETTING, VS_SET_PDC_STATUS, status); }
    public int  getDmsStatus()                 { return readInt(HELPER_VEHICLE_SETTING, VS_GET_DMS_STATUS); }
    public void setDmsStatus(int status)       { writeInt(HELPER_VEHICLE_SETTING, VS_SET_DMS_STATUS, status); }
    public int  getDmsAlarmState()             { return readInt(HELPER_VEHICLE_SETTING, VS_GET_DMS_ALARM); }
    public void setDmsAlarmState(int state)    { writeInt(HELPER_VEHICLE_SETTING, VS_SET_DMS_ALARM, state); }
    public int  getDddStatus()                 { return readInt(HELPER_VEHICLE_SETTING, VS_GET_DDD_STATUS); }
    public void setDddState(int state)         { writeInt(HELPER_VEHICLE_SETTING, VS_SET_DDD_STATE, state); }
    public int  getUdwStatus()                 { return readInt(HELPER_VEHICLE_SETTING, VS_GET_UDW_STATUS); }
    public void setUdwStatus(int status)       { writeInt(HELPER_VEHICLE_SETTING, VS_SET_UDW_STATUS, status); }
    public int  getUdwSensitivity()            { return readInt(HELPER_VEHICLE_SETTING, VS_GET_UDW_SENSITIVITY); }
    public void setUdwSensitivity(int state)   { writeInt(HELPER_VEHICLE_SETTING, VS_SET_UDW_SENSITIVITY, state); }

    // =========================================================================
    // VEHICLE SETTINGS – AMBIENT LIGHTING  (Helper 8)
    // =========================================================================
    private static final int VS_GET_AMBIENT_STATE        = 65;
    private static final int VS_SET_AMBIENT_STATE        = 64;
    private static final int VS_GET_AMBIENT_DRIVING_MODE = 67;
    private static final int VS_SET_AMBIENT_DRIVING_MODE = 66;
    private static final int VS_GET_AMBIENT_IGNITION     = 69;
    private static final int VS_SET_AMBIENT_IGNITION     = 68;
    private static final int VS_GET_AMBIENT_WELCOME      = 71;
    private static final int VS_SET_AMBIENT_WELCOME      = 70;
    private static final int VS_GET_AMBIENT_WELCOME_MODE = 73;
    private static final int VS_SET_AMBIENT_WELCOME_MODE = 72;
    private static final int VS_GET_AMBIENT_CUSTOM       = 75;
    private static final int VS_SET_AMBIENT_CUSTOM       = 74;
    private static final int VS_GET_AMBIENT_BREATH       = 77;
    private static final int VS_SET_AMBIENT_BREATH       = 76;
    private static final int VS_GET_AMBIENT_BRIGHTNESS   = 79;
    private static final int VS_SET_AMBIENT_BRIGHTNESS   = 78;
    private static final int VS_GET_AMBIENT_COLOR        = 81;
    private static final int VS_SET_AMBIENT_COLOR        = 80;

    public int  getAmbientState()              { return readInt(HELPER_VEHICLE_SETTING, VS_GET_AMBIENT_STATE); }
    public void setAmbientState(int state)     { writeInt(HELPER_VEHICLE_SETTING, VS_SET_AMBIENT_STATE, state); }
    public int  getAmbientBrightness()         { return readInt(HELPER_VEHICLE_SETTING, VS_GET_AMBIENT_BRIGHTNESS); }
    public void setAmbientBrightness(int b)    { writeInt(HELPER_VEHICLE_SETTING, VS_SET_AMBIENT_BRIGHTNESS, b); }
    public int  getAmbientColor()              { return readInt(HELPER_VEHICLE_SETTING, VS_GET_AMBIENT_COLOR); }
    public void setAmbientColor(int color)     { writeInt(HELPER_VEHICLE_SETTING, VS_SET_AMBIENT_COLOR, color); }
    public int  getAmbientBreathEffect()       { return readInt(HELPER_VEHICLE_SETTING, VS_GET_AMBIENT_BREATH); }
    public void setAmbientBreathEffect(int s)  { writeInt(HELPER_VEHICLE_SETTING, VS_SET_AMBIENT_BREATH, s); }
    public int  getAmbientCustomState()        { return readInt(HELPER_VEHICLE_SETTING, VS_GET_AMBIENT_CUSTOM); }
    public void setAmbientCustomState(int s)   { writeInt(HELPER_VEHICLE_SETTING, VS_SET_AMBIENT_CUSTOM, s); }
    public int  getAmbientDrivingMode()        { return readInt(HELPER_VEHICLE_SETTING, VS_GET_AMBIENT_DRIVING_MODE); }
    public void setAmbientDrivingMode(int m)   { writeInt(HELPER_VEHICLE_SETTING, VS_SET_AMBIENT_DRIVING_MODE, m); }
    public int  getAmbientIgnitionState()      { return readInt(HELPER_VEHICLE_SETTING, VS_GET_AMBIENT_IGNITION); }
    public void setAmbientIgnitionState(int s) { writeInt(HELPER_VEHICLE_SETTING, VS_SET_AMBIENT_IGNITION, s); }
    public int  getAmbientWelcomeState()       { return readInt(HELPER_VEHICLE_SETTING, VS_GET_AMBIENT_WELCOME); }
    public void setAmbientWelcomeState(int s)  { writeInt(HELPER_VEHICLE_SETTING, VS_SET_AMBIENT_WELCOME, s); }
    public int  getAmbientWelcomeMode()        { return readInt(HELPER_VEHICLE_SETTING, VS_GET_AMBIENT_WELCOME_MODE); }
    public void setAmbientWelcomeMode(int m)   { writeInt(HELPER_VEHICLE_SETTING, VS_SET_AMBIENT_WELCOME_MODE, m); }

    // =========================================================================
    // VEHICLE SETTINGS – CONVENIENCE  (Helper 8)
    // =========================================================================
    private static final int VS_GET_AUTO_LOCK            = 116;
    private static final int VS_SET_AUTO_LOCK            = 115;
    private static final int VS_GET_AUTO_UNLOCK          = 118;
    private static final int VS_SET_AUTO_UNLOCK          = 117;
    private static final int VS_GET_MIRROR_FOLD          = 112;
    private static final int VS_SET_MIRROR_FOLD          = 111;
    private static final int VS_GET_MIRROR_ROTATE_DOWN   = 135;
    private static final int VS_SET_MIRROR_ROTATE_DOWN   = 134;
    private static final int VS_GET_SEAT_AUTO_MOVE       = 137;
    private static final int VS_SET_SEAT_AUTO_MOVE       = 136;
    private static final int VS_GET_MIRROR_SEAT_MEMORY   = 133;
    private static final int VS_SET_MIRROR_SEAT_MEMORY   = 132;
    private static final int VS_GET_CLEAR_MEMORY         = 131;
    private static final int VS_SET_CLEAR_MEMORY         = 130;
    private static final int VS_GET_FOLLOW_ME_HOME       = 104;
    private static final int VS_SET_FOLLOW_ME_HOME       = 103;
    private static final int VS_GET_WELCOME_LIGHT        = 114;
    private static final int VS_SET_WELCOME_LIGHT        = 113;
    private static final int VS_GET_PASSIVE_ENTRY        = 110;
    private static final int VS_SET_PASSIVE_ENTRY        = 109;
    private static final int VS_GET_SINGLE_ENTRY         = 108;
    private static final int VS_SET_SINGLE_ENTRY         = 107;
    private static final int VS_GET_FIND_MY_CAR          = 106;
    private static final int VS_SET_FIND_MY_CAR          = 105;
    private static final int VS_GET_INTELLIGENT_HEADLAMP = 120;
    private static final int VS_SET_INTELLIGENT_HEADLAMP = 119;
    private static final int VS_GET_ATS_STATUS           = 121;
    private static final int VS_GET_AVM_AUTO_OPEN        = 123;
    private static final int VS_SET_AVM_AUTO_OPEN        = 122;
    private static final int VS_GET_LOCK_FEEDBACK        = 124;
    private static final int VS_SET_LOCK_FEEDBACK        = 125;
    private static final int VS_GET_SKYLIGHT             = 126;
    private static final int VS_SET_SKYLIGHT             = 127;
    private static final int VS_GET_STORAGE_SWITCH       = 176;
    private static final int VS_SET_STORAGE_SWITCH       = 175;
    private static final int VS_SET_FACE_ID              = 158;

    public int  getAutoLock()                    { return readInt(HELPER_VEHICLE_SETTING, VS_GET_AUTO_LOCK); }
    public void setAutoLock(int state)           { writeInt(HELPER_VEHICLE_SETTING, VS_SET_AUTO_LOCK, state); }
    public int  getAutoUnlock()                  { return readInt(HELPER_VEHICLE_SETTING, VS_GET_AUTO_UNLOCK); }
    public void setAutoUnlock(int state)         { writeInt(HELPER_VEHICLE_SETTING, VS_SET_AUTO_UNLOCK, state); }
    public int  getMirrorAutoFold()              { return readInt(HELPER_VEHICLE_SETTING, VS_GET_MIRROR_FOLD); }
    public void setMirrorAutoFold(int state)     { writeInt(HELPER_VEHICLE_SETTING, VS_SET_MIRROR_FOLD, state); }
    public int  getMirrorRotateDown()            { return readInt(HELPER_VEHICLE_SETTING, VS_GET_MIRROR_ROTATE_DOWN); }
    public void setMirrorRotateDown(int state)   { writeInt(HELPER_VEHICLE_SETTING, VS_SET_MIRROR_ROTATE_DOWN, state); }
    public int  getSeatAutoMove()                { return readInt(HELPER_VEHICLE_SETTING, VS_GET_SEAT_AUTO_MOVE); }
    public void setSeatAutoMove(int state)       { writeInt(HELPER_VEHICLE_SETTING, VS_SET_SEAT_AUTO_MOVE, state); }
    public int  getMirrorSeatMemory()            { return readInt(HELPER_VEHICLE_SETTING, VS_GET_MIRROR_SEAT_MEMORY); }
    public void setMirrorSeatMemory(int state)   { writeInt(HELPER_VEHICLE_SETTING, VS_SET_MIRROR_SEAT_MEMORY, state); }
    public int  getClearMemory()                 { return readInt(HELPER_VEHICLE_SETTING, VS_GET_CLEAR_MEMORY); }
    public void setClearMemory(int state)        { writeInt(HELPER_VEHICLE_SETTING, VS_SET_CLEAR_MEMORY, state); }
    public int  getFollowMeHome()                { return readInt(HELPER_VEHICLE_SETTING, VS_GET_FOLLOW_ME_HOME); }
    public void setFollowMeHome(int state)       { writeInt(HELPER_VEHICLE_SETTING, VS_SET_FOLLOW_ME_HOME, state); }
    public int  getWelcomeLight()                { return readInt(HELPER_VEHICLE_SETTING, VS_GET_WELCOME_LIGHT); }
    public void setWelcomeLight(int state)       { writeInt(HELPER_VEHICLE_SETTING, VS_SET_WELCOME_LIGHT, state); }
    public int  getPassiveEntry()                { return readInt(HELPER_VEHICLE_SETTING, VS_GET_PASSIVE_ENTRY); }
    public void setPassiveEntry(int state)       { writeInt(HELPER_VEHICLE_SETTING, VS_SET_PASSIVE_ENTRY, state); }
    public int  getSingleEntry()                 { return readInt(HELPER_VEHICLE_SETTING, VS_GET_SINGLE_ENTRY); }
    public void setSingleEntry(int state)        { writeInt(HELPER_VEHICLE_SETTING, VS_SET_SINGLE_ENTRY, state); }
    public int  getFindMyCar()                   { return readInt(HELPER_VEHICLE_SETTING, VS_GET_FIND_MY_CAR); }
    public void setFindMyCar(int state)          { writeInt(HELPER_VEHICLE_SETTING, VS_SET_FIND_MY_CAR, state); }
    public int  getIntelligentHeadlamp()         { return readInt(HELPER_VEHICLE_SETTING, VS_GET_INTELLIGENT_HEADLAMP); }
    public void setIntelligentHeadlamp(int s)    { writeInt(HELPER_VEHICLE_SETTING, VS_SET_INTELLIGENT_HEADLAMP, s); }
    public int  getAtsStatus()                   { return readInt(HELPER_VEHICLE_SETTING, VS_GET_ATS_STATUS); }
    public int  getAvmAutoOpen()                 { return readInt(HELPER_VEHICLE_SETTING, VS_GET_AVM_AUTO_OPEN); }
    public void setAvmAutoOpen(int state)        { writeInt(HELPER_VEHICLE_SETTING, VS_SET_AVM_AUTO_OPEN, state); }
    public int  getLockFeedback()                { return readInt(HELPER_VEHICLE_SETTING, VS_GET_LOCK_FEEDBACK); }
    public void setLockFeedback(int state)       { writeInt(HELPER_VEHICLE_SETTING, VS_SET_LOCK_FEEDBACK, state); }
    public int  getSunroofState()                { return readInt(HELPER_VEHICLE_SETTING, VS_GET_SKYLIGHT); }
    public void setSunroofState(int state)       { writeInt(HELPER_VEHICLE_SETTING, VS_SET_SKYLIGHT, state); }
    public int  getStorageSwitch()               { return readInt(HELPER_VEHICLE_SETTING, VS_GET_STORAGE_SWITCH); }
    public void setStorageSwitch(int state)      { writeInt(HELPER_VEHICLE_SETTING, VS_SET_STORAGE_SWITCH, state); }
    public void setFaceId(int state)             { writeInt(HELPER_VEHICLE_SETTING, VS_SET_FACE_ID, state); }

    // =========================================================================
    // VEHICLE SETTINGS – TAILGATE  (Helper 8)
    // =========================================================================
    private static final int VS_GET_TAILGATE_HEIGHT = 91;
    private static final int VS_SET_TAILGATE_HEIGHT = 90;

    public float getTailgateHeight()          { return readFloat(HELPER_VEHICLE_SETTING, VS_GET_TAILGATE_HEIGHT); }
    public void  setTailgateHeight(float h)   { writeFloat(HELPER_VEHICLE_SETTING, VS_SET_TAILGATE_HEIGHT, h); }

    // =========================================================================
    // VEHICLE SETTINGS – LOCK STATE  (Helper 8)
    // =========================================================================
    private static final int VS_GET_LOCK_STATE = 173;
    private static final int VS_SET_LOCK_STATE = 172;

    public int  getVehicleLockState()          { return readInt(HELPER_VEHICLE_SETTING, VS_GET_LOCK_STATE); }
    public void setVehicleLockState(int state) { writeInt(HELPER_VEHICLE_SETTING, VS_SET_LOCK_STATE, state); }

    // =========================================================================
    // VEHICLE SETTINGS – SPEED WARNINGS  (Helper 8)
    // =========================================================================
    private static final int VS_GET_OVERSPEED_SOUND   = 180;
    private static final int VS_SET_OVERSPEED_SOUND   = 179;
    private static final int VS_GET_SPEED_LIMIT_SOUND = 182;
    private static final int VS_SET_SPEED_LIMIT_SOUND = 181;

    public int  getOverspeedSoundMode()          { return readInt(HELPER_VEHICLE_SETTING, VS_GET_OVERSPEED_SOUND); }
    public void setOverspeedSoundMode(int mode)  { writeInt(HELPER_VEHICLE_SETTING, VS_SET_OVERSPEED_SOUND, mode); }
    public int  getSpeedLimitSoundMode()         { return readInt(HELPER_VEHICLE_SETTING, VS_GET_SPEED_LIMIT_SOUND); }
    public void setSpeedLimitSoundMode(int mode) { writeInt(HELPER_VEHICLE_SETTING, VS_SET_SPEED_LIMIT_SOUND, mode); }

    // =========================================================================
    // VEHICLE SETTINGS – CONFIGURATION  (Helper 8)
    // =========================================================================
    private static final int VS_IS_READY             = 3;
    private static final int VS_GET_CAR_MODE         = 4;
    private static final int VS_GET_TPMS_CONFIG      = 5;
    private static final int VS_GET_AMBIENT_CONFIG   = 6;
    private static final int VS_GET_ADAS_LDW_CONFIG  = 7;
    private static final int VS_GET_RDA_CONFIG       = 8;
    private static final int VS_GET_SAS_CONFIG       = 9;
    private static final int VS_GET_DRIVING_CONFIG   = 10;
    private static final int VS_GET_HVAC_CONFIG      = 11;
    private static final int VS_GET_FCW_CONFIG       = 12;
    private static final int VS_GET_AEB_CONFIG       = 13;
    private static final int VS_GET_PA_CONFIG        = 14;
    private static final int VS_GET_PLCM_CONFIG      = 15;

    public boolean isCarAdapterReady() { return readBool(HELPER_VEHICLE_SETTING, VS_IS_READY); }
    public int  getCarMode()           { return readInt(HELPER_VEHICLE_SETTING, VS_GET_CAR_MODE); }
    public int  getHvacConfig()        { return readInt(HELPER_VEHICLE_SETTING, VS_GET_HVAC_CONFIG); }
    public int  getTpmsConfig()        { return readInt(HELPER_VEHICLE_SETTING, VS_GET_TPMS_CONFIG); }
    public int  getAmbientConfig()     { return readInt(HELPER_VEHICLE_SETTING, VS_GET_AMBIENT_CONFIG); }
    public int  getFcwConfig()         { return readInt(HELPER_VEHICLE_SETTING, VS_GET_FCW_CONFIG); }
    public int  getAebConfig()         { return readInt(HELPER_VEHICLE_SETTING, VS_GET_AEB_CONFIG); }
    public int  getPaConfig()          { return readInt(HELPER_VEHICLE_SETTING, VS_GET_PA_CONFIG); }
    public int  getPlcmConfig()        { return readInt(HELPER_VEHICLE_SETTING, VS_GET_PLCM_CONFIG); }
    public int  getAdasLdwConfig()     { return readInt(HELPER_VEHICLE_SETTING, VS_GET_ADAS_LDW_CONFIG); }
    public int  getRdaConfig()         { return readInt(HELPER_VEHICLE_SETTING, VS_GET_RDA_CONFIG); }
    public int  getSasConfig()         { return readInt(HELPER_VEHICLE_SETTING, VS_GET_SAS_CONFIG); }
    public int  getDrivingConfig()     { return readInt(HELPER_VEHICLE_SETTING, VS_GET_DRIVING_CONFIG); }

    // =========================================================================
    // AUDIO  (Helper 10)
    // =========================================================================
    private static final int AUDIO_IS_READY        = 3;
    private static final int AUDIO_GET_MIN_VOL     = 4;
    private static final int AUDIO_GET_MAX_VOL     = 5;
    private static final int AUDIO_GET_VOLUME      = 6;
    private static final int AUDIO_SET_VOLUME      = 7;
    private static final int AUDIO_SET_MUTE        = 8;
    private static final int AUDIO_GET_MUTE        = 9;
    private static final int AUDIO_VOL_UP          = 10;
    private static final int AUDIO_VOL_DOWN        = 11;
    private static final int AUDIO_SET_FADER_FRONT = 12;
    private static final int AUDIO_SET_BALANCE_RIGHT=13;
    private static final int AUDIO_GET_VOL_GROUP   = 14;
    private static final int AUDIO_SET_LOUDNESS    = 15;
    private static final int AUDIO_GET_LOUDNESS    = 16;
    private static final int AUDIO_SET_SPEED_VOL   = 17;
    private static final int AUDIO_GET_SPEED_VOL   = 18;
    private static final int AUDIO_SET_USER_EQ     = 19;
    private static final int AUDIO_GET_USER_EQ     = 20;
    private static final int AUDIO_GET_EQ_BAND     = 21;
    private static final int AUDIO_SET_EQ_BAND     = 22;
    private static final int AUDIO_GET_EQUALIZER   = 23;
    private static final int AUDIO_SET_REAR_QUIET  = 24;
    private static final int AUDIO_GET_REAR_QUIET  = 25;
    private static final int AUDIO_SET_3D_EFFECT   = 26;
    private static final int AUDIO_GET_3D_EFFECT   = 27;
    private static final int AUDIO_SET_SYSTEM_BEEP = 28;
    private static final int AUDIO_GET_SYSTEM_BEEP = 29;
    private static final int AUDIO_SET_SOUND_FIELD = 30;
    private static final int AUDIO_GET_BALANCE     = 31;
    private static final int AUDIO_GET_FADER       = 32;
    private static final int AUDIO_SET_CHIME       = 33;
    private static final int AUDIO_GET_CHIME       = 34;
    private static final int AUDIO_SET_PRESET_EQ   = 35;
    private static final int AUDIO_SET_BOSE_SOUND  = 36;
    private static final int AUDIO_GET_BOSE_SOUND  = 37;
    private static final int AUDIO_SET_MUSIC_LIGHT = 38;
    private static final int AUDIO_GET_MUSIC_LIGHT = 39;
    private static final int AUDIO_SET_TONE        = 40;
    private static final int AUDIO_GET_TONE        = 41;

    public boolean isAudioReady()                  { return readBool(HELPER_AUDIO, AUDIO_IS_READY); }
    public int     getVolume(int streamType)       { return readIntWithParam(HELPER_AUDIO, AUDIO_GET_VOLUME, streamType); }
    public int     getMinVolume(int streamType)    { return readIntWithParam(HELPER_AUDIO, AUDIO_GET_MIN_VOL, streamType); }
    public int     getMaxVolume(int streamType)    { return readIntWithParam(HELPER_AUDIO, AUDIO_GET_MAX_VOL, streamType); }
    public boolean getMuteState(int streamType)    { return readIntWithParam(HELPER_AUDIO, AUDIO_GET_MUTE, streamType) != 0; }
    public void    setMuteState(int muted)         { writeInt(HELPER_AUDIO, AUDIO_SET_MUTE, muted); }
    public void    volumeUp()                      { callVoid(HELPER_AUDIO, AUDIO_VOL_UP); }
    public void    volumeDown()                    { callVoid(HELPER_AUDIO, AUDIO_VOL_DOWN); }
    public int     getAudioBalance()               { return readInt(HELPER_AUDIO, AUDIO_GET_BALANCE); }
    public int     getAudioFader()                 { return readInt(HELPER_AUDIO, AUDIO_GET_FADER); }
    public int     getLoudnessState()              { return readInt(HELPER_AUDIO, AUDIO_GET_LOUDNESS); }
    public void    setLoudnessState(int state)     { writeInt(HELPER_AUDIO, AUDIO_SET_LOUDNESS, state); }
    public int     getSpeedVolumeLevel()           { return readInt(HELPER_AUDIO, AUDIO_GET_SPEED_VOL); }
    public void    setSpeedVolumeLevel(int level)  { writeInt(HELPER_AUDIO, AUDIO_SET_SPEED_VOL, level); }
    public int     getRearQuietMode()              { return readInt(HELPER_AUDIO, AUDIO_GET_REAR_QUIET); }
    public void    setRearQuietMode(int state)     { writeInt(HELPER_AUDIO, AUDIO_SET_REAR_QUIET, state); }
    public int     get3dEffectType()               { return readInt(HELPER_AUDIO, AUDIO_GET_3D_EFFECT); }
    public void    set3dEffectType(int type)       { writeInt(HELPER_AUDIO, AUDIO_SET_3D_EFFECT, type); }
    public int     getSystemBeepState()            { return readInt(HELPER_AUDIO, AUDIO_GET_SYSTEM_BEEP); }
    public void    setSystemBeepState(int state)   { writeInt(HELPER_AUDIO, AUDIO_SET_SYSTEM_BEEP, state); }
    public int     getChimeVoice()                 { return readInt(HELPER_AUDIO, AUDIO_GET_CHIME); }
    public void    setChimeVoice(int voice)        { writeInt(HELPER_AUDIO, AUDIO_SET_CHIME, voice); }
    public int     getMusicLight()                 { return readInt(HELPER_AUDIO, AUDIO_GET_MUSIC_LIGHT); }
    public void    setMusicLight(int state)        { writeInt(HELPER_AUDIO, AUDIO_SET_MUSIC_LIGHT, state); }
    public int     getToneControl()                { return readInt(HELPER_AUDIO, AUDIO_GET_TONE); }
    public void    setToneControl(int tone)        { writeInt(HELPER_AUDIO, AUDIO_SET_TONE, tone); }
    public int     getBoseSoundType()              { return readInt(HELPER_AUDIO, AUDIO_GET_BOSE_SOUND); }
    public void    setBoseSoundType(int type)      { writeInt(HELPER_AUDIO, AUDIO_SET_BOSE_SOUND, type); }

    /**
     * Set volume for a specific stream.  Takes two parameters so uses a
     * dedicated transact call (not the single-param writeInt helper).
     */
    public void setVolume(int streamType, int level) {
        writeIntPair(HELPER_AUDIO, AUDIO_SET_VOLUME, streamType, level);
    }

    // =========================================================================
    // RAW / DIAGNOSTIC ACCESS
    // =========================================================================

    /**
     * Returns the AIDL interface descriptor of the given helper binder.
     * Useful for reverse-engineering or debugging.
     */
    public String getHelperDescriptor(int helperCode) {
        getHelper(helperCode); // populates cache
        String d = descriptorCache.get(helperCode);
        return d != null ? d : "unknown";
    }

    /**
     * Probe a range of transaction codes on a helper, returning a human-readable
     * summary of each code's response (int value or "void").
     */
    public String probeHelper(int helperCode, int startCode, int endCode) {
        IBinder helper = getHelper(helperCode);
        if (helper == null) return "Helper " + helperCode + " not found";

        String descriptor = descriptorCache.containsKey(helperCode)
                ? descriptorCache.get(helperCode) : "unknown";
        StringBuilder result = new StringBuilder();
        result.append("=== Helper ").append(helperCode).append(" ===\n");

        for (int code = startCode; code <= endCode; code++) {
            Parcel data  = Parcel.obtain();
            Parcel reply = Parcel.obtain();
            try {
                data.writeInterfaceToken(descriptor);
                if (helper.transact(code, data, reply, 0)) {
                    reply.readException();
                    int size = reply.dataAvail();
                    if (size > 0) {
                        int pos    = reply.dataPosition();
                        int intVal = reply.readInt();
                        result.append(code).append(": int=").append(intVal);
                        if (intVal > 1_000_000_000 || intVal < -1_000_000_000) {
                            reply.setDataPosition(pos);
                            result.append(" (float=").append(reply.readFloat()).append(")");
                        }
                        result.append("\n");
                    } else {
                        result.append(code).append(": void\n");
                    }
                }
            } catch (Exception ignored) {
            } finally {
                data.recycle();
                reply.recycle();
            }
        }
        return result.toString();
    }

    // =========================================================================
    // TRANSPORT PRIMITIVES
    // =========================================================================

    public int readInt(int helperCode, int methodCode) {
        IBinder helper = getHelper(helperCode);
        if (helper == null) return -999;
        String descriptor = getDescriptor(helperCode);
        Parcel data  = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        int result = -1;
        try {
            data.writeInterfaceToken(descriptor);
            if (helper.transact(methodCode, data, reply, 0)) {
                reply.readException();
                if (reply.dataAvail() >= 4) result = reply.readInt();
            }
        } catch (Exception e) {
            Log.e(TAG, "readInt error: " + e.getMessage());
        } finally {
            data.recycle();
            reply.recycle();
        }
        return result;
    }

    public int readIntWithParam(int helperCode, int methodCode, int param) {
        IBinder helper = getHelper(helperCode);
        if (helper == null) return -999;
        String descriptor = getDescriptor(helperCode);
        Parcel data  = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        int result = -1;
        try {
            data.writeInterfaceToken(descriptor);
            data.writeInt(param);
            if (helper.transact(methodCode, data, reply, 0)) {
                reply.readException();
                if (reply.dataAvail() >= 4) result = reply.readInt();
            }
        } catch (Exception e) {
            Log.e(TAG, "readIntWithParam error: " + e.getMessage());
        } finally {
            data.recycle();
            reply.recycle();
        }
        return result;
    }

    public float readFloat(int helperCode, int methodCode) {
        IBinder helper = getHelper(helperCode);
        if (helper == null) return -999f;
        String descriptor = getDescriptor(helperCode);
        Parcel data  = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        float result = -1f;
        try {
            data.writeInterfaceToken(descriptor);
            if (helper.transact(methodCode, data, reply, 0)) {
                reply.readException();
                if (reply.dataAvail() >= 4) result = reply.readFloat();
            }
        } catch (Exception e) {
            Log.e(TAG, "readFloat error: " + e.getMessage());
        } finally {
            data.recycle();
            reply.recycle();
        }
        return result;
    }

    public float readFloatWithParam(int helperCode, int methodCode, int param) {
        IBinder helper = getHelper(helperCode);
        if (helper == null) return -999f;
        String descriptor = getDescriptor(helperCode);
        Parcel data  = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        float result = -1f;
        try {
            data.writeInterfaceToken(descriptor);
            data.writeInt(param);
            if (helper.transact(methodCode, data, reply, 0)) {
                reply.readException();
                if (reply.dataAvail() >= 4) result = reply.readFloat();
            }
        } catch (Exception e) {
            Log.e(TAG, "readFloatWithParam error: " + e.getMessage());
        } finally {
            data.recycle();
            reply.recycle();
        }
        return result;
    }

    public boolean readBool(int helperCode, int methodCode) {
        return readInt(helperCode, methodCode) != 0;
    }

    public String readString(int helperCode, int methodCode) {
        IBinder helper = getHelper(helperCode);
        if (helper == null) return "";
        String descriptor = getDescriptor(helperCode);
        Parcel data  = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        String result = "";
        try {
            data.writeInterfaceToken(descriptor);
            if (helper.transact(methodCode, data, reply, 0)) {
                reply.readException();
                result = reply.readString();
            }
        } catch (Exception e) {
            Log.e(TAG, "readString error: " + e.getMessage());
        } finally {
            data.recycle();
            reply.recycle();
        }
        return result != null ? result : "";
    }

    public void writeInt(int helperCode, int methodCode, int value) {
        IBinder helper = getHelper(helperCode);
        if (helper == null) return;
        String descriptor = getDescriptor(helperCode);
        Parcel data  = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        try {
            data.writeInterfaceToken(descriptor);
            data.writeInt(value);
            helper.transact(methodCode, data, reply, 0);
            reply.readException();
        } catch (Exception e) {
            Log.e(TAG, "writeInt error: " + e.getMessage());
        } finally {
            data.recycle();
            reply.recycle();
        }
    }

    /** Sends two int parameters in one transact call (used by setVolume). */
    public void writeIntPair(int helperCode, int methodCode, int value1, int value2) {
        IBinder helper = getHelper(helperCode);
        if (helper == null) return;
        String descriptor = getDescriptor(helperCode);
        Parcel data  = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        try {
            data.writeInterfaceToken(descriptor);
            data.writeInt(value1);
            data.writeInt(value2);
            helper.transact(methodCode, data, reply, 0);
            reply.readException();
        } catch (Exception e) {
            Log.e(TAG, "writeIntPair error: " + e.getMessage());
        } finally {
            data.recycle();
            reply.recycle();
        }
    }

    public void writeFloat(int helperCode, int methodCode, float value) {
        IBinder helper = getHelper(helperCode);
        if (helper == null) return;
        String descriptor = getDescriptor(helperCode);
        Parcel data  = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        try {
            data.writeInterfaceToken(descriptor);
            data.writeFloat(value);
            helper.transact(methodCode, data, reply, 0);
            reply.readException();
        } catch (Exception e) {
            Log.e(TAG, "writeFloat error: " + e.getMessage());
        } finally {
            data.recycle();
            reply.recycle();
        }
    }

    public void callVoid(int helperCode, int methodCode) {
        IBinder helper = getHelper(helperCode);
        if (helper == null) return;
        String descriptor = getDescriptor(helperCode);
        Parcel data  = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        try {
            data.writeInterfaceToken(descriptor);
            helper.transact(methodCode, data, reply, 0);
            reply.readException();
        } catch (Exception e) {
            Log.e(TAG, "callVoid error: " + e.getMessage());
        } finally {
            data.recycle();
            reply.recycle();
        }
    }

    // =========================================================================
    // PRIVATE HELPERS
    // =========================================================================

    private String getDescriptor(int helperCode) {
        String d = descriptorCache.get(helperCode);
        return d != null ? d : "";
    }

    private IBinder getHelper(int helperCode) {
        IBinder cached = helperCache.get(helperCode);
        if (cached != null && cached.isBinderAlive()) return cached;

        if (mServiceBinder == null) return null;

        Parcel data   = Parcel.obtain();
        Parcel reply  = Parcel.obtain();
        IBinder result = null;
        try {
            data.writeInterfaceToken(DESCRIPTOR);
            data.writeInt(helperCode);
            if (mServiceBinder.transact(TRANSACTION_queryClient, data, reply, 0)) {
                reply.readException();
                result = reply.readStrongBinder();
                if (result != null) {
                    helperCache.put(helperCode, result);
                    try {
                        String desc = result.getInterfaceDescriptor();
                        descriptorCache.put(helperCode, desc);
                    } catch (Exception ignored) {}
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "getHelper error: " + e.getMessage());
        } finally {
            data.recycle();
            reply.recycle();
        }
        return result;
    }

    private void doRegister(int helperCode, IBinder callbackBinder) {
        IBinder helper = getHelper(helperCode);
        if (helper == null) {
            Log.e(TAG, "doRegister: helper " + helperCode + " not available");
            return;
        }
        String descriptor = getDescriptor(helperCode);
        Parcel data  = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        try {
            data.writeInterfaceToken(descriptor);
            data.writeStrongBinder(callbackBinder);
            helper.transact(1, data, reply, 0); // 1 = registListener
            reply.readException();
            Log.d(TAG, "Registered callback on helper " + helperCode);
        } catch (Exception e) {
            Log.e(TAG, "doRegister error helper=" + helperCode + ": " + e.getMessage());
        } finally {
            data.recycle();
            reply.recycle();
        }
    }

    private void doUnregister(int helperCode, IBinder callbackBinder) {
        IBinder helper = getHelper(helperCode);
        if (helper == null) return;
        String descriptor = getDescriptor(helperCode);
        Parcel data  = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        try {
            data.writeInterfaceToken(descriptor);
            data.writeStrongBinder(callbackBinder);
            helper.transact(2, data, reply, 0); // 2 = unregistListener
            reply.readException();
            Log.d(TAG, "Unregistered callback on helper " + helperCode);
        } catch (Exception e) {
            Log.e(TAG, "doUnregister error helper=" + helperCode + ": " + e.getMessage());
        } finally {
            data.recycle();
            reply.recycle();
        }
    }

    // =========================================================================
    // CALLBACK BINDER FACTORIES
    // =========================================================================

    /**
     * HVAC Callback Binder
     * Descriptor: com.saicmotor.carapi.hvac.ICarHvacCallback
     * Transactions 1–31
     */
    private IBinder createHvacCallbackBinder() {
        return new Binder() {
            @Override
            public String getInterfaceDescriptor() {
                return "com.saicmotor.carapi.hvac.ICarHvacCallback";
            }
            @Override
            protected boolean onTransact(int code, Parcel data, Parcel reply, int flags) {
                data.enforceInterface("com.saicmotor.carapi.hvac.ICarHvacCallback");
                String key;
                Object val;
                switch (code) {
                    case 1:  key = "HvacPower";           val = data.readInt() != 0; break;
                    case 2:  key = "AC";                  val = data.readInt() != 0; break;
                    case 3:  key = "AutoMode";            val = data.readInt() != 0; break;
                    case 4:  key = "Sync";                val = data.readInt() != 0; break;
                    case 5:  key = "Eco";                 val = data.readInt() != 0; break;
                    case 6:  key = "AirCirculation";      val = data.readInt(); break;
                    case 7:  key = "FanDirection";        val = data.readInt(); break;
                    case 8:  key = "FanSpeed";            val = data.readInt(); break;
                    case 9:  key = "DriverTemp";          val = data.readFloat(); break;
                    case 10: key = "PassengerTemp";       val = data.readFloat(); break;
                    case 11: key = "OutsideTemp";         val = data.readFloat(); break;
                    case 12: key = "AirPurifying";        val = data.readInt(); break;
                    case 13: key = "CustomZoneConfig";    val = data.readInt(); break;
                    case 14: key = "CustomBlowerConfig";  val = data.readInt(); break;
                    case 15: key = "FrontDefrost";        val = data.readInt() != 0; break;
                    case 16: key = "RearDefrost";         val = data.readInt() != 0; break;
                    case 17: key = "SeatHeat";            val = new int[]{data.readInt(), data.readInt()}; break;
                    case 18: key = "SeatVent";            val = new int[]{data.readInt(), data.readInt()}; break;
                    case 19: key = "HvacControlArea";     val = data.readInt(); break;
                    case 20: key = "AirclnrUserAuto";     val = data.readInt() != 0; break;
                    case 21: key = "AirclnrStatus";       val = data.readInt() != 0; break;
                    case 22: key = "AirclnrAuto";         val = data.readInt() != 0; break;
                    case 23: key = "AirclnrBlower";       val = data.readInt(); break;
                    case 24: key = "AirclnrFilterLife";   val = data.readInt(); break;
                    case 25: key = "AirclnrFilterValid";  val = data.readInt(); break;
                    case 26: key = "PM25";                val = data.readInt(); break;
                    case 27: key = "AirclnrIonizer";      val = data.readInt() != 0; break;
                    case 28: key = "HvacFollowEcon";      val = data.readInt(); break;
                    case 29: key = "SteeringWheelHeat";   val = data.readInt(); break;
                    case 30: key = "AirSeatLinkage";      val = data.readInt(); break;
                    case 31: key = "AnionPurify";         val = data.readInt(); break;
                    default: return false;
                }
                if (reply != null) reply.writeNoException();
                if (listener != null) listener.onHvac(key, val);
                return true;
            }
        };
    }

    /**
     * EV Callback Binder
     * Descriptor: com.saicmotor.carapi.ev.ICarEvCallback
     * Transactions 1–14
     */
    private IBinder createEvCallbackBinder() {
        return new Binder() {
            @Override
            public String getInterfaceDescriptor() {
                return "com.saicmotor.carapi.ev.ICarEvCallback";
            }
            @Override
            protected boolean onTransact(int code, Parcel data, Parcel reply, int flags) {
                data.enforceInterface("com.saicmotor.carapi.ev.ICarEvCallback");
                String key;
                Object val;
                switch (code) {
                    case 1:  key = "BatteryPercent";   val = data.readInt(); break;
                    case 2:  key = "ChargingTime";     val = data.readInt(); break;
                    case 3:  key = "ChargeStatus";     val = data.readInt(); break;
                    case 4:  key = "ChargeCtrl";       val = data.readInt(); break;
                    case 5:  key = "ReserChargeCtrl";  val = data.readInt(); break;
                    case 6:  key = "DischargingTime";  val = data.readInt(); break;
                    case 7:  key = "DischargeCtrl";    val = data.readInt(); break;
                    case 8:  key = "DistanceUnit";     val = data.readInt(); break;
                    case 9:  key = "CurrentRange";     val = data.readInt(); break;
                    case 10: key = "BatteryHeat";      val = data.readInt(); break;
                    case 11: key = "ChargeStopReason"; val = data.readInt(); break;
                    case 12: key = "ElecClockCtrl";    val = data.readInt(); break;
                    case 13: key = "ElectricMgmt";     val = data.readInt(); break;
                    case 14: key = "WirelessCharger";  val = data.readInt(); break;
                    default: return false;
                }
                if (reply != null) reply.writeNoException();
                if (listener != null) listener.onEv(key, val);
                return true;
            }
        };
    }

    /**
     * General Callback Binder
     * Descriptor: com.saicmotor.carapi.general.ICarGeneralCallback
     * Transactions 1–8
     */
    private IBinder createGeneralCallbackBinder() {
        return new Binder() {
            @Override
            public String getInterfaceDescriptor() {
                return "com.saicmotor.carapi.general.ICarGeneralCallback";
            }
            @Override
            protected boolean onTransact(int code, Parcel data, Parcel reply, int flags) {
                data.enforceInterface("com.saicmotor.carapi.general.ICarGeneralCallback");
                String key;
                Object val;
                switch (code) {
                    case 1: key = "PowerStatus";   val = data.readInt(); break;
                    case 2: key = "Brightness";    val = data.readInt(); break;
                    case 3: key = "Speed";         val = data.readFloat(); break;
                    case 4: key = "VehicleReset";  val = data.readInt() != 0; break;
                    case 5: key = "Reverse";       val = data.readInt() != 0; break;
                    case 6: key = "TotalMileage";  val = data.readInt(); break;
                    case 7: key = "IgnitionState"; val = data.readInt(); break;
                    case 8: key = "NationChanged"; val = data.readInt(); break;
                    default: return false;
                }
                if (reply != null) reply.writeNoException();
                if (listener != null) listener.onGeneral(key, val);
                return true;
            }
        };
    }

    /**
     * Car State Callback Binder
     * Descriptor: com.saicmotor.carapi.carstate.ICarStateCallback
     * Transactions 1–6
     */
    private IBinder createStateCallbackBinder() {
        return new Binder() {
            @Override
            public String getInterfaceDescriptor() {
                return "com.saicmotor.carapi.carstate.ICarStateCallback";
            }
            @Override
            protected boolean onTransact(int code, Parcel data, Parcel reply, int flags) {
                data.enforceInterface("com.saicmotor.carapi.carstate.ICarStateCallback");
                String key;
                Object val;
                switch (code) {
                    case 1: key = "ServiceReady";    val = data.readInt() != 0; break;
                    case 2: key = "EcallState";      val = data.readInt(); break;
                    case 3: key = "GearState";       val = data.readInt(); break;
                    case 4: key = "ScsAvailability"; val = data.readInt(); break;
                    case 5: key = "VcuAvailability"; val = data.readInt(); break;
                    case 6: key = "SensorEptReady";  val = data.readInt(); break;
                    default: return false;
                }
                if (reply != null) reply.writeNoException();
                if (listener != null) listener.onState(key, val);
                return true;
            }
        };
    }

    /**
     * Audio Callback Binder
     * Descriptor: com.saicmotor.carapi.audio.ICarAudioCallback
     * Transactions 1–3
     */
    private IBinder createAudioCallbackBinder() {
        return new Binder() {
            @Override
            public String getInterfaceDescriptor() {
                return "com.saicmotor.carapi.audio.ICarAudioCallback";
            }
            @Override
            protected boolean onTransact(int code, Parcel data, Parcel reply, int flags) {
                data.enforceInterface("com.saicmotor.carapi.audio.ICarAudioCallback");
                String key;
                Object val;
                switch (code) {
                    case 1: key = "ServiceReady"; val = data.readInt() != 0; break;
                    case 2: key = "Volume";       val = new int[]{data.readInt(), data.readInt()}; break;
                    case 3: key = "Mute";         val = new int[]{data.readInt(), data.readInt()}; break;
                    default: return false;
                }
                if (reply != null) reply.writeNoException();
                if (listener != null) listener.onAudio(key, val);
                return true;
            }
        };
    }

    /**
     * Vehicle Setting Callback Binder
     * Descriptor: com.saicmotor.carapi.vs.ICarVehicleSettingCallback
     * Transactions 1–60
     */
    private IBinder createVsCallbackBinder() {
        return new Binder() {
            @Override
            public String getInterfaceDescriptor() {
                return "com.saicmotor.carapi.vs.ICarVehicleSettingCallback";
            }
            @Override
            protected boolean onTransact(int code, Parcel data, Parcel reply, int flags) {
                data.enforceInterface("com.saicmotor.carapi.vs.ICarVehicleSettingCallback");
                String key;
                Object val;
                switch (code) {
                    case 1:  key = "CarAdapterReady";    val = data.readInt() != 0; break;
                    case 2:  key = "EpsMode";            val = data.readInt(); break;
                    case 3:  key = "EpsEnable";          val = data.readInt(); break;
                    case 4:  key = "FcwState";           val = data.readInt(); break;
                    case 5:  key = "FcwAutoBrake";       val = data.readInt(); break;
                    case 6:  key = "FcwSensitivity";     val = data.readInt(); break;
                    case 7:  key = "AebState";           val = data.readInt(); break;
                    case 8:  key = "AebPedestrian";      val = data.readInt(); break;
                    case 9:  key = "FcwSystemStatus";    val = data.readInt(); break;
                    case 10: key = "LasMode";            val = data.readInt(); break;
                    case 11: key = "LasSensitivity";     val = data.readInt(); break;
                    case 12: key = "LasVibration";       val = data.readInt(); break;
                    case 13: key = "LasSound";           val = data.readInt(); break;
                    case 14: key = "HdcStatus";          val = data.readInt(); break;
                    case 15: key = "PaStatus";           val = data.readInt(); break;
                    case 16: key = "RdaState";           val = data.readInt(); break;
                    case 17: key = "BsdState";           val = data.readInt(); break;
                    case 18: key = "DowState";           val = data.readInt(); break;
                    case 19: key = "LcaState";           val = data.readInt(); break;
                    case 20: key = "RctaState";          val = data.readInt(); break;
                    case 21: key = "SlifWarning";        val = data.readInt(); break;
                    case 22: key = "SasMode";            val = data.readInt(); break;
                    case 23: key = "ScsState";           val = data.readInt(); break;
                    case 24: key = "TjaMode";            val = data.readInt(); break;
                    case 25: key = "ChargingMode";       val = data.readInt(); break;
                    case 26: key = "DrivingMode";        val = data.readInt(); break;
                    case 27: key = "AmbientState";       val = data.readInt(); break;
                    case 28: key = "AmbientDrivingMode"; val = data.readInt(); break;
                    case 29: key = "AmbientIgnition";    val = data.readInt(); break;
                    case 30: key = "AmbientWelcome";     val = data.readInt(); break;
                    case 31: key = "AmbientWelcomeMode"; val = data.readInt(); break;
                    case 32: key = "AmbientCustom";      val = data.readInt(); break;
                    case 33: key = "AmbientBreath";      val = data.readInt(); break;
                    case 34: key = "AmbientBrightness";  val = data.readInt(); break;
                    case 35: key = "AmbientColor";       val = data.readInt(); break;
                    case 36: key = "PowertrainMode";     val = data.readInt(); break;
                    case 37: key = "DrivingEpsMode";     val = data.readInt(); break;
                    case 38: key = "DrivingAmbientMode"; val = data.readInt(); break;
                    case 39: key = "DrivingAcMode";      val = data.readInt(); break;
                    case 40: key = "TailgateHeight";     val = data.readFloat(); break;
                    case 41: key = "TirePressure";       val = new int[]{data.readInt(), data.readInt()}; break;
                    case 42: key = "TirePressureFloat";  val = new float[]{data.readInt(), data.readFloat()}; break;
                    case 43: key = "TireTemp";           val = new int[]{data.readInt(), data.readInt()}; break;
                    case 44: key = "TireStatus";         val = new int[]{data.readInt(), data.readInt()}; break;
                    case 45: key = "TpmsStatus";         val = data.readInt(); break;
                    case 46: key = "TpmsAutoLocation";   val = data.readInt() != 0; break;
                    case 47: key = "TpmsFault";          val = data.readInt() != 0; break;
                    case 48: key = "TpmsLearning";       val = data.readInt(); break;
                    case 49: key = "TpmsTelltale";       val = data.readInt(); break;
                    case 50: key = "TpmsWinter";         val = data.readInt(); break;
                    case 51: key = "FollowMeHome";       val = data.readInt(); break;
                    case 52: key = "FindMyCar";          val = data.readInt(); break;
                    case 53: key = "SingleEntry";        val = data.readInt(); break;
                    case 54: key = "PassiveEntry";       val = data.readInt(); break;
                    case 55: key = "MirrorFold";         val = data.readInt(); break;
                    case 56: key = "WelcomeLight";       val = data.readInt(); break;
                    case 57: key = "AutoLock";           val = data.readInt(); break;
                    case 58: key = "AutoUnlock";         val = data.readInt(); break;
                    case 59: key = "IntelligentHeadlamp";val = data.readInt(); break;
                    case 60: key = "LockFeedback";       val = data.readInt(); break;
                    default: return false;
                }
                if (reply != null) reply.writeNoException();
                if (listener != null) listener.onVehicleSetting(key, val);
                return true;
            }
        };
    }
}
