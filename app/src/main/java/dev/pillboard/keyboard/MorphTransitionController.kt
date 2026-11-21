package dev.pillboard.keyboard

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.view.View
import android.view.ViewGroup

/**
 * Runs one liquid morph at a time on [host]'s overlay. The real (new) key stays invisible while the liquid plays
 * and is revealed in the same frame the overlay is removed, so the end state is the real key, never a copy.
 * Starting a new morph (or [cancel]) finishes the running one at once: no stacked animations, no stuck-hidden key.
 */
internal class MorphTransitionController(private val host: ViewGroup) {
    private var drawable: LiquidMorphDrawable? = null
    private var anim: ValueAnimator? = null
    private var held: View? = null

    fun start(spec: LiquidMorphDrawable.Spec, newKey: View, preset: MorphPreset = MorphPresets.shape()) {
        cancel()
        val d = LiquidMorphDrawable(spec, host.resources.displayMetrics.density)
        drawable = d
        held = newKey
        newKey.alpha = 0f
        host.overlay.add(d)
        anim = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = preset.ms
            interpolator = preset.curve
            addUpdateListener { d.setProgress(it.animatedFraction) }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(a: Animator) { anim = null; finish() }
            })
            start()
        }
    }

    fun cancel() {
        val a = anim
        anim = null
        a?.removeAllUpdateListeners()
        a?.removeAllListeners()
        a?.cancel()
        finish()
    }

    private fun finish() {
        drawable?.let { host.overlay.remove(it); it.release() }
        drawable = null
        held?.alpha = 1f
        held = null
    }
}
