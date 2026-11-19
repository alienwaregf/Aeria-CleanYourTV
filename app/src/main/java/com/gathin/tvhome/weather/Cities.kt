package com.gathin.tvhome.weather

import android.content.Context
import kotlin.math.cos
import kotlin.math.sqrt

object Cities {
    class City(val name: String, val aliases: List<String>, val province: String, val lat: Double, val lon: Double, val rank: Int, val ascii: String)

    @Volatile private var all: List<City>? = null

    fun load(context: Context): List<City> = all ?: synchronized(this) {
        all ?: context.assets.open("cities-cn.tsv").bufferedReader().useLines { lines ->
            lines.filter { !it.startsWith("#") && it.isNotBlank() }.mapNotNull { line ->
                val c = line.split('\t')
                if (c.size < 7) null else runCatching {
                    City(c[0], c[1].split(',').filter { it.isNotEmpty() }, c[2], c[3].toDouble(), c[4].toDouble(), c[5].toInt(), c[6])
                }.getOrNull()
            }.toList()
        }.also { all = it }
    }

    fun find(context: Context, typed: String): City? {
        val q = typed.filterNot { it.isWhitespace() }.removePrefix("中国")
        if (q.isEmpty() || q.any { it.code < 0x4e00 || it.code > 0x9fff }) return null
        val cities = load(context)
        fun inProvince(city: City) = city.province.isNotEmpty() && q.startsWith(city.province)
        fun rest(city: City) = short(q.removePrefix(city.province).trimStart('省', '市').removePrefix("自治区").removePrefix("壮族自治区"))
        return cities.firstOrNull { it.name == short(q) }
            ?: cities.firstOrNull { inProvince(it) && it.name == rest(it) }
            ?: cities.firstOrNull { short(q) in it.aliases }
            ?: cities.firstOrNull { inProvince(it) && rest(it) in it.aliases }
    }

    fun near(context: Context, name: String, lat: Double, lon: Double, km: Double, ascii: Boolean = false): City? =
        load(context)
            .filter { if (ascii) it.ascii.equals(name, ignoreCase = true) else it.name == short(name) }
            .minByOrNull { distanceKm(it.lat, it.lon, lat, lon) }
            ?.takeIf { distanceKm(it.lat, it.lon, lat, lon) <= km }

    fun mainNear(context: Context, lat: Double, lon: Double, km: Double): City? =
        load(context)
            .map { it to distanceKm(it.lat, it.lon, lat, lon) }
            .filter { it.second <= km }
            .minWithOrNull(compareBy<Pair<City, Double>>({ it.first.rank }, { it.second }))
            ?.first

    private val suffix = Regex("(壮族自治区|回族自治区|维吾尔自治区|特别行政区|自治区|自治州|自治县|地区|新区|省|市|县|区|盟|旗)$")

    private fun short(name: String): String = suffix.replace(name, "").takeIf { it.length >= 2 } ?: name

    fun distanceKm(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val x = (lon2 - lon1) * cos(Math.toRadians((lat1 + lat2) / 2))
        val y = lat2 - lat1
        return sqrt(x * x + y * y) * 111.2
    }
}
