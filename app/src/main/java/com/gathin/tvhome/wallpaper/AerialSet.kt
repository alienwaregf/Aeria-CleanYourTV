package com.gathin.tvhome.wallpaper

import android.content.Context
import androidx.core.content.edit
import java.time.LocalDate

object AerialSet {
    const val MAX = 3

    private const val PREFS = "aerial_set"

    private fun prefs(context: Context) = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun ids(context: Context): List<String> =
        prefs(context).getString("films", null)?.split(',')?.filter { it.isNotEmpty() }.orEmpty()

    private fun save(context: Context, ids: List<String>, day: String? = null) = prefs(context).edit {
        putString("films", ids.joinToString(","))
        if (day != null) putString("day", day)
    }

    @Synchronized
    fun films(context: Context): List<Aerial> {
        val all = Aerials.all(context)
        val known = ids(context).mapNotNull { id -> all.firstOrNull { it.id == id } }
        if (known.isNotEmpty()) return known
        if (all.isEmpty()) return emptyList()
        val first = runCatching { Wallpapers.liveSpot() }.getOrNull()?.first?.let { id -> all.firstOrNull { it.id == id } } ?: all.random()
        save(context, listOf(first.id), today())
        return listOf(first)
    }

    @Synchronized
    fun playlist(context: Context, current: String?): List<Aerial> {
        var set = films(context)
        val all = Aerials.all(context)
        if (current != null && set.none { it.id == current }) {
            all.firstOrNull { it.id == current }?.let { film ->
                set = (listOf(film) + set).take(MAX)
                save(context, set.map { it.id })
            }
        }
        val at = set.indexOfFirst { it.id == current }.coerceAtLeast(0)
        return set.drop(at) + set.take(at)
    }

    @Synchronized
    fun nextNew(context: Context): Aerial {
        val all = Aerials.all(context)
        prefs(context).getString("next", null)?.let { id -> all.firstOrNull { it.id == id } }?.let { return it }
        val kept = ids(context).toSet()
        val film = all.filter { it.id !in kept }.randomOrNull() ?: all.randomOrNull() ?: error("no aerial films")
        prefs(context).edit { putString("next", film.id) }
        return film
    }

    @Synchronized
    fun addNew(context: Context): Aerial {
        val film = nextNew(context)
        val set = (listOf(film.id) + ids(context).filter { it != film.id }).take(MAX)
        save(context, set, today())
        prefs(context).edit { remove("next") }
        com.gathin.tvhome.system.DiagLog.i("aerial films now ${set.joinToString(",") { it.take(8) }}")
        return film
    }

    fun due(context: Context): Boolean = prefs(context).getString("day", null) != today()

    @Synchronized
    fun takeDaily(context: Context): Aerial? = if (due(context)) addNew(context) else null

    internal var today: () -> String = { LocalDate.now().toString() }
}
