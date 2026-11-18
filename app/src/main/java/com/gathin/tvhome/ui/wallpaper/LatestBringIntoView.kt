package com.gathin.tvhome.ui.wallpaper

import androidx.compose.foundation.MutatorMutex
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.gestures.stopScroll
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.node.LayoutAwareModifierNode
import androidx.compose.ui.node.ModifierNodeElement
import androidx.compose.ui.node.requireLayoutCoordinates
import androidx.compose.ui.platform.InspectorInfo
import androidx.compose.ui.relocation.BringIntoViewModifierNode
import androidx.compose.ui.relocation.bringIntoView
import kotlinx.coroutines.CancellationException

internal fun Modifier.latestBringIntoView(scroll: ScrollState): Modifier =
    this then LatestBringIntoViewElement(scroll)

private data class LatestBringIntoViewElement(val scroll: ScrollState) : ModifierNodeElement<LatestBringIntoViewNode>() {
    override fun create() = LatestBringIntoViewNode(scroll)
    override fun update(node: LatestBringIntoViewNode) { node.scroll = scroll }
    override fun InspectorInfo.inspectableProperties() { name = "latestBringIntoView" }
}

private class LatestBringIntoViewNode(var scroll: ScrollState) : Modifier.Node(), BringIntoViewModifierNode, LayoutAwareModifierNode {
    private val requests = MutatorMutex()
    private var pendingRequests = 0
    private var hasBeenPlaced = false

    override fun onPlaced(coordinates: LayoutCoordinates) { hasBeenPlaced = true }
    override fun onDetach() { hasBeenPlaced = false }

    override suspend fun bringIntoView(childCoordinates: LayoutCoordinates, boundsProvider: () -> Rect?) {
        val replacing = pendingRequests > 0
        pendingRequests++
        try {
            requests.mutate {
                if (replacing) scroll.stopScroll()
                bringIntoView {
                    if (!isAttached || !hasBeenPlaced || !childCoordinates.isAttached) {
                        throw CancellationException("Bring-into-view target is no longer placed")
                    }
                    val coordinates = requireLayoutCoordinates()
                    val childRect = boundsProvider()
                        ?: throw CancellationException("Bring-into-view target no longer has bounds")
                    childRect.translate(
                        coordinates.localBoundingBoxOf(childCoordinates, clipBounds = false).topLeft,
                    )
                }
            }
        } finally {
            pendingRequests--
        }
    }
}
