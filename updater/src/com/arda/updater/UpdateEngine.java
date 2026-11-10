package com.arda.updater;

import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageInstaller;
import android.content.pm.PackageManager;
import android.content.pm.Signature;
import android.os.Build;
import android.util.Base64;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.MessageDigest;
import java.security.PublicKey;
import java.security.SignatureException;
import java.security.spec.X509EncodedKeySpec;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

final class UpdateEngine {
    static final String BASE = "https://aap.opencharge.top/arda/";
    static final String LATEST = BASE + "stable/latest.json";
    static final String HOST = "aap.opencharge.top";
    static final String GITHUB_HOST = "github.com";
    static final String GITHUB_RELEASE_PREFIX =
            "/Deadknight/arda-releases/releases/download/";
    static final String GITHUB_RELEASE_CDN_HOST = "release-assets.githubusercontent.com";
    static final String GITHUB_OBJECTS_CDN_HOST = "objects.githubusercontent.com";
    static final int MAX_ASSET_REDIRECTS = 3;
    static final String PLATFORM_SIGNER_SHA256 =
            "c8a2e9bccf597c2fb6dc66bee293fc13f2fc47ec77bc6b2b0d52c11f51192ab8";

    private static final long MAX_JSON = 512L * 1024L;
    private static final long MAX_SIGNATURE = 16L * 1024L;
    private static final long MAX_APK = 350L * 1024L * 1024L;

    private final Context context;
    private final PackageManager pm;

    // Deliberately narrow V1 allowlist. Core AAOS packages are excluded.
    private static final Map<String, Boolean> ALLOWED = new HashMap<>();
    static {
        ALLOWED.put("com.github.deadknight.ardaplatform.debug", true);
        ALLOWED.put("com.github.deadknight.ardausbaccessorysink", true);
        ALLOWED.put("com.arda.inputbridge", true);
        ALLOWED.put("com.arda.projectioninsets", true);
        ALLOWED.put("com.arda.vehicle.manager", true);
        ALLOWED.put("com.arda.vehicle.smoke", true);
    }

    interface ProgressListener {
        void onProgress(String phase, long bytes, long total);
    }

    private interface ByteProgress {
        void onBytes(long bytes, long total);
    }

    UpdateEngine(Context context) {
        this.context = context.getApplicationContext();
        this.pm = this.context.getPackageManager();
    }

    UpdateModels.Plan check() throws Exception {
        byte[] latestBytes = getBytes(LATEST, 64L * 1024L);
        JSONObject latest = new JSONObject(new String(latestBytes, StandardCharsets.UTF_8));

        if (latest.getInt("schema") != 1) throw new SecurityException("Unsupported latest schema");
        if (!"stable".equals(latest.getString("channel"))) {
            throw new SecurityException("Unexpected channel");
        }

        String manifestUrl = latest.getString("manifest");
        String signatureUrl = latest.getString("signature");
        requireControlUrl(manifestUrl);
        requireControlUrl(signatureUrl);

        byte[] manifestBytes = getBytes(manifestUrl, MAX_JSON);
        byte[] signatureText = getBytes(signatureUrl, MAX_SIGNATURE);
        verifyManifest(manifestBytes, signatureText);

        JSONObject root = new JSONObject(new String(manifestBytes, StandardCharsets.UTF_8));
        if (root.getInt("schema") != 1) throw new SecurityException("Unsupported manifest schema");
        if (!"stable".equals(root.getString("channel"))) throw new SecurityException("Channel mismatch");

        long serial = root.getLong("serial");
        long accepted = context.getSharedPreferences("state", Context.MODE_PRIVATE)
                .getLong("accepted_serial", 0L);
        if (serial < accepted) throw new SecurityException("Manifest rollback blocked");

        String device = Build.DEVICE;
        JSONObject targets = root.getJSONObject("targets");
        if (!targets.has(device)) throw new SecurityException("No target for " + device);
        JSONObject target = targets.getJSONObject(device);

        int sdk = target.optInt("sdk", Build.VERSION.SDK_INT);
        if (sdk != Build.VERSION.SDK_INT) {
            throw new SecurityException("SDK mismatch: manifest=" + sdk + " device=" + Build.VERSION.SDK_INT);
        }

        String fpPrefix = target.optString("fingerprintPrefix", "");
        if (!fpPrefix.isEmpty() && !Build.FINGERPRINT.startsWith(fpPrefix)) {
            throw new SecurityException("Base fingerprint incompatible");
        }

        UpdateModels.Plan plan = new UpdateModels.Plan();
        plan.release = root.getString("release");
        plan.serial = serial;
        plan.device = device;

        JSONArray components = target.getJSONArray("components");
        for (int i = 0; i < components.length(); i++) {
            JSONObject o = components.getJSONObject(i);
            UpdateModels.Component c = parseComponent(o);
            if (!ALLOWED.containsKey(c.packageName)) {
                throw new SecurityException("Package not allowed by updater V1: " + c.packageName);
            }
            c.rebootRequired = ALLOWED.get(c.packageName);

            PackageInfo installed = pm.getPackageInfo(
                    c.packageName, PackageManager.PackageInfoFlags.of(PackageManager.GET_SIGNING_CERTIFICATES));
            String installedSigner = signerSha256(installed);
            if (!PLATFORM_SIGNER_SHA256.equals(installedSigner)) {
                throw new SecurityException("Installed signer mismatch for " + c.packageName);
            }
            if (!PLATFORM_SIGNER_SHA256.equals(c.signerSha256)) {
                throw new SecurityException("Manifest signer mismatch for " + c.packageName);
            }
            if (c.versionCode < installed.getLongVersionCode()) {
                throw new SecurityException("Version downgrade blocked for " + c.packageName);
            }

            ApplicationInfo ai = installed.applicationInfo;
            if (ai == null || ai.sourceDir == null) {
                throw new SecurityException("No installed source for " + c.packageName);
            }
            String installedSha = sha256(new File(ai.sourceDir));
            if (!installedSha.equals(c.sha256)) {
                if ((ai.flags & ApplicationInfo.FLAG_PERSISTENT) != 0) {
                    // V15 permits ONLY the known ArdaPlatform persistent target.
                    if (!"com.github.deadknight.ardaplatform.debug".equals(c.packageName)) {
                        plan.blocked.add(c.id + ": persistent update requires an image update");
                        continue;
                    }
                    c.staged = true;
                }
                plan.components.add(c);
            }
        }
        if (plan.requiresStaged() && plan.components.size() != 1) {
            throw new SecurityException("V15 staged install only supports one APK per release");
        }
        return plan;
    }

    List<UpdateModels.Downloaded> downloadAndValidate(
            UpdateModels.Plan plan, ProgressListener progress) throws Exception {
        File dir = new File(context.getCacheDir(), "release-" + plan.serial);
        deleteRecursively(dir);
        if (!dir.mkdirs() && !dir.isDirectory()) throw new java.io.IOException("Cannot create cache dir");

        List<UpdateModels.Downloaded> result = new ArrayList<>();
        for (UpdateModels.Component c : plan.components) {
            requirePayloadManifestUrl(c.url);
            if (c.size <= 0 || c.size > MAX_APK) throw new SecurityException("Invalid APK size");
            File f = new File(dir, c.id.replaceAll("[^a-zA-Z0-9._-]", "_") + ".apk");
            if (progress != null) progress.onProgress("Downloading " + c.id, 0L, c.size);
            download(c.url, f, c.size, (received, total) -> {
                if (progress != null) {
                    progress.onProgress("Downloading " + c.id, received, total);
                }
            });
            if (progress != null) {
                progress.onProgress("Verifying SHA256 and APK signature for " + c.id,
                        c.size, c.size);
            }

            String digest = sha256(f);
            if (!digest.equals(c.sha256)) throw new SecurityException("SHA-256 mismatch: " + c.id);

            PackageInfo archive = pm.getPackageArchiveInfo(
                    f.getAbsolutePath(),
                    PackageManager.PackageInfoFlags.of(PackageManager.GET_SIGNING_CERTIFICATES));
            if (archive == null) throw new SecurityException("APK parse failed: " + c.id);
            if (!c.packageName.equals(archive.packageName)) {
                throw new SecurityException("APK package mismatch: " + c.id);
            }
            if (archive.getLongVersionCode() != c.versionCode) {
                throw new SecurityException("APK version mismatch: " + c.id);
            }
            String archiveSigner = signerSha256(archive);
            if (!PLATFORM_SIGNER_SHA256.equals(archiveSigner)
                    || !archiveSigner.equals(c.signerSha256)) {
                throw new SecurityException("APK signer mismatch: " + c.id);
            }
            result.add(new UpdateModels.Downloaded(c, f));
        }
        return result;
    }

    void installStagedSingle(UpdateModels.Plan plan, List<UpdateModels.Downloaded> files)
            throws Exception {
        if (!plan.requiresStaged() || files.size() != 1 || plan.components.size() != 1
                || !files.get(0).component.staged) {
            throw new SecurityException("Unexpected staged installation shape");
        }
        UpdateModels.Downloaded d = files.get(0);
        if (!"com.github.deadknight.ardaplatform.debug".equals(d.component.packageName)) {
            throw new SecurityException("Staged target not allowlisted by V15");
        }
        android.content.SharedPreferences p = context.getSharedPreferences("state", Context.MODE_PRIVATE);
        if (p.getInt("staged_session_id", -1) >= 0) {
            throw new IllegalStateException("An existing staged session must be resolved first");
        }
        PackageInstaller installer = pm.getPackageInstaller();
        PackageInstaller.SessionParams params =
                new PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL);
        params.setAppPackageName(d.component.packageName);
        params.setSize(d.file.length());
        params.setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED);
        params.setStaged(); // System API, privileged system_current application.
        int id = installer.createSession(params);
        boolean committed = false;
        try {
            try (PackageInstaller.Session session = installer.openSession(id);
                 InputStream in = new FileInputStream(d.file);
                 OutputStream out = session.openWrite("base.apk", 0, d.file.length())) {
                byte[] buf = new byte[256 * 1024];
                int n;
                while ((n = in.read(buf)) != -1) out.write(buf, 0, n);
                session.fsync(out);
            }
            if (!p.edit().putInt("staged_session_id", id)
                    .putString("staged_package", d.component.packageName)
                    .putLong("staged_version", d.component.versionCode)
                    .putString("staged_sha256", d.component.sha256)
                    .putString("staged_release", plan.release)
                    .putLong("staged_serial", plan.serial)
                    .putString("last_result", "Staged APK submitted. Tap Check to refresh readiness.")
                    .commit()) {
                throw new java.io.IOException("Cannot persist staged session state");
            }
            Intent result = new Intent(context, InstallResultReceiver.class)
                    .setAction(InstallResultReceiver.ACTION_INSTALL_RESULT)
                    .putExtra("staged", true)
                    .putExtra("serial", plan.serial)
                    .putExtra("release", plan.release)
                    .putExtra("reboot_required", true);
            PendingIntent pending = PendingIntent.getBroadcast(
                    context, id, result,
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_MUTABLE);
            try (PackageInstaller.Session session = installer.openSession(id)) {
                session.commit(pending.getIntentSender());
            }
            committed = true;
        } finally {
            if (!committed) {
                try { installer.abandonSession(id); } catch (Exception ignored) {}
                p.edit().remove("staged_session_id").remove("staged_package")
                        .remove("staged_version").remove("staged_sha256")
                        .remove("staged_release").remove("staged_serial").apply();
            }
        }
    }

    boolean installedStagedCandidateMatches(String pkg, long version, String sha) {
        try {
            if (!"com.github.deadknight.ardaplatform.debug".equals(pkg)) return false;
            PackageInfo pi = pm.getPackageInfo(pkg,
                    PackageManager.PackageInfoFlags.of(PackageManager.GET_SIGNING_CERTIFICATES));
            return pi.getLongVersionCode() == version
                    && PLATFORM_SIGNER_SHA256.equals(signerSha256(pi))
                    && pi.applicationInfo != null
                    && sha.equals(sha256(new File(pi.applicationInfo.sourceDir)));
        } catch (Exception e) {
            return false;
        }
    }

    void installAtomically(UpdateModels.Plan plan, List<UpdateModels.Downloaded> files) throws Exception {
        if (files.isEmpty()) throw new IllegalArgumentException("No updates to install");

        PackageInstaller installer = pm.getPackageInstaller();

        PackageInstaller.SessionParams parentParams =
                new PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL);
        parentParams.setMultiPackage();
        parentParams.setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED);
        int parentId = installer.createSession(parentParams);

        PackageInstaller.Session parent = installer.openSession(parentId);
        boolean success = false;
        try {
            for (UpdateModels.Downloaded d : files) {
                PackageInstaller.SessionParams childParams =
                        new PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL);
                childParams.setAppPackageName(d.component.packageName);
                childParams.setSize(d.file.length());
                childParams.setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED);

                int childId = installer.createSession(childParams);
                try (PackageInstaller.Session child = installer.openSession(childId);
                     InputStream in = new FileInputStream(d.file);
                     OutputStream out = child.openWrite("base.apk", 0, d.file.length())) {
                    byte[] buf = new byte[256 * 1024];
                    int n;
                    while ((n = in.read(buf)) != -1) out.write(buf, 0, n);
                    child.fsync(out);
                }
                parent.addChildSessionId(childId);
            }

            Intent result = new Intent(context, InstallResultReceiver.class)
                    .setAction(InstallResultReceiver.ACTION_INSTALL_RESULT)
                    .putExtra("serial", plan.serial)
                    .putExtra("release", plan.release)
                    .putExtra("reboot_required", plan.rebootRequired());

            PendingIntent pending = PendingIntent.getBroadcast(
                    context,
                    (int) (plan.serial & 0x7fffffff),
                    result,
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_MUTABLE);

            parent.commit(pending.getIntentSender());
            success = true;
        } finally {
            parent.close();
            if (!success) {
                try { installer.abandonSession(parentId); } catch (Exception ignored) {}
            }
        }
    }

    private UpdateModels.Component parseComponent(JSONObject o) throws Exception {
        UpdateModels.Component c = new UpdateModels.Component();
        c.id = o.getString("id");
        c.packageName = o.getString("packageName");
        c.versionCode = o.getLong("versionCode");
        c.size = o.getLong("size");
        c.sha256 = normalizedSha(o.getString("sha256"));
        c.signerSha256 = normalizedSha(o.getString("signerSha256"));
        c.url = o.getString("url");
        return c;
    }

    private void verifyManifest(byte[] manifest, byte[] signatureText) throws Exception {
        String pem;
        try (InputStream in = context.getResources().openRawResource(
                context.getResources().getIdentifier("arda_manifest_pubkey", "raw", context.getPackageName()))) {
            pem = new String(readAll(in, 64 * 1024), StandardCharsets.US_ASCII);
        }
        String b64 = pem.replace("-----BEGIN PUBLIC KEY-----", "")
                .replace("-----END PUBLIC KEY-----", "")
                .replaceAll("\\s", "");
        byte[] der = Base64.decode(b64, Base64.DEFAULT);
        PublicKey key = KeyFactory.getInstance("EC")
                .generatePublic(new X509EncodedKeySpec(der));

        byte[] sig = Base64.decode(
                new String(signatureText, StandardCharsets.US_ASCII).trim(), Base64.DEFAULT);

        java.security.Signature verifier = java.security.Signature.getInstance("SHA256withECDSA");
        verifier.initVerify(key);
        verifier.update(manifest);
        if (!verifier.verify(sig)) throw new SignatureException("Manifest signature invalid");
    }

    private byte[] getBytes(String value, long max) throws Exception {
        requireControlUrl(value);
        HttpURLConnection c = (HttpURLConnection) new URL(value).openConnection();
        c.setConnectTimeout(10_000);
        c.setReadTimeout(20_000);
        c.setInstanceFollowRedirects(false);
        c.setRequestProperty("Accept", "application/json,text/plain,*/*");
        int code = c.getResponseCode();
        if (code != 200) throw new java.io.IOException("HTTP " + code + " for " + value);
        long length = c.getContentLengthLong();
        if (length > max) throw new java.io.IOException("Response too large");
        try (InputStream in = new BufferedInputStream(c.getInputStream())) {
            return readAll(in, max);
        } finally {
            c.disconnect();
        }
    }

    private void download(String value, File outFile, long expectedSize,
            ByteProgress progress) throws Exception {
        requirePayloadManifestUrl(value);

        URL current = new URL(value);
        int redirects = 0;

        while (true) {
            if (redirects == 0) {
                requirePayloadManifestUrl(current.toString());
            } else {
                requirePayloadRedirectUrl(current.toString());
            }

            HttpURLConnection c = (HttpURLConnection) current.openConnection();
            c.setConnectTimeout(10_000);
            c.setReadTimeout(60_000);
            c.setInstanceFollowRedirects(false);
            c.setRequestProperty("Accept", "application/octet-stream");
            c.setRequestProperty("User-Agent", "ARDA-Updater/1");

            int code = c.getResponseCode();

            if (isRedirectCode(code)) {
                String location = c.getHeaderField("Location");
                c.disconnect();

                if (location == null || location.isEmpty()) {
                    throw new java.io.IOException("Redirect without Location");
                }
                if (redirects >= MAX_ASSET_REDIRECTS) {
                    throw new SecurityException("Too many asset redirects");
                }

                URL next = new URL(current, location);
                requirePayloadRedirectUrl(next.toString());
                current = next;
                redirects++;
                continue;
            }

            if (code != 200) {
                c.disconnect();
                throw new java.io.IOException("HTTP " + code + " for asset");
            }

            long contentLength = c.getContentLengthLong();
            if (contentLength > 0 && contentLength != expectedSize) {
                c.disconnect();
                throw new java.io.IOException("Content-Length mismatch");
            }

            long total = 0;
            long lastReportedAt = 0L;
            int lastReportedPercent = -1;
            try (InputStream in = new BufferedInputStream(c.getInputStream());
                 OutputStream out = new FileOutputStream(outFile)) {
                byte[] buf = new byte[256 * 1024];
                int n;
                while ((n = in.read(buf)) != -1) {
                    total += n;
                    if (total > expectedSize || total > MAX_APK) {
                        throw new java.io.IOException("Asset exceeds declared size");
                    }
                    out.write(buf, 0, n);
                    int percent = (int) (total * 100L / expectedSize);
                    long now = android.os.SystemClock.uptimeMillis();
                    if (percent != lastReportedPercent
                            && (now - lastReportedAt >= 250L || percent == 100)) {
                        lastReportedPercent = percent;
                        lastReportedAt = now;
                        if (progress != null) progress.onBytes(total, expectedSize);
                    }
                }
            } finally {
                c.disconnect();
            }

            if (progress != null) progress.onBytes(total, expectedSize);
            if (total != expectedSize) {
                throw new java.io.IOException("Downloaded size mismatch");
            }
            return;
        }
    }

    private static byte[] readAll(InputStream in, long max) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[16 * 1024];
        long total = 0;
        int n;
        while ((n = in.read(buf)) != -1) {
            total += n;
            if (total > max) throw new java.io.IOException("Input exceeds limit");
            out.write(buf, 0, n);
        }
        return out.toByteArray();
    }

    private static String signerSha256(PackageInfo info) throws Exception {
        if (info.signingInfo == null) throw new SecurityException("No signing info");
        Signature[] signers = info.signingInfo.getApkContentsSigners();
        if (signers == null || signers.length != 1) {
            throw new SecurityException("Expected exactly one APK signer");
        }
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        return hex(md.digest(signers[0].toByteArray()));
    }

    private static String sha256(File f) throws Exception {
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        try (InputStream in = new FileInputStream(f)) {
            byte[] buf = new byte[256 * 1024];
            int n;
            while ((n = in.read(buf)) != -1) md.update(buf, 0, n);
        }
        return hex(md.digest());
    }

    private static String normalizedSha(String value) {
        String v = value.toLowerCase(Locale.US).replace(":", "");
        if (!v.matches("[0-9a-f]{64}")) throw new IllegalArgumentException("Invalid SHA-256");
        return v;
    }

    private static void requireControlUrl(String value) throws Exception {
        URI uri = new URI(value);
        if (!isBasicHttpsUri(uri)
                || !HOST.equalsIgnoreCase(uri.getHost())
                || uri.getRawPath() == null
                || !uri.getRawPath().startsWith("/arda/")
                || uri.getRawQuery() != null
                || uri.getRawFragment() != null) {
            throw new SecurityException("Untrusted control-plane URL");
        }
    }

    private static void requirePayloadManifestUrl(String value) throws Exception {
        URI uri = new URI(value);
        if (!isBasicHttpsUri(uri)
                || !GITHUB_HOST.equalsIgnoreCase(uri.getHost())
                || uri.getRawPath() == null
                || !uri.getRawPath().startsWith(GITHUB_RELEASE_PREFIX)
                || uri.getRawPath().length() <= GITHUB_RELEASE_PREFIX.length()
                || uri.getRawPath().toLowerCase(Locale.US).contains("%2f")
                || uri.getRawPath().contains("..")
                || uri.getRawQuery() != null
                || uri.getRawFragment() != null) {
            throw new SecurityException("Untrusted payload URL");
        }
    }

    private static void requirePayloadRedirectUrl(String value) throws Exception {
        URI uri = new URI(value);
        if (!isBasicHttpsUri(uri) || uri.getRawFragment() != null) {
            throw new SecurityException("Untrusted payload redirect URL");
        }

        if (GITHUB_HOST.equalsIgnoreCase(uri.getHost())) {
            requirePayloadManifestUrl(value);
            return;
        }

        String host = uri.getHost();
        boolean allowedCdn =
                GITHUB_RELEASE_CDN_HOST.equalsIgnoreCase(host)
                || GITHUB_OBJECTS_CDN_HOST.equalsIgnoreCase(host);

        if (!allowedCdn || uri.getRawPath() == null || uri.getRawPath().isEmpty()) {
            throw new SecurityException("Untrusted payload redirect host");
        }
    }

    private static boolean isBasicHttpsUri(URI uri) {
        return "https".equalsIgnoreCase(uri.getScheme())
                && uri.getHost() != null
                && uri.getUserInfo() == null
                && uri.getPort() == -1;
    }

    private static boolean isRedirectCode(int code) {
        return code == HttpURLConnection.HTTP_MOVED_PERM
                || code == HttpURLConnection.HTTP_MOVED_TEMP
                || code == HttpURLConnection.HTTP_SEE_OTHER
                || code == 307
                || code == 308;
    }

    private static String hex(byte[] bytes) {
        StringBuilder b = new StringBuilder(bytes.length * 2);
        for (byte v : bytes) b.append(String.format(Locale.US, "%02x", v & 0xff));
        return b.toString();
    }

    private static void deleteRecursively(File f) {
        if (f == null || !f.exists()) return;
        if (f.isDirectory()) {
            File[] children = f.listFiles();
            if (children != null) for (File c : children) deleteRecursively(c);
        }
        //noinspection ResultOfMethodCallIgnored
        f.delete();
    }
}
