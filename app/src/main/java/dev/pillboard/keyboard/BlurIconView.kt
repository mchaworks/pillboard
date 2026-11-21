package dev.pillboard.keyboard

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.TimeInterpolator
import android.animation.ValueAnimator
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.RenderEffect
import android.graphics.Shader
import android.os.Build
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import androidx.annotation.RequiresApi
import kotlin.math.round

/**
 * [ImageView] painted with the same Blur Replace scalar as the text ([BlurTextRenderer]): s = 0 sharp, opaque, in place;
 * s = 1 blurred, transparent, shrunk and drifted. Outgoing (sign -1) travels along [BlurDir], incoming (sign +1)
 * starts behind it. Blur is a RenderEffect on API 31+ (quantised), plain alpha + scale + drift below.
 */
internal class FxImage(c: Context) : ImageView(c) {
    private var lastR = -1f

    fun fx(s: Float, dir: BlurDir, sign: Int) {
        val c = s.coerceIn(0f, 1f)
        val k = 1f - c
        alpha = k * k
        val sc = 1f - (1f - BlurTextTokens.SCALE_MIN) * c
        scaleX = sc; scaleY = sc
        val d = -sign * BlurTextTokens.SHIFT_DP * 1.5f * resources.displayMetrics.density * c
        translationX = dir.mx * d
        translationY = dir.my * d
        if (Build.VERSION.SDK_INT >= 31) {
            val step = BlurTextTokens.BLUR_STEP_PX
            val r = round(BlurTextTokens.MAX_BLUR_DP * resources.displayMetrics.density * c / step) * step
            if (r != lastR) { lastR = r; blur(r) }
        }
    }

    @RequiresApi(31)
    private fun blur(r: Float) {
        setRenderEffect(if (r > 0f) RenderEffect.createBlurEffect(r, r, Shader.TileMode.DECAL) else null)
    }
}

/**
 * An icon slot whose icon changes with Blur Replace ([set]), and that can fade in / out the same way
 * ([fadeIn], [fadeOut]). Two stacked images: the old one leaves while the new one arrives.
 */
internal class BlurIconView(ctx: Context, private val tint: Int, private val iconAlpha: Float = 0.75f) : FrameLayout(ctx) {
    private var front = img()
    private var back = img()
    private var shown = 0
    private val anims = ArrayList<ValueAnimator>(2)

    init {
        addView(front, LayoutParams(-1, -1))
        addView(back, LayoutParams(-1, -1))
        back.visibility = View.INVISIBLE
    }

    private fun img() = FxImage(context).apply {
        imageTintList = ColorStateList.valueOf(tint)
        scaleType = ImageView.ScaleType.CENTER_INSIDE
        setPadding(9.dp, 9.dp, 9.dp, 9.dp)
        imageAlpha = (iconAlpha * 255).toInt()
    }

    private fun run(v: FxImage, from: Float, to: Float, dur: Long, delay: Long, ease: TimeInterpolator, dir: BlurDir, sign: Int, end: (() -> Unit)? = null) {
        if (!animationsOn(context)) { v.fx(to, dir, sign); end?.invoke(); return }
        v.fx(from, dir, sign)
        val a = ValueAnimator.ofFloat(from, to).apply {
            duration = dur; startDelay = delay; interpolator = ease
            addUpdateListener { v.fx(it.animatedValue as Float, dir, sign) }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) { anims.remove(animation); end?.invoke() }
            })
        }
        anims += a
        a.start()
    }

    private fun settle() {
        val running = ArrayList(anims)
        anims.clear()
        running.forEach { it.removeAllListeners(); it.cancel() }
        back.visibility = View.INVISIBLE; back.fx(0f, BlurDir.UP, 1)
        front.visibility = View.VISIBLE; front.fx(0f, BlurDir.UP, 1)
    }

    /** Swap the icon. The old one blurs away along [dir], the new one arrives along it. */
    fun set(res: Int, dir: BlurDir = BlurDir.UP, animate: Boolean = true) {
        if (res == shown) return
        settle()
        if (shown == 0 || !animate || !animationsOn(context) || !isLaidOut) {
            front.setImageResource(res); shown = res; return
        }
        val old = front; val nw = back
        nw.setImageResource(res); nw.visibility = View.VISIBLE
        shown = res
        front = nw; back = old
        run(old, 0f, 1f, BlurTextTokens.OUT_MS, 0L, BlurTextTokens.outEase, dir, -1) { old.visibility = View.INVISIBLE }
        run(nw, 1f, 0f, BlurTextTokens.IN_MS, BlurTextTokens.IN_DELAY_MS, BlurTextTokens.inEase, dir, 1)
    }

    /** Appear from blur (used when a popup opens). */
    fun fadeIn(delay: Long = 0L, dir: BlurDir = BlurDir.UP) {
        settle()
        run(front, 1f, 0f, BlurTextTokens.IN_MS, delay, BlurTextTokens.inEase, dir, 1)
    }

    /** Leave into blur (used when a popup closes). */
    fun fadeOut(delay: Long = 0L, dir: BlurDir = BlurDir.DOWN, end: (() -> Unit)? = null) {
        run(front, 0f, 1f, BlurTextTokens.OUT_MS, delay, BlurTextTokens.outEase, dir, -1, end)
    }

    override fun onDetachedFromWindow() {
        settle()
        super.onDetachedFromWindow()
    }
}
