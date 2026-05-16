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
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class AbrpUploadService extends Service {

    public static final String ACTION_STOP = "com.leonkernan.abrp_uploader.STOP";

    private static final String TAG = "AbrpUploadService";
    private static final String CHANNEL_ID = "abrp_uploader";
    private static final int NOTIF_ID = 1;
    private static final long UPLOAD_INTERVAL_MS = 5_000;
    private static final String API_URL = "https://api.iternio.com/1/tlm/send";

    private SaicCarAdapter carAdapter;
    private LocationManager locationManager;
    private Location lastLocation;
    private Handler handler;
    private ExecutorService executor;
    private SharedPreferences prefs;

    // ---------- Lifecycle ----------

    @Override
    public void onCreate() {
        super.onCreate();
        prefs = getSharedPreferences("abrp_prefs", MODE_PRIVATE);
        handler = new Handler(Looper.getMainLooper());
        executor = Executors.newSingleThreadExecutor();

        createNotificationChannel();
        startForeground(NOTIF_ID, buildNotification("Connecting to car…"));

        prefs.edit().putBoolean("service_running", true).apply();

        connectCarAdapter();
        requestLocationUpdates();
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
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        executor.shutdown();
        if (carAdapter != null) {
            carAdapter.unregisterAllListeners();
            carAdapter.disconnect();
        }
        if (locationManager != null) {
            try { locationManager.removeUpdates(locationListener); } catch (Exception ignored) {}
        }
        prefs.edit().putBoolean("service_running", false).apply();
        super.onDestroy();
    }

    // ---------- Car adapter ----------

    private void connectCarAdapter() {
        if (carAdapter != null) {
            carAdapter.disconnect();
        }
        carAdapter = new SaicCarAdapter(new SaicCarAdapter.Listener() {
            @Override
            public void onConnected(boolean success) {
                if (success) {
                    carAdapter.registerAllListeners();
                    scheduleUploads();
                    updateNotification("Running");
                } else {
                    updateNotification("Car service unavailable — retrying in 30s");
                    handler.postDelayed(() -> connectCarAdapter(), 30_000);
                }
            }

            @Override
            public void onDisconnected() {
                handler.removeCallbacks(uploadRunnable);
                updateNotification("Car disconnected — retrying in 15s");
                handler.postDelayed(() -> connectCarAdapter(), 15_000);
            }

            @Override public void onHvac(String key, Object value) {}
            @Override public void onEv(String key, Object value) {}
            @Override public void onGeneral(String key, Object value) {}
            @Override public void onState(String key, Object value) {}
            @Override public void onAudio(String key, Object value) {}
            @Override public void onVehicleSetting(String key, Object value) {}
        });
        carAdapter.connect(this);
    }

    // ---------- Upload loop ----------

    private final Runnable uploadRunnable = new Runnable() {
        @Override
        public void run() {
            doUpload();
            handler.postDelayed(this, UPLOAD_INTERVAL_MS);
        }
    };

    private void scheduleUploads() {
        handler.removeCallbacks(uploadRunnable);
        handler.post(uploadRunnable);
    }

    private void doUpload() {
        String token = prefs.getString("token", "").trim();
        if (token.isEmpty()) {
            updateNotification("No ABRP token — open app to configure");
            return;
        }
        if (carAdapter == null || !carAdapter.isConnected()) return;

        int soc          = carAdapter.getBatteryPercentage();
        float speedKmh   = carAdapter.getSpeed();
        int rangeKm      = carAdapter.getCurrentRange();
        int chargeStatus = carAdapter.getChargeStatus();
        float extTemp    = carAdapter.getOutsideTemperature();
        long utc         = System.currentTimeMillis() / 1000;

        StringBuilder tlm = new StringBuilder()
                .append("{\"utc\":").append(utc)
                .append(",\"soc\":").append(soc)
                .append(",\"speed\":").append(Math.round(speedKmh))
                .append(",\"is_charging\":").append(chargeStatus != 0 ? 1 : 0)
                .append(",\"est_battery_range\":").append(rangeKm)
                .append(",\"ext_temp\":").append(Math.round(extTemp));

        if (lastLocation != null) {
            tlm.append(",\"lat\":").append(lastLocation.getLatitude());
            tlm.append(",\"lon\":").append(lastLocation.getLongitude());
            if (lastLocation.hasAltitude())
                tlm.append(",\"elevation\":").append(Math.round(lastLocation.getAltitude()));
            if (lastLocation.hasBearing())
                tlm.append(",\"heading\":").append(Math.round(lastLocation.getBearing()));
        }
        tlm.append("}");

        String tlmJson = tlm.toString();

        executor.execute(() -> {
            HttpURLConnection conn = null;
            try {
                String urlStr = API_URL
                        + "?token=" + URLEncoder.encode(token, StandardCharsets.UTF_8.name())
                        + "&tlm=" + URLEncoder.encode(tlmJson, StandardCharsets.UTF_8.name());

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
                    String time = android.text.format.DateFormat
                            .format("HH:mm:ss", System.currentTimeMillis()).toString();
                    prefs.edit()
                            .putString("last_upload_time", time)
                            .putString("last_upload_status", "OK")
                            .apply();
                    updateNotification("SOC " + soc + "% · " + Math.round(speedKmh) + " km/h · " + time);
                } else {
                    prefs.edit().putString("last_upload_status", "HTTP " + code).apply();
                    updateNotification("Upload error: HTTP " + code);
                }
            } catch (Exception e) {
                Log.e(TAG, "Upload failed: " + e.getMessage());
                prefs.edit().putString("last_upload_status", e.getMessage()).apply();
            } finally {
                if (conn != null) conn.disconnect();
            }
        });
    }

    // ---------- Location ----------

    private final LocationListener locationListener = new LocationListener() {
        @Override
        public void onLocationChanged(Location location) {
            lastLocation = location;
        }
        @Override public void onStatusChanged(String provider, int status, Bundle extras) {}
        @Override public void onProviderEnabled(String provider) {}
        @Override public void onProviderDisabled(String provider) {}
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
