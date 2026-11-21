package dev.pillboard.keyboard

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.text.TextPaint
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.View
import android.view.ViewConfiguration
import android.widget.LinearLayout
import android.widget.OverScroller
import android.widget.PopupWindow
import android.widget.TextView
import kotlin.math.abs
import kotlin.math.max

/**
 * Scrollable emoji grid drawn straight on the canvas (a few hundred glyphs, no per-emoji views). Tap picks, a long press
 * on an emoji that takes skin tones opens a small tone picker above it. Scroll + fling use the platform [OverScroller].
 */
@SuppressLint("ViewConstructor", "ClickableViewAccessibility")
internal class EmojiGridView(ctx: Context, private val t: KbTokens) : View(ctx) {

    var onPick: ((String) -> Unit)? = null
    var emptyHint: String = ""

    var items: List<String> = emptyList()
        set(v) { field = v; scrollTo(0, 0); scroller.forceFinished(true); relayout(); invalidate() }

    private val glyph = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER; textSize = 27f * ctx.resources.displayMetrics.scaledDensity }
    private val hintPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER; textSize = 14f * ctx.resources.displayMetrics.scaledDensity; color = t.keyText; alpha = 140
    }
    private val pressPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = t.pressed }
    private val fm = Paint.FontMetrics()
    private val rect = RectF()

    private val cellH = 46.dp
    private var cols = 8
    private var cellW = 1f
    private val scroller = OverScroller(ctx)
    private val slop = ViewConfiguration.get(ctx).scaledTouchSlop
    private var tracker: VelocityTracker? = null
    private var downY = 0f
    private var lastY = 0f
    private var moved = false
    private var pressed = -1
    private var longFired = false
    private var tonePopup: PopupWindow? = null
    private val longPress = Runnable { onLong() }

    private fun relayout() {
        if (width <= 0) return
        cols = max(6, (width / 44.dp))
        cellW = width.toFloat() / cols
    }

    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) { super.onSizeChanged(w, h, ow, oh); relayout(); clampScroll() }

    private fun contentH() = ((items.size + cols - 1) / cols) * cellH
    private fun maxScroll() = max(0, contentH() - height)
    private fun clampScroll() { val y = scrollY.coerceIn(0, maxScroll()); if (y != scrollY) scrollTo(0, y) }

    override fun onDraw(c: Canvas) {
        if (items.isEmpty()) {
            if (emptyHint.isNotEmpty()) c.drawText(emptyHint, scrollX + width / 2f, scrollY + height / 2f, hintPaint)
            return
        }
        glyph.getFontMetrics(fm)
        val first = (scrollY / cellH).coerceAtLeast(0)
        val last = ((scrollY + height) / cellH + 1).coerceAtMost((items.size + cols - 1) / cols - 1)
        for (row in first..last) for (col in 0 until cols) {
            val i = row * cols + col
            if (i >= items.size) break
            val cx = (col + 0.5f) * cellW
            val top = row * cellH.toFloat()
            if (i == pressed) {
                val m = 3.dp.toFloat()
                rect.set(col * cellW + m, top + m, (col + 1) * cellW - m, top + cellH - m)
                c.drawRoundRect(rect, 12.dp.toFloat(), 12.dp.toFloat(), pressPaint)
            }
            c.drawText(items[i], cx, top + cellH / 2f - (fm.ascent + fm.descent) / 2f, glyph)
        }
    }

    private fun cellAt(x: Float, y: Float): Int {
        if (cellW <= 0f) return -1
        val col = (x / cellW).toInt()
        val row = ((y + scrollY) / cellH).toInt()
        if (col !in 0 until cols || row < 0) return -1
        val i = row * cols + col
        return if (i < items.size) i else -1
    }

    override fun computeScroll() {
        if (scroller.computeScrollOffset()) {
            scrollTo(0, scroller.currY)
            postInvalidateOnAnimation()
        }
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (tracker == null) tracker = VelocityTracker.obtain()
        tracker!!.addMovement(e)
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                parent?.requestDisallowInterceptTouchEvent(true)
                scroller.forceFinished(true)
                downY = e.y; lastY = e.y; moved = false; longFired = false
                pressed = cellAt(e.x, e.y)
                if (pressed >= 0) postDelayed(longPress, 380)
                invalidate()
            }
            MotionEvent.ACTION_MOVE -> {
                if (!moved && abs(e.y - downY) > slop) { moved = true; pressed = -1; removeCallbacks(longPress); invalidate() }
                if (moved) {
                    val ny = (scrollY + (lastY - e.y)).toInt().coerceIn(0, maxScroll())
                    scrollTo(0, ny)
                }
                lastY = e.y
            }
            MotionEvent.ACTION_UP -> {
                removeCallbacks(longPress)
                val hit = pressed
                pressed = -1
                if (moved) {
                    tracker?.computeCurrentVelocity(1000)
                    val vy = -(tracker?.yVelocity ?: 0f).toInt()
                    scroller.fling(0, scrollY, 0, vy, 0, 0, 0, maxScroll())
                    postInvalidateOnAnimation()
                } else if (hit >= 0 && !longFired) pick(items[hit])
                recycle(); invalidate()
            }
            MotionEvent.ACTION_CANCEL -> { removeCallbacks(longPress); pressed = -1; recycle(); invalidate() }
        }
        return true
    }

    private fun recycle() { tracker?.recycle(); tracker = null }

    private fun pick(e: String) {
        if (Prefs.bool(context, "haptic", true)) performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
        onPick?.invoke(e)
    }

    private fun onLong() {
        val i = pressed
        if (i < 0) return
        val variants = EmojiData.tones(items[i])
        if (variants.isEmpty()) return
        longFired = true
        pressed = -1
        invalidate()
        if (Prefs.bool(context, "haptic", true)) performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
        showTones(i, variants)
    }

    private fun showTones(i: Int, variants: List<String>) {
        dismissTones()
        val itemW = 44.dp; val itemH = 52.dp; val pad = 6.dp
        val bw = variants.size * itemW + pad * 2
        val bh = itemH + pad * 2
        val box = LinearLayout(context).apply {
            background = t.popupBackground()
            elevation = t.popupElevation
            setPadding(pad, pad, pad, pad)
        }
        val pop = PopupWindow(box, bw, bh, false)
        variants.forEach { v ->
            box.addView(TextView(context).apply {
                text = v; textSize = 24f; gravity = Gravity.CENTER
                setOnClickListener { dismissTones(); pick(v) }
            }, LinearLayout.LayoutParams(itemW, itemH))
        }
        pop.isOutsideTouchable = true
        pop.isFocusable = false
        tonePopup = pop
        val loc = IntArray(2); getLocationOnScreen(loc)
        val win = IntArray(2); getLocationInWindow(win)
        val ox = loc[0] - win[0]; val oy = loc[1] - win[1]
        val col = i % cols; val row = i / cols
        val cellX = loc[0] + (col + 0.5f) * cellW
        val cellTop = loc[1] + row * cellH - scrollY
        val screenW = resources.displayMetrics.widthPixels
        val x = (cellX - bw / 2f).toInt().coerceIn(4.dp, (screenW - bw - 4.dp).coerceAtLeast(4.dp))
        val y = (cellTop - bh - 2.dp).coerceAtLeast(0)
        pop.showAtLocation(this, Gravity.NO_GRAVITY, x - ox, y - oy)
        postDelayed({ if (tonePopup === pop) dismissTones() }, 4000)     // outside taps don't reliably close a non-focusable popup
    }

    fun dismissTones() { tonePopup?.dismiss(); tonePopup = null }

    override fun onDetachedFromWindow() {
        removeCallbacks(longPress); dismissTones(); recycle()
        super.onDetachedFromWindow()
    }
}
