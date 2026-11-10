package com.arda.updater;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInstaller;

public final class InstallResultReceiver extends BroadcastReceiver {
    static final String ACTION_INSTALL_RESULT = "com.arda.updater.action.INSTALL_RESULT";
    static final String ACTION_STATE_CHANGED = "com.arda.updater.action.STATE_CHANGED";

    @Override
    public void onReceive(Context context, Intent intent) {
        int status = intent.getIntExtra(
                PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE);
        String message = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE);
        long serial = intent.getLongExtra("serial", 0L);
        String release = intent.getStringExtra("release");
        boolean rebootRequired = intent.getBooleanExtra("reboot_required", true);

        android.content.SharedPreferences p =
                context.getSharedPreferences("state", Context.MODE_PRIVATE);

        if (intent.getBooleanExtra("staged", false)) {
            // Callback success is NOT application success: wait for READY + reboot +
            // installed version/signer/SHA verification in MainActivity.
            if (status == PackageInstaller.STATUS_SUCCESS) {
                p.edit().putString("last_result", "Staged session accepted."
                        + " Open updater and tap Check for reboot readiness.").apply();
            } else {
                p.edit().putString("last_result", "Staged session callback: status="
                        + status + " " + (message == null ? "" : message)).apply();
                // Keep pending session ID for UI forensic inspection; never reboot.
            }
            context.sendBroadcast(new Intent(ACTION_STATE_CHANGED)
                    .setPackage(context.getPackageName()));
            return;
        }

        if (status == PackageInstaller.STATUS_SUCCESS) {
            p.edit()
                    .putLong("accepted_serial", serial)
                    .putString("installed_release", release == null ? "" : release)
                    .putBoolean("reboot_required", rebootRequired)
                    .putString("last_result", "Install successful")
                    .apply();
        } else {
            p.edit()
                    .putString("last_result",
                            "Install failed: status=" + status + " " + (message == null ? "" : message))
                    .apply();
        }

        Intent changed = new Intent(ACTION_STATE_CHANGED)
                .setPackage(context.getPackageName());
        context.sendBroadcast(changed);
    }
}
