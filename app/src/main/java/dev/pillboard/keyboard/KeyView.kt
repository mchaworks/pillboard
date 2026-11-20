package dev.pillboard.keyboard

import android.animation.ArgbEvaluator
import android.animation.ValueAnimator
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
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.PopupWindow
import android.widget.TextView
import androidx.core.content.ContextCompat

val Int.dp: Int get() = (this * Resources.getSystem().displayMetrics.density).toInt()

enum class KeyStyle { LETTER, SPECIAL, ACCENT, ACTIVE }

internal fun animationsOn(c: Context) =
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
    private var fading = false

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
        val wasSolid = p.isShowing && !fading
        fading = false
        bubble.animate().cancel()
        if (p.isShowing) {
            p.update(x, y, ww, wh)
            if (wasSolid) { bubble.alpha = 1f; bubble.scaleX = 1f; bubble.scaleY = 1f }
            else popIn(bubble, bw, bh, t.popupInMs, motionOn)
        } else {
            p.width = ww; p.height = wh
            p.showAtLocation(key, Gravity.NO_GRAVITY, x, y)
            popIn(bubble, bw, bh, t.popupInMs, motionOn)
        }
    }

    /** Hide only if [key] is the one currently shown (fast typing can overlap presses). */
    fun hide(key: View) { if (owner === key) dismiss() }

    /** Quick fade-out unless [immediate]; a new [show] cancels the fade, so rolling typing never flickers. */
    fun dismiss(immediate: Boolean = false) {
        owner = null
        bubble.animate().cancel()
        val p = popup ?: return
        if (immediate || !motionOn || !p.isShowing) { fading = false; p.dismiss(); return }
        fading = true
        bubble.animate().alpha(0f).setDuration(t.fadeOutMs).withEndAction {
            if (fading) { fading = false; p.dismiss() }
        }.start()
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
    private var restColors = IntArray(2)
    private val downColors = IntArray(2)
    private var morph: ValueAnimator? = null
    private val argb = ArgbEvaluator()
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

    private fun applyStyle(style: KeyStyle, animate: Boolean = false) {
        val oldColors = restColors
        val oldRadius = shape.cornerRadius
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
        restColors = t.sheen(fill)          // cached: no allocation on every release
        downColors[0] = pressedFill; downColors[1] = pressedFill
        morph?.cancel()
        paint(false)
        shape.cornerRadius = rest
        setTextColor(ink)
        icon?.setTint(ink)
        if (animate && motionOn && isAttachedToWindow && width > 0) morphFrom(oldColors, oldRadius)
        invalidate()
    }

    /** Organic style change (e.g. Shift): tone and corner radius flow to the new state instead of snapping. */
    private fun morphFrom(oldColors: IntArray, oldRadius: Float) {
        val newColors = restColors
        shape.setColors(oldColors); shape.cornerRadius = oldRadius
        morph = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = t.morphMs
            interpolator = t.spring
            addUpdateListener {
                val f = it.animatedFraction
                shape.setColors(intArrayOf(
                    argb.evaluate(f, oldColors[0], newColors[0]) as Int,
                    argb.evaluate(f, oldColors[1], newColors[1]) as Int))
                shape.cornerRadius = oldRadius + (rest - oldRadius) * f
            }
            start()
        }
    }

    private fun morphRadius(to: Float, ms: Long) {
        morph?.cancel()
        val from = shape.cornerRadius
        if (!motionOn || ms == 0L || from == to) { shape.cornerRadius = to; return }
        morph = ValueAnimator.ofFloat(from, to).apply {
            duration = ms
            interpolator = android.view.animation.DecelerateInterpolator()
            addUpdateListener { shape.cornerRadius = it.animatedValue as Float }
            start()
        }
    }

    private fun paint(down: Boolean) {
        shape.setColors(if (down) downColors else restColors)
        if (down) shape.cornerRadius = rest + t.pressRadiusBoost   // release morphs back via morphRadius()
        shape.setStroke(1.dp, when {
            down -> t.glassHighlight
            currentStyle == KeyStyle.LETTER -> t.keyStroke
            else -> t.glassStroke
        })
    }

    /** Update in place (no view rebuild), e.g. when Shift changes. */
    fun setKeyStyle(style: KeyStyle) = applyStyle(style, animate = true)
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
        morph?.cancel()
        paint(down)
        if (!down) morphRadius(rest, t.radiusReleaseMs)
        setTextColor(if (down) pressedInk else ink)
        icon?.setTint(if (down) pressedInk else ink)
        invalidate()
        val sc = if (down) t.pressScale else 1f
        animate().cancel()
        if (!motionOn) { scaleX = sc; scaleY = sc; return }
        animate().scaleX(sc).scaleY(sc).setDuration(if (down) t.pressMs else t.releaseMs)
            .setInterpolator(if (down) null else t.spring).start()
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
                // 1) input first: nothing visual may ever sit in front of the commit
                if (repeats) {
                    onKey(); postDelayed(repeater, 400)
                } else {
                    alts = altsFor?.invoke(text.toString()) ?: emptyList()
                    if (alts.isEmpty()) onKey() else { pendingUp = true; postDelayed(longPress, 350) }
                }
                // 2) then feedback
                animateTo(true)
                if (Prefs.bool(context, "haptic", true)) performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                if (previewable()) preview?.show(this, text.toString())
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
