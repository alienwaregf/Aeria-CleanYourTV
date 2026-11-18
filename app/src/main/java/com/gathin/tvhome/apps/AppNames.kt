package com.gathin.tvhome.apps

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.edit
import org.json.JSONObject

object AppNames {
    private const val PREFS = "settings"
    private const val KEY = "app_names"
    const val MAX_NAME = 24

    var all by mutableStateOf<Map<String, String>>(emptyMap()); private set
    private lateinit var app: Context

    fun init(context: Context) {
        if (::app.isInitialized) return
        app = context.applicationContext
        val json = runCatching { JSONObject(app.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, null) ?: "{}") }
            .getOrDefault(JSONObject())
        all = json.keys().asSequence().associateWith { json.optString(it) }.filterValues { it.isNotBlank() }
    }

    fun of(key: String): String? = all[key]

    fun rename(key: String, name: String?) {
        if (!::app.isInitialized) return
        val clean = name?.trim()?.take(MAX_NAME)?.takeIf { it.isNotEmpty() }
        all = if (clean == null) all - key else all + (key to clean)
        val json = JSONObject()
        all.forEach { (k, v) -> json.put(k, v) }
        app.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit { putString(KEY, json.toString()) }
    }
}
