package com.gathin.tvhome;

import android.content.Context;
import android.content.res.Configuration;
import java.util.Locale;
import java.util.regex.Pattern;

final class LauncherLocale {
    private static final Pattern TAG = Pattern.compile("[a-z]{2,3}(-[A-Za-z0-9]{1,8})*");

    private LauncherLocale() {
    }

    static Locale parse(String tag) {
        if (tag == null || !TAG.matcher(tag.trim()).matches()) return null;
        return Locale.forLanguageTag(tag.trim());
    }

    static Context wrap(Context base) {
        Locale locale = parse(Config.locale(base));
        if (locale == null) return base;
        Locale.setDefault(locale);
        Configuration c = new Configuration(base.getResources().getConfiguration());
        c.setLocale(locale);
        return base.createConfigurationContext(c);
    }
}
