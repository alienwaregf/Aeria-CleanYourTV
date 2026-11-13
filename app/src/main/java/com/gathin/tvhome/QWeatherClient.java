package com.gathin.tvhome;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.util.zip.GZIPInputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.json.JSONObject;
import org.json.JSONArray;

/**
 * QWeather (AndFeng) source, mirroring WeatherClient's shape so Weather.kt needs
 * no new Result type: codes are converted here to Caiyun-style skycon strings,
 * and the result is a plain WeatherClient.Result.
 *
 * API: QWeather v1 (api docs at dev.qweather.com), API Key auth in the
 * X-QW-Api-Key header, per-account dedicated API Host, lat/lon in the path
 * (latitude first), at most 2 decimal places. No combined endpoint, so one
 * refresh is 5 calls: daily (high/low, sunrise/sunset, 3-day line) + current
 * (temperature, condition) + hourly (next-6-hours trend) + warning (alert
 * title) + air quality (China AQI, cn-mee). At the 30-minute keyed cadence
 * that is ~240 calls/day, ~7,200/month, inside the 50,000/month free tier
 * for weather and basic services.
 *
 * Failure policy, same as Caiyun: no second fetch, no retry loop. Current or
 * daily failing fails the whole refresh and the last reading stays; hourly,
 * warning or air quality failing only drops the trend / the alert title /
 * the AQI text for this refresh.
 * The key and host live in launcher_settings (weather_qweather_key,
 * weather_qweather_host), entered on the phone settings page, never hardcoded
 * and never logged. The gateway gzips responses even when identity is
 * requested, so fetchJson decompresses whenever Content-Encoding says gzip.
 */
public final class QWeatherClient {
    private static final ExecutorService EXECUTOR = Executors.newSingleThreadExecutor();
    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    public interface Callback {
        void onResult(WeatherClient.Result result);
    }

    QWeatherClient() {
    }

    public static void load(final Context context, final Callback callback) {
        EXECUTOR.execute(new Runnable() {
            @Override
            public final void run() {
                final WeatherClient.Result[] holder = new WeatherClient.Result[1];
                try {
                    holder[0] = fetch(context);
                } catch (Exception unused) {
                    holder[0] = null;
                }
                MAIN.post(new Runnable() {
                    @Override
                    public final void run() {
                        callback.onResult(holder[0]);
                    }
                });
            }
        });
    }

    private static WeatherClient.Result fetch(Context context) throws Exception {
        SharedPreferences prefs = context.getSharedPreferences("launcher_settings", 0);
        String key = prefs.getString("weather_qweather_key", "").trim();
        if (key.isEmpty()) {
            return null;
        }
        String host = prefs.getString("weather_qweather_host", "").trim();
        if (host.isEmpty() || host.contains("/")) {
            return null;
        }
        String[] city = WeatherClient.resolveCity(context, prefs);
        if (city == null) {
            return null;
        }
        // v1 path parameters: latitude first, at most 2 decimal places.
        String lat = String.format(Locale.US, "%.2f", Double.parseDouble(city[2]));
        String lon = String.format(Locale.US, "%.2f", Double.parseDouble(city[3]));
        JSONObject daily = fetchJson(host, key, "/weather/v1/daily/" + lat + "/" + lon + "?days=4&localTime=true&lang=zh-hans");
        JSONObject current = fetchJson(host, key, "/weather/v1/current/" + lat + "/" + lon + "?localTime=true&lang=zh-hans");
        JSONObject hourly = null;
        try {
            hourly = fetchJson(host, key, "/weather/v1/hourly/" + lat + "/" + lon + "?hours=24&localTime=true&lang=zh-hans");
        } catch (Exception unused) {
            hourly = null;
        }
        JSONObject warning = null;
        try {
            warning = fetchJson(host, key, "/weatheralert/v1/current/" + lat + "/" + lon + "?localTime=true&lang=zh-hans");
        } catch (Exception unused) {
            warning = null;
        }
        JSONObject air = null;
        try {
            air = fetchJson(host, key, "/airquality/v1/current/" + lat + "/" + lon + "?lang=zh-hans");
        } catch (Exception unused) {
            air = null;
        }

        JSONArray days = daily.optJSONArray("days");
        if (days == null || days.length() == 0) {
            return null;
        }
        JSONObject today = days.optJSONObject(0);
        if (today == null) {
            return null;
        }
        boolean isDay = isDayNow(today.optJSONObject("astro"));
        double temp = valueOf(current.optJSONObject("temperature"));
        String nowCode = codeOf(current.optJSONObject("condition"));
        String skycon = withWind(skyconFor(nowCode, isDay), windScale(current));
        if (Double.isNaN(temp)) {
            return null;
        }

        Integer high = null;
        Integer low = null;
        double max = valueOf(today.optJSONObject("temperatureMax"));
        double min = valueOf(today.optJSONObject("temperatureMin"));
        if (!Double.isNaN(max)) high = (int) Math.round(max);
        if (!Double.isNaN(min)) low = (int) Math.round(min);

        String[] dailySkycon = new String[]{"", "", "", ""};
        int[] dailyMin = new int[]{Integer.MIN_VALUE, Integer.MIN_VALUE, Integer.MIN_VALUE, Integer.MIN_VALUE};
        String[] dailyDay = new String[]{"", "", "", ""};
        String[] dailyNight = new String[]{"", "", "", ""};
        double[] rainDay = new double[]{Double.NaN, Double.NaN, Double.NaN, Double.NaN};
        double[] rainNight = new double[]{Double.NaN, Double.NaN, Double.NaN, Double.NaN};
        for (int i = 0; i < 4 && i < days.length(); i++) {
            JSONObject day = days.optJSONObject(i);
            if (day == null) continue;
            JSONObject dayPart = day.optJSONObject("daytime");
            JSONObject nightPart = day.optJSONObject("nighttime");
            String daySky = withWind(skyconFor(codeOf(dayPart == null ? null : dayPart.optJSONObject("condition")), true), windScale(dayPart));
            String nightSky = withWind(skyconFor(codeOf(nightPart == null ? null : nightPart.optJSONObject("condition")), false), windScale(nightPart));
            dailyDay[i] = daySky;
            dailyNight[i] = nightSky;
            // The day's primary phenomenon is the more severe of its two halves;
            // rain totals (below) still grade the severe line when it rains.
            dailySkycon[i] = rankOf(daySky) >= rankOf(nightSky) ? daySky : nightSky;
            double dmin = valueOf(day.optJSONObject("temperatureMin"));
            if (!Double.isNaN(dmin)) dailyMin[i] = (int) Math.round(dmin);
            rainDay[i] = rainAmount(dayPart);
            rainNight[i] = rainAmount(nightPart);
        }

        ArrayList<String> hourlyList = new ArrayList<>();
        if (hourly != null) {
            JSONArray hours = hourly.optJSONArray("hours");
            if (hours != null) {
                // hours[0] is the current hour, as with Caiyun; the trend uses the next 6.
                for (int i = 1; i < hours.length() && hourlyList.size() < 6; i++) {
                    JSONObject h = hours.optJSONObject(i);
                    if (h == null) continue;
                    String s = withWind(skyconFor(codeOf(h.optJSONObject("condition")), true), windScale(h));
                    if (!s.isEmpty()) hourlyList.add(s);
                }
            }
        }

        return new WeatherClient.Result(city[1], temp, skycon, high, low, pickAlertTitle(warning),
                hourlyList.toArray(new String[0]), dailySkycon, dailyMin, dailyDay, dailyNight, rainDay, rainNight, isDay, pickAqiText(air));
    }

    // Highest severity wins (extreme > severe > moderate > minor > unknown);
    // first item wins on a tie. A "cancel" message is not an active alert.
    // A failed warning call (null) clears the title for this refresh only.
    private static String pickAlertTitle(JSONObject warning) {
        if (warning == null) return null;
        JSONArray alerts = warning.optJSONArray("alerts");
        if (alerts == null) return null;
        String bestTitle = null;
        int bestLevel = -1;
        for (int i = 0; i < alerts.length(); i++) {
            JSONObject item = alerts.optJSONObject(i);
            if (item == null) continue;
            JSONObject messageType = item.optJSONObject("messageType");
            if (messageType != null && "cancel".equals(messageType.optString("code", ""))) continue;
            String headline = item.optString("headline", "").trim();
            if (headline.isEmpty()) continue;
            int level = severityRank(item.optString("severity", ""));
            if (level > bestLevel) {
                bestLevel = level;
                bestTitle = headline;
            }
        }
        return bestTitle;
    }

    private static int severityRank(String severity) {
        if ("extreme".equals(severity)) return 4;
        if ("severe".equals(severity)) return 3;
        if ("moderate".equals(severity)) return 2;
        if ("minor".equals(severity)) return 1;
        return 0;
    }

    // Air quality, China standard only: in China the indexes carry cn-mee
    // (HJ 633-2012); the universal QAQI (qaqi) does not apply in China.
    // aqiDisplay is the display value the docs recommend showing to users.
    // "空气优 42" style text, or null when the call failed or the index is missing.
    private static String pickAqiText(JSONObject air) {
        if (air == null) return null;
        JSONArray indexes = air.optJSONArray("indexes");
        if (indexes == null) return null;
        JSONObject chosen = null;
        for (int i = 0; i < indexes.length(); i++) {
            JSONObject item = indexes.optJSONObject(i);
            if (item == null) continue;
            String code = item.optString("code", "").trim();
            if ("cn-mee".equals(code)) {
                chosen = item;
                break;
            }
            if (chosen == null && "cn-mee-1h".equals(code)) chosen = item;
        }
        if (chosen == null && indexes.length() > 0) chosen = indexes.optJSONObject(0);
        if (chosen == null) return null;
        String display = chosen.optString("aqiDisplay", "").trim();
        if (display.isEmpty()) return null;
        String category = chosen.optString("category", "").trim();
        return category.isEmpty() ? "空气 " + display : "空气" + category + " " + display;
    }

    // QWeather condition code -> Caiyun-style skycon. Codes with a range
    // (314 small-to-moderate rain, 315 moderate-to-heavy, 316 heavy-to-torrential,
    // 408/409/410 snow ranges) map to the lighter single level for the current
    // condition; the future-day severe line is graded from the 12h rain totals,
    // not from these codes, so the range is not lost there. 900 (hot), 901 (cold)
    // and 999 (unknown) have no phenomenon bucket: they read as clear, and the
    // temperature carries the signal; they must never fall through to rain.
    // An unrecognised future code also reads as cloudy, never as rain.
    private static String skyconFor(String code, boolean isDay) {
        String suffix = isDay ? "_DAY" : "_NIGHT";
        if ("100".equals(code)) return "CLEAR" + suffix;
        if ("101".equals(code) || "102".equals(code) || "103".equals(code)) return "PARTLY_CLOUDY" + suffix;
        if ("104".equals(code)) return "CLOUDY";
        if ("300".equals(code) || "309".equals(code) || "399".equals(code)) return "LIGHT_RAIN";
        if ("301".equals(code)) return "MODERATE_RAIN";
        if ("302".equals(code) || "303".equals(code) || "304".equals(code)) return "THUNDER_SHOWER";
        if ("305".equals(code) || "314".equals(code)) return "LIGHT_RAIN";
        if ("306".equals(code) || "315".equals(code)) return "MODERATE_RAIN";
        if ("307".equals(code) || "316".equals(code)) return "HEAVY_RAIN";
        if ("308".equals(code) || "310".equals(code) || "311".equals(code) || "312".equals(code) || "317".equals(code) || "318".equals(code)) return "STORM_RAIN";
        if ("313".equals(code)) return "SLEET";
        if ("400".equals(code) || "407".equals(code) || "499".equals(code) || "408".equals(code)) return "LIGHT_SNOW";
        if ("401".equals(code) || "409".equals(code)) return "MODERATE_SNOW";
        if ("402".equals(code) || "410".equals(code)) return "HEAVY_SNOW";
        if ("403".equals(code)) return "STORM_SNOW";
        if ("404".equals(code) || "405".equals(code) || "406".equals(code)) return "SLEET";
        if ("500".equals(code) || "501".equals(code) || "509".equals(code) || "510".equals(code) || "514".equals(code) || "515".equals(code)) return "FOG";
        if ("502".equals(code)) return "LIGHT_HAZE";
        if ("511".equals(code)) return "MODERATE_HAZE";
        if ("512".equals(code) || "513".equals(code)) return "HEAVY_HAZE";
        if ("503".equals(code) || "507".equals(code) || "508".equals(code)) return "SAND";
        if ("504".equals(code)) return "DUST";
        if ("900".equals(code) || "901".equals(code) || "999".equals(code)) return "CLEAR" + suffix;
        return "CLOUDY";
    }

    // QWeather has no wind phenomenon code: wind is a separate scale. Only when
    // the condition is otherwise clear, partly cloudy or cloudy and the Beaufort
    // scale reaches 8 (gale) does wind become the primary phenomenon.
    private static String withWind(String skycon, double scale) {
        if (!Double.isNaN(scale) && scale >= 8.0
                && ("CLEAR_DAY".equals(skycon) || "CLEAR_NIGHT".equals(skycon)
                || "PARTLY_CLOUDY_DAY".equals(skycon) || "PARTLY_CLOUDY_NIGHT".equals(skycon)
                || "CLOUDY".equals(skycon))) {
            return "WIND";
        }
        return skycon;
    }

    // Severity order for picking a day's primary phenomenon from its halves.
    private static int rankOf(String skycon) {
        if ("STORM_RAIN".equals(skycon) || "STORM_SNOW".equals(skycon)) return 9;
        if ("HEAVY_RAIN".equals(skycon) || "HEAVY_SNOW".equals(skycon)) return 8;
        if ("MODERATE_RAIN".equals(skycon) || "MODERATE_SNOW".equals(skycon)) return 7;
        if ("LIGHT_RAIN".equals(skycon) || "LIGHT_SNOW".equals(skycon)
                || "THUNDER_SHOWER".equals(skycon) || "SLEET".equals(skycon)) return 6;
        if ("FOG".equals(skycon) || "LIGHT_HAZE".equals(skycon) || "MODERATE_HAZE".equals(skycon)
                || "HEAVY_HAZE".equals(skycon) || "WIND".equals(skycon)
                || "DUST".equals(skycon) || "SAND".equals(skycon)) return 5;
        if ("CLOUDY".equals(skycon)) return 2;
        if ("PARTLY_CLOUDY_DAY".equals(skycon) || "PARTLY_CLOUDY_NIGHT".equals(skycon)) return 1;
        return 0;
    }

    // Daytime [07:00, 19:00) and nighttime [19:00, next 07:00) precipitation
    // amounts are the period totals in mm, so they feed Weather's GB/T 28592
    // 12h rain grading as-is. Snow water is not rain: a snow segment reports
    // NaN so it never grades as rain (the skycon fallback handles the snow item).
    private static double rainAmount(JSONObject part) {
        if (part == null) return Double.NaN;
        JSONObject precipitation = part.optJSONObject("precipitation");
        if (precipitation == null) return Double.NaN;
        if ("snow".equals(precipitation.optString("type", ""))) return Double.NaN;
        return valueOf(precipitation.optJSONObject("amount"));
    }

    // Daytime when now is between today's sunrise and sunset (local times,
    // localTime=true); defaults to daytime when the astro block is missing.
    private static boolean isDayNow(JSONObject astro) {
        try {
            if (astro == null) return true;
            int now = Calendar.getInstance().get(Calendar.HOUR_OF_DAY) * 60 + Calendar.getInstance().get(Calendar.MINUTE);
            int rise = minutesOf(astro.optString("sunrise", ""));
            int set = minutesOf(astro.optString("sunset", ""));
            if (rise < 0 || set < 0) return true;
            return now >= rise && now < set;
        } catch (Exception unused) {
            return true;
        }
    }

    private static int minutesOf(String isoDateTime) {
        if (isoDateTime == null || isoDateTime.length() < 16) return -1;
        int t = isoDateTime.indexOf('T');
        if (t < 0 || isoDateTime.length() < t + 6) return -1;
        try {
            int hour = Integer.parseInt(isoDateTime.substring(t + 1, t + 3));
            int minute = Integer.parseInt(isoDateTime.substring(t + 4, t + 6));
            return hour * 60 + minute;
        } catch (NumberFormatException unused) {
            return -1;
        }
    }

    private static String codeOf(JSONObject condition) {
        if (condition == null) return "";
        return condition.optString("code", "").trim();
    }

    private static double valueOf(JSONObject valueObj) {
        if (valueObj == null) return Double.NaN;
        return valueObj.optDouble("value", Double.NaN);
    }

    private static double windScale(JSONObject obj) {
        if (obj == null) return Double.NaN;
        JSONObject wind = obj.optJSONObject("wind");
        if (wind == null) return Double.NaN;
        return wind.optDouble("scale", Double.NaN);
    }

    private static JSONObject fetchJson(String host, String key, String path) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL("https://" + host + path).openConnection();
        c.setConnectTimeout(8000);
        c.setReadTimeout(8000);
        c.setRequestProperty("Accept", "application/json");
        c.setRequestProperty("Accept-Encoding", "identity");
        c.setRequestProperty("X-QW-Api-Key", key);
        try {
            int code = c.getResponseCode();
            if (code < 200 || code >= 300) throw new Exception("HTTP " + code);
            InputStream in = c.getInputStream();
            // The gateway gzips the body even when identity is requested
            // (verified: a 401 and the user's 200 both came back gzip),
            // so decompress whenever the header says so.
            String encoding = c.getContentEncoding();
            if (encoding != null && encoding.toLowerCase(Locale.US).contains("gzip")) {
                in = new GZIPInputStream(in);
            }
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int n;
            while ((n = in.read(buf)) != -1) out.write(buf, 0, n);
            in.close();
            return new JSONObject(new String(out.toByteArray(), StandardCharsets.UTF_8));
        } finally {
            c.disconnect();
        }
    }

}
