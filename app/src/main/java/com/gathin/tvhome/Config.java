package com.gathin.tvhome;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * Slimming-only settings. The desktop is gone, and with it every desktop feature —
 * this file keeps only what SystemSlimmingActivity needs: the SharedPreferences file
 * (same name as before, so an upgrade keeps the cached slimming list and switches)
 * and the locale tag the remaining activity wraps itself in.
 */
final class Config {
    /** SharedPreferences file. Kept unchanged across the desktop removal so the
     *  slimming cache (folder, list, date, label, notes, daily switch) survives an upgrade. */
    static final String SETTINGS_NAME = "launcher_settings";

    private Config() {
    }

    static SharedPreferences prefs(Context ctx) {
        return ctx.getSharedPreferences(SETTINGS_NAME, Context.MODE_PRIVATE);
    }

    /** The locale tag from res/values/config.xml ("" = the TV's own language). */
    static String locale(Context ctx) {
        return ctx.getString(R.string.locale).trim();
    }
}
