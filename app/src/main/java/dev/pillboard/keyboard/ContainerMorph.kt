package dev.pillboard.keyboard

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.graphics.Outline
import android.view.View
import android.view.ViewOutlineProvider

/**
 * Container morph: [view] keeps its real size and content, but its outline (clip + elevation shadow) is a rounded
 * rect that interpolates from [from] to [to], position, size and corner radius together, so a small element
 * (a key) continuously becomes a larger one (a popup). Swap [from] and [to] to run it in reverse. Cancelling the
 * returned animator restores the view's normal outline immediately. One reused [Outline] state, no per-frame
 * allocation beyond the animator itself.
 */
internal fun morphContainer(
    view: View, from: RoundRectState, to: RoundRectState, preset: MorphPreset,
    onProgress: ((Float) -> Unit)? = null, onEnd: (() -> Unit)? = null,
): ValueAnimator {
    val cur = RoundRectState()
    cur.lerp(from, to, 0f)
    view.clipToOutline = true
    view.outlineProvider = object : ViewOutlineProvider() {
        override fun getOutline(v: View, outline: Outline) {
            outline.setRoundRect(cur.l.toInt(), cur.t.toInt(), cur.r.toInt(), cur.b.toInt(), cur.radius)
        }
    }
    view.invalidateOutline()
    onProgress?.invoke(0f)
    return ValueAnimator.ofFloat(0f, 1f).apply {
        duration = preset.ms
        interpolator = preset.curve
        addUpdateListener {
            val f = it.animatedFraction
            cur.lerp(from, to, f)
            view.invalidateOutline()
            onProgress?.invoke(f.coerceIn(0f, 1f))
        }
        addListener(object : AnimatorListenerAdapter() {
            override fun onAnimationEnd(a: Animator) {
                view.outlineProvider = ViewOutlineProvider.BACKGROUND
                view.clipToOutline = false
                onEnd?.invoke()
            }
        })
        start()
    }
}
