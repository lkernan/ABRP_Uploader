package com.leonkernan.abrp_uploader;

import android.Manifest;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.widget.Button;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import com.google.android.material.switchmaterial.SwitchMaterial;
import com.google.android.material.textfield.TextInputEditText;
import com.google.android.material.textfield.TextInputLayout;

public class MainActivity extends AppCompatActivity {

    private static final int LOCATION_PERMISSION_REQUEST = 100;

    private TextInputLayout tokenLayout;
    private TextInputEditText tokenInput;
    private SwitchMaterial serviceSwitch;
    private TextView statusText;
    private SharedPreferences prefs;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        prefs = getSharedPreferences("abrp_prefs", MODE_PRIVATE);

        tokenLayout = findViewById(R.id.token_layout);
        tokenInput = findViewById(R.id.token_input);
        serviceSwitch = findViewById(R.id.service_switch);
        statusText = findViewById(R.id.status_text);
        Button saveButton = findViewById(R.id.save_button);

        tokenInput.setText(prefs.getString("token", ""));
        serviceSwitch.setChecked(prefs.getBoolean("service_enabled", false));

        saveButton.setOnClickListener(v -> saveToken());

        serviceSwitch.setOnCheckedChangeListener((btn, checked) -> {
            if (checked) {
                String token = currentToken();
                if (token.isEmpty()) {
                    serviceSwitch.setChecked(false);
                    tokenLayout.setError(getString(R.string.token_required));
                    return;
                }
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
        tokenInput.setText(prefs.getString("token", ""));
        serviceSwitch.setChecked(prefs.getBoolean("service_enabled", false));
        refreshStatus();
    }

    private void saveToken() {
        String token = currentToken();
        if (token.isEmpty()) {
            tokenLayout.setError(getString(R.string.token_required));
            return;
        }
        tokenLayout.setError(null);
        prefs.edit().putString("token", token).apply();

        if (serviceSwitch.isChecked()) {
            startForegroundService(new Intent(this, AbrpUploadService.class));
        }
        refreshStatus();
    }

    private String currentToken() {
        CharSequence text = tokenInput.getText();
        return text != null ? text.toString().trim() : "";
    }

    private void refreshStatus() {
        boolean running = prefs.getBoolean("service_running", false);
        boolean enabled = prefs.getBoolean("service_enabled", false);

        if (enabled && running) {
            String lastTime = prefs.getString("last_upload_time", null);
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

    private void requestLocationPermissionIfNeeded() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
                != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this,
                    new String[]{Manifest.permission.ACCESS_FINE_LOCATION},
                    LOCATION_PERMISSION_REQUEST);
        }
    }
}
