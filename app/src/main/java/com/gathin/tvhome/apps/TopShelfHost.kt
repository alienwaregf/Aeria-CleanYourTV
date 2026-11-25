package com.gathin.tvhome.apps

import android.content.Context
import android.graphics.Bitmap
import android.view.View
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView

class ShelfHostState internal constructor(
    val shelf: Shelf,
    val scene: State<Bitmap?>,
    val stageCovers: State<Boolean>,
) {
    fun shelfAppFor(context: Context, pkg: String): ShelfApp? {
        return TopShelfContent.shelfApp(context, pkg)
    }
}

@Composable
fun rememberShelfHost(
    onExitUp: () -> Unit,
    onExitDown: () -> Unit,
): ShelfHostState {
    val context = LocalContext.current
    val exitUp = rememberUpdatedState(onExitUp)
    val exitDown = rememberUpdatedState(onExitDown)
    val scene = remember { mutableStateOf<Bitmap?>(null) }
    val covers = remember { mutableStateOf(false) }
    val shelf = remember(context) {
        Shelf(context, object : ShelfCards.Actions {
            override fun open(show: ShelfCard, view: View): Boolean {
                val launchPkg = if (YoutubeSubs.isYoutube(show.pkg)) {
                    YoutubeSubs.firstInstalledLaunchPkg(context) ?: return false
                } else show.pkg
                val detail = TopShelfContent.cardIntent(context, show, launchPkg)
                if (detail != null && TopShelfContent.launchCard(context, detail, view)) return true
                val app = TopShelfContent.appLaunchIntent(context, launchPkg) ?: return false
                return TopShelfContent.launchCard(context, app, view)
            }
        }).also { s ->
            s.onSceneChanged {
                scene.value = s.scene()
                covers.value = s.stageCovers()
            }
            s.onCardsExitUp { exitUp.value() }
            s.onCardsExitDown { exitDown.value() }
        }
    }
    DisposableEffect(context) {
        onDispose { shelf.close() }
    }
    return ShelfHostState(shelf, scene, covers)
}

@Composable
fun ShelfHostView(state: ShelfHostState, modifier: Modifier = Modifier) {
    key(state.shelf) {
        AndroidView(
            factory = { state.shelf.view() },
            modifier = modifier.fillMaxSize(),
        )
    }
}
