package com.arda.updater;

import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Bundle;
import android.os.PowerManager;
import android.graphics.Insets;
import android.view.View;
import android.view.WindowInsets;
import android.widget.Button;
import android.widget.ProgressBar;
import android.widget.TextView;

import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class MainActivity extends Activity {
    private final ExecutorService executor = Executors.newSingleThreadExecutor();

    private TextView status;
    private Button check;
    private Button install;
    private Button reboot;
    private ProgressBar downloadProgress;

    private UpdateModels.Plan currentPlan;
    private boolean receiverRegistered;

    private final BroadcastReceiver stateReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            refreshPersistentState();
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(com.arda.updater.R.layout.activity_main);
        applyHorizontalSystemBarInsets();

        status = findViewById(R.id.status);
        check = findViewById(R.id.check);
        install = findViewById(R.id.install);
        reboot = findViewById(R.id.reboot);
        downloadProgress = findViewById(R.id.download_progress);

        check.setOnClickListener(v -> {
            if (!refreshStagedState()) checkForUpdates();
        });
        install.setOnClickListener(v -> installCurrentPlan());
        reboot.setOnClickListener(v -> {
            android.content.SharedPreferences prefs = getSharedPreferences("state", MODE_PRIVATE);
            int id = prefs.getInt("staged_session_id", -1);
            if (id >= 0) {
                android.content.pm.PackageInstaller.SessionInfo si =
                        getPackageManager().getPackageInstaller().getSessionInfo(id);
                if (si == null || !si.isStaged() || !si.isStagedSessionReady()) {
                    refreshPersistentState();
                    return; // Never reboot a staged update before Android reports READY.
                }
            }
            PowerManager pm = getSystemService(PowerManager.class);
            pm.reboot("arda-update");
        });

        refreshPersistentState();
    }

    private void applyHorizontalSystemBarInsets() {
        final View content = findViewById(android.R.id.content);
        final int baseLeft = content.getPaddingLeft();
        final int baseTop = content.getPaddingTop();
        final int baseRight = content.getPaddingRight();
        final int baseBottom = content.getPaddingBottom();

        content.setOnApplyWindowInsetsListener((view, windowInsets) -> {
            Insets systemBars = windowInsets.getInsets(
                    WindowInsets.Type.statusBars()
                            | WindowInsets.Type.navigationBars());

            // Horizontal rail sizes come from runtime WindowInsets and may vary
            // by HU/profile. Projection top/bottom safe-area is handled separately;
            // do not duplicate top/bottom values here.
            view.setPadding(
                    baseLeft + systemBars.left,
                    baseTop,
                    baseRight + systemBars.right,
                    baseBottom);
            return windowInsets;
        });

        content.requestApplyInsets();
    }

    @Override
    protected void onStart() {
        super.onStart();
        if (!receiverRegistered) {
            registerReceiver(
                    stateReceiver,
                    new IntentFilter(InstallResultReceiver.ACTION_STATE_CHANGED),
                    Context.RECEIVER_NOT_EXPORTED);
            receiverRegistered = true;
        }
        refreshPersistentState();
    }

    @Override
    protected void onStop() {
        if (receiverRegistered) {
            unregisterReceiver(stateReceiver);
            receiverRegistered = false;
        }
        super.onStop();
    }

    @Override
    protected void onDestroy() {
        executor.shutdownNow();
        super.onDestroy();
    }

    private void checkForUpdates() {
        setBusy("Checking signed stable feed…");
        executor.execute(() -> {
            try {
                UpdateModels.Plan plan = new UpdateEngine(this).check();
                currentPlan = plan;
                runOnUiThread(() -> {
                    check.setEnabled(true);
                    downloadProgress.setVisibility(View.GONE);
                    install.setEnabled(!plan.components.isEmpty());
                    StringBuilder b = new StringBuilder();
                    if (plan.components.isEmpty() && plan.blocked.isEmpty()) {
                        b.append("Up to date.\nRelease: ").append(plan.release);
                    } else {
                        b.append("Release: ").append(plan.release).append('\n');
                        b.append("Serial: ").append(plan.serial).append('\n');
                        b.append("Device: ").append(plan.device).append("\n\n");
                        for (UpdateModels.Component c : plan.components) {
                            b.append(c.staged ? "• Staged reboot update: " : "• APK update: ")
                                    .append(c.id)
                                    .append("  ").append(c.packageName)
                                    .append("  v").append(c.versionCode).append('\n');
                        }
                        if (!plan.blocked.isEmpty()) {
                            b.append("\nNormal APK install blocked (staged/reboot or image update):\n");
                            for (String blocked : plan.blocked) {
                                b.append("• ").append(blocked).append('\n');
                            }
                        }
                        if (!plan.components.isEmpty()) {
                            b.append(plan.requiresStaged()
                                    ? "\nStaged install: download, verify, wait for READY, then reboot."
                                    : "\nOther eligible APKs can be installed atomically.");
                        }
                    }
                    status.setText(b.toString());
                });
            } catch (Exception e) {
                showError("Check failed", e);
            }
        });
    }

    private void installCurrentPlan() {
        UpdateModels.Plan plan = currentPlan;
        if (plan == null || plan.components.isEmpty()) return;

        setBusy("Downloading and verifying APKs…");
        executor.execute(() -> {
            try {
                UpdateEngine engine = new UpdateEngine(this);
                List<UpdateModels.Downloaded> files = engine.downloadAndValidate(plan,
                        (phase, received, total) -> runOnUiThread(() -> {
                            if (phase.startsWith("Downloading ")) {
                                int percent = total > 0L ? (int) (received * 100L / total) : 0;
                                downloadProgress.setIndeterminate(false);
                                downloadProgress.setMax(100);
                                downloadProgress.setProgress(percent);
                                status.setText(phase + "\n" + percent + "% ("
                                        + (received / 1024L) + "/" + (total / 1024L)
                                        + " KiB)");
                            } else {
                                downloadProgress.setIndeterminate(true);
                                status.setText(phase + "…");
                            }
                        }));
                runOnUiThread(() -> {
                    downloadProgress.setIndeterminate(true);
                    status.setText("Verified. Staging atomic install…");
                });
                if (plan.requiresStaged()) {
                    engine.installStagedSingle(plan, files);
                } else {
                    engine.installAtomically(plan, files);
                }
                runOnUiThread(() -> {
                    downloadProgress.setVisibility(View.GONE);
                    check.setEnabled(true);
                    install.setEnabled(false);
                    status.setText(plan.requiresStaged()
                            ? "Staged session submitted. Tap Check to refresh READY state; do not reboot yet."
                            : "Install committed. Waiting for PackageInstaller result…");
                });
            } catch (Exception e) {
                showError("Install failed", e);
            }
        });
    }

    private void setBusy(String text) {
        check.setEnabled(false);
        install.setEnabled(false);
        status.setText(text);
        downloadProgress.setIndeterminate(true);
        downloadProgress.setVisibility(View.VISIBLE);
    }

    private void showError(String prefix, Exception e) {
        runOnUiThread(() -> {
            check.setEnabled(true);
            install.setEnabled(currentPlan != null && !currentPlan.components.isEmpty());
            downloadProgress.setVisibility(View.GONE);
            status.setText(prefix + ":\n" + e.getClass().getSimpleName() + ": " + e.getMessage());
        });
    }

    private void clearStagedState(android.content.SharedPreferences p) {
        p.edit().remove("staged_session_id").remove("staged_package")
                .remove("staged_version").remove("staged_sha256")
                .remove("staged_release").remove("staged_serial").apply();
    }

    // Returns true if a staged session is outstanding; Check button refreshes its status.
    private boolean refreshStagedState() {
        android.content.SharedPreferences p = getSharedPreferences("state", MODE_PRIVATE);
        int id = p.getInt("staged_session_id", -1);
        if (id < 0) return false;
        check.setEnabled(true);
        install.setEnabled(false);
        downloadProgress.setVisibility(View.GONE);
        android.content.pm.PackageInstaller.SessionInfo si =
                getPackageManager().getPackageInstaller().getSessionInfo(id);
        String pkg = p.getString("staged_package", "");
        long version = p.getLong("staged_version", -1L);
        String sha = p.getString("staged_sha256", "");
        // Verify actual installed package, signer and SHA before accepting any release.
        if (new UpdateEngine(this).installedStagedCandidateMatches(pkg, version, sha)) {
            p.edit().putLong("accepted_serial", p.getLong("staged_serial", 0L))
                    .putString("installed_release", p.getString("staged_release", ""))
                    .putBoolean("reboot_required", false)
                    .putString("last_result", "Staged OTA applied and verified: " + pkg
                            + " v" + version).apply();
            clearStagedState(p);
            reboot.setVisibility(View.GONE);
            status.setText("Staged OTA applied and verified: " + pkg + " v" + version);
            return false;
        }
        if (si != null && si.isStaged()) {
            if (si.isStagedSessionFailed()) {
                status.setText("Staged session FAILED. Android reports: "
                        + si.getStagedSessionErrorMessage());
                reboot.setVisibility(View.GONE);
                clearStagedState(p);
                return false;
            }
            if (si.isStagedSessionReady()) {
                reboot.setVisibility(View.VISIBLE);
                status.setText("Staged APK READY. Android will install it on the next reboot."
                        + "\nTap Reboot only when safe to restart the Pi4.");
            } else {
                reboot.setVisibility(View.GONE);
                status.setText("Staged APK pending verification. Tap Check to refresh;"
                        + " do NOT reboot before READY.");
            }
            return true;
        }
        // Session not found: never call reboot; preserve forensic state for inspection.
        reboot.setVisibility(View.GONE);
        status.setText("Staged session not visible; target v" + version
                + " is NOT verified. Do not retry or reboot; inspect session state.");
        return true;
    }

    private void refreshPersistentState() {
        if (refreshStagedState()) return;
        android.content.SharedPreferences p = getSharedPreferences("state", MODE_PRIVATE);
        String last = p.getString("last_result", "");
        boolean needsReboot = p.getBoolean("reboot_required", false);
        if (!last.isEmpty()) {
            downloadProgress.setVisibility(View.GONE);
            status.setText(last);
        }
        reboot.setVisibility(needsReboot ? View.VISIBLE : View.GONE);
    }
}
