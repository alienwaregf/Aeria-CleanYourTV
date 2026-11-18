package com.gathin.tvhome.system

import android.content.Context
import android.os.Build
import org.json.JSONArray
import org.json.JSONObject

class DeviceProfile(
    val hiddenHardware: Set<Int> = emptySet(),
    val labels: Map<Int, String> = emptyMap(),
    val xiaomiSourceIds: Map<Int, Int> = emptyMap(),
    val xiaomiFallbackSourceIds: Map<Int, Int> = emptyMap(),
    val ignoresPictureBacklight: Boolean = false,
    val bootStockMs: Int = 0,
    val ignoresScreenBrightness: Boolean = false,
    val filterHomeAlways: Boolean = false,
    val neverFilterKeys: Boolean = false,
) {
    val isEmpty get() = hiddenHardware.isEmpty() && labels.isEmpty() && xiaomiSourceIds.isEmpty() &&
        xiaomiFallbackSourceIds.isEmpty() && !ignoresPictureBacklight && bootStockMs <= 0 &&
        !ignoresScreenBrightness && !filterHomeAlways && !neverFilterKeys

    fun toJson(): JSONObject = JSONObject().apply {
        if (hiddenHardware.isNotEmpty()) put("hidden", JSONArray(hiddenHardware.sorted()))
        if (labels.isNotEmpty()) put("labels", JSONObject(labels.toSortedMap().mapKeys { it.key.toString() }))
        if (xiaomiSourceIds.isNotEmpty()) put("xiaomiSources", JSONObject(xiaomiSourceIds.toSortedMap().mapKeys { it.key.toString() }))
        if (xiaomiFallbackSourceIds.isNotEmpty()) put("xiaomiFallbackSources", JSONObject(xiaomiFallbackSourceIds.toSortedMap().mapKeys { it.key.toString() }))
        if (ignoresPictureBacklight) put("ignoresPictureBacklight", true)
        if (bootStockMs > 0) put("bootStockMs", bootStockMs)
        if (ignoresScreenBrightness) put("ignoresScreenBrightness", true)
        if (filterHomeAlways) put("filterHomeAlways", true)
        if (neverFilterKeys) put("neverFilterKeys", true)
    }

    enum class Origin { BuiltIn, ThisTv, None }

    companion object {
        private const val PREFS = "system"
        private const val KEY = "device_profile"

        @Volatile private var cached: Pair<DeviceProfile, Origin>? = null

        fun current(context: Context): DeviceProfile = load(context).first

        fun origin(context: Context): Origin = load(context).second

        private fun load(context: Context): Pair<DeviceProfile, Origin> = cached ?: run {
            val own = ownJson(context)?.let { runCatching { parse(JSONObject(it)) }.getOrNull() }
            val result = when {
                own != null -> own to Origin.ThisTv
                else -> builtIn(context)?.let { it to Origin.BuiltIn } ?: (DeviceProfile() to Origin.None)
            }
            cached = result
            result
        }

        fun builtIn(context: Context): DeviceProfile? = runCatching {
            val json = context.assets.open("devices.json").bufferedReader().use { it.readText() }
            match(json, Build.MODEL, buildMajor(), buildMinor())
        }.getOrNull()

        fun ownJson(context: Context): String? =
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, null)

        fun setOwn(context: Context, json: String?): Boolean {
            val text = json?.trim()?.takeIf { it.isNotEmpty() }
            val normalized = if (text == null) null else runCatching { parse(JSONObject(text)).toJson().toString() }.getOrNull() ?: return false
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().apply {
                if (normalized == null) remove(KEY) else putString(KEY, normalized)
            }.commit()
            cached = null
            return true
        }

        fun match(json: String, model: String, buildMajor: Int, buildMinor: Int = 0): DeviceProfile? {
            val entries = JSONObject(json).getJSONArray("profiles")
            for (i in 0 until entries.length()) {
                val entry = entries.getJSONObject(i)
                val models = entry.getJSONArray("models")
                if ((0 until models.length()).none { models.getString(it) == model }) continue
                if (entry.has("buildMajorBelow") && buildMajor >= entry.getInt("buildMajorBelow")) continue
                if (entry.has("buildAtLeast")) {
                    val (major, minor) = entry.getString("buildAtLeast").split('.').map { it.toInt() } + listOf(0)
                    if (buildMajor < major || buildMajor == major && buildMinor < minor) continue
                }
                return parse(entry)
            }
            return null
        }

        fun parse(o: JSONObject): DeviceProfile {
            fun ints(key: String): Map<Int, Int> = o.optJSONObject(key)?.let { m ->
                m.keys().asSequence().associate { it.trim().toInt() to m.getInt(it) }
            } ?: emptyMap()
            return DeviceProfile(
                hiddenHardware = o.optJSONArray("hidden")?.let { a -> (0 until a.length()).map { a.getInt(it) }.toSet() } ?: emptySet(),
                labels = o.optJSONObject("labels")?.let { m ->
                    m.keys().asSequence().associate { it.trim().toInt() to m.getString(it).take(24) }
                } ?: emptyMap(),
                xiaomiSourceIds = ints("xiaomiSources"),
                xiaomiFallbackSourceIds = ints("xiaomiFallbackSources"),
                ignoresPictureBacklight = o.optBoolean("ignoresPictureBacklight", false),
                bootStockMs = o.optInt("bootStockMs", 0).coerceIn(0, 45_000),
                ignoresScreenBrightness = o.optBoolean("ignoresScreenBrightness", false),
                filterHomeAlways = o.optBoolean("filterHomeAlways", false),
                neverFilterKeys = o.optBoolean("neverFilterKeys", false),
            )
        }

        private fun buildMajor(): Int = Build.DISPLAY.substringBefore('.').toIntOrNull() ?: 0

        private fun buildMinor(): Int = Build.DISPLAY.split('.').getOrNull(1)?.toIntOrNull() ?: 0
    }
}
