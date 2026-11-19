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
import java.util.Calendar;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.json.JSONObject;
import org.json.JSONArray;

public final class QWeatherClient {

    public static final class Result {
        public final String city;
        public final String skycon;
        public final boolean day;
        public final double temperatureC;
        public final Integer highC;
        public final Integer lowC;
        public final String alertTitle;
        public final int[] dailyMinC;
        public final int[] dailyMaxC;
        public final String[] dailySkyconDay;
        public final String[] dailyTextDay;
        public final String nowText;
        public final double dayCloudCover;
        public final double nightCloudCover;

        public final String aqi;

        Result(String str, double d, String str2, Integer high, Integer low, String alert, int[] dailyMin, int[] dailyMax, String[] dailyDay, String[] textDay, boolean day, String aqi, String nowText, double dayCloud, double nightCloud) {
            this.city = str;
            this.temperatureC = d;
            this.skycon = str2;
            this.day = day;
            this.aqi = aqi;
            this.highC = high;
            this.lowC = low;
            this.alertTitle = alert;
            this.dailyMinC = dailyMin;
            this.dailyMaxC = dailyMax;
            this.dailySkyconDay = dailyDay;
            this.dailyTextDay = textDay;
            this.nowText = nowText;
            this.dayCloudCover = dayCloud;
            this.nightCloudCover = nightCloud;
        }
    }
    private static final ExecutorService EXECUTOR = Executors.newSingleThreadExecutor();
    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    public interface Callback {
        void onResult(Result result);
    }

    QWeatherClient() {
    }

    public static void load(final Context context, final Callback callback) {
        EXECUTOR.execute(new Runnable() {
            @Override
            public final void run() {
                final Result[] holder = new Result[1];
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

    private static String[] chosenWeatherCity(Context context, SharedPreferences launcherPrefs) {
        if (!launcherPrefs.getString("weather_city", "").trim().isEmpty()) return null;
        SharedPreferences weather = context.getSharedPreferences("weather", 0);
        if (!weather.getBoolean("chosen", false)) return null;
        float lat = weather.getFloat("lat", Float.NaN);
        float lon = weather.getFloat("lon", Float.NaN);
        if (Float.isNaN(lat) || Float.isNaN(lon)) return null;
        String place = weather.getString("place", "").trim();
        if (place.isEmpty()) return null;
        return new String[]{"", place, String.valueOf(lat), String.valueOf(lon)};
    }

    private static Result fetch(Context context) throws Exception {
        SharedPreferences prefs = context.getSharedPreferences("launcher_settings", 0);
        String key = prefs.getString("weather_qweather_key", "").trim();
        if (key.isEmpty()) {
            return null;
        }
        String host = prefs.getString("weather_qweather_host", "").trim();
        if (host.isEmpty() || host.contains("/")) {
            return null;
        }
        String[] city = chosenWeatherCity(context, prefs);
        if (city == null) {
            city = CityLookup.resolveCity(context, prefs);
        }
        if (city == null) {
            return null;
        }
        String lat = String.format(Locale.US, "%.2f", Double.parseDouble(city[2]));
        String lon = String.format(Locale.US, "%.2f", Double.parseDouble(city[3]));
        JSONObject daily = fetchJson(host, key, "/weather/v1/daily/" + lat + "/" + lon + "?days=4&localTime=true&lang=zh-hans");
        JSONObject current = fetchJson(host, key, "/weather/v1/current/" + lat + "/" + lon + "?localTime=true&lang=zh-hans");
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
        JSONObject dayPart0 = today.optJSONObject("daytime");
        JSONObject nightPart0 = today.optJSONObject("nighttime");
        double dayCloud = dayPart0 == null ? Double.NaN : dayPart0.optDouble("cloudCover", Double.NaN);
        double nightCloud = nightPart0 == null ? Double.NaN : nightPart0.optDouble("cloudCover", Double.NaN);
        boolean isDay = isDayNow(today.optJSONObject("astro"));
        double temp = valueOf(current.optJSONObject("temperature"));
        String nowCode = codeOf(current.optJSONObject("condition"));
        String nowText = textOf(current.optJSONObject("condition"));
        String skycon = skyconFor(nowCode, isDay);
        if (Double.isNaN(temp)) {
            return null;
        }

        Integer high = null;
        Integer low = null;
        double max = valueOf(today.optJSONObject("temperatureMax"));
        double min = valueOf(today.optJSONObject("temperatureMin"));
        if (!Double.isNaN(max)) high = (int) Math.round(max);
        if (!Double.isNaN(min)) low = (int) Math.round(min);

        int[] dailyMin = new int[]{Integer.MIN_VALUE, Integer.MIN_VALUE, Integer.MIN_VALUE, Integer.MIN_VALUE};
        int[] dailyMax = new int[]{Integer.MIN_VALUE, Integer.MIN_VALUE, Integer.MIN_VALUE, Integer.MIN_VALUE};
        String[] dailyDay = new String[]{"", "", "", ""};
        String[] textDay = new String[]{"", "", "", ""};
        for (int i = 0; i < 4 && i < days.length(); i++) {
            JSONObject day = days.optJSONObject(i);
            if (day == null) continue;
            JSONObject dayPart = day.optJSONObject("daytime");
            dailyDay[i] = skyconFor(codeOf(dayPart == null ? null : dayPart.optJSONObject("condition")), true);
            textDay[i] = textOf(dayPart == null ? null : dayPart.optJSONObject("condition"));
            double dmin = valueOf(day.optJSONObject("temperatureMin"));
            if (!Double.isNaN(dmin)) dailyMin[i] = (int) Math.round(dmin);
            double dmax = valueOf(day.optJSONObject("temperatureMax"));
            if (!Double.isNaN(dmax)) dailyMax[i] = (int) Math.round(dmax);
        }

        return new Result(city[1], temp, skycon, high, low, pickAlertTitle(warning),
                dailyMin, dailyMax, dailyDay, textDay, isDay, pickAqiText(air), nowText, dayCloud, nightCloud);
    }

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
        if (chosen == null) return null;
        String display = chosen.optString("aqiDisplay", "").trim();
        if (display.isEmpty()) return null;
        String category = chosen.optString("category", "").trim();
        return category.isEmpty() ? "空气 " + display : "空气" + category + " " + display;
    }

    private static String skyconFor(String code, boolean isDay) {
        String suffix = isDay ? "_DAY" : "_NIGHT";
        if ("100".equals(code) || "150".equals(code)) return "CLEAR" + suffix;
        if ("101".equals(code) || "102".equals(code) || "103".equals(code) || "152".equals(code) || "153".equals(code)) return "PARTLY_CLOUDY" + suffix;
        if ("104".equals(code)) return "CLOUDY";
        if ("300".equals(code) || "309".equals(code) || "399".equals(code) || "350".equals(code)) return "LIGHT_RAIN";
        if ("301".equals(code) || "351".equals(code)) return "MODERATE_RAIN";
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
        if ("900".equals(code) || "901".equals(code)) return "CLEAR" + suffix;
        return "CLOUDY";
    }

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

    private static String textOf(JSONObject condition) {
        if (condition == null) return "";
        return condition.optString("text", "").trim();
    }

    private static double valueOf(JSONObject valueObj) {
        if (valueObj == null) return Double.NaN;
        return valueObj.optDouble("value", Double.NaN);
    }

    private static JSONObject fetchJson(String host, String key, String path) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL("https://" + host + path).openConnection();
        c.setConnectTimeout(8000);
        c.setReadTimeout(8000);
        c.setRequestProperty("Accept", "application/json");
        c.setRequestProperty("Accept-Encoding", "identity");
        c.setRequestProperty("X-QW-Api-Key", key);
        InputStream in = null;
        try {
            int code = c.getResponseCode();
            if (code < 200 || code >= 300) throw new Exception("HTTP " + code);
            in = c.getInputStream();
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
            if (in != null) {
                try {
                    in.close();
                } catch (Exception ignored) {
                }
            }
            c.disconnect();
        }
    }

}
