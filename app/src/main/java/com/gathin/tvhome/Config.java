package com.gathin.tvhome;

import android.content.Context;
import android.content.SharedPreferences;

final class Config {
    static final String SETTINGS_NAME = "launcher_settings";

    private Config() {
    }

    static SharedPreferences prefs(Context ctx) {
        return ctx.getSharedPreferences(SETTINGS_NAME, Context.MODE_PRIVATE);
    }

    static String locale(Context ctx) {
        return ctx.getString(R.string.locale).trim();
    }
}
