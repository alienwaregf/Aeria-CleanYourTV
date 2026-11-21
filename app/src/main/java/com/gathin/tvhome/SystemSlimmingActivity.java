package com.gathin.tvhome;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.util.Log;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.widget.Toast;

import androidx.activity.ComponentActivity;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

public final class SystemSlimmingActivity extends ComponentActivity {
    private static final String TAG = "TvHome.SystemSlimming";
    private static final String UPSTREAM_REPO = "Gathin23/cleanyourtv";

    @Override
    protected void attachBaseContext(android.content.Context base) {
        super.attachBaseContext(base);
    }

    private static final String EXAMPLES_API =
            "https://api.github.com/repos/" + UPSTREAM_REPO + "/contents/examples";
    private static final String EXAMPLES_RAW =
            "https://raw.githubusercontent.com/" + UPSTREAM_REPO + "/main/examples/";
    private static final String SNAPSHOT_ASSET = "slimming/debloat-snapshot.zip";
    private static final long MAX_LIST_BYTES = 256 * 1024;

    private static final String KEY_FOLDER = "slimming_folder";
    public static final String KEY_LIST = "slimming_list";
    public static final String KEY_DATE = "slimming_date";
    public static final String KEY_LABEL = "slimming_label";
    public static final String KEY_NOTES = "slimming_notes";
    public static final String KEY_SLIMMING_DAILY = "slimming_daily_check";
    private static final String KEY_SLIMMING_LAST_DAILY = "slimming_last_daily_check";

    static final int STATE_INSTALLED = 0;
    static final int STATE_DISABLED = 1;
    static final int STATE_NOT_INSTALLED = 2;

    private final SlimmingUiState ui = new SlimmingUiState();

    private List<String> folders;
    private String currentLabel;
    private boolean loading;
    private int loadGen;
    private String pendingFolder;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        cleanupLegacyState(this);
        SlimmingScreenKt.showSlimmingScreen(this, ui, new SlimmingCallbacks() {
            @Override public void onDailyToggle() { toggleDaily(); }
            @Override public void onPick() { showFolderPicker(); }
            @Override public void onRetry() {
                if (pendingFolder != null) loadList(pendingFolder, false);
                else loadAll();
            }
            @Override public void onRow(String pkg) { toggleRow(pkg); }
            @Override public void onDisable(boolean includeOptional) { disableRecommended(includeOptional); }
            @Override public void onRestore(boolean includeOptional) { enableRecommended(includeOptional); }
            @Override public void onPickerPick(String folder) {
                ui.setShowPicker(false);
                loadList(folder, false);
            }
            @Override public void onPickerDismiss() { ui.setShowPicker(false); }
        });
        ui.setDevice(getString(R.string.slimming_device, deviceName()));
        renderDaily();
        if (!showCached()) ui.setStatusText(getString(R.string.slimming_loading));
        loadAll();
        slimmingDailyCheckIfDue(this);
        new Thread(() -> refreshSlimmingCache(getApplicationContext())).start();
    }

    private static void cleanupLegacyState(Context ctx) {
        SharedPreferences p = Config.prefs(ctx);
        if (p.getBoolean("cleanup_done_v13", false)) return;
        p.edit()
                .remove("tmdb_api_key").remove("tmdb_enabled")
                .remove("theme_signature")
                .remove("web_enabled").remove("web_token").remove("web_daily_check")
                .remove("web_upstream_sha").remove("web_upstream_etag")
                .remove("web_upstream_result").remove("web_last_check_at")
                .putBoolean("cleanup_done_v13", true).apply();
        File dir = ctx.getExternalFilesDir(null);
        if (dir != null) {
            deleteRecursive(new File(dir, "config.json"));
            deleteRecursive(new File(dir, "wallpaper.jpg"));
            deleteRecursive(new File(dir, "theme_revision"));
            deleteRecursive(new File(dir, "wallpapers"));
            deleteRecursive(new File(dir, ".backup"));
            deleteRecursive(new File(dir, ".tmp-theme"));
        }
        deleteRecursive(new File(ctx.getFilesDir(), "config.json"));
    }

    private static void deleteRecursive(File f) {
        if (f == null || !f.exists()) return;
        if (f.isDirectory()) {
            File[] kids = f.listFiles();
            if (kids != null) for (File k : kids) deleteRecursive(k);
        }
        f.delete();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (!ui.getRecommended().isEmpty() || !ui.getOptional().isEmpty()) refreshStates();
    }

    private void toggleDaily() {
        boolean next = !Config.prefs(this).getBoolean(KEY_SLIMMING_DAILY, true);
        Config.prefs(this).edit().putBoolean(KEY_SLIMMING_DAILY, next).apply();
        renderDaily();
    }

    private void renderDaily() {
        ui.setDailyText(getString(R.string.slimming_daily_state,
                getString(Config.prefs(this).getBoolean(KEY_SLIMMING_DAILY, true)
                        ? R.string.on : R.string.off)));
    }

    private static String deviceName() {
        String maker = Build.MANUFACTURER == null ? "" : Build.MANUFACTURER.trim();
        String model = Build.MODEL == null ? "" : Build.MODEL.trim();
        if (model.isEmpty()) model = Build.DEVICE == null ? "" : Build.DEVICE.trim();
        if (model.isEmpty()) model = Build.PRODUCT == null ? "" : Build.PRODUCT.trim();
        if (maker.isEmpty()) return model;
        if (model.isEmpty()) return maker;
        return maker + " " + model;
    }

    private boolean showCached() {
        String folder = Config.prefs(this).getString(KEY_FOLDER, null);
        String list = Config.prefs(this).getString(KEY_LIST, null);
        String date = Config.prefs(this).getString(KEY_DATE, null);
        if (folder == null || list == null) return false;
        Debloat debloat = parseDebloat(list);
        if (debloat.isEmpty()) return false;
        currentLabel = Config.prefs(this).getString(KEY_LABEL, folder);
        setRows(debloat);
        ui.setListText(getString(R.string.slimming_list, currentLabel,
                debloat.recommended.size(), debloat.optional.size()));
        ui.setSourceText(getString(R.string.slimming_cached, date == null ? "" : date));
        return true;
    }

    private void loadAll() {
        if (loading) return;
        loading = true;
        loadGen++;
        final int gen = loadGen;
        runOnUiThread(() -> {
            ui.setShowRetry(false);
            if (ui.getRecommended().isEmpty() && ui.getOptional().isEmpty()) ui.setStatusText(getString(R.string.slimming_loading));
        });
        new Thread(() -> {
            List<String> useFolders = liveFolders();
            final boolean githubOk = useFolders != null;
            if (useFolders == null) useFolders = snapshotFolders();
            final List<String> found = useFolders;
            runOnUiThread(() -> {
                if (gen != loadGen) { loading = false; return; }
                folders = found;
                if (folders == null) {
                    ui.setStatusText(getString(R.string.slimming_no_github));
                    ui.setShowRetry(true);
                    loading = false;
                    return;
                }
                ui.setShowPick(true);
                Match match = matchDevice(folders);
                if (match.level == Match.HIGH) {
                    loadList(match.folder, true);
                } else if (!githubOk) {
                    ui.setStatusText(getString(R.string.slimming_no_github));
                    ui.setShowRetry(true);
                    ui.setListText(getString(R.string.slimming_list_none));
                    ui.setSourceText("");
                    loading = false;
                } else {
                    String makerNorm = normalise(Build.MANUFACTURER == null ? "" : Build.MANUFACTURER);
                    List<String> makerTokens = tokens(Build.MANUFACTURER);
                    boolean sameBrand = false;
                    for (String folder : folders) {
                        if (!makerNorm.isEmpty() && normalise(folder).contains(makerNorm)) { sameBrand = true; break; }
                        if (!makerTokens.isEmpty() && !java.util.Collections.disjoint(tokens(folder), makerTokens)) { sameBrand = true; break; }
                    }
                    if (sameBrand) {
                        ui.setStatusText(getString(R.string.slimming_no_model_same_brand));
                    } else {
                        ui.setStatusText(getString(R.string.slimming_no_brand));
                    }
                    ui.setListText(getString(R.string.slimming_list_none));
                    ui.setSourceText("");
                    loading = false;
                }
            });
        }).start();
    }

    private void loadList(String folder, boolean autoHigh) {
        pendingFolder = folder;
        loadGen++;
        final int gen = loadGen;
        new Thread(() -> {
            String today = LocalDate.now().toString();
            String label = folder;
            String tvJson = fetchText(EXAMPLES_RAW + folder + "/tv.json");
            if (tvJson == null) tvJson = snapshotTvJson(folder);
            if (tvJson != null) {
                try {
                    JSONObject o = new JSONObject(tvJson);
                    String tv = o.optString("tv");
                    if (!tv.isEmpty()) label = tv;
                } catch (Exception ignored) {
                }
            }
            if (label.equals(folder) && folder.equals(Config.prefs(this).getString(KEY_FOLDER, ""))) {
                String cachedLabel = Config.prefs(this).getString(KEY_LABEL, "");
                if (!cachedLabel.isEmpty()) label = cachedLabel;
            }
            byte[] live = fetchBytes(EXAMPLES_RAW + folder + "/debloat.txt", MAX_LIST_BYTES);
            String source;
            String date;
            Debloat debloat = null;
            String liveText = null;
            if (live != null) {
                liveText = new String(live, StandardCharsets.UTF_8);
                debloat = parseDebloat(liveText);
                source = getString(R.string.slimming_live, today);
                date = today;
            } else if (folder.equals(Config.prefs(this).getString(KEY_FOLDER, ""))) {
                String cached = Config.prefs(this).getString(KEY_LIST, null);
                if (cached != null) debloat = parseDebloat(cached);
                source = getString(R.string.slimming_cached,
                        Config.prefs(this).getString(KEY_DATE, ""));
                date = null;
            } else {
                byte[] snap = snapshotEntry(folder);
                if (snap != null) {
                    debloat = parseDebloat(new String(snap, StandardCharsets.UTF_8));
                    source = getString(R.string.slimming_snapshot, snapshotDate());
                } else {
                    source = null;
                }
                date = null;
            }
            final String useLabel = label;
            final String useSource = source;
            final Debloat useDebloat = debloat;
            final String useLiveText = liveText;
            final String useFolder = folder;
            final String useDate = date;
            final boolean useAutoHigh = autoHigh;
            runOnUiThread(() -> {
                if (gen != loadGen) { loading = false; return; }
                loading = false;
                if (useDebloat == null) {
                    ui.setStatusText(getString(R.string.slimming_failed, useFolder));
                    ui.setShowRetry(true);
                    return;
                }
                currentLabel = useLabel;
                setRows(useDebloat);
                ui.setListText(getString(R.string.slimming_list, useLabel,
                        useDebloat.recommended.size(), useDebloat.optional.size()));
                ui.setSourceText(useSource == null ? "" : useSource);
                if (useDebloat.isEmpty()) {
                    ui.setStatusText(getString(R.string.slimming_empty));
                } else if (useAutoHigh) {
                    ui.setStatusText(getString(R.string.slimming_match_high));
                } else {
                    ui.setStatusText("");
                }
                if (useDate != null && !useDebloat.isEmpty() && useLiveText != null) {
                    Config.prefs(this).edit()
                            .putString(KEY_FOLDER, useFolder)
                            .putString(KEY_LIST, useLiveText)
                            .putString(KEY_DATE, useDate)
                            .putString(KEY_LABEL, useLabel)
                            .apply();
                }
                refreshStates();
            });
        }).start();
    }

    private void setRows(Debloat debloat) {
        ui.getRecommended().clear();
        for (String pkg : debloat.recommended) ui.getRecommended().add(new Row(pkg));
        ui.getOptional().clear();
        for (String pkg : debloat.optional) ui.getOptional().add(new Row(pkg));
    }

    private void refreshStates() {
        new Thread(() -> {
            PackageManager pm = getPackageManager();
            refreshStateList(pm, ui.getRecommended());
            refreshStateList(pm, ui.getOptional());
        }).start();
    }

    private volatile boolean busy;

    private void setBusy(boolean value) {
        busy = value;
        runOnUiThread(() -> ui.setBusy(value));
    }

    private Toast disableToast;

    private void toastDisable(String line, int duration) {
        runOnUiThread(() -> {
            if (disableToast != null) disableToast.cancel();
            disableToast = Toast.makeText(SystemSlimmingActivity.this, line, duration);
            disableToast.show();
        });
    }

    private static String appName(PackageManager pm, ApplicationInfo ai, String pkg) {
        if (ai == null) return pkg;
        CharSequence label = pm.getApplicationLabel(ai);
        if (label == null || label.length() == 0) return pkg;
        return label.toString();
    }

    private static int adbFailureRes(String msg) {
        if (msg.contains("port 5555 closed")) return R.string.slimming_disable_no_adb;
        if (msg.contains("wasn't accepted")) return R.string.slimming_disable_not_accepted;
        return R.string.slimming_disable_failed;
    }

    private void disableRecommended(boolean includeOptional) {
        if (busy) return;
        List<String> pkgs = new ArrayList<>();
        for (Row row : ui.getRecommended()) pkgs.add(row.pkg);
        if (includeOptional) {
            for (Row row : ui.getOptional()) pkgs.add(row.pkg);
        }
        if (pkgs.isEmpty()) {
            ui.setStatusText(getString(R.string.slimming_disable_nothing));
            return;
        }
        setBusy(true);
        final int total = pkgs.size();
        new Thread(() -> {
            try {
                int done = 0;
                int skipped = 0;
                boolean disconnected = false;
                List<String> failed = new ArrayList<>();
                try (Adb adb = Adb.connect(getApplicationContext(), 120_000, () ->
                        runOnUiThread(() -> ui.setStatusText(getString(R.string.slimming_disable_waiting))))) {
                    PackageManager pm = getPackageManager();
                    for (int i = 0; i < pkgs.size(); i++) {
                        String pkg = pkgs.get(i);
                        final int index = i;
                        ApplicationInfo ai;
                        try {
                            ai = pm.getApplicationInfo(pkg, 0);
                        } catch (PackageManager.NameNotFoundException e) {
                            ai = null;
                        }
                        final String name = appName(pm, ai, pkg);
                        if (ai == null) {
                            skipped++;
                            continue;
                        }
                        if (!ai.enabled) {
                            skipped++;
                            continue;
                        }
                        runOnUiThread(() -> ui.setStatusText(getString(R.string.slimming_disable_running, index + 1, total, name)));
                        try {
                            adb.shell("am force-stop " + pkg);
                            adb.shell("pm clear " + pkg);
                            adb.shell("pm disable-user " + pkg);
                        } catch (IOException e) {
                            failed.add(pkg);
                            disconnected = true;
                            break;
                        }
                        boolean disabled = false;
                        try {
                            disabled = !pm.getApplicationInfo(pkg, 0).enabled;
                        } catch (PackageManager.NameNotFoundException e) {
                            disabled = true;
                        }
                        if (disabled) {
                            done++;
                        } else {
                            failed.add(pkg);
                        }
                    }
                } catch (IOException e) {
                    String msg = e.getMessage() == null ? "" : e.getMessage();
                    final int res = adbFailureRes(msg);
                    runOnUiThread(() -> {
                        ui.setStatusText(getString(res, msg));
                    });
                    return;
                }
                final int useDone = done;
                final int useSkipped = skipped;
                final int useFailed = failed.size();
                final boolean useDisconnected = disconnected;
                runOnUiThread(() -> {
                    if (useDisconnected) {
                        ui.setStatusText(getString(R.string.slimming_adb_disconnected));
                    } else {
                        ui.setStatusText(getString(R.string.slimming_disable_done, useDone, useSkipped, useFailed));
                    }
                    refreshStates();
                });
            } finally {
                setBusy(false);
            }
        }).start();
    }

    private void enableRecommended(boolean includeOptional) {
        if (busy) return;
        List<String> pkgs = new ArrayList<>();
        for (Row row : ui.getRecommended()) pkgs.add(row.pkg);
        if (includeOptional) {
            for (Row row : ui.getOptional()) pkgs.add(row.pkg);
        }
        if (pkgs.isEmpty()) {
            ui.setStatusText(getString(R.string.slimming_restore_nothing));
            return;
        }
        setBusy(true);
        final int total = pkgs.size();
        new Thread(() -> {
            try {
                int done = 0;
                int skipped = 0;
                boolean disconnected = false;
                List<String> failed = new ArrayList<>();
                try (Adb adb = Adb.connect(getApplicationContext(), 120_000, () ->
                        runOnUiThread(() -> ui.setStatusText(getString(R.string.slimming_disable_waiting))))) {
                    PackageManager pm = getPackageManager();
                    for (int i = 0; i < pkgs.size(); i++) {
                        String pkg = pkgs.get(i);
                        final int index = i;
                        ApplicationInfo ai;
                        try {
                            ai = pm.getApplicationInfo(pkg, 0);
                        } catch (PackageManager.NameNotFoundException e) {
                            ai = null;
                        }
                        final String name = appName(pm, ai, pkg);
                        if (ai == null) {
                            skipped++;
                            continue;
                        }
                        if (ai.enabled) {
                            skipped++;
                            continue;
                        }
                        runOnUiThread(() -> ui.setStatusText(getString(R.string.slimming_restore_running, index + 1, total, name)));
                        try {
                            adb.shell("pm enable " + pkg);
                        } catch (IOException e) {
                            failed.add(pkg);
                            disconnected = true;
                            break;
                        }
                        boolean enabled = false;
                        try {
                            enabled = pm.getApplicationInfo(pkg, 0).enabled;
                        } catch (PackageManager.NameNotFoundException e) {
                            enabled = false;
                        }
                        if (enabled) {
                            done++;
                        } else {
                            failed.add(pkg);
                        }
                    }
                } catch (IOException e) {
                    String msg = e.getMessage() == null ? "" : e.getMessage();
                    final int res = adbFailureRes(msg);
                    runOnUiThread(() -> {
                        ui.setStatusText(getString(res, msg));
                    });
                    return;
                }
                final int useDone = done;
                final int useSkipped = skipped;
                final int useFailed = failed.size();
                final boolean useDisconnected = disconnected;
                runOnUiThread(() -> {
                    if (useDisconnected) {
                        ui.setStatusText(getString(R.string.slimming_adb_disconnected));
                    } else {
                        ui.setStatusText(getString(R.string.slimming_restore_done, useDone, useSkipped, useFailed));
                    }
                    refreshStates();
                });
            } finally {
                setBusy(false);
            }
        }).start();
    }

    private void toggleRow(String pkg) {
        if (busy) return;
        PackageManager pm = getPackageManager();
        ApplicationInfo ai;
        try {
            ai = pm.getApplicationInfo(pkg, 0);
        } catch (PackageManager.NameNotFoundException e) {
            ai = null;
        }
        final String name = appName(pm, ai, pkg);
        if (ai == null) {
            toastDisable(getString(R.string.slimming_toast_not_installed, name), Toast.LENGTH_SHORT);
            return;
        }
        final boolean enable = !ai.enabled;
        setBusy(true);
        new Thread(() -> {
            try {
                try (Adb adb = Adb.connect(getApplicationContext(), 120_000, () ->
                        runOnUiThread(() -> ui.setStatusText(getString(R.string.slimming_disable_waiting))))) {
                    adb.shell((enable ? "pm enable " : "pm disable-user ") + pkg);
                } catch (IOException e) {
                    String msg = e.getMessage() == null ? "" : e.getMessage();
                    final int res = adbFailureRes(msg);
                    runOnUiThread(() -> {
                        ui.setStatusText(getString(res, msg));
                        Toast.makeText(SystemSlimmingActivity.this, getString(res, msg), Toast.LENGTH_LONG).show();
                    });
                    return;
                }
                boolean nowEnabled = false;
                try {
                    nowEnabled = getPackageManager().getApplicationInfo(pkg, 0).enabled;
                } catch (PackageManager.NameNotFoundException e) {
                    nowEnabled = false;
                }
                if (enable == nowEnabled) {
                    toastDisable(getString(enable ? R.string.slimming_toast_enabled : R.string.slimming_toast_row_disabled, name), Toast.LENGTH_SHORT);
                } else {
                    toastDisable(getString(enable ? R.string.slimming_toast_enable_failed : R.string.slimming_toast_disable_failed, name), Toast.LENGTH_SHORT);
                }
                runOnUiThread(() -> refreshStates());
            } finally {
                setBusy(false);
            }
        }).start();
    }

    private void refreshStateList(PackageManager pm, List<Row> list) {
        for (int i = 0; i < list.size(); i++) {
            Row old = list.get(i);
            Row row = new Row(old.pkg);
            try {
                ApplicationInfo ai = pm.getApplicationInfo(row.pkg, 0);
                row.label = String.valueOf(pm.getApplicationLabel(ai));
                row.state = ai.enabled ? STATE_INSTALLED : STATE_DISABLED;
                row.icon = drawableToBitmap(ai.loadIcon(pm));
            } catch (PackageManager.NameNotFoundException e) {
                row.label = row.pkg;
                row.state = STATE_NOT_INSTALLED;
            }
            list.set(i, row);
        }
    }

    private static Bitmap drawableToBitmap(Drawable d) {
        if (d == null) return null;
        if (d instanceof BitmapDrawable) return ((BitmapDrawable) d).getBitmap();
        int w = Math.max(1, d.getIntrinsicWidth());
        int h = Math.max(1, d.getIntrinsicHeight());
        Bitmap b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(b);
        d.setBounds(0, 0, w, h);
        d.draw(c);
        return b;
    }

    private void showFolderPicker() {
        if (folders == null || folders.isEmpty()) {
            Toast.makeText(this, R.string.slimming_loading, Toast.LENGTH_SHORT).show();
            return;
        }
        ui.setPickerFolders(sortFolders(folders));
        ui.setShowPicker(true);
    }

    private static List<String> sortFolders(List<String> folders) {
        String brand = normalise(Build.BRAND == null ? "" : Build.BRAND);
        List<String> same = new ArrayList<>();
        for (String f : folders) {
            if (!brand.isEmpty() && normalise(f).startsWith(brand)) same.add(f);
        }
        java.util.Collections.sort(same);
        if (!same.isEmpty()) return same;
        List<String> all = new ArrayList<>(folders);
        java.util.Collections.sort(all);
        return all;
    }

    private static List<String> liveFolders() {
        String json = fetchText(EXAMPLES_API);
        if (json == null) return null;
        try {
            JSONArray arr = new JSONArray(json);
            List<String> out = new ArrayList<>();
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.optJSONObject(i);
                if (o != null && "dir".equals(o.optString("type"))) out.add(o.optString("name"));
            }
            return out.isEmpty() ? null : out;
        } catch (Exception e) {
            return null;
        }
    }

    private List<String> snapshotFolders() {
        List<String> out = new ArrayList<>();
        try (InputStream in = getAssets().open(SNAPSHOT_ASSET);
             ZipInputStream zip = new ZipInputStream(in)) {
            ZipEntry e;
            while ((e = zip.getNextEntry()) != null) {
                String name = e.getName();
                if (name.startsWith("examples/") && name.endsWith("/debloat.txt")) {
                    out.add(name.substring("examples/".length(), name.length() - "/debloat.txt".length()));
                }
            }
            return out.isEmpty() ? null : out;
        } catch (Exception e) {
            return null;
        }
    }

    private String snapshotTvJson(String folder) {
        byte[] raw = snapshotEntry(folder, "tv.json");
        return raw == null ? null : new String(raw, StandardCharsets.UTF_8);
    }

    private byte[] snapshotEntry(String folder) {
        return snapshotEntry(folder, "debloat.txt");
    }

    private byte[] snapshotEntry(String folder, String file) {
        String want = "examples/" + folder + "/" + file;
        try (InputStream in = getAssets().open(SNAPSHOT_ASSET);
             ZipInputStream zip = new ZipInputStream(in)) {
            ZipEntry e;
            while ((e = zip.getNextEntry()) != null) {
                if (want.equals(e.getName())) return readAll(zip);
            }
            return null;
        } catch (Exception e) {
            return null;
        }
    }

    private String snapshotDate() {
        try (InputStream in = getAssets().open(SNAPSHOT_ASSET);
             ZipInputStream zip = new ZipInputStream(in)) {
            ZipEntry e;
            while ((e = zip.getNextEntry()) != null) {
                if ("snapshot.json".equals(e.getName())) {
                    String json = new String(readAll(zip), StandardCharsets.UTF_8);
                    return new JSONObject(json).optString("generated");
                }
            }
            return "";
        } catch (Exception e) {
            return "";
        }
    }

    private static byte[] fetchBytes(String urlStr, long maxBytes) {
        try {
            HttpURLConnection conn = (HttpURLConnection) new URL(urlStr).openConnection();
            conn.setConnectTimeout(15000);
            conn.setReadTimeout(15000);
            conn.setRequestProperty("User-Agent", "cleanyourtv-fork-websettings");
            try {
                if (conn.getResponseCode() != 200) return null;
                try (InputStream in = conn.getInputStream()) {
                    ByteArrayOutputStream out = new ByteArrayOutputStream();
                    byte[] buf = new byte[8192];
                    int n;
                    long total = 0;
                    while ((n = in.read(buf)) != -1) {
                        total += n;
                        if (total > maxBytes) return null;
                        out.write(buf, 0, n);
                    }
                    return out.toByteArray();
                }
            } finally {
                conn.disconnect();
            }
        } catch (Exception e) {
            Log.w(TAG, "fetchBytes " + urlStr + ": " + e.getMessage());
            return null;
        }
    }

    private static String fetchText(String urlStr) {
        byte[] b = fetchBytes(urlStr, 1024 * 1024);
        if (b == null) return null;
        try {
            return new String(b, "UTF-8");
        } catch (Exception e) {
            return null;
        }
    }

    private static byte[] readAll(InputStream in) throws java.io.IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) != -1) out.write(buf, 0, n);
        return out.toByteArray();
    }

    static final class Debloat {
        final List<String> recommended;
        final List<String> optional;

        Debloat(List<String> recommended, List<String> optional) {
            this.recommended = recommended;
            this.optional = optional;
        }

        boolean isEmpty() {
            return recommended.isEmpty() && optional.isEmpty();
        }
    }

    static Debloat parseDebloat(String text) {
        LinkedHashSet<String> rec = new LinkedHashSet<>();
        LinkedHashSet<String> opt = new LinkedHashSet<>();
        for (String line : text.split("\n")) {
            String s = line.trim();
            if (s.isEmpty()) continue;
            if (s.startsWith("#")) {
                String t = s.substring(1).trim();
                int space = t.indexOf(' ');
                if (space > 0) t = t.substring(0, space);
                int tab = t.indexOf('\t');
                if (tab > 0) t = t.substring(0, tab);
                if (isPackageName(t)) opt.add(t);
                continue;
            }
            int space = s.indexOf(' ');
            if (space > 0) s = s.substring(0, space);
            int tab = s.indexOf('\t');
            if (tab > 0) s = s.substring(0, tab);
            if (isPackageName(s)) rec.add(s);
        }
        return new Debloat(new ArrayList<>(rec), new ArrayList<>(opt));
    }

    static boolean isPackageName(String s) {
        if (s == null || s.isEmpty() || s.indexOf('.') < 0) return false;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (!((c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z')
                    || (c >= '0' && c <= '9') || c == '.' || c == '_')) return false;
        }
        return true;
    }

    static String normalise(String s) {
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char c = Character.toLowerCase(s.charAt(i));
            if ((c >= 'a' && c <= 'z') || (c >= '0' && c <= '9')) out.append(c);
        }
        return out.toString();
    }

    static List<String> tokens(String s) {
        List<String> out = new ArrayList<>();
        if (s == null) return out;
        for (String t : s.toLowerCase(java.util.Locale.ROOT).split("[^a-z0-9]+")) {
            if (!t.isEmpty()) out.add(t);
        }
        return out;
    }

    static boolean containsInOrder(List<String> have, List<String> need) {
        int i = 0;
        for (String h : have) {
            if (i < need.size() && h.equals(need.get(i))) i++;
        }
        return i == need.size();
    }

    static final class Match {
        static final int HIGH = 2;
        static final int NONE = 0;
        final int level;
        final String folder;

        Match(int level, String folder) {
            this.level = level;
            this.folder = folder;
        }
    }

    static Match matchDevice(List<String> folders) {
        return matchDevice(Build.MANUFACTURER, Build.MODEL, Build.DEVICE, Build.PRODUCT, folders);
    }

    static Match matchDevice(String maker, String model, String device, String product,
                             List<String> folders) {
        List<String> makerTokens = tokens(maker);
        Set<String> candidates = new LinkedHashSet<>();
        for (String base : new String[]{model, device, product}) {
            if (base == null || base.trim().isEmpty()) continue;
            candidates.add(normalise(maker + base));
            candidates.add(normalise(base));
        }
        List<List<String>> codenameTokens = new ArrayList<>();
        for (String base : new String[]{device, product}) {
            List<String> t = tokens(base);
            if (!t.isEmpty()) codenameTokens.add(t);
        }
        List<String> highs = new ArrayList<>();
        for (String folder : folders) {
            String f = normalise(folder);
            List<String> ft = tokens(folder);
            if (f.isEmpty()) continue;
            boolean high = false;
            for (String c : candidates) {
                if (!c.isEmpty() && c.equals(f)) high = true;
            }
            if (!high) {
                for (List<String> ct : codenameTokens) {
                    if (containsInOrder(ft, ct)
                            && (makerTokens.isEmpty() || !java.util.Collections.disjoint(ft, makerTokens))) {
                        high = true;
                        break;
                    }
                }
            }
            if (high) {
                if (!highs.contains(folder)) highs.add(folder);
                continue;
            }
        }
        if (highs.size() == 1) return new Match(Match.HIGH, highs.get(0));
        if (highs.size() > 1) {
            String base = null;
            for (String h : highs) {
                List<String> ht = tokens(h);
                boolean prefixOfAll = true;
                for (String o : highs) {
                    if (o.equals(h)) continue;
                    List<String> ot = tokens(o);
                    if (ot.size() <= ht.size()
                            || !ot.subList(0, ht.size()).equals(ht)) {
                        prefixOfAll = false;
                        break;
                    }
                }
                if (prefixOfAll) {
                    if (base != null) return new Match(Match.NONE, null);
                    base = h;
                }
            }
            if (base != null) return new Match(Match.HIGH, base);
        }
        return new Match(Match.NONE, null);
    }


    static void slimmingDailyCheckIfDue(Context ctx) {
        if (!Config.prefs(ctx).getBoolean(KEY_SLIMMING_DAILY, true)) return;
        long last = Config.prefs(ctx).getLong(KEY_SLIMMING_LAST_DAILY, 0);
        if (System.currentTimeMillis() - last < 24L * 60 * 60 * 1000) return;
        new Thread(() -> {
            refreshSlimmingCache(ctx.getApplicationContext());
            Config.prefs(ctx).edit()
                    .putLong(KEY_SLIMMING_LAST_DAILY, System.currentTimeMillis()).apply();
        }).start();
    }

    public static void refreshSlimmingCache(Context ctx) {
        String folder = Config.prefs(ctx).getString(KEY_FOLDER, null);
        if (folder == null) return;
        byte[] live = fetchBytes(EXAMPLES_RAW + folder + "/debloat.txt", MAX_LIST_BYTES);
        if (live == null) return;
        String liveText = new String(live, StandardCharsets.UTF_8);
        Debloat debloat = parseDebloat(liveText);
        if (debloat.isEmpty()) return;
        String label = folder;
        String tvJson = fetchText(EXAMPLES_RAW + folder + "/tv.json");
        if (tvJson != null) {
            try {
                JSONObject o = new JSONObject(tvJson);
                String tv = o.optString("tv");
                if (!tv.isEmpty()) label = tv;
            } catch (Exception ignored) {
            }
        }
        if (label.equals(folder)) {
            String cachedLabel = Config.prefs(ctx).getString(KEY_LABEL, "");
            if (!cachedLabel.isEmpty()) label = cachedLabel;
        }
        String cached = Config.prefs(ctx).getString(KEY_LIST, null);
        if (cached != null) {
            Debloat cachedDebloat = parseDebloat(cached);
            if (debloat.recommended.equals(cachedDebloat.recommended)
                    && debloat.optional.equals(cachedDebloat.optional)
                    && label.equals(Config.prefs(ctx).getString(KEY_LABEL, ""))) return;
        }
        Config.prefs(ctx).edit()
                .putString(KEY_LIST, liveText)
                .putString(KEY_DATE, LocalDate.now().toString())
                .putString(KEY_LABEL, label)
                .apply();
    }

    public static final class Row {
        final String pkg;
        String label;
        int state = STATE_NOT_INSTALLED;
        Bitmap icon;

        Row(String pkg) {
            this.pkg = pkg;
            this.label = pkg;
        }
    }
}
