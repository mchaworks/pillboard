package dev.pillboard.keyboard

import android.annotation.SuppressLint
import android.animation.ValueAnimator
import android.content.Context
import android.content.res.Resources
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.animation.OvershootInterpolator
import android.widget.TextView
import com.google.android.material.color.MaterialColors

val Int.dp: Int get() = (this * Resources.getSystem().displayMetrics.density).toInt()

enum class KeyStyle { LETTER, SPECIAL, ACCENT }

/** A key that morphs from rounded-rect to pill on press (M3 Expressive shape morph). */
@SuppressLint("ViewConstructor", "ClickableViewAccessibility")
class KeyView(
    context: Context,
    label: String,
    style: KeyStyle,
    private val repeats: Boolean,
    private val onKey: () -> Unit,
) : TextView(context) {

    private val restRadius = 9.dp.toFloat()
    private val shape = GradientDrawable()
    private var morph: ValueAnimator? = null
    private val repeater = object : Runnable {
        override fun run() { onKey(); postDelayed(this, 45) }
    }

    init {
        text = label
        contentDescription = label
        gravity = Gravity.CENTER
        val primary = MaterialColors.getColor(context, com.google.android.material.R.attr.colorPrimary, Color.BLUE)
        val onPrimary = MaterialColors.getColor(context, com.google.android.material.R.attr.colorOnPrimary, Color.WHITE)
        val ink = context.getColor(R.color.kb_key_text)
        when (style) {
            KeyStyle.LETTER -> { shape.setColor(context.getColor(R.color.kb_key)); setTextColor(ink) }
            KeyStyle.SPECIAL -> { shape.setColor(context.getColor(R.color.kb_key_special)); setTextColor(ink) }
            KeyStyle.ACCENT -> { shape.setColor(primary); setTextColor(onPrimary) }
        }
        textSize = if (label.length == 1 && style != KeyStyle.ACCENT) 22f else 15f
        shape.cornerRadius = restRadius
        background = shape
        elevation = 1.dp.toFloat()
    }

    private fun animateTo(down: Boolean) {
        morph?.cancel()
        val target = if (down) height / 2f else restRadius
        morph = ValueAnimator.ofFloat(shape.cornerRadius, target).apply {
            duration = if (down) 90 else 220
            if (!down) interpolator = OvershootInterpolator(2f)
            addUpdateListener { shape.cornerRadius = it.animatedValue as Float }
            start()
        }
        val s = if (down) 0.95f else 1f
        animate().scaleX(s).scaleY(s).setDuration(if (down) 90 else 160).start()
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                parent?.requestDisallowInterceptTouchEvent(true)
                animateTo(true)
                if (Prefs.bool(context, "haptic", true)) performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                if (repeats) { onKey(); postDelayed(repeater, 400) }
            }
            MotionEvent.ACTION_UP -> {
                removeCallbacks(repeater)
                animateTo(false)
                val inside = e.x in 0f..width.toFloat() && e.y in 0f..height.toFloat()
                if (!repeats && inside) onKey()
            }
            MotionEvent.ACTION_CANCEL -> { removeCallbacks(repeater); animateTo(false) }
        }
        return true
    }
}
