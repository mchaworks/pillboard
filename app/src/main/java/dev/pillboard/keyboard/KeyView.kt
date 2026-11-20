package dev.pillboard.keyboard

import android.annotation.SuppressLint
import android.content.Context
import android.content.res.Resources
import android.graphics.drawable.GradientDrawable
import android.provider.Settings
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.animation.OvershootInterpolator
import android.widget.LinearLayout
import android.widget.PopupWindow
import android.widget.TextView

val Int.dp: Int get() = (this * Resources.getSystem().displayMetrics.density).toInt()

enum class KeyStyle { LETTER, SPECIAL, ACCENT, ACTIVE }

/**
 * Rounded-rect key: instant tonal change + slight scale-down on press, spring release.
 * Plain keys commit on touch DOWN (no dropped letters when typing fast / rolling fingers).
 * Keys with long-press alternates ([altsFor] returns a non-empty list) commit on UP instead.
 */
@SuppressLint("ViewConstructor", "ClickableViewAccessibility")
class KeyView(
    context: Context,
    label: String,
    style: KeyStyle,
    private val t: KbTokens,
    private val repeats: Boolean,
    private val onKey: () -> Unit,
) : TextView(context) {

    /** Returns long-press alternates for the key's current label (empty = none). */
    var altsFor: ((String) -> List<String>)? = null
    var onAlt: ((String) -> Unit)? = null

    private val restRadius = Prefs.int(context, "key_r", 10).dp.toFloat()
    private var fill = 0
    private var pressedFill = 0
    private var ink = 0
    private var pressedInk = 0
    private val shape = GradientDrawable()
    private val motionOn: Boolean = Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) != 0f

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
        applyStyle(style)
        textSize = if (label.length == 1 && style != KeyStyle.ACCENT) 22f else 15f
        shape.cornerRadius = restRadius
        shape.setStroke(1.dp, t.keyStroke)
        background = shape
        elevation = 1.2f * resources.displayMetrics.density
        if (label == "backspace") {
            text = ""
            val icon = androidx.core.content.ContextCompat.getDrawable(context, R.drawable.ic_backspace)
            icon?.setTint(ink)
            setCompoundDrawablesWithIntrinsicBounds(null, icon, null, null)
            compoundDrawablePadding = 0
            contentDescription = "Backspace"
        }
    }

    private fun applyStyle(style: KeyStyle) {
        when (style) {
            KeyStyle.LETTER -> { fill = t.key; ink = t.keyText }
            KeyStyle.SPECIAL -> { fill = t.special; ink = t.specialText }
            KeyStyle.ACCENT -> { fill = t.accent; ink = t.onAccent }
            KeyStyle.ACTIVE -> { fill = t.keyText; ink = t.key }
        }
        pressedFill = t.pressed
        pressedInk = t.pressedText
        shape.setColor(fill)
        shape.setStroke(1.dp, if (style == KeyStyle.LETTER) t.keyStroke else t.glassStroke)
        setTextColor(ink)
        compoundDrawables.getOrNull(1)?.setTint(ink)
    }

    /** Update in place (no view rebuild), e.g. when Shift changes. */
    fun setKeyStyle(style: KeyStyle) = applyStyle(style)
    fun setLabel(l: String) { text = l; contentDescription = l }

    private fun animateTo(down: Boolean) {
        // Color first, instantly: input never waits on animation.
        shape.setColor(if (down) pressedFill else fill)
        setTextColor(if (down) pressedInk else ink)
        compoundDrawables.getOrNull(1)?.setTint(if (down) pressedInk else ink)
        val sc = if (down) 0.94f else 1f
        shape.cornerRadius = if (down) restRadius + 3.dp else restRadius
        shape.setStroke(1.dp, if (down) t.glassHighlight else t.keyStroke)
        animate().cancel()
        if (!motionOn) { scaleX = sc; scaleY = sc; return }
        animate().scaleX(sc).scaleY(sc).setDuration(if (down) 65 else 230)
            .setInterpolator(if (down) null else OvershootInterpolator(1.7f)).start()
    }

    // ---- long-press alternates ----
    private fun showAlts() {
        if (alts.isEmpty()) return
        val items = alts.map { a ->
            TextView(context).apply { text = a; textSize = 22f; gravity = Gravity.CENTER; setTextColor(t.specialText) }
        }
        val itemH = 52.dp
        val pad = 6.dp
        val box = LinearLayout(context).apply {
            background = GradientDrawable().apply {
                setColor(t.glassFill)
                cornerRadius = 18.dp.toFloat()
                setStroke(1.dp, t.glassStroke)
            }
            setPadding(pad, pad, pad, pad)
            items.forEach { addView(it, LinearLayout.LayoutParams(itemW, itemH)) }
        }
        val loc = IntArray(2); getLocationOnScreen(loc)
        val total = alts.size * itemW + pad * 2
        val screenW = resources.displayMetrics.widthPixels
        val x = (loc[0] + width / 2 - total / 2).coerceIn(4.dp, (screenW - total - 4.dp).coerceAtLeast(4.dp))
        val y = (loc[1] - itemH - pad * 2 - 8.dp).coerceAtLeast(0)
        popupLeft = x + pad
        altViews = items
        altIdx = 0
        popup = PopupWindow(box, total, itemH + pad * 2, false).apply {
            isTouchable = false
            elevation = 12.dp.toFloat()
            showAtLocation(this@KeyView, Gravity.NO_GRAVITY, x, y)
        }
        highlight()
        performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
    }

    private fun highlight() {
        altViews.forEachIndexed { i, v ->
            val on = i == altIdx
            v.background = if (on) GradientDrawable().apply {
                setColor(t.accent); cornerRadius = 13.dp.toFloat()
                setStroke(1.dp, t.glassHighlight)
            } else null
            v.setTextColor(if (on) t.onAccent else t.specialText)
        }
    }

    private fun dismissAlts() {
        popup?.dismiss(); popup = null
        altViews = emptyList()
    }

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
                }
            }
            MotionEvent.ACTION_MOVE -> if (popup != null) {
                val i = ((e.rawX - popupLeft) / itemW).toInt().coerceIn(0, altViews.lastIndex)
                if (i != altIdx) { altIdx = i; highlight() }
            }
            MotionEvent.ACTION_UP -> {
                removeCallbacks(repeater); removeCallbacks(longPress)
                animateTo(false)
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
                animateTo(false)
            }
        }
        return true
    }

    override fun onDetachedFromWindow() {
        removeCallbacks(repeater); removeCallbacks(longPress)
        dismissAlts()
        super.onDetachedFromWindow()
    }
}
