package com.gathin.tvhome.apps

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class Shelf(private val context: Context, actions: ShelfCards.Actions) {

    private var app: ShelfApp? = null
    private var shownAt: Int = -1
    private var step: Int = 0
    private var away: Boolean = false
    private var artAt: Int = 0
    private var artFor: String? = null
    private var cardsFor: String? = null
    private var sceneChanged: (() -> Unit)? = null

    private val view: FrameLayout = object : FrameLayout(context) {
        override fun hasOverlappingRendering(): Boolean = false
    }
    private var top: Stage
    private var under: Stage
    private val art: Array<ImageView>
    private val cards: ShelfCards
    private val artIo: ExecutorService = Executors.newSingleThreadExecutor()

    init {
        top = Stage(context)
        under = Stage(context)
        art = Array(2) {
            ImageView(context).apply {
                scaleType = ImageView.ScaleType.CENTER_CROP
                visibility = View.INVISIBLE
            }
        }
        for (imageView in art) {
            view.addView(imageView, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        }
        view.addView(under, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        view.addView(top, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        cards = ShelfCards(context, actions)
        view.addView(cards, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        cards.visibility = View.INVISIBLE
        under.visibility = View.INVISIBLE
        top.visibility = View.INVISIBLE
    }


    fun onSceneChanged(runnable: (() -> Unit)?) {
        sceneChanged = runnable
    }

    fun onCardsExitUp(cb: (() -> Unit)?) {
        cards.onExitUp = cb
    }

    fun onCardsExitDown(cb: (() -> Unit)?) {
        cards.onExitDown = cb
    }


    fun view(): View = view

    fun cardsEntry(): View? {
        if (cardsFor == null || cards.visibility != View.VISIBLE || away) {
            return null
        }
        return cards.entry()
    }

    fun scene(): Bitmap? {
        if (away || top.visibility != View.VISIBLE) {
            return null
        }
        return top.colourPicture()
    }

    fun stageCovers(): Boolean {
        for (imageView in art) {
            if (imageView.visibility == View.VISIBLE && imageView.alpha >= 0.999f) {
                return true
            }
        }
        return !(away ||
            (under.visibility != View.VISIBLE &&
                (top.visibility != View.VISIBLE || top.alpha < 0.999f)))
    }



    fun content(map: Map<String, List<ShelfCard>>) {
        if (context.getSharedPreferences("topshelf", Context.MODE_PRIVATE)
                .getBoolean("app_content_enabled", true)
        ) {
            for (list in map.values) {
                cards.prefetch(list)
            }
        }
        if (away || app == null) {
            return
        }
        if ((cards.visibility == View.VISIBLE) != (cardsOf(app) != null)) {
            step = 0
            show(app)
        }
    }

    private fun cardsOf(app: ShelfApp?): List<ShelfCard>? {
        if (app == null || !TopShelfContent.shelfEnabled(context)) {
            return null
        }
        val list = TopShelfContent.cardsFor(app.pkg)
        if (list.isEmpty()) {
            return null
        }
        return list
    }

    private fun staged(): Boolean =
        context.getSharedPreferences("topshelf", Context.MODE_PRIVATE)
            .getBoolean("app_mark", true)


    fun focus(app: ShelfApp?, i: Int) {
        if (app == this.app && (top.visibility == View.VISIBLE || away)) {
            return
        }
        this.app = app
        if (away) {
            return
        }
        step = if (shownAt < 0 || i == shownAt) 0 else Integer.signum(i - shownAt)
        shownAt = i
        show(app)
    }

    fun refresh() {
        val list = cardsOf(app)
        if (!away) {
            art(if (list != null) app else null)
        }
        if (cards.layoutChanged()) {
            cardsFor = null
        }
        val topVisible = top.visibility == View.VISIBLE
        if (away) {
            return
        }
        if (topVisible == (staged() && app != null) &&
            (cardsFor != null || list == null)
        ) {
            if ((cards.visibility == View.VISIBLE) == (list != null)) {
                return
            }
        }
        step = 0
        show(app)
    }

    fun away(away: Boolean) {
        if (this.away == away) {
            return
        }
        this.away = away
        step = 0
        show(if (away) null else app)
    }


    private fun show(app: ShelfApp?) {
        val list = cardsOf(app)
        art(if (away || list == null) null else app)
        if (list != null) {
            stage(null)
            val focusedApp = app!!
            if (focusedApp.pkg == cardsFor && cards.visibility == View.VISIBLE) {
                return
            }
            cardsFor = focusedApp.pkg
            val wasVisible = cards.visibility == View.VISIBLE
            cards.set(focusedApp.label, list)
            cards.visibility = View.VISIBLE
            if (!wasVisible) {
                cards.alpha = 0.0f
            }
            Motion.change(Motion.SHELF_SWAP, cards, { cards.alpha = 1.0f })
            val dir = if (wasVisible) step else 0
            Motion.step(
                Motion.SHELF_SWAP, context, dir,
                cards.slideKey, { f -> cards.slide(f) },
                Any(), 0.0f, { _ -> },
            )
            return
        }
        cardsFor = null
        if (cards.visibility == View.VISIBLE) {
            Motion.change(Motion.SHELF_SWAP, cards, { cards.alpha = 0.0f }) {
                if (cardsFor == null && cards.alpha < 0.01f) {
                    cards.visibility = View.INVISIBLE
                }
            }
        }
        stage(if (!staged()) null else app)
    }

    private fun stage(app: ShelfApp?) {
        val oldTop = top
        if (app == null) {
            if (oldTop.visibility != View.VISIBLE) {
                return
            }
            Motion.change(Motion.SHELF_SWAP, oldTop, { oldTop.alpha = 0.0f }) {
                if (oldTop.alpha < 0.01f) {
                    oldTop.visibility = View.INVISIBLE
                    sceneChanged?.invoke()
                }
            }
            under.visibility = View.INVISIBLE
            return
        }
        top = under
        under = oldTop
        top.bringToFront()
        val newTop = top
        val oldUnder = under
        newTop.set(app)
        newTop.alpha = 0.0f
        newTop.visibility = View.VISIBLE
        sceneChanged?.invoke()
        Motion.change(Motion.SHELF_SWAP, newTop, { newTop.alpha = 1.0f }) {
            if (top === newTop && newTop.alpha > 0.99f) {
                oldUnder.visibility = View.INVISIBLE
            }
            sceneChanged?.invoke()
        }
        val dir = if (oldUnder.visibility == View.VISIBLE) step else 0
        Motion.step(
            Motion.SHELF_SWAP, context, dir,
            newTop.slideKey, { f -> newTop.slide(f) },
            oldUnder.slideKey, oldUnder.shift, { f -> oldUnder.slide(f) },
        )
    }


    private fun art(app: ShelfApp?) {
        val pkg = if (app == null || !TopShelfContent.appArtEnabled(context)) null else app.pkg
        if (pkg == artFor) {
            return
        }
        artFor = pkg
        val list = if (pkg == null) null else cardsOf(app)
        if (list.isNullOrEmpty()) {
            artShow(null)
            return
        }
        artShow(null)
        val pkgNonnull: String = pkg ?: return
        val widthPx = context.resources.displayMetrics.widthPixels
        val heightPx = context.resources.displayMetrics.heightPixels
        artIo.execute {
            val bitmap = TopShelfContent.appArtBackdrop(context, pkgNonnull, list, widthPx, heightPx)
            view.post {
                if (bitmap == null) {
                    return@post
                }
                if (pkgNonnull == artFor) {
                    artShow(bitmap)
                } else if (!bitmap.isRecycled && !TopShelfContent.isArtCached(bitmap)) {
                    bitmap.recycle()
                }
            }
        }
    }

    private fun artShow(bitmap: Bitmap?) {
        val current = art[artAt]
        if (current.visibility == View.VISIBLE) {
            Motion.progress(Motion.SHELF_SWAP, current, current.alpha, 0.0f,
                { f ->
                    current.alpha = f
                    sceneChanged?.invoke()
                },
                {
                    if (current.alpha < 0.01f) {
                        current.visibility = View.INVISIBLE
                    }
                })
        }
        if (bitmap == null || bitmap.isRecycled) {
            return
        }
        artAt = 1 - artAt
        val next = art[artAt]
        val old = (next.drawable as? BitmapDrawable)?.bitmap
        if (old != null && old !== bitmap && !old.isRecycled && !TopShelfContent.isArtCached(old)) {
            old.recycle()
        }
        next.setImageBitmap(bitmap)
        next.alpha = 0.0f
        next.visibility = View.VISIBLE
        next.bringToFront()
        under.bringToFront()
        top.bringToFront()
        cards.bringToFront()
        Motion.progress(Motion.SHELF_SWAP, next, 0.0f, 1.0f,
            { f ->
                next.alpha = f
                sceneChanged?.invoke()
            }, null)
    }

    class Stage(context: Context) : View(context) {

        private val fill = Paint()
        private val picture = Paint(Paint.FILTER_BITMAP_FLAG)
        private var color: Int = 0
        private var colourFor: Int = 0
        private var colourPicture: Bitmap? = null
        private var mark: Bitmap? = null
        private var markPkg: String? = null

        var shift: Float = 0.0f

        val slideKey: Any = Any()

        override fun hasOverlappingRendering(): Boolean = false

        fun slide(f: Float) {
            shift = f
            invalidate()
        }

        fun set(app: ShelfApp) {
            val art = TopShelfContent.stageArt(
                context, app, context.resources.displayMetrics.widthPixels,
            )
            color = art.color
            fill.shader = null
            val oldMark = mark
            val oldPkg = markPkg
            mark = art.mark
            markPkg = app.pkg
            if (oldMark != null && oldMark !== art.mark && !oldMark.isRecycled) {
                val stillCached = oldPkg != null && TopShelfContent.cachedMark(oldPkg) === oldMark
                if (!stillCached) {
                    oldMark.recycle()
                }
            }
            invalidate()
        }

        override fun onDraw(canvas: Canvas) {
            val width = width
            val height = height
            if (fill.shader == null && width > 0) {
                fill.shader = LinearGradient(
                    0.0f, 0.0f, width.toFloat(), height.toFloat(),
                    TopShelfContent.shade(color, 1.18f),
                    TopShelfContent.shade(color, 0.72f),
                    Shader.TileMode.CLAMP,
                )
            }
            canvas.drawRect(0.0f, 0.0f, width.toFloat(), height.toFloat(), fill)
            val markBitmap = mark
            if (markBitmap != null) {
                val centerY = Dimens.px(context, Dimens.SHELF_MARK_Y)
                canvas.drawBitmap(
                    markBitmap,
                    (width / 2.0f - markBitmap.width / 2.0f) + shift,
                    centerY - markBitmap.height / 2.0f,
                    picture,
                )
            }
        }

        fun colourPicture(): Bitmap {
            val cached = colourPicture
            if (cached != null && colourFor == color) {
                return cached
            }
            val bitmap = Bitmap.createBitmap(96, 54, Bitmap.Config.ARGB_8888)
            val paint = Paint()
            paint.shader = LinearGradient(
                0.0f, 0.0f, 96.0f, 54.0f,
                TopShelfContent.shade(color, 1.18f),
                TopShelfContent.shade(color, 0.72f),
                Shader.TileMode.CLAMP,
            )
            Canvas(bitmap).drawRect(0.0f, 0.0f, 96.0f, 54.0f, paint)
            bitmap.setHasAlpha(false)
            colourPicture = bitmap
            colourFor = color
            return bitmap
        }
    }
}

data class ShelfApp(
    val pkg: String,
    val label: String,
    val banner: Drawable?,
    val icon: Drawable?,
) {
    fun picture(): Drawable? = banner ?: icon
}
