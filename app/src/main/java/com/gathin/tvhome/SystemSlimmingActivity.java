package com.gathin.tvhome;

import android.app.Activity;
import android.app.AlertDialog;
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
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import android.widget.Toast;

import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

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

/**
 * System Slimming (fork only, v3): on-TV manual debloat. The screen detects
 * the device (Build.MANUFACTURER + Build.MODEL + Build.DEVICE + Build.PRODUCT),
 * matches it against the upstream per-model lists (examples/<folder>/debloat.txt),
 * and shows each package with its live state from PackageManager. Tap a row
 * opens the SYSTEM app-details page for that package, so the user disables it
 * there by hand. The app never disables anything itself — a normal app
 * cannot disable other packages.
 *
 * Fetch order for a list: (1) live from upstream (always tried first, so the
 * data stays the latest while upstream exists); (2) the last fetched list
 * cached on the device; (3) the bundled snapshot (assets/slimming/
 * debloat-snapshot.zip, built from this fork's examples/ at build time — the
 * fallback for a fresh install when upstream is gone for good). Every list
 * shown is labelled with its source and date, so the user always knows how
 * fresh it is. A fetch failure never empties the screen: the failure is shown
 * and the last list (cached or snapshot) stays, labelled.
 *
 * The bundled snapshot is read-only — an APK's assets can never be overwritten
 * on the device, so the snapshot is never modified. Daily freshness comes from
 * the on-device cache instead: it is refreshed once a day (24h-gated, from this
 * activity's onCreate, default ON) and once more, silently, every time this
 * screen opens (see refreshSlimmingCache). Both are check-only: they replace
 * the cached list, never disable anything.
 */
public final class SystemSlimmingActivity extends Activity {
    private static final String TAG = "TvHome.SystemSlimming";
    /** Upstream repo the slimming lists are fetched from (same repo as before). */
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

    private static final int STATE_INSTALLED = 0;
    private static final int STATE_DISABLED = 1;
    private static final int STATE_NOT_INSTALLED = 2;

    private TextView deviceView;
    private TextView listView;
    private TextView sourceView;
    private TextView statusView;
    private TextView notesView;
    private TextView dailyChoice;
    private TextView pickChoice;
    private TextView retryChoice;
    private RecyclerView listViewRec;
    private RecyclerView listViewOpt;
    private SlimmingAdapter adapterRec;
    private SlimmingAdapter adapterOpt;
    private final List<Row> rowsRec = new ArrayList<>();
    private final List<Row> rowsOpt = new ArrayList<>();

    /** Folder names from the live API, or from the snapshot when the API is unreachable. */
    private List<String> folders;
    private String currentLabel;
    private boolean loading;
    /** The folder the last load attempt was for, so Retry re-loads that folder
     *  instead of re-matching from scratch (a hand-picked folder stays picked). */
    private String pendingFolder;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        setContentView(R.layout.activity_system_slimming);
        cleanupLegacyState(this);
        deviceView = findViewById(R.id.slimDevice);
        listView = findViewById(R.id.slimList);
        sourceView = findViewById(R.id.slimSource);
        statusView = findViewById(R.id.slimStatus);
        notesView = findViewById(R.id.slimNotes);
        dailyChoice = findViewById(R.id.slimDailyChoice);
        dailyChoice.setOnClickListener(v -> {
            boolean next = !Config.prefs(this).getBoolean(KEY_SLIMMING_DAILY, true);
            Config.prefs(this).edit().putBoolean(KEY_SLIMMING_DAILY, next).apply();
            renderDaily();
        });
        pickChoice = findViewById(R.id.slimPickChoice);
        retryChoice = findViewById(R.id.slimRetryChoice);
        listViewRec = findViewById(R.id.slimRowsRec);
        listViewRec.setLayoutManager(new LinearLayoutManager(this));
        // No setHasFixedSize(true): the list is wrap_content inside the ScrollView, so it must
        // re-measure when the rows land — a fixed size would pin it to its first (empty) height.
        listViewRec.setNestedScrollingEnabled(false);
        adapterRec = new SlimmingAdapter(rowsRec);
        listViewRec.setAdapter(adapterRec);
        listViewOpt = findViewById(R.id.slimRowsOpt);
        listViewOpt.setLayoutManager(new LinearLayoutManager(this));
        listViewOpt.setNestedScrollingEnabled(false);
        adapterOpt = new SlimmingAdapter(rowsOpt);
        listViewOpt.setAdapter(adapterOpt);
        pickChoice.setOnClickListener(v -> showFolderPicker());
        retryChoice.setOnClickListener(v -> {
            if (pendingFolder != null) loadList(pendingFolder, false);
            else loadAll();
        });
        deviceView.setText(getString(R.string.slimming_device, deviceName()));
        renderDaily();
        // The cached list (if any) shows immediately, labelled; the live load
        // replaces it when it succeeds, or the failure note explains why not.
        if (!showCached()) statusView.setText(R.string.slimming_loading);
        loadAll();
        // The triggers the desktop used to own: the 24h-gated daily refresh and the
        // silent refresh on every open, both check-only (see below).
        slimmingDailyCheckIfDue(this);
        new Thread(() -> refreshSlimmingCache(getApplicationContext())).start();
    }

    /**
     * One-time upgrade cleanup: the desktop is gone, so the credentials and theme
     * residue it left behind are removed. Idempotent: removing an absent key and
     * deleting an absent file are both no-ops, and a marker records that the pass
     * ran. The values are never read or logged — only the keys are removed.
     * Theme residue: the overlay (config.json, plus the internal-files copy the
     * old overlay reader kept), the pushed wallpaper, the wallpapers collection,
     * the theme revision token and the apply snapshot/rollback backup.
     */
    private static void cleanupLegacyState(Context ctx) {
        SharedPreferences p = Config.prefs(ctx);
        if (p.getBoolean("cleanup_done_v13", false)) return;
        p.edit()
                .remove("tmdb_api_key").remove("tmdb_enabled")
                // Weather keys are NOT removed here: the merged build keeps them (see WeatherClient).
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
        // States are never trusted from before: re-query every package, so a
        // package the user just disabled on the system page shows its green
        // check the moment they come back.
        if (!rowsRec.isEmpty() || !rowsOpt.isEmpty()) refreshStates();
    }

    private void renderDaily() {
        dailyChoice.setText(getString(R.string.slimming_daily_state,
                getString(Config.prefs(this).getBoolean(KEY_SLIMMING_DAILY, true)
                        ? R.string.on : R.string.off)));
    }

    /** "Sony KD-43X85K" from the build strings; falls back to DEVICE/PRODUCT when MODEL is empty. */
    private static String deviceName() {
        String maker = Build.MANUFACTURER == null ? "" : Build.MANUFACTURER.trim();
        String model = Build.MODEL == null ? "" : Build.MODEL.trim();
        if (model.isEmpty()) model = Build.DEVICE == null ? "" : Build.DEVICE.trim();
        if (model.isEmpty()) model = Build.PRODUCT == null ? "" : Build.PRODUCT.trim();
        if (maker.isEmpty()) return model;
        if (model.isEmpty()) return maker;
        return maker + " " + model;
    }

    /** Shows the cached list, if the device ever fetched one. True when one was shown. */
    private boolean showCached() {
        String folder = Config.prefs(this).getString(KEY_FOLDER, null);
        String list = Config.prefs(this).getString(KEY_LIST, null);
        String date = Config.prefs(this).getString(KEY_DATE, null);
        if (folder == null || list == null) return false;
        Debloat debloat = parseDebloat(list);
        if (debloat.isEmpty()) return false;
        currentLabel = Config.prefs(this).getString(KEY_LABEL, folder);
        setRows(debloat);
        listView.setText(getString(R.string.slimming_list, currentLabel,
                debloat.recommended.size(), debloat.optional.size()));
        sourceView.setText(getString(R.string.slimming_cached, date == null ? "" : date));
        showNotes(Config.prefs(this).getString(KEY_NOTES, ""));
        return true;
    }

    /** The list's region/verification note, verbatim from its tv.json. Hidden when there is none. */
    private void showNotes(String notes) {
        if (notes == null || notes.isEmpty()) {
            notesView.setVisibility(View.GONE);
        } else {
            notesView.setText(notes);
            notesView.setVisibility(View.VISIBLE);
        }
    }

    /** The whole load, off the main thread: folder list, match, then the list itself. */
    private void loadAll() {
        if (loading) return;
        loading = true;
        runOnUiThread(() -> {
            retryChoice.setVisibility(View.GONE);
            if (rowsRec.isEmpty() && rowsOpt.isEmpty()) statusView.setText(R.string.slimming_loading);
        });
        new Thread(() -> {
            List<String> useFolders = liveFolders();
            if (useFolders == null) useFolders = snapshotFolders();
            final List<String> found = useFolders;
            runOnUiThread(() -> {
                folders = found;
                if (folders == null) {
                    // No folder list at all (API down and the snapshot asset
                    // missing): keep whatever is on screen and say so.
                    statusView.setText(R.string.slimming_failed_folders);
                    retryChoice.setVisibility(View.VISIBLE);
                    loading = false;
                    return;
                }
                pickChoice.setVisibility(View.VISIBLE);
                Match match = matchDevice(folders);
                if (match.level == Match.HIGH) {
                    loadList(match.folder, true);
                } else {
                    // No confident match: say so plainly and let the user pick
                    // instead of guessing silently. The closest medium match,
                    // if any, is named so the user can judge it.
                    if (match.closest != null) {
                        statusView.setText(getString(R.string.slimming_match_medium, match.closest));
                    } else {
                        statusView.setText(R.string.slimming_match_none);
                    }
                    listView.setText(getString(R.string.slimming_list_none));
                    sourceView.setText("");
                    showNotes("");
                    loading = false;
                }
            });
        }).start();
    }

    /** Loads one folder's list: live, then the cache for that folder, then the snapshot.
     *  autoHigh is true only when the folder came from an exact device match, so the
     *  "high match" note is shown then and never for a hand-picked list. */
    private void loadList(String folder, boolean autoHigh) {
        pendingFolder = folder;
        new Thread(() -> {
            String today = LocalDate.now().toString();
            String label = folder;
            String notes = "";
            // The label and the region/verification note come from tv.json:
            // live first, then the bundled snapshot's copy, then the cache.
            // (The label itself carries the region, e.g. "Australia & India".)
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
                    statusView.setText(getString(R.string.slimming_failed, useFolder));
                    retryChoice.setVisibility(View.VISIBLE);
                    return;
                }
                currentLabel = useLabel;
                setRows(useDebloat);
                listView.setText(getString(R.string.slimming_list, useLabel,
                        useDebloat.recommended.size(), useDebloat.optional.size()));
                sourceView.setText(useSource == null ? "" : useSource);
                showNotes(useNotes);
                if (useDebloat.isEmpty()) {
                    statusView.setText(R.string.slimming_empty);
                } else if (useAutoHigh) {
                    statusView.setText(R.string.slimming_match_high);
                } else {
                    statusView.setText("");
                }
                if (useDate != null && !useDebloat.isEmpty() && useLiveText != null) {
                    // A live fetch is the only thing worth caching: it is the
                    // newest list, and it is what the next failure falls back to.
                    // The raw text is cached, so the #-marked optional items
                    // survive the round trip (join() of the parsed list would
                    // lose them).
                    Config.prefs(this).edit()
                            .putString(KEY_FOLDER, useFolder)
                            .putString(KEY_LIST, useLiveText)
                            .putString(KEY_DATE, useDate)
                            .putString(KEY_LABEL, useLabel)
                            .putString(KEY_NOTES, useNotes)
                            .apply();
                }
                refreshStates();
                listViewRec.post(() -> {
                    RecyclerView.ViewHolder h = listViewRec.findViewHolderForAdapterPosition(0);
                    if (h != null) h.itemView.requestFocus();
                });
            });
        }).start();
    }

    private void setRows(Debloat debloat) {
        rowsRec.clear();
        for (String pkg : debloat.recommended) rowsRec.add(new Row(pkg));
        rowsOpt.clear();
        for (String pkg : debloat.optional) rowsOpt.add(new Row(pkg));
        adapterRec.notifyDataSetChanged();
        adapterOpt.notifyDataSetChanged();
    }

    /** Re-queries every package's live state; the green check only ever comes from here. */
    private void refreshStates() {
        new Thread(() -> {
            PackageManager pm = getPackageManager();
            refreshStateList(pm, rowsRec);
            refreshStateList(pm, rowsOpt);
            runOnUiThread(() -> {
                adapterRec.notifyDataSetChanged();
                adapterOpt.notifyDataSetChanged();
            });
        }).start();
    }

    private void refreshStateList(PackageManager pm, List<Row> list) {
        for (Row row : list) {
            try {
                ApplicationInfo ai = pm.getApplicationInfo(row.pkg, 0);
                row.label = String.valueOf(pm.getApplicationLabel(ai));
                row.state = ai.enabled ? STATE_INSTALLED : STATE_DISABLED;
            } catch (PackageManager.NameNotFoundException e) {
                row.label = row.pkg;
                row.state = STATE_NOT_INSTALLED;
            }
        }
    }

    private void showFolderPicker() {
        if (folders == null || folders.isEmpty()) {
            Toast.makeText(this, R.string.slimming_loading, Toast.LENGTH_SHORT).show();
            return;
        }
        List<String> sorted = sortFolders(folders);
        new AlertDialog.Builder(this)
                .setTitle(R.string.slimming_pick)
                .setItems(sorted.toArray(new String[0]), (dialog, which) -> loadList(sorted.get(which), false))
                .show();
    }

    /** Only this brand's folders, alphabetical. The brand (Build.BRAND) cannot be
     *  wrong even when the model is, so the picker no longer lists every other brand.
     *  When this brand has none, all folders (alphabetical) so the picker is never empty. */
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

    /** The live folder list from the GitHub contents API; null on any failure. */
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

    /** Folder names from the bundled snapshot asset; null when the asset is missing. */
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

    /** One folder's tv.json out of the bundled snapshot; null when the folder has none in it. */
    private String snapshotTvJson(String folder) {
        byte[] raw = snapshotEntry(folder, "tv.json");
        return raw == null ? null : new String(raw, StandardCharsets.UTF_8);
    }

    /** One folder's list out of the bundled snapshot; null when the folder is not in it. */
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

    /** The snapshot's build date from its manifest; "" when unreadable. */
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

    /** GET bytes, capped; null on any failure or non-200 (never throws for a bad download). */
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

    /** One debloat.txt, split in two: the default (uncommented) packages are
     *  the "recommended to disable" column, the #-commented package names are
     *  the "choose by your use" column. A #-line only counts as optional when
     *  its first token looks like a package name (has a dot); plain #-comments
     *  are still skipped. */
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

    /** A package name: letters/digits/dots/underscores, with at least one dot. */
    static boolean isPackageName(String s) {
        if (s == null || s.isEmpty() || s.indexOf('.') < 0) return false;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (!((c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z')
                    || (c >= '0' && c <= '9') || c == '.' || c == '_')) return false;
        }
        return true;
    }

    /** Lowercase, alphanumerics only: "BRAVIA 4K VH22" and "bravia-4k-vh22" become one string. */
    static String normalise(String s) {
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char c = Character.toLowerCase(s.charAt(i));
            if ((c >= 'a' && c <= 'z') || (c >= '0' && c <= '9')) out.append(c);
        }
        return out.toString();
    }

    /** Lowercase alphanumeric tokens: "BRAVIA_VH21" -> [bravia, vh21];
     *  "sony-bravia-4k-vh21" -> [sony, bravia, 4k, vh21]. */
    static List<String> tokens(String s) {
        List<String> out = new ArrayList<>();
        if (s == null) return out;
        for (String t : s.toLowerCase(java.util.Locale.ROOT).split("[^a-z0-9]+")) {
            if (!t.isEmpty()) out.add(t);
        }
        return out;
    }

    /** True when every token of `need` appears in `have`, in order (not necessarily adjacent). */
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

    /** Device → folder match. The folders are keyed by platform/device codename,
     *  not by marketing size: a KD-43X85K reports Build.DEVICE "BRAVIA_VH21" and
     *  its list lives in sony-bravia-4k-vh21/, whose header names the KD-65X85K.
     *  HIGH is (1) an exact normalised match on any build-string combination, or
     *  (2) the device/product codename's tokens all appearing in the folder's
     *  tokens, in order, with the maker token present — the platform-key rule.
     *  anything else (including several HIGHs, which are ambiguous) is no match.
     *  One exception to ambiguity: several HIGHs that differ only by a regional
     *  suffix (-eu/-sa/-tw/-us) resolve to the unsuffixed base folder, which is
     *  the generic list; the region note is shown so the user can switch to a
     *  regional variant if the packages don't line up on their unit.
     *  The debloat.txt headers are NOT consulted: they would need a per-folder
     *  fetch, and the codename is the key the folders are filed under. */
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
        // The codename tokens that identify the platform (device first, then product).
        List<List<String>> codenameTokens = new ArrayList<>();
        for (String base : new String[]{device, product}) {
            List<String> t = tokens(base);
            if (!t.isEmpty()) codenameTokens.add(t);
        }
        // Non-maker tokens from every build string, for the medium overlap count.
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
                    // Containment only counts past the manufacturer: "sony"
                    // alone matches every Sony folder and proves nothing.
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
            // Several HIGHs that differ only by a regional suffix: the base
            // (unsuffixed) folder is the generic list, the suffixed ones are
            // regional specialisations. Pick the base when exactly one high is
            // a token-prefix of every other high (e.g. sony-bravia-4k-vh21
            // against -eu/-sa/-tw/-us); otherwise stay ambiguous and let the
            // user pick. The region note is shown either way, so a wrong
            // regional pick can be spotted and switched.
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

    // ---- cache freshness: daily refresh + silent refresh on settings open ----

    /** The 24h-gated daily refresh (called from onCreate): re-fetches
     *  the cached folder's list and replaces the cache when it changed. Default ON
     *  (user decision 2026-11-08: update switches default on, out of the box).
     *  Silent on failure; the old cache stays. Never disables anything. */
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

    /** Ungated cache refresh, called every time this screen opens: same work as the
     *  daily check, but on every open instead of once a day. Silent on failure; the
     *  old cache stays.
     *  The only visible trace is the slimming screen's source/date label, which
     *  shows the new date the next time it opens. Never disables anything. */
    public static void refreshSlimmingCache(Context ctx) {
        String folder = Config.prefs(ctx).getString(KEY_FOLDER, null);
        if (folder == null) return; // System Slimming never opened: nothing to refresh
        byte[] live = fetchBytes(EXAMPLES_RAW + folder + "/debloat.txt", MAX_LIST_BYTES);
        if (live == null) return;
        String liveText = new String(live, StandardCharsets.UTF_8);
        Debloat debloat = parseDebloat(liveText);
        if (debloat.isEmpty()) return;
        String cached = Config.prefs(ctx).getString(KEY_LIST, null);
        if (cached != null) {
            Debloat cachedDebloat = parseDebloat(cached);
            if (debloat.recommended.equals(cachedDebloat.recommended)
                    && debloat.optional.equals(cachedDebloat.optional)) return; // unchanged
        }
        Config.prefs(ctx).edit()
                .putString(KEY_LIST, liveText)
                .putString(KEY_DATE, LocalDate.now().toString())
                .apply();
    }

    private static final class Row {
        final String pkg;
        String label;
        int state = STATE_NOT_INSTALLED;

        Row(String pkg) {
            this.pkg = pkg;
            this.label = pkg;
        }
    }

    private final class SlimmingAdapter extends RecyclerView.Adapter<SlimmingAdapter.Holder> {
        private final List<Row> rows;

        SlimmingAdapter(List<Row> rows) {
            this.rows = rows;
        }

        final class Holder extends RecyclerView.ViewHolder {
            final TextView label;
            final TextView pkg;
            final TextView state;

            Holder(View itemView) {
                super(itemView);
                label = itemView.findViewById(R.id.slimLabel);
                pkg = itemView.findViewById(R.id.slimPackage);
                state = itemView.findViewById(R.id.slimState);
            }
        }

        @Override
        public Holder onCreateViewHolder(ViewGroup parent, int viewType) {
            View v = LayoutInflater.from(parent.getContext())
                    .inflate(R.layout.item_slimming, parent, false);
            return new Holder(v);
        }

        @Override
        public void onBindViewHolder(Holder h, int position) {
            Row row = rows.get(position);
            h.label.setText(row.label);
            h.pkg.setText(row.pkg);
            switch (row.state) {
                case STATE_DISABLED:
                    h.state.setText(R.string.slimming_state_disabled);
                    h.state.setTextColor(0xFF4DAE6D);
                    h.itemView.setAlpha(1f);
                    break;
                case STATE_INSTALLED:
                    h.state.setText(R.string.slimming_state_installed);
                    h.state.setTextColor(0xCCFFFFFF);
                    h.itemView.setAlpha(1f);
                    break;
                default:
                    h.state.setText(R.string.slimming_state_not_installed);
                    h.state.setTextColor(0x80FFFFFF);
                    h.itemView.setAlpha(0.5f);
                    break;
            }
            h.itemView.setOnClickListener(v -> openDetails(row.pkg));
        }

        @Override
        public int getItemCount() {
            return rows.size();
        }
    }

    /** The system app-details page: the user disables the app there, by hand. */
    private void openDetails(String pkg) {
        try {
            startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.parse("package:" + pkg)));
        } catch (Exception e) {
            Toast.makeText(this, R.string.not_available, Toast.LENGTH_LONG).show();
        }
    }
}
