package com.leonkernan.abrp_uploader;

import android.Manifest;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import com.google.android.material.switchmaterial.SwitchMaterial;
import com.google.android.material.textfield.TextInputEditText;
import com.google.android.material.textfield.TextInputLayout;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

public class MainActivity extends AppCompatActivity {

    private static final int LOCATION_PERMISSION_REQUEST = 100;

    private static final int COLOR_OK      = 0xFF4CAF50; // green
    private static final int COLOR_ERROR   = 0xFFF44336; // red
    private static final int COLOR_PENDING = 0xFF9E9E9E; // grey

    private TextInputLayout   apiKeyLayout;
    private TextInputEditText apiKeyInput;
    private TextInputLayout   tokenLayout;
    private TextInputEditText tokenInput;
    private SwitchMaterial    serviceSwitch;
    private TextView          statusText;
    private Button            testButton;
    private View              connectionStatusRow;
    private View              connectionIndicator;
    private TextView          connectionStatusText;
    private SharedPreferences prefs;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        prefs = getSharedPreferences("abrp_prefs", MODE_PRIVATE);

        apiKeyLayout        = findViewById(R.id.api_key_layout);
        apiKeyInput         = findViewById(R.id.api_key_input);
        tokenLayout         = findViewById(R.id.token_layout);
        tokenInput          = findViewById(R.id.token_input);
        serviceSwitch       = findViewById(R.id.service_switch);
        statusText          = findViewById(R.id.status_text);
        testButton          = findViewById(R.id.test_button);
        connectionStatusRow = findViewById(R.id.connection_status_row);
        connectionIndicator = findViewById(R.id.connection_indicator);
        connectionStatusText = findViewById(R.id.connection_status_text);

        apiKeyInput.setText(prefs.getString("api_key", ""));
        tokenInput.setText(prefs.getString("token", ""));
        serviceSwitch.setChecked(prefs.getBoolean("service_enabled", false));

        // If the user wants the service running, kick it on every activity launch.
        // startForegroundService is idempotent — if the service is already up this
        // is a no-op aside from delivering a new intent. We don't trust
        // service_running as a sole indicator because it can be stale-true if a
        // previous process was force-killed (e.g. by `adb install -r`) without
        // onDestroy running.
        boolean enabled = prefs.getBoolean("service_enabled", false);
        boolean haveCreds = !prefs.getString("token",   "").trim().isEmpty()
                         && !prefs.getString("api_key", "").trim().isEmpty();
        if (enabled && haveCreds) {
            startForegroundService(new Intent(this, AbrpUploadService.class));
            requestLocationPermissionIfNeeded();
        }

        findViewById(R.id.save_button).setOnClickListener(v -> saveCredentials());
        testButton.setOnClickListener(v -> testConnection());

        serviceSwitch.setOnCheckedChangeListener((btn, checked) -> {
            if (checked) {
                String apiKey = textOf(apiKeyInput);
                String token  = textOf(tokenInput);
                if (apiKey.isEmpty() || token.isEmpty()) {
                    serviceSwitch.setChecked(false);
                    if (apiKey.isEmpty()) apiKeyLayout.setError(getString(R.string.api_key_required));
                    if (token.isEmpty())  tokenLayout.setError(getString(R.string.token_required));
                    return;
                }
                apiKeyLayout.setError(null);
                tokenLayout.setError(null);
                prefs.edit().putBoolean("service_enabled", true).apply();
                startForegroundService(new Intent(this, AbrpUploadService.class));
                requestLocationPermissionIfNeeded();
            } else {
                prefs.edit().putBoolean("service_enabled", false).apply();
                stopService(new Intent(this, AbrpUploadService.class));
            }
            refreshStatus();
        });
    }

    @Override
    protected void onResume() {
        super.onResume();
        apiKeyInput.setText(prefs.getString("api_key", ""));
        tokenInput.setText(prefs.getString("token", ""));
        serviceSwitch.setChecked(prefs.getBoolean("service_enabled", false));
        refreshStatus();
    }

    // ---------- Credentials ----------

    private void saveCredentials() {
        String apiKey = textOf(apiKeyInput);
        String token  = textOf(tokenInput);
        boolean valid = true;

        if (apiKey.isEmpty()) {
            apiKeyLayout.setError(getString(R.string.api_key_required));
            valid = false;
        } else {
            apiKeyLayout.setError(null);
        }
        if (token.isEmpty()) {
            tokenLayout.setError(getString(R.string.token_required));
            valid = false;
        } else {
            tokenLayout.setError(null);
        }
        if (!valid) return;

        prefs.edit()
                .putString("api_key", apiKey)
                .putString("token",   token)
                .apply();

        if (serviceSwitch.isChecked()) {
            startForegroundService(new Intent(this, AbrpUploadService.class));
        }
        refreshStatus();
    }

    // ---------- Connection test ----------

    private void testConnection() {
        String apiKey = textOf(apiKeyInput);
        String token  = textOf(tokenInput);

        if (apiKey.isEmpty() || token.isEmpty()) {
            if (apiKey.isEmpty()) apiKeyLayout.setError(getString(R.string.api_key_required));
            if (token.isEmpty())  tokenLayout.setError(getString(R.string.token_required));
            return;
        }

        setConnectionStatus(COLOR_PENDING, getString(R.string.conn_testing));
        testButton.setEnabled(false);

        new Thread(() -> {
            String result = pingAbrp(apiKey, token);
            runOnUiThread(() -> {
                testButton.setEnabled(true);
                if (result == null) {
                    setConnectionStatus(COLOR_OK, getString(R.string.conn_ok));
                } else {
                    setConnectionStatus(COLOR_ERROR, result);
                }
            });
        }).start();
    }

    /**
     * Sends a minimal test request to the ABRP API.
     * Returns null on success, or a short error string on failure.
     */
    private String pingAbrp(String apiKey, String token) {
        HttpURLConnection conn = null;
        try {
            // Minimal valid telemetry payload
            String tlm = "{\"utc\":" + (System.currentTimeMillis() / 1000) + ",\"soc\":0}";
            String urlStr = "https://api.iternio.com/1/tlm/send"
                    + "?api_key=" + URLEncoder.encode(apiKey, StandardCharsets.UTF_8.name())
                    + "&token="   + URLEncoder.encode(token,  StandardCharsets.UTF_8.name())
                    + "&tlm="     + URLEncoder.encode(tlm,    StandardCharsets.UTF_8.name());

            conn = (HttpURLConnection) new URL(urlStr).openConnection();
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(8_000);
            conn.setReadTimeout(8_000);

            int code = conn.getResponseCode();
            if (code == 200) return null;

            // Read error body for a more helpful message
            BufferedReader br = new BufferedReader(
                    new InputStreamReader(conn.getErrorStream()));
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = br.readLine()) != null) sb.append(line);
            br.close();

            if (code == 401) return getString(R.string.conn_err_auth);
            return getString(R.string.conn_err_http, code);

        } catch (java.net.UnknownHostException e) {
            return getString(R.string.conn_err_no_internet);
        } catch (java.net.SocketTimeoutException e) {
            return getString(R.string.conn_err_timeout);
        } catch (Exception e) {
            return e.getMessage();
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    private void setConnectionStatus(int color, String message) {
        connectionStatusRow.setVisibility(View.VISIBLE);
        connectionIndicator.getBackground().setTint(color);
        connectionStatusText.setText(message);
        connectionStatusText.setTextColor(color);
    }

    // ---------- Service status ----------

    private void refreshStatus() {
        boolean running = prefs.getBoolean("service_running", false);
        boolean enabled = prefs.getBoolean("service_enabled", false);

        if (enabled && running) {
            String lastTime   = prefs.getString("last_upload_time",   null);
            String lastStatus = prefs.getString("last_upload_status", null);
            if (lastTime != null) {
                statusText.setText(getString(R.string.status_last_upload, lastTime,
                        "OK".equals(lastStatus) ? getString(R.string.status_ok) : lastStatus));
            } else {
                statusText.setText(R.string.status_running_no_upload);
            }
        } else if (enabled) {
            statusText.setText(R.string.status_starting);
        } else {
            statusText.setText(R.string.status_stopped);
        }
    }

    // ---------- Helpers ----------

    private String textOf(TextInputEditText field) {
        CharSequence text = field.getText();
        return text != null ? text.toString().trim() : "";
    }

    /**
     * Permissions that need a runtime grant (vs being granted at install time
     * via the platform signature). CAR_SPEED and CAR_ENERGY are dangerous-level
     * AAOS permissions — without these, the corresponding property reads throw
     * SecurityException and we have no SOC / speed data.
     */
    private static final String[] RUNTIME_PERMISSIONS = {
            Manifest.permission.ACCESS_FINE_LOCATION,
            "android.car.permission.CAR_SPEED",
            "android.car.permission.CAR_ENERGY",
    };

    private void requestLocationPermissionIfNeeded() {
        java.util.List<String> missing = new java.util.ArrayList<>();
        for (String p : RUNTIME_PERMISSIONS) {
            if (ContextCompat.checkSelfPermission(this, p)
                    != PackageManager.PERMISSION_GRANTED) {
                missing.add(p);
            }
        }
        if (!missing.isEmpty()) {
            ActivityCompat.requestPermissions(this,
                    missing.toArray(new String[0]),
                    LOCATION_PERMISSION_REQUEST);
        }
    }
}
