package com.gathin.tvhome.apps

class TileRecipe(
    val useIcon: Boolean = false,
    val top: Int? = null,
    val bottom: Int? = null,
    val white: Boolean = false,
    val keepColors: Boolean = false,
    val glyph: com.gathin.tvhome.ui.theme.Glyph? = null,
)

object TileRecipes {
    private val byPackage = mapOf(
        "com.mitv.tvhome" to TileRecipe(top = 0xFF4C8DFF.toInt(), bottom = 0xFF1F55E0.toInt(), white = true),
        "com.duokan.videodaily" to TileRecipe(top = 0xFFFF4B4B.toInt(), bottom = 0xFFD0101C.toInt()),
        "cn.wps.moffice_i18n_TV" to TileRecipe(top = 0xFFFF5A3C.toInt(), bottom = 0xFFD81E1E.toInt(), white = true),
        "com.dangbeimarket" to TileRecipe(useIcon = true, keepColors = true),
        "com.wtj.cn.alipantv" to TileRecipe(useIcon = true),
        "com.tcl.tshop" to TileRecipe(keepColors = true),
        "com.quark.yun.tv" to TileRecipe(top = 0xFF6A7BFF.toInt(), bottom = 0xFF3F3BE0.toInt(), white = true),
        "com.lizongying.mytv" to TileRecipe(top = 0xFF1FA88E.toInt(), bottom = 0xFF0E5C55.toInt(), glyph = com.gathin.tvhome.ui.theme.Glyph.Antenna),
        "com.baidu.netdisk.tv" to TileRecipe(useIcon = true),
        "com.dangbei.dbmusic" to TileRecipe(useIcon = true),
        "com.dangbei.tvlauncher" to TileRecipe(useIcon = true),
        "com.tencent.karaoketv" to TileRecipe(useIcon = true),
        "cn.jmake.karaoke.box.open" to TileRecipe(useIcon = true),
        "com.xunlei.downloadprovider.hd" to TileRecipe(useIcon = true),
        "com.ucmobile.yun.tv" to TileRecipe(keepColors = true),
        "com.zmg.ott" to TileRecipe(top = 0xFF3FA9FF.toInt(), bottom = 0xFF1747E0.toInt(), white = true),
        "com.sohuott.tv.vod" to TileRecipe(keepColors = true),
    )

    fun of(pkg: String): TileRecipe? = byPackage[pkg]
}
