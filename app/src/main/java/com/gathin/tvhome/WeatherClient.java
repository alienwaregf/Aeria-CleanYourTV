package com.gathin.tvhome;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import com.gathin.tvhome.weather.Cities;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.json.JSONObject;
import org.json.JSONArray;

/* loaded from: /tmp/v12dex/classes.dex */
public final class WeatherClient {
    private static final ExecutorService EXECUTOR = Executors.newSingleThreadExecutor();
    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    public interface Callback {
        void onResult(Result result);
    }

    WeatherClient() {
    }

    public static final class Result {
        public final String city;
        public final String skycon;
        // True when the reading is daytime. Caiyun derives it from the skycon
        // _NIGHT suffix; QWeather derives it from sunrise/sunset.
        public final boolean day;
        public final double temperatureC;
        // Today's high/low from the daily forecast; null when the daily block is missing.
        public final Integer highC;
        public final Integer lowC;
        // Title of the most severe active alert (e.g. "XX市气象台发布大风蓝色预警[IV级/一般]");
        // null when there is no alert, or the token has no alert permission.
        public final String alertTitle;
        // Skycon values for the next 6 hourly steps (hourly[0] is the current hour and is
        // skipped); empty when the hourly block is missing. Drives the cloud trend only.
        public final String[] hourlySkycons;
        // Daily skycon values and min temperatures for today + the next 3 days
        // (index 0 is today); "" / Integer.MIN_VALUE when the daily block is missing.
        // Drives the 3-day severe-weather line only.
        public final String[] dailySkycons;
        public final int[] dailyMinC;
        // Daytime (08-20) and nighttime (20-32) skycon values for today + the next 3 days;
        // "" when missing. Only used to tell "light to moderate rain" apart from plain
        // light or moderate rain on the 3-day severe-weather line.
        public final String[] dailySkyconDay;
        public final String[] dailySkyconNight;
        // Daytime (08-20) and nighttime (20-32) rainfall totals for today + the next 3 days,
        // in mm, from the daily precipitation avg (mean mm/h over the full 12h window)
        // times 12. NaN when the precipitation block is missing. Drives the 3-day
        // severe-weather line's GB/T 28592 rain grading; the skycon pair above is only
        // the fallback when these are missing.
        public final double[] dailyRainDayMm;
        public final double[] dailyRainNightMm;

        // Air quality text, e.g. "空气优 42"; null when the source does not report it.
        public final String aqi;

        Result(String str, double d, String str2, Integer high, Integer low, String alert, String[] hourly, String[] dailySkycon, int[] dailyMin, String[] dailyDay, String[] dailyNight, double[] rainDay, double[] rainNight, boolean day, String aqi) {
            this.city = str;
            this.temperatureC = d;
            this.skycon = str2;
            this.day = day;
            this.aqi = aqi;
            this.highC = high;
            this.lowC = low;
            this.alertTitle = alert;
            this.hourlySkycons = hourly;
            this.dailySkycons = dailySkycon;
            this.dailyMinC = dailyMin;
            this.dailySkyconDay = dailyDay;
            this.dailySkyconNight = dailyNight;
            this.dailyRainDayMm = rainDay;
            this.dailyRainNightMm = rainNight;
        }
    }

    // Highest alert level wins; the code's last two digits are the level
    // (01 blue, 02 yellow, 03 orange, 04 red). First item wins on a tie.
    private static String pickAlertTitle(JSONObject alertObj) {
        if (alertObj == null) return null;
        JSONArray content = alertObj.optJSONArray("content");
        if (content == null) return null;
        String bestTitle = null;
        int bestLevel = -1;
        for (int i = 0; i < content.length(); i++) {
            JSONObject item = content.optJSONObject(i);
            if (item == null) continue;
            String title = item.optString("title", "").trim();
            if (title.isEmpty()) continue;
            int level = -1;
            String code = item.optString("code", "").trim();
            if (code.length() >= 2) {
                try {
                    level = Integer.parseInt(code.substring(code.length() - 2));
                } catch (NumberFormatException unused) {
                    level = -1;
                }
            }
            if (level > bestLevel) {
                bestLevel = level;
                bestTitle = title;
            }
        }
        return bestTitle;
    }

    // Caiyun realtime air_quality: aqi.chn is the China-standard index,
    // description.chn the category word when present. "空气优 42" format,
    // same as QWeather, so the display line is shared. A description that
    // is not one of the six standard words (e.g. "missing") is ignored and
    // the band is derived from the number instead.
    private static String pickCaiyunAqiText(JSONObject air) {
        if (air == null) return null;
        JSONObject aqi = air.optJSONObject("aqi");
        if (aqi == null) return null;
        double chn = aqi.optDouble("chn", Double.NaN);
        if (Double.isNaN(chn)) return null;
        int value = (int) Math.round(chn);
        String category = null;
        JSONObject description = air.optJSONObject("description");
        if (description != null) {
            String d = description.optString("chn", "").trim();
            if ("优".equals(d) || "良".equals(d) || "轻度污染".equals(d)
                    || "中度污染".equals(d) || "重度污染".equals(d) || "严重污染".equals(d)) {
                category = d;
            }
        }
        if (category == null) category = aqiCategory(value);
        return "空气" + category + " " + value;
    }

    private static String aqiCategory(int value) {
        if (value <= 50) return "优";
        if (value <= 100) return "良";
        if (value <= 150) return "轻度污染";
        if (value <= 200) return "中度污染";
        if (value <= 300) return "重度污染";
        return "严重污染";
    }

    private static String[] toEntry(Cities.City c) {
        return new String[]{c.getProvince(), c.getName(), String.valueOf(c.getLat()), String.valueOf(c.getLon())};
    }

    static String[] cityEntry(Context context, String str) {
        if (str == null) {
            return null;
        }
        try {
            Cities.City c = Cities.INSTANCE.find(context, str.trim());
            return c == null ? null : toEntry(c);
        } catch (Exception unused) {
            return null;
        }
    }

    public static boolean isKnownCity(Context context, String str) {
        return cityEntry(context, str) != null;
    }

    public static void load(final Context context, final Callback callback) {
        EXECUTOR.execute(new Runnable() { // from class: com.gathin.tvhome.WeatherClient$$ExternalSyntheticLambda0
            @Override // java.lang.Runnable
            public final void run() {
                WeatherClient.lambda$load$1(context, callback);
            }
        });
    }

    static /* synthetic */ void lambda$load$1(Context context, final Callback callback) {
        final Result[] resultFetch = new Result[1];
        try {
            resultFetch[0] = fetch(context);
        } catch (Exception unused) {
            resultFetch[0] = null;
        }
        MAIN.post(new Runnable() { // from class: com.gathin.tvhome.WeatherClient$$ExternalSyntheticLambda1
            @Override // java.lang.Runnable
            public final void run() {
                callback.onResult(resultFetch[0]);
            }
        });
    }

    private static Result fetch(Context context) throws Exception {
        SharedPreferences prefs = context.getSharedPreferences("launcher_settings", 0);
        String token = prefs.getString("weather_caiyun_token", "").trim();
        if (token.isEmpty()) {
            return null;
        }
        String[] city = resolveCity(context, prefs);
        if (city == null) {
            return null;
        }
        Result combined = fetchCombined(token, city);
        if (combined != null) {
            return combined;
        }
        // No second fetch on failure (user 2026-11-09): the last reading stays on
        // Home, so a failed refresh costs one Caiyun call, not two.
        return null;
    }

    private static Result fetchCombined(String token, String[] city) {
        try {
            // Combined endpoint: one call returns realtime + daily + alerts.
            // Steps follow the console's own example (dailysteps=3, hourlysteps=48),
            // with dailysteps raised to 4 so the daily array also covers the day after
            // tomorrow (index 3), which the 3-day severe-weather line needs; the API
            // rejects small values such as dailysteps=1/hourlysteps=7 with a parameters
            // error. alert=true is kept (verified working); the trend still only uses
            // the next 6 hourly skycon values, still one call. unit=metric:v2 pins
            // precipitation to mm/h, which the daily rain totals below need.
            JSONObject root = new JSONObject(fetchUrl(String.format(Locale.US, "https://api.caiyunapp.com/v2.6/%s/%s,%s/weather?alert=true&unit=metric:v2&dailysteps=4&hourlysteps=48", token, city[3], city[2])));
            if (!"ok".equals(root.optString("status", ""))) {
                return null;
            }
            JSONObject result = root.optJSONObject("result");
            if (result == null) {
                return null;
            }
            JSONObject realtime = result.optJSONObject("realtime");
            if (realtime == null) {
                return null;
            }
            double temp = realtime.optDouble("temperature", Double.NaN);
            String skycon = realtime.optString("skycon", "").trim();
            if (Double.isNaN(temp) || skycon.isEmpty()) {
                return null;
            }
            // Same combined call already carries realtime air quality, so this
            // costs no extra request; the AQI line is shared with QWeather.
            String aqi = pickCaiyunAqiText(realtime.optJSONObject("air_quality"));
            Integer high = null;
            Integer low = null;
            String[] dailySkycon = new String[]{"", "", "", ""};
            int[] dailyMin = new int[]{Integer.MIN_VALUE, Integer.MIN_VALUE, Integer.MIN_VALUE, Integer.MIN_VALUE};
            String[] dailyDay = new String[]{"", "", "", ""};
            String[] dailyNight = new String[]{"", "", "", ""};
            double[] rainDay = new double[]{Double.NaN, Double.NaN, Double.NaN, Double.NaN};
            double[] rainNight = new double[]{Double.NaN, Double.NaN, Double.NaN, Double.NaN};
            JSONObject daily = result.optJSONObject("daily");
            if (daily != null) {
                JSONArray temps = daily.optJSONArray("temperature");
                if (temps != null && temps.length() > 0) {
                    JSONObject today = temps.optJSONObject(0);
                    if (today != null) {
                        double max = today.optDouble("max", Double.NaN);
                        double min = today.optDouble("min", Double.NaN);
                        if (!Double.isNaN(max)) high = (int) Math.round(max);
                        if (!Double.isNaN(min)) low = (int) Math.round(min);
                    }
                }
                JSONArray skycons = daily.optJSONArray("skycon");
                if (skycons != null) {
                    for (int i = 0; i < 4 && i < skycons.length(); i++) {
                        JSONObject item = skycons.optJSONObject(i);
                        if (item != null) dailySkycon[i] = item.optString("value", "").trim();
                    }
                }
                JSONArray skyconDay = daily.optJSONArray("skycon_08_20");
                if (skyconDay != null) {
                    for (int i = 0; i < 4 && i < skyconDay.length(); i++) {
                        JSONObject item = skyconDay.optJSONObject(i);
                        if (item != null) dailyDay[i] = item.optString("value", "").trim();
                    }
                }
                JSONArray skyconNight = daily.optJSONArray("skycon_20_32");
                if (skyconNight != null) {
                    for (int i = 0; i < 4 && i < skyconNight.length(); i++) {
                        JSONObject item = skyconNight.optJSONObject(i);
                        if (item != null) dailyNight[i] = item.optString("value", "").trim();
                    }
                }
                // Daily precipitation avg is the mean mm/h over the full 12h window
                // (Caiyun v2.6 daily docs), so avg times 12 is the period total in mm.
                // Both spellings are tried: the docs use precipitation_08h_20h, while
                // the combined /weather response spells the matching skycon fields
                // skycon_08_20 / skycon_20_32, so the precipitation fields may too.
                JSONArray precipDay = daily.optJSONArray("precipitation_08h_20h");
                if (precipDay == null) precipDay = daily.optJSONArray("precipitation_08_20");
                if (precipDay != null) {
                    for (int i = 0; i < 4 && i < precipDay.length(); i++) {
                        JSONObject item = precipDay.optJSONObject(i);
                        if (item != null) {
                            double avg = item.optDouble("avg", Double.NaN);
                            if (!Double.isNaN(avg)) rainDay[i] = avg * 12.0;
                        }
                    }
                }
                JSONArray precipNight = daily.optJSONArray("precipitation_20h_32h");
                if (precipNight == null) precipNight = daily.optJSONArray("precipitation_20_32");
                if (precipNight != null) {
                    for (int i = 0; i < 4 && i < precipNight.length(); i++) {
                        JSONObject item = precipNight.optJSONObject(i);
                        if (item != null) {
                            double avg = item.optDouble("avg", Double.NaN);
                            if (!Double.isNaN(avg)) rainNight[i] = avg * 12.0;
                        }
                    }
                }
                if (temps != null) {
                    for (int i = 0; i < 4 && i < temps.length(); i++) {
                        JSONObject item = temps.optJSONObject(i);
                        if (item != null) {
                            double min = item.optDouble("min", Double.NaN);
                            if (!Double.isNaN(min)) dailyMin[i] = (int) Math.round(min);
                        }
                    }
                }
            }
            String alertTitle = pickAlertTitle(result.optJSONObject("alert"));
            ArrayList<String> hourly = new ArrayList<>();
            JSONObject hourlyObj = result.optJSONObject("hourly");
            if (hourlyObj != null) {
                JSONArray skycons = hourlyObj.optJSONArray("skycon");
                if (skycons != null) {
                    for (int i = 1; i < skycons.length() && hourly.size() < 6; i++) {
                        JSONObject item = skycons.optJSONObject(i);
                        if (item == null) continue;
                        String value = item.optString("value", "").trim();
                        if (!value.isEmpty()) hourly.add(value);
                    }
                }
            }
            return new Result(city[1], temp, skycon, high, low, alertTitle, hourly.toArray(new String[0]), dailySkycon, dailyMin, dailyDay, dailyNight, rainDay, rainNight, !skycon.endsWith("_NIGHT"), aqi);
        } catch (Exception unused) {
            return null;
        }
    }

    public static String[] resolveCity(Context context, SharedPreferences sharedPreferences) throws Exception {
        String strTrim = sharedPreferences.getString("weather_city", "").trim();
        if (!strTrim.isEmpty()) {
            String[] strArrCityEntry = cityEntry(context, strTrim);
            return strArrCityEntry;
        }
        String[] strArrIpCityName = ipCityName();
        if (strArrIpCityName == null) {
            return null;
        }
        String[] strArrMatchCity = matchCity(context, strArrIpCityName[0]);
        return strArrMatchCity;
    }

    private static String stemOf(String x) {
        if (x == null) return "";
        String s = x.trim();
        String[] suffixes = {"特别行政区", "自治区", "省", "市", "区", "县", "旗"};
        for (String suf : suffixes) {
            if (s.endsWith(suf) && s.length() > suf.length()) return s.substring(0, s.length() - suf.length());
        }
        return s;
    }

    private static boolean isChinaProv(String prov) {
        String stem = stemOf(prov);
        String[] cn = {"北京", "天津", "上海", "重庆", "河北", "山西", "辽宁", "吉林", "黑龙江", "江苏", "浙江", "安徽", "福建", "江西", "山东", "河南", "湖北", "湖南", "广东", "海南", "四川", "贵州", "云南", "陕西", "甘肃", "青海", "台湾", "内蒙古", "广西", "西藏", "宁夏", "新疆", "香港", "澳门"};
        for (String c : cn) if (c.equals(stem)) return true;
        return false;
    }

    private static boolean poisoned(String prov, String city) {
        String s = (prov == null ? "" : prov) + " " + (city == null ? "" : city);
        return s.contains("。") || s.contains("，") || s.contains("提供") || s.contains("帮助") || s.contains("查询服务") || s.contains("位置信息");
    }

    private static String geoFetch(String url, String charset) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(5000);
        c.setReadTimeout(5000);
        c.setRequestProperty("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 Chrome/126.0");
        try {
            int code = c.getResponseCode();
            if (code < 200 || code >= 300) throw new Exception("HTTP " + code);
            InputStream in = c.getInputStream();
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int n;
            while ((n = in.read(buf)) != -1) out.write(buf, 0, n);
            in.close();
            return new String(out.toByteArray(), Charset.forName(charset));
        } finally {
            c.disconnect();
        }
    }

    private static String publicIpv4() {
        try {
            String ip = geoFetch("https://ipv4.icanhazip.com", "UTF-8").trim();
            if (ip.matches("\\d{1,3}(\\.\\d{1,3}){3}")) return ip;
        } catch (Exception unused) {
        }
        return null;
    }

    private static String[] geoFromIpcn(String ip) {
        try {
            String txt = geoFetch("https://ip.cn/ip/" + ip + ".html", "UTF-8");
            int i = txt.indexOf("归属地为：");
            if (i < 0) return null;
            String raw = txt.substring(i + 5);
            int end = raw.indexOf('<');
            if (end >= 0) raw = raw.substring(0, end);
            int q = raw.indexOf('"');
            if (q >= 0) raw = raw.substring(0, q);
            String[] toks = raw.trim().split("\\s+");
            ArrayList<String> kept = new ArrayList<>();
            for (String t : toks) {
                if (kept.size() >= 5) break;
                String c = t.replaceAll("[A-Za-z][A-Za-z .&'-]*$", "").trim();
                if (c.isEmpty() || c.contains("提供") || c.contains("帮助") || c.contains("服务") || c.contains("查询") || c.contains("了解") || c.contains("信息")) continue;
                kept.add(c);
            }
            if (kept.size() >= 2) {
                String last = kept.get(kept.size() - 1);
                if (last.equals("移动") || last.equals("联通") || last.equals("电信") || last.equals("铁通") || last.equals("广电") || last.equals("鹏博士") || last.equals("教育网")) kept.remove(kept.size() - 1);
            }
            if (kept.size() < 2) return null;
            String prov = kept.get(1);
            String city = kept.size() > 2 ? kept.get(2) : "";
            if (poisoned(prov, city)) return null;
            return new String[]{prov, city};
        } catch (Exception unused) {
            return null;
        }
    }

    private static String[] geoFromIp138(String ip) {
        try {
            String txt = geoFetch("https://www.ip138.com/iplookup.asp?ip=" + ip + "&action=2", "GBK");
            Matcher m = Pattern.compile("var ip_result = (\\{[\\s\\S]*?\\});").matcher(txt);
            if (!m.find()) return null;
            JSONObject d = new JSONObject(m.group(1));
            JSONArray list = d.optJSONArray("ip_c_list");
            if (list == null || list.length() == 0) return null;
            JSONObject c = list.optJSONObject(0);
            if (c == null) return null;
            String prov = c.optString("prov", "").trim();
            String city = c.optString("city", "").trim();
            if ((prov.isEmpty() && city.isEmpty()) || poisoned(prov, city)) return null;
            return new String[]{prov, city};
        } catch (Exception unused) {
            return null;
        }
    }

    private static String[] voteField(String[] g138, String[] gcn, int idx) {
        String v138 = g138 != null && g138.length > idx ? g138[idx] : null;
        String vcn = gcn != null && gcn.length > idx ? gcn[idx] : null;
        if (v138 != null && !v138.isEmpty()) return new String[]{v138, "ip138"};
        if (vcn != null && !vcn.isEmpty()) return new String[]{vcn, "ip.cn"};
        return null;
    }

    private static String[] ipCityName() {
        String ip = publicIpv4();
        String[] g138 = ip != null ? geoFromIp138(ip) : null;
        String[] gcn = ip != null ? geoFromIpcn(ip) : null;
        String[] provVote = voteField(g138, gcn, 0);
        String[] cityVote = voteField(g138, gcn, 1);
        if (provVote != null && isChinaProv(provVote[0])) {
            String name = cityVote != null ? cityVote[0] : provVote[0];
            String src = cityVote != null ? cityVote[1] : provVote[1];
            return new String[]{name, src};
        }
        return null;
    }

    private static String[] matchCity(Context context, String str) {
        if (str == null) {
            return null;
        }
        try {
            Cities.City c = Cities.INSTANCE.find(context, str.trim());
            if (c != null) {
                return toEntry(c);
            }
        } catch (Exception unused) {
            return null;
        }
        // Fallback: the longest listed name contained in the input, as before.
        try {
            String t = str.trim();
            Cities.City best = null;
            for (Cities.City city : Cities.INSTANCE.load(context)) {
                if (t.contains(city.getName()) && (best == null || city.getName().length() > best.getName().length())) {
                    best = city;
                }
            }
            return best == null ? null : toEntry(best);
        } catch (Exception unused) {
            return null;
        }
    }

    private static String fetchUrl(String str) throws Exception {
        return new String(fetchBytes(str), StandardCharsets.UTF_8);
    }

    private static byte[] fetchBytes(String str) throws Exception {
        HttpURLConnection httpURLConnection = (HttpURLConnection) new URL(str).openConnection();
        httpURLConnection.setConnectTimeout(8000);
        httpURLConnection.setReadTimeout(8000);
        httpURLConnection.setRequestProperty("Accept", "application/json");
        try {
            int responseCode = httpURLConnection.getResponseCode();
            if (responseCode < 200 || responseCode >= 300) {
                throw new Exception("HTTP " + responseCode);
            }
            InputStream inputStream = httpURLConnection.getInputStream();
            ByteArrayOutputStream byteArrayOutputStream = new ByteArrayOutputStream();
            byte[] bArr = new byte[4096];
            int i;
            while ((i = inputStream.read(bArr)) != -1) {
                byteArrayOutputStream.write(bArr, 0, i);
            }
            inputStream.close();
            return byteArrayOutputStream.toByteArray();
        } finally {
            httpURLConnection.disconnect();
        }
    }

}
