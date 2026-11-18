package com.gathin.tvhome;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.util.Log;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.widget.Toast;

import androidx.activity.ComponentActivity;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
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
        super.attachBaseContext(LauncherLocale.wrap(base));
    }

    private static final String EXAMPLES_API =
            "https://api.github.com/repos/" + UPSTREAM_REPO + "/contents/examples";
    private static final String EXAMPLES_RAW =
            "https://raw.githubusercontent.com/" + UPSTREAM_REPO + "/main/examples/";
    private static final String SNAPSHOT_ASSET = "slimming/debloat-snapshot.zip";
    private static final long MAX_LIST_BYTES = 256 * 1024;

    private static final String KEY_FOLDER = "slimming_folder";
    private static final String KEY_LIST = "slimming_list";
    private static final String KEY_DATE = "slimming_date";
    private static final String KEY_LABEL = "slimming_label";
    private static final String KEY_NOTES = "slimming_notes";
    static final String KEY_SLIMMING_DAILY = "slimming_daily_check";
    private static final String KEY_SLIMMING_LAST_DAILY = "slimming_last_daily_check";

    static final int STATE_INSTALLED = 0;
    static final int STATE_DISABLED = 1;
    static final int STATE_NOT_INSTALLED = 2;

    private final SlimmingUiState ui = new SlimmingUiState();

    private List<String> folders;
    private String currentLabel;
    private boolean loading;
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
            @Override public void onRow(String pkg) { openDetails(pkg); }
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
        showNotes(Config.prefs(this).getString(KEY_NOTES, ""));
        return true;
    }

    private void showNotes(String notes) {
        ui.setNotes(notes == null || notes.isEmpty() ? null : notes);
    }

    private void loadAll() {
        if (loading) return;
        loading = true;
        runOnUiThread(() -> {
            ui.setShowRetry(false);
            if (ui.getRecommended().isEmpty() && ui.getOptional().isEmpty()) ui.setStatusText(getString(R.string.slimming_loading));
        });
        new Thread(() -> {
            List<String> useFolders = liveFolders();
            if (useFolders == null) useFolders = snapshotFolders();
            final List<String> found = useFolders;
            runOnUiThread(() -> {
                folders = found;
                if (folders == null) {
                    ui.setStatusText(getString(R.string.slimming_failed_folders));
                    ui.setShowRetry(true);
                    loading = false;
                    return;
                }
                ui.setShowPick(true);
                Match match = matchDevice(folders);
                if (match.level == Match.HIGH) {
                    loadList(match.folder, true);
                } else {
                    if (match.closest != null) {
                        ui.setStatusText(getString(R.string.slimming_match_medium, match.closest));
                    } else {
                        ui.setStatusText(getString(R.string.slimming_match_none));
                    }
                    ui.setListText(getString(R.string.slimming_list_none));
                    ui.setSourceText("");
                    showNotes("");
                    loading = false;
                }
            });
        }).start();
    }

    private void loadList(String folder, boolean autoHigh) {
        pendingFolder = folder;
        new Thread(() -> {
            String today = LocalDate.now().toString();
            String label = folder;
            String notes = "";
            String tvJson = fetchText(EXAMPLES_RAW + folder + "/tv.json", null);
            if (tvJson == null) tvJson = snapshotTvJson(folder);
            if (tvJson != null) {
                try {
                    JSONObject o = new JSONObject(tvJson);
                    String tv = o.optString("tv");
                    if (!tv.isEmpty()) label = tv;
                    notes = o.optString("notes");
                } catch (Exception ignored) {
                }
            }
            if (label.equals(folder) && folder.equals(Config.prefs(this).getString(KEY_FOLDER, ""))) {
                String cachedLabel = Config.prefs(this).getString(KEY_LABEL, "");
                if (!cachedLabel.isEmpty()) label = cachedLabel;
                notes = Config.prefs(this).getString(KEY_NOTES, "");
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
            final String useNotes = notes;
            final String useSource = source;
            final Debloat useDebloat = debloat;
            final String useLiveText = liveText;
            final String useFolder = folder;
            final String useDate = date;
            final boolean useAutoHigh = autoHigh;
            runOnUiThread(() -> {
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
                showNotes(useNotes);
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
                            .putString(KEY_NOTES, useNotes)
                            .apply();
                }
                refreshStates();
                ui.setFocusFirst(ui.getFocusFirst() + 1);
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

    private void refreshStateList(PackageManager pm, List<Row> list) {
        for (int i = 0; i < list.size(); i++) {
            Row old = list.get(i);
            Row row = new Row(old.pkg);
            try {
                ApplicationInfo ai = pm.getApplicationInfo(row.pkg, 0);
                row.label = String.valueOf(pm.getApplicationLabel(ai));
                row.state = ai.enabled ? STATE_INSTALLED : STATE_DISABLED;
            } catch (PackageManager.NameNotFoundException e) {
                row.label = row.pkg;
                row.state = STATE_NOT_INSTALLED;
            }
            list.set(i, row);
        }
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
        String json = fetchText(EXAMPLES_API, null);
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

    private static String fetchText(String urlStr, String etag) {
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
            if (!s.isEmpty()) rec.add(s);
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
        final String closest;

        Match(int level, String folder, String closest) {
            this.level = level;
            this.folder = folder;
            this.closest = closest;
        }
    }

    static Match matchDevice(List<String> folders) {
        return matchDevice(Build.MANUFACTURER, Build.MODEL, Build.DEVICE, Build.PRODUCT, folders);
    }

    static Match matchDevice(String maker, String model, String device, String product,
                             List<String> folders) {
        String makerNorm = normalise(maker == null ? "" : maker);
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
        Set<String> buildTokens = new LinkedHashSet<>();
        for (String base : new String[]{model, device, product}) buildTokens.addAll(tokens(base));
        buildTokens.removeAll(makerTokens);

        List<String> highs = new ArrayList<>();
        String bestMedium = null;
        int bestShared = 0;
        int bestContain = 0;
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
            int shared = 0;
            for (String t : buildTokens) if (ft.contains(t)) shared++;
            int contain = 0;
            for (String c : candidates) {
                if (c.isEmpty()) continue;
                if (c.contains(f) || f.contains(c)) {
                    int overlap = Math.min(c.length(), f.length());
                    if (overlap > makerNorm.length() && overlap > contain) contain = overlap;
                }
            }
            if ((shared > 0 || contain > 0)
                    && (shared > bestShared || (shared == bestShared && contain > bestContain))) {
                bestShared = shared;
                bestContain = contain;
                bestMedium = folder;
            }
        }
        if (highs.size() == 1) return new Match(Match.HIGH, highs.get(0), null);
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
                    if (base != null) return new Match(Match.NONE, null, bestMedium);
                    base = h;
                }
            }
            if (base != null) return new Match(Match.HIGH, base, null);
        }
        return new Match(Match.NONE, null, bestMedium);
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
        String notes = "";
        String tvJson = fetchText(EXAMPLES_RAW + folder + "/tv.json", null);
        if (tvJson != null) {
            try {
                JSONObject o = new JSONObject(tvJson);
                String tv = o.optString("tv");
                if (!tv.isEmpty()) label = tv;
                notes = o.optString("notes");
            } catch (Exception ignored) {
            }
        }
        if (label.equals(folder)) {
            String cachedLabel = Config.prefs(ctx).getString(KEY_LABEL, "");
            if (!cachedLabel.isEmpty()) label = cachedLabel;
            notes = Config.prefs(ctx).getString(KEY_NOTES, "");
        }
        String cached = Config.prefs(ctx).getString(KEY_LIST, null);
        if (cached != null) {
            Debloat cachedDebloat = parseDebloat(cached);
            if (debloat.recommended.equals(cachedDebloat.recommended)
                    && debloat.optional.equals(cachedDebloat.optional)
                    && label.equals(Config.prefs(ctx).getString(KEY_LABEL, ""))
                    && notes.equals(Config.prefs(ctx).getString(KEY_NOTES, ""))) return;
        }
        Config.prefs(ctx).edit()
                .putString(KEY_LIST, liveText)
                .putString(KEY_DATE, LocalDate.now().toString())
                .putString(KEY_LABEL, label)
                .putString(KEY_NOTES, notes)
                .apply();
    }

    public static final class Row {
        final String pkg;
        String label;
        int state = STATE_NOT_INSTALLED;

        Row(String pkg) {
            this.pkg = pkg;
            this.label = pkg;
        }
    }

    private void openDetails(String pkg) {
        try {
            startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.parse("package:" + pkg)));
        } catch (Exception e) {
            Toast.makeText(this, R.string.not_available, Toast.LENGTH_LONG).show();
        }
    }
}
