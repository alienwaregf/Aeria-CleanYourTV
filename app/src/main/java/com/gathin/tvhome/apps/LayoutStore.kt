package com.gathin.tvhome.apps

import android.content.Context
import androidx.core.content.edit
import org.json.JSONArray
import org.json.JSONObject

data class Folder(val id: String, val name: String, val apps: List<String>) {
    val key: String get() = FOLDER_KEY_PREFIX + id
}

const val FOLDER_KEY_PREFIX = "folder:"

class Arrangement(val home: List<AppEntry>, val folders: Map<String, List<AppEntry>>, val hidden: List<AppEntry>)

class LayoutStore(context: Context) {
    private val prefs = context.getSharedPreferences("layout", Context.MODE_PRIVATE)

    private var order: List<String> =
        prefs.getString("order", null)?.split(',')?.filter { it.isNotBlank() } ?: emptyList()
    private var hidden: Set<String> = prefs.getStringSet("hidden", null)?.toSet() ?: emptySet()
    private var folders: Map<String, Folder> = readFolders(prefs.getString("folders", null))
    private var initialized = prefs.getBoolean("initialized", false)

    fun arrange(all: List<AppEntry>, folderTile: (Folder, List<AppEntry>) -> AppEntry): Arrangement {
        val byKey = all.associateBy { it.key }
        if (!initialized) {
            order = all.sortedWith(Defaults.comparator).map { it.key }
            hidden = Defaults.hidden(byKey.keys)
            initialized = true
        }
        val known = order.toHashSet().apply { folders.values.forEach { addAll(it.apps) } }
        val fresh = all.filter { it.key !in known }.sortedWith(Defaults.comparator).map { it.key }
        order = order + fresh
        save()
        val contents = folders.mapValues { (_, f) -> f.apps.mapNotNull(byKey::get).filter { it.key !in hidden } }
        val home = order.mapNotNull { key ->
            val folder = folderOf(key)
            if (folder != null) contents[folder.id]?.takeIf { it.isNotEmpty() }?.let { folderTile(folder, it) }
            else byKey[key]?.takeIf { key !in hidden }
        }
        val inAnyOrder = order + folders.values.flatMap { it.apps }
        return Arrangement(home, contents, inAnyOrder.mapNotNull(byKey::get).filter { it.key in hidden })
    }

    fun folders(): List<Folder> = order.mapNotNull(::folderOf)

    fun leading(tiles: Int, installed: Set<String>): Set<String> = buildSet {
        var n = 0
        for (key in order) {
            if (n >= tiles) break
            if (key in hidden) continue
            val folder = folderOf(key)
            if (folder != null) {
                val inside = folder.apps.filter { it !in hidden && it in installed }
                if (inside.isEmpty()) continue
                addAll(inside.take(9))
            } else if (key in installed) add(key)
            else if (!key.startsWith(SOURCE_KEY_PREFIX)) continue
            n++
        }
    }

    fun folderHolding(key: String): Folder? = folders.values.firstOrNull { key in it.apps }

    fun putInFolder(key: String, id: String?, newName: String): String {
        val list = order.toMutableList()
        val at = list.indexOf(key).takeIf { it >= 0 }
            ?: folderHolding(key)?.let { list.indexOf(it.key) + 1 }?.takeIf { it > 0 }
            ?: list.size
        takeOut(key, list)
        val folder = id?.let(folders::get)
        val into = if (folder != null) {
            folder.copy(apps = folder.apps + key)
        } else {
            val newId = ((folders.keys.mapNotNull { it.toIntOrNull() }.maxOrNull() ?: 0) + 1).toString()
            Folder(newId, newName, listOf(key)).also { list.add(at.coerceAtMost(list.size), it.key) }
        }
        folders = folders + (into.id to into)
        dropEmpty(list)
        order = list
        save()
        return into.id
    }

    fun takeOutOfFolder(key: String) {
        val folder = folderHolding(key) ?: return
        val list = order.toMutableList()
        takeOut(key, list)
        val at = list.indexOf(folder.key)
        list.add(if (at >= 0) at + 1 else list.size, key)
        dropEmpty(list)
        order = list
        save()
    }

    fun moveInFolder(key: String, target: String) {
        if (key == target) return
        val folder = folderHolding(key)?.takeIf { target in it.apps } ?: return
        val list = folder.apps.toMutableList()
        val from = list.indexOf(key)
        val to = list.indexOf(target)
        list.removeAt(from)
        list.add(list.indexOf(target).let { if (from < to) it + 1 else it }, key)
        folders = folders + (folder.id to folder.copy(apps = list))
        save()
    }

    fun renameFolder(id: String, name: String) {
        val folder = folders[id] ?: return
        folders = folders + (id to folder.copy(name = name))
        save()
    }

    fun ungroup(id: String) {
        val folder = folders[id] ?: return
        val list = order.toMutableList()
        val at = list.indexOf(folder.key)
        list.remove(folder.key)
        list.addAll(if (at >= 0) at else list.size, folder.apps.filter { it !in list })
        folders = folders - id
        order = list
        save()
    }

    private fun folderOf(key: String): Folder? =
        if (key.startsWith(FOLDER_KEY_PREFIX)) folders[key.removePrefix(FOLDER_KEY_PREFIX)] else null

    private fun takeOut(key: String, list: MutableList<String>) {
        list.remove(key)
        folders = folders.mapValues { (_, f) -> if (key in f.apps) f.copy(apps = f.apps - key) else f }
    }

    private fun dropEmpty(list: MutableList<String>) {
        val empty = folders.values.filter { it.apps.isEmpty() }
        empty.forEach { list.remove(it.key) }
        folders = folders - empty.map { it.id }.toSet()
    }

    fun hide(key: String) { hidden = hidden + key; save() }
    fun unhide(key: String) { hidden = hidden - key; save() }

    fun move(key: String, target: String) {
        if (key == target) return
        val list = order.toMutableList()
        val from = list.indexOf(key)
        val to = list.indexOf(target)
        if (from < 0 || to < 0) return
        list.removeAt(from)
        val insertAt = list.indexOf(target).let { if (from < to) it + 1 else it }
        list.add(insertAt, key)
        order = list
        save()
    }

    private fun save() = prefs.edit {
        putString("order", order.joinToString(","))
        putStringSet("hidden", hidden)
        putString("folders", JSONArray().apply {
            folders.values.forEach { f ->
                put(JSONObject().put("id", f.id).put("name", f.name).put("apps", JSONArray(f.apps)))
            }
        }.toString())
        putBoolean("initialized", true)
    }

    private fun readFolders(json: String?): Map<String, Folder> = runCatching {
        val array = JSONArray(json ?: return@runCatching emptyMap())
        buildMap {
            for (i in 0 until array.length()) {
                runCatching {
                    val o = array.getJSONObject(i)
                    val apps = o.getJSONArray("apps").let { a -> (0 until a.length()).map(a::getString) }
                    val id = o.getString("id")
                    put(id, Folder(id, o.getString("name"), apps))
                }
            }
        }
    }.getOrDefault(emptyMap())
}

private object Defaults {
    private val preferred = listOf(
        "com.ktcp.video",
        "com.gitvdemo.video",
        "com.cibn.tv",
        "com.hunantv.license",
        "com.xiaodianshi.tv.yst",
        "com.netflix.ninja",
        "com.google.android.youtube.tv",
        "com.apple.atve.androidtv.appletv",
        "com.disney.disneyplus",
        "com.amazon.amazonvideo.livingroom",
        "com.hbo.hbonow",
        "com.spotify.tv.android",
        "com.mitv.mivideoplayer",
        "com.quark.yun.tv",
    )

    private val utilities = listOf(
        "com.xiaomi.mitv.settings",
        "com.android.tv.settings",
        "com.xiaomi.mitv.tvmanager",
        "com.mitv.tvhome",
        "com.google.android.tvlauncher",
        "com.google.android.apps.tv.launcherx",
    )

    val comparator = compareBy<AppEntry>(
        { preferred.indexOf(it.key).let { i -> if (i < 0) Int.MAX_VALUE else i } },
        { if (it.key in utilities || it.key.startsWith("com.android.")) 1 else 0 },
        { utilities.indexOf(it.key) },
        { it.label.lowercase() },
    )

    fun hidden(installed: Set<String>): Set<String> = buildSet {
        if ("com.xiaomi.mitv.settings" in installed) add("com.android.tv.settings")
    }
}
