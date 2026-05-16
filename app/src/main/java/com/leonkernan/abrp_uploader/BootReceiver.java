package com.leonkernan.abrp_uploader;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;

public class BootReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(Context context, Intent intent) {
        if (!Intent.ACTION_BOOT_COMPLETED.equals(intent.getAction())) return;

        SharedPreferences prefs = context.getSharedPreferences("abrp_prefs", Context.MODE_PRIVATE);
        boolean enabled = prefs.getBoolean("service_enabled", false);
        String token = prefs.getString("token", "").trim();

        if (enabled && !token.isEmpty()) {
            context.startForegroundService(new Intent(context, AbrpUploadService.class));
        }
    }
}
