package com.gathin.tvhome.apps

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Outline
import android.graphics.Typeface
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.GradientDrawable
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewOutlineProvider
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import com.gathin.tvhome.R
import java.util.concurrent.Executors
import kotlin.math.ceil

class ShelfCards(context: Context, private val actions: Actions) : FrameLayout(context) {

    interface Actions {
        fun open(show: ShelfCard, view: View): Boolean
    }

    companion object {
        private const val CARD_FOCUS = 1.09f

        const val POSTERS = 3

        val LAYOUT_NAMES = arrayOf("Large", "Medium", "Compact", "Posters")
        const val LAYOUT_PREF = "shelf_card_layout"


        private val posterExecutor = Executors.newFixedThreadPool(maxOf(2, minOf(4, Runtime.getRuntime().availableProcessors())))

        private const val POSTER_RETRY_MS = 10 * 60 * 1000L

        private fun layout(context: Context): Int {
            val name = context.getSharedPreferences("topshelf", Context.MODE_PRIVATE)
                .getString(LAYOUT_PREF, "Large")
            val index = LAYOUT_NAMES.indexOf(name)
            return if (index >= 0) index else 0
        }
    }

    private val asked = HashSet<ImageView>()
    private val posterFailedAt = HashMap<String, Long>()
    private var at = 0
    private val cards = ArrayList<View>()
    private val glide: Follow
    private val heading: TextView
    private var layout = 0
    private var scroll = 0f

    var shift = 0f

    private var shows: List<ShelfCard> = emptyList()

    val slideKey = Any()

    private val strip: FrameLayout

    var onExitUp: (() -> Unit)? = null
    var onExitDown: (() -> Unit)? = null

    init {
        setClipChildren(false)
        heading = TextView(context).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            typeface = Typeface.DEFAULT
            setTextColor(Color.argb(180, 255, 255, 255))
            setSingleLine(true)
        }
        addView(heading, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT))
        strip = FrameLayout(context).apply {
            setClipChildren(false)
        }
        addView(strip, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        glide = Motion.follow(Motion.ROW_GLIDE, this) {
            scroll = it
            place()
        }
        visibility = View.INVISIBLE
    }

    override fun hasOverlappingRendering(): Boolean = false

    private fun cardW(): Float = Dimens.SHELF_LAYOUTS[layout][0]
    private fun cardH(): Float = Dimens.SHELF_LAYOUTS[layout][1]
    private fun cardGap(): Float = Dimens.SHELF_LAYOUTS[layout][2]
    private fun rowY(): Float = Dimens.SHELF_LAYOUTS[layout][3]

    private fun px(dp: Float): Int = Dimens.px(context, dp)

    private fun picture(show: ShelfCard): String? =
        if (layout == POSTERS && show.backdropUri != null) show.backdropUri else show.imageUri

    fun layoutChanged(): Boolean = layout != ShelfCards.layout(context)

    fun contentChanged(list: List<ShelfCard>): Boolean {
        if (shows.size != list.size) return true
        for (i in shows.indices) {
            val a = shows[i]
            val b = list[i]
            if (a.intentUri != b.intentUri || a.title != b.title || a.imageUri != b.imageUri || a.backdropUri != b.backdropUri || a.logoUri != b.logoUri) return true
        }
        return false
    }

    fun entry(): View? {
        if (cards.isEmpty()) return null
        return cards[minOf(at, cards.size - 1)]
    }

    fun prefetch(list: List<ShelfCard>?) {
        if (list == null) return
        layout = ShelfCards.layout(context)
        val w = px(cardW())
        val h = px(cardH())
        for (i in 0 until minOf(3, list.size)) {
            val uri = picture(list[i]) ?: continue
            posterExecutor.execute {
                TopShelfContent.loadPoster(context, uri, w, h)
            }
        }
    }

    private fun want(i: Int) {
        val stepPx = px(cardW() + cardGap())
        val iCeil = if (width > 0) ceil((width - left()) / stepPx.toFloat()).toInt() else 3
        val w = px(cardW())
        val h = px(cardH())
        for (index in maxOf(0, i - 1) until minOf(cards.size, i + iCeil + 1)) {
            val card = cards[index] as FrameLayout
            val imageView = card.getChildAt(0) as ImageView
            val uri = picture(shows[index])
            if (uri != null && System.currentTimeMillis() - (posterFailedAt[uri] ?: 0L) >= POSTER_RETRY_MS && asked.add(imageView)) {
                imageView.tag = uri
                posterExecutor.execute {
                    val bitmap = TopShelfContent.loadPoster(context, uri, w, h)
                    imageView.post {
                        if (bitmap == null) {
                            posterFailedAt[uri] = System.currentTimeMillis()
                            asked.remove(imageView)
                        } else if (imageView.tag == uri) {
                            posterFailedAt -= uri
                            imageView.setImageBitmap(bitmap)
                        }
                    }
                }
            }
            val logoView = (card.getChildAt(2) as LinearLayout).getChildAt(0) as ImageView
            val logoUri = shows[index].logoUri
            if (logoUri != null && logoView.tag != logoUri && System.currentTimeMillis() - (posterFailedAt[logoUri] ?: 0L) >= POSTER_RETRY_MS) {
                logoView.tag = logoUri
                posterExecutor.execute {
                    val size = px(24f)
                    val bitmap = TopShelfContent.loadPoster(context, logoUri, size, size)
                    logoView.post {
                        if (bitmap == null) {
                            posterFailedAt[logoUri] = System.currentTimeMillis()
                            logoView.tag = null
                        } else if (logoView.tag == logoUri) {
                            posterFailedAt -= logoUri
                            logoView.setImageBitmap(bitmap)
                            logoView.visibility = View.VISIBLE
                        }
                    }
                }
            }
        }
    }

    fun set(label: String, list: List<ShelfCard>) {
        val focused = findFocus()
        val oldIndex = if (focused == null || !cards.contains(focused)) -1 else at
        val oldPosters = HashMap<String, Bitmap>()
        val oldLogos = HashMap<String, Bitmap>()
        val reused = HashSet<Bitmap>()
        for (view in cards) {
            val card = view as FrameLayout
            val imageView = card.getChildAt(0) as ImageView
            val uri = imageView.tag as? String
            val bitmap = (imageView.drawable as? BitmapDrawable)?.bitmap
            if (uri != null && bitmap != null && !bitmap.isRecycled) {
                oldPosters[uri] = bitmap
            }
            val logoView = (card.getChildAt(2) as LinearLayout).getChildAt(0) as ImageView
            val logoUri = logoView.tag as? String
            val logoBitmap = (logoView.drawable as? BitmapDrawable)?.bitmap
            if (logoUri != null && logoBitmap != null && !logoBitmap.isRecycled) {
                oldLogos[logoUri] = logoBitmap
            }
        }
        for (view in cards) {
            val imageView = (view as FrameLayout).getChildAt(0) as ImageView
            imageView.setImageBitmap(null)
            imageView.tag = null
            strip.removeView(view)
        }
        cards.clear()
        asked.clear()
        shows = list
        at = 0
        heading.text = label
        layout = ShelfCards.layout(context)
        val w = px(cardW())
        val h = px(cardH())
        val corner = px(15f).toFloat()
        val titleSp = when (layout) {
            0 -> 16f
            1 -> 14f
            2 -> 12f
            else -> 11f
        }
        for (i in list.indices) {
            val show = list[i]
            val imageView = ImageView(context).apply {
                scaleType = ImageView.ScaleType.CENTER_CROP
            }
            val posterUri = picture(show)
            val reusedPoster = if (posterUri != null) oldPosters[posterUri] else null
            if (reusedPoster != null && reusedPoster.width == w && reusedPoster.height == h) {
                reused += reusedPoster
                imageView.setImageBitmap(reusedPoster)
                imageView.tag = posterUri
                asked.add(imageView)
            }
            val logoView = ImageView(context).apply {
                scaleType = ImageView.ScaleType.CENTER_CROP
                outlineProvider = object : ViewOutlineProvider() {
                    override fun getOutline(view: View, outline: Outline) {
                        outline.setOval(0, 0, view.width, view.height)
                    }
                }
                clipToOutline = true
                visibility = View.GONE
            }
            val reusedLogo = if (show.logoUri != null) oldLogos[show.logoUri] else null
            if (reusedLogo != null) {
                reused += reusedLogo
                logoView.setImageBitmap(reusedLogo)
                logoView.tag = show.logoUri
                logoView.visibility = View.VISIBLE
            }
            val titleView = TextView(context).apply {
                text = show.title
                setTextSize(TypedValue.COMPLEX_UNIT_SP, titleSp)
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(Color.WHITE)
                setSingleLine(true)
                ellipsize = TextUtils.TruncateAt.END
            }
            val titleRow = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                addView(logoView, LinearLayout.LayoutParams(px(24f), px(24f)).apply {
                    rightMargin = px(8f)
                })
                addView(titleView, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            }
            val scrim = View(context).apply {
                background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(Color.TRANSPARENT, Color.argb(170, 0, 0, 0)))
            }
            val card = FrameLayout(context).apply {
                background = GradientDrawable().apply {
                    cornerRadius = corner
                    setColor(Color.argb(40, 255, 255, 255))
                }
                outlineProvider = object : ViewOutlineProvider() {
                    override fun getOutline(view: View, outline: Outline) {
                        outline.setRoundRect(0, 0, view.width, view.height, corner)
                    }
                }
                clipToOutline = true
                isFocusable = true
                contentDescription = show.title
                addView(imageView, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
                addView(scrim, LayoutParams(LayoutParams.MATCH_PARENT, px(72f)).apply {
                    gravity = Gravity.BOTTOM
                })
                addView(titleRow, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
                    gravity = Gravity.BOTTOM or Gravity.START
                    leftMargin = px(14f)
                    rightMargin = px(14f)
                    bottomMargin = px(10f)
                })
                setOnFocusChangeListener { view, focused ->
                    Motion.progress(
                        Motion.TILE_FOCUS, view, view.scaleX,
                        if (focused) CARD_FOCUS else 1.0f,
                        { v ->
                            view.scaleX = v
                            view.scaleY = v
                        },
                        null,
                    )
                    if (focused) {
                        at = i
                        view.bringToFront()
                        follow(i)
                        want(i)
                    }
                }
                setOnClickListener { view ->
                    if (!actions.open(show, view)) {
                        Toast.makeText(context, context.getString(R.string.launch_failed, show.title), Toast.LENGTH_SHORT).show()
                    }
                }
            }
            strip.addView(card, LayoutParams(w, h))
            cards.add(card)
        }
        for (bitmap in oldPosters.values + oldLogos.values) {
            if (bitmap !in reused && !bitmap.isRecycled) bitmap.recycle()
        }
        want(0)
        glide.cancel()
        scroll = 0f
        requestLayout()
        if (oldIndex < 0 || cards.isEmpty()) return
        cards[minOf(oldIndex, cards.size - 1)].requestFocus()
    }

    private fun follow(i: Int) {
        val stepPx = px(cardW() + cardGap()).toFloat()
        val target = minOf(
            i * stepPx,
            maxOf(0f, cards.size * stepPx - px(cardGap()) - (width - left() * 2f)),
        )
        glide.to(target, scroll)
    }

    private fun left(): Float {
        val inset = px(45f).toFloat()
        if (cards.isEmpty()) return inset
        val total = cards.size * px(cardW() + cardGap()).toFloat() - px(cardGap())
        return if (total <= width - inset * 2f) (width - total) / 2f else inset
    }

    private fun place() {
        val stepPx = px(cardW() + cardGap()).toFloat()
        val x0 = left() - scroll + shift
        val cardTop = px(rowY() - cardH() / 2f)
        for (i in cards.indices) {
            cards[i].translationX = i * stepPx + x0
            cards[i].translationY = (cardTop + px(12f)).toFloat()
        }
        heading.translationX = left() + shift
        heading.translationY = (cardTop - heading.height - px(8f)).toFloat()
    }

    fun slide(f: Float) {
        shift = f
        place()
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        super.onLayout(changed, left, top, right, bottom)
        place()
    }

    override fun focusSearch(focused: View, direction: Int): View? {
        val index = cards.indexOf(focused)
        if (index < 0) return super.focusSearch(focused, direction)
        return when (direction) {
            View.FOCUS_LEFT -> if (index > 0) cards[index - 1] else focused
            View.FOCUS_RIGHT -> if (index + 1 < cards.size) cards[index + 1] else focused
            View.FOCUS_UP, View.FOCUS_DOWN -> focused
            else -> super.focusSearch(focused, direction)
        }
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_DOWN) {
            val focused = findFocus()
            if (focused != null && cards.contains(focused)) {
                when (event.keyCode) {
                    KeyEvent.KEYCODE_DPAD_UP -> {
                        onExitUp?.invoke()
                        return true
                    }
                    KeyEvent.KEYCODE_DPAD_DOWN -> {
                        onExitDown?.invoke()
                        return true
                    }
                }
            }
        }
        return super.dispatchKeyEvent(event)
    }
}
