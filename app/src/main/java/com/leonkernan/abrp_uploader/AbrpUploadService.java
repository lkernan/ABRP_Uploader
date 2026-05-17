package com.leonkernan.abrp_uploader;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.content.SharedPreferences;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.util.Log;

import androidx.core.app.NotificationCompat;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Foreground service that polls vehicle telemetry every 5 s and pushes it to ABRP.
 *
 * Reliability design:
 *  - Uses ScheduledExecutorService (not Handler chains) so exceptions inside one
 *    upload cycle cannot break the schedule. Even on a thrown RuntimeException
 *    the next tick still fires.
 *  - Uploads unconditionally: if the car adapter never connects we still emit
 *    GPS + timestamp so ABRP at least sees the vehicle online.
 *  - Car adapter (re-)connection is retried every 30 s by the scheduler itself
 *    if {@link CarPropertyAdapter#isConnected()} is false. This handles the
 *    case where the underlying android.car.Car createCar() / connect() call
 *    fails silently with no listener callback.
 *  - All IPC + HTTP happens on the scheduler thread, never the main thread.
 */
public class AbrpUploadService extends Service {

    public static final String ACTION_STOP = "com.leonkernan.abrp_uploader.STOP";

    private static final String TAG             = "AbrpUploadService";
    private static final String CHANNEL_ID      = "abrp_uploader";
    private static final int    NOTIF_ID        = 1;
    private static final long   UPLOAD_INTERVAL_SEC = 5;
    private static final long   CAR_RECONNECT_INTERVAL_SEC = 30;
    private static final String API_URL         = "https://api.iternio.com/1/tlm/send";

    // Set from the main thread (LocationListener uses Main looper), read from
    // the scheduler thread inside doUpload. volatile is sufficient since Location
    // is effectively immutable for our purposes.
    private volatile Location lastLocation;

    private CarPropertyAdapter   carAdapter;
    private LocationManager      locationManager;
    private ScheduledExecutorService scheduler;
    private SharedPreferences    prefs;
    private Handler              mainHandler;

    private volatile long lastSuccessfulUploadMs = 0L;
    private volatile long lastCarConnectAttemptMs = 0L;

    // ---------- Lifecycle ----------

    @Override
    public void onCreate() {
        super.onCreate();
        prefs       = getSharedPreferences("abrp_prefs", MODE_PRIVATE);
        mainHandler = new Handler(Looper.getMainLooper());
        scheduler   = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "abrp-upload");
            t.setDaemon(false);
            return t;
        });

        createNotificationChannel();
        startForeground(NOTIF_ID, buildNotification("Starting…"));
        prefs.edit().putBoolean("service_running", true).apply();

        connectCarAdapter();
        requestLocationUpdates();

        // Fire the first upload after a short warm-up, then every UPLOAD_INTERVAL_SEC.
        // scheduleWithFixedDelay ensures we always get at least UPLOAD_INTERVAL_SEC
        // between cycles even if a previous upload was slow.
        scheduler.scheduleWithFixedDelay(
                this::safeUploadCycle,
                3, UPLOAD_INTERVAL_SEC, TimeUnit.SECONDS);

        Log.i(TAG, "Service started, upload scheduler armed");
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_STOP.equals(intent.getAction())) {
            prefs.edit().putBoolean("service_enabled", false).apply();
            stopSelf();
            return START_NOT_STICKY;
        }
        return START_STICKY;
    }

    @Override
    public IBinder onBind(Intent intent) { return null; }

    @Override
    public void onDestroy() {
        Log.i(TAG, "Service stopping");
        if (scheduler != null) scheduler.shutdownNow();
        if (carAdapter != null) carAdapter.disconnect();
        if (locationManager != null) {
            try { locationManager.removeUpdates(locationListener); } catch (Exception ignored) {}
        }
        if (mainHandler != null) mainHandler.removeCallbacksAndMessages(null);
        prefs.edit().putBoolean("service_running", false).apply();
        super.onDestroy();
    }

    // ---------- Car adapter ----------

    private void connectCarAdapter() {
        lastCarConnectAttemptMs = System.currentTimeMillis();
        // Must run on main thread because ServiceConnection callbacks need a Looper.
        mainHandler.post(() -> {
            if (carAdapter != null) {
                try { carAdapter.disconnect(); } catch (Exception ignored) {}
            }
            carAdapter = new CarPropertyAdapter(new CarPropertyAdapter.Listener() {
                @Override
                public void onConnected() {
                    Log.i(TAG, "CarPropertyAdapter connected");
                }
                @Override
                public void onDisconnected() {
                    Log.w(TAG, "CarPropertyAdapter disconnected — scheduler will retry");
                    // Reconnect is handled by the scheduler watchdog.
                }
            });
            try {
                carAdapter.connect(AbrpUploadService.this);
            } catch (Throwable t) {
                Log.e(TAG, "carAdapter.connect threw", t);
            }
        });
    }

    // ---------- Upload cycle (runs on scheduler thread) ----------

    private void safeUploadCycle() {
        try {
            // Self-watchdog: if the car adapter looks dead and we haven't tried
            // reconnecting recently, kick a reconnect.
            if ((carAdapter == null || !carAdapter.isConnected())
                    && System.currentTimeMillis() - lastCarConnectAttemptMs
                       > CAR_RECONNECT_INTERVAL_SEC * 1000) {
                Log.w(TAG, "Car adapter not connected, attempting reconnect");
                connectCarAdapter();
            }
            doUpload();
        } catch (Throwable t) {
            // Absolutely never let an exception escape — would not break the
            // schedule with scheduleWithFixedDelay's executor, but we want a
            // clean log line anyway.
            Log.e(TAG, "upload cycle threw", t);
        }
    }

    private void doUpload() {
        String token  = prefs.getString("token",   "").trim();
        String apiKey = prefs.getString("api_key", "").trim();

        if (token.isEmpty()) {
            updateNotification("No ABRP token — open app to configure");
            return;
        }
        if (apiKey.isEmpty()) {
            updateNotification("No API key — open app to configure");
            return;
        }

        // Read whatever the car will give us. Each getter has internal try/catch
        // and returns 0 if the call fails — we keep going regardless.
        boolean carUp = carAdapter != null && carAdapter.isConnected();

        float speedKmh = carUp ? carAdapter.getFloatProperty(
                CarPropertyAdapter.PROP_VEHICLE_SPEED,
                CarPropertyAdapter.PROP_AREA_GLOBAL) : 0f;

        int soc = carUp ? Math.round(carAdapter.getFloatProperty(
                CarPropertyAdapter.PROP_EV_BATTERY_PCT,
                CarPropertyAdapter.PROP_AREA_GLOBAL)) : 0;

        int rangeKm = carUp ? carAdapter.getIntProperty(
                CarPropertyAdapter.PROP_EV_RANGE_KM,
                CarPropertyAdapter.PROP_AREA_GLOBAL) : 0;

        float extTemp = carUp ? carAdapter.getFloatProperty(
                CarPropertyAdapter.PROP_OUTSIDE_TEMP,
                CarPropertyAdapter.PROP_AREA_HVAC) : 0f;

        long utc = System.currentTimeMillis() / 1000;
        Location loc = lastLocation;

        // Build telemetry JSON. Always include utc; only include vehicle-derived
        // fields if we actually have a live car link (avoid blasting zeros).
        StringBuilder tlm = new StringBuilder("{\"utc\":").append(utc);
        if (carUp) {
            tlm.append(",\"soc\":").append(soc)
               .append(",\"speed\":").append(Math.round(speedKmh))
               .append(",\"est_battery_range\":").append(rangeKm)
               .append(",\"ext_temp\":").append(Math.round(extTemp));
        }
        if (loc != null) {
            tlm.append(",\"lat\":").append(loc.getLatitude());
            tlm.append(",\"lon\":").append(loc.getLongitude());
            if (loc.hasAltitude())
                tlm.append(",\"elevation\":").append(Math.round(loc.getAltitude()));
            if (loc.hasBearing())
                tlm.append(",\"heading\":").append(Math.round(loc.getBearing()));
        }
        tlm.append("}");

        sendToAbrp(apiKey, token, tlm.toString(), soc, speedKmh, carUp);
    }

    private void sendToAbrp(String apiKey, String token, String tlmJson,
                            int soc, float speedKmh, boolean carUp) {
        HttpURLConnection conn = null;
        try {
            String urlStr = API_URL
                    + "?api_key=" + URLEncoder.encode(apiKey, StandardCharsets.UTF_8.name())
                    + "&token="   + URLEncoder.encode(token,  StandardCharsets.UTF_8.name())
                    + "&tlm="     + URLEncoder.encode(tlmJson, StandardCharsets.UTF_8.name());

            conn = (HttpURLConnection) new URL(urlStr).openConnection();
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(8_000);
            conn.setReadTimeout(8_000);

            int code = conn.getResponseCode();
            BufferedReader br = new BufferedReader(new InputStreamReader(
                    code == 200 ? conn.getInputStream() : conn.getErrorStream()));
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = br.readLine()) != null) sb.append(line);
            br.close();

            Log.d(TAG, "ABRP [" + code + "]: " + sb);

            if (code == 200) {
                lastSuccessfulUploadMs = System.currentTimeMillis();
                String time = android.text.format.DateFormat
                        .format("HH:mm:ss", lastSuccessfulUploadMs).toString();
                prefs.edit()
                        .putString("last_upload_time",   time)
                        .putString("last_upload_status", "OK")
                        .apply();
                String detail = carUp
                        ? ("SOC " + soc + "% · " + Math.round(speedKmh) + " km/h · " + time)
                        : ("GPS only · " + time);
                updateNotification(detail);
            } else {
                prefs.edit().putString("last_upload_status", "HTTP " + code).apply();
                updateNotification("Upload error: HTTP " + code);
            }
        } catch (java.net.UnknownHostException e) {
            prefs.edit().putString("last_upload_status", "No internet").apply();
            updateNotification("Offline — will retry");
        } catch (Exception e) {
            Log.e(TAG, "Upload failed", e);
            prefs.edit().putString("last_upload_status",
                    e.getClass().getSimpleName() + ": " + e.getMessage()).apply();
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    // ---------- Location ----------

    private final LocationListener locationListener = new LocationListener() {
        @Override public void onLocationChanged(Location location) { lastLocation = location; }
        @Override public void onStatusChanged(String p, int s, Bundle e) {}
        @Override public void onProviderEnabled(String p) {}
        @Override public void onProviderDisabled(String p) {}
    };

    private void requestLocationUpdates() {
        try {
            locationManager = (LocationManager) getSystemService(LOCATION_SERVICE);
            if (locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
                locationManager.requestLocationUpdates(
                        LocationManager.GPS_PROVIDER, 3_000, 0f,
                        locationListener, Looper.getMainLooper());
                Location last = locationManager.getLastKnownLocation(LocationManager.GPS_PROVIDER);
                if (last != null) lastLocation = last;
            }
        } catch (SecurityException e) {
            Log.w(TAG, "Location permission not granted: " + e.getMessage());
        } catch (Exception e) {
            Log.w(TAG, "Location unavailable: " + e.getMessage());
        }
    }

    // ---------- Notification ----------

    private void createNotificationChannel() {
        NotificationChannel ch = new NotificationChannel(
                CHANNEL_ID,
                getString(R.string.notif_channel_name),
                NotificationManager.IMPORTANCE_LOW);
        ch.setDescription(getString(R.string.notif_channel_desc));
        getSystemService(NotificationManager.class).createNotificationChannel(ch);
    }

    private Notification buildNotification(String status) {
        PendingIntent openApp = PendingIntent.getActivity(this, 0,
                new Intent(this, MainActivity.class),
                PendingIntent.FLAG_IMMUTABLE);

        Intent stopIntent = new Intent(this, AbrpUploadService.class);
        stopIntent.setAction(ACTION_STOP);
        PendingIntent stopPi = PendingIntent.getService(this, 1,
                stopIntent, PendingIntent.FLAG_IMMUTABLE);

        return new NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.mipmap.ic_launcher)
                .setContentTitle(getString(R.string.notif_title))
                .setContentText(status)
                .setContentIntent(openApp)
                .addAction(android.R.drawable.ic_delete, getString(R.string.notif_action_stop), stopPi)
                .setOngoing(true)
                .build();
    }

    private void updateNotification(String status) {
        getSystemService(NotificationManager.class).notify(NOTIF_ID, buildNotification(status));
    }
}
