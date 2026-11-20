package dev.pillboard.keyboard

import android.annotation.SuppressLint
import android.content.Context
import android.content.res.Resources
import android.graphics.Canvas
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.provider.Settings
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.animation.DecelerateInterpolator
import android.view.animation.OvershootInterpolator
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.PopupWindow
import android.widget.TextView
import androidx.core.content.ContextCompat

val Int.dp: Int get() = (this * Resources.getSystem().displayMetrics.density).toInt()

enum class KeyStyle { LETTER, SPECIAL, ACCENT, ACTIVE }

private fun animationsOn(c: Context) =
    Settings.Global.getFloat(c.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) != 0f

/** Wraps [bubble] in a transparent, padded holder so its elevation shadow isn't clipped by the popup window. */
private fun shadowHolder(ctx: Context, bubble: View): FrameLayout = FrameLayout(ctx).apply {
    clipChildren = false
    clipToPadding = false
    val p = KbTokens.POPUP_SHADOW_PAD
    setPadding(p, p, p, p)
    addView(bubble, FrameLayout.LayoutParams(-1, -1))
}

/** Scale/fade a popup bubble in from its bottom edge. */
private fun popIn(bubble: View, w: Int, h: Int, ms: Long, on: Boolean) {
    bubble.animate().cancel()
    if (!on) { bubble.alpha = 1f; bubble.scaleX = 1f; bubble.scaleY = 1f; return }
    bubble.pivotX = w / 2f; bubble.pivotY = h.toFloat()
    bubble.alpha = 0f; bubble.scaleX = 0.86f; bubble.scaleY = 0.86f
    bubble.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(ms).setInterpolator(DecelerateInterpolator()).start()
}

/**
 * One reusable floating character preview per keyboard (never one window per key).
 * Shown after the key has already been committed, so it can never delay typing.
 */
class KeyPreview(ctx: Context, private val t: KbTokens) {
    private val motionOn = animationsOn(ctx)
    private val bubble = TextView(ctx).apply {
        gravity = Gravity.CENTER
        textSize = 30f
        includeFontPadding = false
        setTextColor(t.keyText)
        background = t.popupBackground()
        elevation = t.popupElevation
    }
    private val holder = shadowHolder(ctx, bubble)
    private var popup: PopupWindow? = null
    private var owner: View? = null

    fun show(key: View, glyph: String) {
        val pad = KbTokens.POPUP_SHADOW_PAD
        val bw = (key.width + 14.dp).coerceAtLeast(52.dp)
        val bh = (key.height + 14.dp).coerceAtLeast(60.dp)
        val ww = bw + pad * 2
        val wh = bh + pad * 2
        val loc = IntArray(2); key.getLocationOnScreen(loc)
        val screenW = key.resources.displayMetrics.widthPixels
        val bx = (loc[0] + key.width / 2 - bw / 2).coerceIn(2.dp, (screenW - bw - 2.dp).coerceAtLeast(2.dp))
        val x = (bx - pad).coerceAtLeast(0)
        val y = (loc[1] + 2.dp - bh - pad).coerceAtLeast(0)
        bubble.text = glyph
        owner = key
        val p = popup ?: PopupWindow(holder, ww, wh, false).also { it.isTouchable = false; popup = it }
        if (p.isShowing) {
            p.update(x, y, ww, wh)
        } else {
            p.width = ww; p.height = wh
            p.showAtLocation(key, Gravity.NO_GRAVITY, x, y)
            popIn(bubble, bw, bh, t.popupInMs, motionOn)
        }
    }

    /** Hide only if [key] is the one currently shown (fast typing can overlap presses). */
    fun hide(key: View) { if (owner === key) dismiss() }

    fun dismiss() {
        owner = null
        bubble.animate().cancel()
        popup?.dismiss()
    }
}

/**
 * Rounded-rect key: instant tonal change + slight scale-down on press, spring release.
 * Plain keys commit on touch DOWN (no dropped letters when typing fast / rolling fingers).
 * Keys with long-press alternates ([altsFor] returns a non-empty list) commit on UP instead.
 * Icon keys ([iconRes] != 0) draw a Material-style vector centered on the key.
 */
@SuppressLint("ViewConstructor", "ClickableViewAccessibility")
class KeyView(
    context: Context,
    label: String,
    style: KeyStyle,
    private val t: KbTokens,
    private val repeats: Boolean,
    iconRes: Int = 0,
    private val onKey: () -> Unit,
) : TextView(context) {

    /** Returns long-press alternates for the key's current label (empty = none). */
    var altsFor: ((String) -> List<String>)? = null
    var onAlt: ((String) -> Unit)? = null
    /** Shared character-preview bubble; null disables previews (e.g. in the settings preview). */
    var preview: KeyPreview? = null
    var iconSize = 22.dp

    private val baseRadius = Prefs.int(context, "key_r", KbTokens.KEY_R_DEFAULT).dp.toFloat()
    private var rest = baseRadius
    private var currentStyle = style
    private var fill = 0
    private var pressedFill = 0
    private var ink = 0
    private var pressedInk = 0
    private var icon: Drawable? = null
    private val shape = GradientDrawable()
    private val motionOn = animationsOn(context)

    private var pendingUp = false
    private var alts = emptyList<String>()
    private var altViews = emptyList<TextView>()
    private var altIdx = 0
    private var popup: PopupWindow? = null
    private var popupLeft = 0
    private val itemW = 44.dp

    private val repeater = object : Runnable {
        override fun run() { onKey(); postDelayed(this, 45) }
    }
    private val longPress = Runnable { showAlts() }

    init {
        text = label
        contentDescription = label
        gravity = Gravity.CENTER
        textSize = if (label.length == 1 && style != KeyStyle.ACCENT) 22f else 15f
        applyStyle(style)
        background = shape
        elevation = t.keyElevation
        if (iconRes != 0) setIcon(iconRes, label)
    }

    private fun radiusFor(s: KeyStyle) = when (s) {
        KeyStyle.LETTER -> baseRadius
        KeyStyle.SPECIAL -> baseRadius + t.specialRadiusBoost
        KeyStyle.ACCENT, KeyStyle.ACTIVE -> baseRadius + t.actionRadiusBoost
    }

    private fun applyStyle(style: KeyStyle) {
        currentStyle = style
        when (style) {
            KeyStyle.LETTER -> { fill = t.key; ink = t.keyText }
            KeyStyle.SPECIAL -> { fill = t.special; ink = t.specialText }
            KeyStyle.ACCENT -> { fill = t.accent; ink = t.onAccent }
            KeyStyle.ACTIVE -> { fill = t.keyText; ink = t.key }
        }
        pressedFill = t.pressed
        pressedInk = t.pressedText
        rest = radiusFor(style)
        paint(false)
        setTextColor(ink)
        icon?.setTint(ink)
        invalidate()
    }

    private fun paint(down: Boolean) {
        shape.setColors(if (down) intArrayOf(pressedFill, pressedFill) else t.sheen(fill))
        shape.cornerRadius = if (down) rest + t.pressRadiusBoost else rest
        shape.setStroke(1.dp, when {
            down -> t.glassHighlight
            currentStyle == KeyStyle.LETTER -> t.keyStroke
            else -> t.glassStroke
        })
    }

    /** Update in place (no view rebuild), e.g. when Shift changes. */
    fun setKeyStyle(style: KeyStyle) = applyStyle(style)
    fun setLabel(l: String) { text = l; contentDescription = l }

    /** Show a vector icon instead of text; [desc] is the accessibility label. */
    fun setIcon(res: Int, desc: String? = null) {
        icon = if (res == 0) null else ContextCompat.getDrawable(context, res)?.mutate()
        icon?.setTint(ink)
        if (icon != null) text = ""
        if (desc != null) contentDescription = desc
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val d = icon ?: return
        val s = iconSize
        val l = (width - s) / 2
        val tp = (height - s) / 2
        d.setBounds(l, tp, l + s, tp + s)
        d.draw(canvas)
    }

    private fun animateTo(down: Boolean) {
        // Color first, instantly: input never waits on animation.
        paint(down)
        setTextColor(if (down) pressedInk else ink)
        icon?.setTint(if (down) pressedInk else ink)
        invalidate()
        val sc = if (down) t.pressScale else 1f
        animate().cancel()
        if (!motionOn) { scaleX = sc; scaleY = sc; return }
        animate().scaleX(sc).scaleY(sc).setDuration(if (down) t.pressMs else t.releaseMs)
            .setInterpolator(if (down) null else OvershootInterpolator(t.releaseOvershoot)).start()
    }

    // ---- long-press alternates ----
    private fun showAlts() {
        if (alts.isEmpty()) return
        preview?.hide(this)
        val items = alts.map { a ->
            TextView(context).apply { text = a; textSize = 22f; gravity = Gravity.CENTER; setTextColor(t.keyText) }
        }
        val itemH = 52.dp
        val pad = 6.dp
        val bw = alts.size * itemW + pad * 2
        val bh = itemH + pad * 2
        val box = LinearLayout(context).apply {
            background = t.popupBackground()
            elevation = t.popupElevation
            setPadding(pad, pad, pad, pad)
            items.forEach { addView(it, LinearLayout.LayoutParams(itemW, itemH)) }
        }
        val sp = KbTokens.POPUP_SHADOW_PAD
        val loc = IntArray(2); getLocationOnScreen(loc)
        val screenW = resources.displayMetrics.widthPixels
        val bx = (loc[0] + width / 2 - bw / 2).coerceIn(4.dp, (screenW - bw - 4.dp).coerceAtLeast(4.dp))
        val by = (loc[1] - bh - 8.dp).coerceAtLeast(sp)
        val wx = (bx - sp).coerceAtLeast(0)
        val wy = (by - sp).coerceAtLeast(0)
        popupLeft = wx + sp + pad
        altViews = items
        altIdx = 0
        popup = PopupWindow(shadowHolder(context, box), bw + sp * 2, bh + sp * 2, false).apply {
            isTouchable = false
            showAtLocation(this@KeyView, Gravity.NO_GRAVITY, wx, wy)
        }
        popIn(box, bw, bh, t.popupInMs, motionOn)
        highlight()
        performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
    }

    private fun highlight() {
        altViews.forEachIndexed { i, v ->
            val on = i == altIdx
            v.background = if (on) GradientDrawable().apply {
                setColor(t.accent); cornerRadius = t.popupRadius - 6.dp
                setStroke(1.dp, t.glassHighlight)
            } else null
            v.setTextColor(if (on) t.onAccent else t.keyText)
        }
    }

    private fun dismissAlts() {
        popup?.dismiss(); popup = null
        altViews = emptyList()
    }

    private fun previewable() = icon == null && !repeats && text.length == 1 && !text[0].isWhitespace()

    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                parent?.requestDisallowInterceptTouchEvent(true)
                animateTo(true)
                if (Prefs.bool(context, "haptic", true)) performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                if (repeats) {
                    onKey(); postDelayed(repeater, 400)
                } else {
                    alts = altsFor?.invoke(text.toString()) ?: emptyList()
                    if (alts.isEmpty()) onKey() else { pendingUp = true; postDelayed(longPress, 350) }
                    // After the commit: the preview can never delay input.
                    if (previewable()) preview?.show(this, text.toString())
                }
            }
            MotionEvent.ACTION_MOVE -> if (popup != null) {
                val i = ((e.rawX - popupLeft) / itemW).toInt().coerceIn(0, altViews.lastIndex)
                if (i != altIdx) { altIdx = i; highlight() }
            }
            MotionEvent.ACTION_UP -> {
                removeCallbacks(repeater); removeCallbacks(longPress)
                animateTo(false)
                preview?.hide(this)
                if (popup != null) {
                    val pick = alts.getOrNull(altIdx)
                    dismissAlts()
                    if (pick != null) onAlt?.invoke(pick)
                } else if (pendingUp) onKey()
                pendingUp = false
            }
            MotionEvent.ACTION_CANCEL -> {
                removeCallbacks(repeater); removeCallbacks(longPress)
                dismissAlts(); pendingUp = false
                preview?.hide(this)
                animateTo(false)
            }
        }
        return true
    }

    override fun onDetachedFromWindow() {
        removeCallbacks(repeater); removeCallbacks(longPress)
        dismissAlts()
        preview?.hide(this)
        super.onDetachedFromWindow()
    }
}
