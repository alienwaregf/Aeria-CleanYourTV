package com.gathin.tvhome;

import android.content.Context;
import android.content.SharedPreferences;
import com.gathin.tvhome.weather.Cities;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.json.JSONArray;
import org.json.JSONObject;

public final class CityLookup {
    CityLookup() {
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

    private static String[] ipCityName() {
        String ip = publicIpv4();
        String[] g138 = ip != null ? geoFromIp138(ip) : null;
        String[] gcn = ip != null ? geoFromIpcn(ip) : null;
        String[] g = g138;
        String src = "ip138";
        if (g == null || g.length == 0 || !isChinaProv(g[0])) {
            g = gcn;
            src = "ip.cn";
        }
        if (g == null || g.length == 0 || !isChinaProv(g[0])) return null;
        String prov = g[0];
        String city = g.length > 1 ? g[1] : "";
        String name = city != null && !city.isEmpty() ? city : prov;
        return new String[]{name, src};
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
}
