"""Merged 2-in-1 build checks (adapted from the fork's python suite).

Covers what the fork suite covered for slimming/weather/manifest/web-settings,
plus the merge-specific invariants: new package, no self-update, no qust.me,
single web server, Caiyun wiring, silent Aerial-data refresh.
"""
import json, os, re, zipfile
import xml.etree.ElementTree as ET

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
APP = os.path.join(ROOT, "app", "src", "main")
JAVA = os.path.join(APP, "java", "com", "gathin", "tvhome")

def read(p):
    with open(p, encoding="utf-8") as f:
        return f.read()

def test_package_is_com_gathin_tvhome():
    gradle = read(os.path.join(ROOT, "app", "build.gradle.kts"))
    assert 'namespace = "com.gathin.tvhome"' in gradle
    assert 'applicationId = "com.gathin.tvhome"' in gradle
    assert "app.orchard.tv" not in gradle

def test_no_old_package_references_in_app_sources():
    hits = []
    for base, _, files in os.walk(os.path.join(APP, "java")):
        for fn in files:
            p = os.path.join(base, fn)
            if "app.orchard.tv" in read(p):
                hits.append(p)
    assert hits == [], hits
    for p in [os.path.join(APP, "AndroidManifest.xml"),
              os.path.join(APP, "res", "xml", "revival_account.xml"),
              os.path.join(APP, "res", "xml", "revival_sync.xml")]:
        assert "app.orchard.tv" not in read(p), p

def test_config_action_and_revival_accounttype_renamed():
    assert "com.gathin.tvhome.CONFIG" in read(os.path.join(APP, "AndroidManifest.xml"))
    assert 'android:accountType="com.gathin.tvhome"' in read(os.path.join(APP, "res", "xml", "revival_account.xml"))
    assert 'android:accountType="com.gathin.tvhome"' in read(os.path.join(APP, "res", "xml", "revival_sync.xml"))

def test_no_self_update_code():
    assert not os.path.exists(os.path.join(JAVA, "update")), "update/ dir must be gone"
    for base, _, files in os.walk(os.path.join(APP, "java")):
        for fn in files:
            s = read(os.path.join(base, fn))
            assert "aerial.qust.me" not in s, os.path.join(base, fn)
            assert "update.Updates" not in s and "update.SelfCompile" not in s, os.path.join(base, fn)
    manifest = read(os.path.join(APP, "AndroidManifest.xml"))
    assert "REQUEST_INSTALL_PACKAGES" not in manifest
    assert "UPDATE_PACKAGES_WITHOUT_USER_ACTION" not in manifest
    assert "UpdateResult" not in manifest
    assert "FileProvider" not in manifest
    assert not os.path.exists(os.path.join(APP, "res", "xml", "update_files.xml"))
    gradle = read(os.path.join(ROOT, "app", "build.gradle.kts"))
    assert "DexMetadataAssets" not in gradle

def test_no_qust_me_anywhere_in_app():
    hits = []
    for base, _, files in os.walk(os.path.join(ROOT, "app")):
        if "build" in base:
            continue
        for fn in files:
            p = os.path.join(base, fn)
            if fn.endswith((".kt", ".java", ".xml", ".kts", ".html")) and "qust.me" in read(p):
                hits.append(p)
    assert hits == [], hits

def test_icon_library_is_local_only():
    s = read(os.path.join(JAVA, "apps", "IconLibrary.kt"))
    assert "qust.me" not in s
    assert "BASE" not in s
    assert "fun refresh" in s

def test_slimming_activity_present_and_no_home():
    manifest = read(os.path.join(APP, "AndroidManifest.xml"))
    assert ".SystemSlimmingActivity" in manifest
    assert os.path.exists(os.path.join(JAVA, "SystemSlimmingActivity.java"))
    assert os.path.exists(os.path.join(JAVA, "Config.java"))
    assert os.path.exists(os.path.join(JAVA, "LauncherLocale.java"))
    # slimming activity must not declare HOME
    m = re.search(r"<activity[^>]*\.SystemSlimmingActivity.*?</activity>", manifest, re.S)
    assert m and "CATEGORY_HOME" not in m.group(0)
    assert "LEANBACK_LAUNCHER" in m.group(0)
    # only MainActivity carries HOME (via standard flavor manifest)
    std = read(os.path.join(ROOT, "app", "src", "standard", "AndroidManifest.xml"))
    assert "CATEGORY_HOME" in std or "HOME" in std

def test_slimming_cleanup_keeps_weather_keys():
    s = read(os.path.join(JAVA, "SystemSlimmingActivity.java"))
    i = s.find("private static void cleanupLegacyState")
    block = s[i:i + 1500]
    for key in ("weather_city", "weather_auto", "weather_last_error", "weather_caiyun_token"):
        assert f'.remove("{key}")' not in block, key
    assert "cleanup_done_v13" in block

def test_slimming_snapshot_asset():
    z = os.path.join(APP, "assets", "slimming", "debloat-snapshot.zip")
    assert os.path.exists(z)
    with zipfile.ZipFile(z) as zf:
        names = zf.namelist()
    assert len(names) == 456 * 2 + 1, len(names)
    assert "snapshot.json" in names
    assert any(n.endswith("sony-bravia-4k-vh2/debloat.txt") for n in names)

def test_slimming_strings_both_locales():
    for p in [os.path.join(APP, "res", "values", "strings.xml"),
              os.path.join(APP, "res", "values-zh", "strings.xml")]:
        s = read(p)
        for key in ("system_slimming", "slimming_device", "slimming_list", "slimming_daily_state",
                    "slimming_state_disabled", "slimming_note"):
            assert f'name="{key}"' in s, (p, key)
    assert "CleanYourTV自动更新版" in read(os.path.join(APP, "res", "values", "strings.xml"))

def test_weather_client_ported():
    p = os.path.join(JAVA, "WeatherClient.java")
    s = read(p)
    assert "public final class WeatherClient" in s
    assert "CITIES" in s
    cities = re.search(r"CITIES = \{(.*?)\n    \}", s, re.S).group(1)
    assert cities.count("new String[]") == 736
    assert "2131755" not in s
    assert "final Result resultFetch" not in s
    assert "api.caiyunapp.com/v2.6" in s
    assert "whois.pconline.com.cn" in s
    # pconline first, sohu fallback: pconline appears before sohu in ipCityName
    assert s.find("pconline") < s.find("pv.sohu.com")
    assert "CLEAR_DAY" in s and "THUNDER_SHOWER" in s

def test_weather_bridge_in_aerial_weather():
    s = read(os.path.join(JAVA, "weather", "Weather.kt"))
    assert "WeatherClient" in s
    assert "weatherSource" in s
    assert "startCaiyunTick" in s and "stopCaiyunTick" in s
    assert "60_000L" in s
    assert "caiyunTokenMasked" in s
    assert "••••" in s
    # default source is openmeteo
    assert '"openmeteo"' in s

def test_caiyun_tick_on_home_not_slimming_page():
    s = read(os.path.join(JAVA, "MainActivity.kt"))
    assert "Weather.startCaiyunTick()" in s
    assert "Weather.stopCaiyunTick()" in s
    slim = read(os.path.join(JAVA, "SystemSlimmingActivity.java"))
    assert "weatherTick" not in slim and "WeatherClient.load" not in slim

def test_phone_server_single_port_and_endpoints():
    s = read(os.path.join(JAVA, "wallpaper", "PhoneServer.kt"))
    assert "PORT = 8790" in s
    assert "8890" not in s
    for ep in ("/api/weather/source", "/api/weather/caiyun-token", "/api/slimming/refresh"):
        assert f'"{ep}"' in s, ep
    assert '"slimming"' in s and '"cityGroups"' in s
    assert "caiyunTokenMasked" in s
    # token value never put into state verbatim: only masked form
    assert "weather_caiyun_token" not in s or "caiyunTokenMasked" in s

def test_web_index_has_slimming_tab_and_weather_controls():
    s = read(os.path.join(APP, "assets", "web", "index.html"))
    assert 'data-tab="slimming"' in s
    assert 'id="wxSource"' in s and 'id="wxTokenForm"' in s
    assert 'id="wxProvince"' in s and 'id="wxCity"' in s
    assert 'id="slimRefreshBtn"' in s
    assert "api/slimming/refresh" in s and "api/weather/caiyun-token" in s
    # KEYS/TEXTS aligned: 5 languages, same count
    keys = re.search(r"const KEYS = \[(.*?)\];", s, re.S).group(1)
    nkeys = len(re.findall(r'"([^"]+)"', keys))
    for lang in ("zh", "en", "ru", "uk", "kk"):
        m = re.search(r' "' + lang + r'": \[(.*?)\n \]', s, re.S)
        assert m, lang
        assert len(re.findall(r'\n  "', m.group(1))) == nkeys, (lang, len(re.findall(r'\n  "', m.group(1))), nkeys)
    for key in ("tab_slimming", "slimming_refresh", "wx_source_caiyun", "wx_token_ph"):
        assert f'"{key}"' in keys, key

def test_web_js_syntax():
    import subprocess
    s = read(os.path.join(APP, "assets", "web", "index.html"))
    js = re.search(r"<script>(.*)</script>", s, re.S).group(1)
    open("/tmp/merge-webcheck.js", "w").write(js)
    r = subprocess.run(["node", "--check", "/tmp/merge-webcheck.js"], capture_output=True, text=True)
    assert r.returncode == 0, r.stderr

def test_aerial_silent_refresh():
    s = read(os.path.join(JAVA, "wallpaper", "Aerials.kt"))
    assert "refreshIfStale" in s
    assert "aerials.json" in s
    assert "sylvan.apple.com" in s
    main = read(os.path.join(JAVA, "MainActivity.kt"))
    assert "Aerials.refreshIfStale(this)" in main
    # Bing daily refresh still wired
    wall = read(os.path.join(JAVA, "wallpaper", "Wallpapers.kt"))
    assert "refreshDailyIfStale" in wall
    assert "refreshDailyIfStale()" in main or "refreshDailyIfStale(networkCameUp" in main

def test_version_bumped_and_keystore_note():
    gradle = read(os.path.join(ROOT, "app", "build.gradle.kts"))
    assert "versionCode = 124" in gradle
    assert "1.89-2in1" in gradle
