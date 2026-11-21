package dev.pillboard.keyboard

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Outline
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.drawable.Drawable
import android.view.MotionEvent
import android.view.View
import android.view.ViewOutlineProvider
import android.widget.FrameLayout
import kotlin.math.ceil

/** Fixed geometry of the capsule that grows out of the strip button (dp). */
internal object StripCapsule {
    const val ITEM_H = 40
    const val PAD = 4
    /** Where the capsule's bottom edge sits below the shell's top edge: root pad 8 + strip pad 3 + half the button 18 + half a cell 20 + pad 4. */
    private const val BOTTOM_BELOW_SHELL = 53
    /** Spring overshoot + breathing room above the tallest capsule. */
    private const val SLACK = 14

    /** Transparent headroom (px) the input view keeps above the shell so the capsule can rise out of it. */
    fun headroom(featureCount: Int): Int {
        val n = featureCount + 1
        return (n * ITEM_H + 2 * PAD - BOTTOM_BELOW_SHELL + SLACK).coerceAtLeast(24).dp
    }
}

/**
 * The input view: the keyboard shell ([root] at the bottom) plus [capTop] px of transparent headroom above it. The
 * glass of the whole thing (shell AND the capsule that can rise out of it) is this view's background, one
 * [GlassSurfaceDrawable], so the capsule is part of the shell's own surface and not a window of its own.
 *
 * The headroom is not part of the keyboard as far as the app is concerned: [PillboardService.onComputeInsets] keeps the
 * app's content insets at the shell's top edge and makes the headroom touch-transparent until the capsule is open.
 */
internal class ShellHost(ctx: Context) : FrameLayout(ctx) {
    /** Headroom above the shell, px. */
    var capTop = 0
    /** True while the capsule is open or closing: the whole host then takes touches (see onComputeInsets). */
    var menuOpen = false
    /** Where a touch counts as "on the capsule" (host coordinates). */
    val menuRect = Rect()
    var onOutsideTap: (() -> Unit)? = null
    private var swallowing = false

    init {
        clipChildren = false
        clipToPadding = false
    }

    override fun onInterceptTouchEvent(e: MotionEvent): Boolean {
        if (menuOpen && e.actionMasked == MotionEvent.ACTION_DOWN && !menuRect.contains(e.x.toInt(), e.y.toInt())) {
            swallowing = true          // the tap that closes the capsule must not also type a key
            onOutsideTap?.invoke()
            return true
        }
        return false
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (swallowing) {
            if (e.actionMasked == MotionEvent.ACTION_UP || e.actionMasked == MotionEvent.ACTION_CANCEL) swallowing = false
            return true
        }
        return super.onTouchEvent(e)
    }
}

/**
 * Window background that only defines the shape of the window-blur region: the merged outline of shell and capsule
 * (without the neck fillets, so the blur never extends past the glass). Almost fully transparent (alpha 1/255).
 */
internal class ShapeMaskDrawable(private val glass: GlassSurfaceDrawable, private val host: View) : Drawable() {
    private val path = Path()
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x01000000 }
    private val loc = IntArray(2)

    override fun draw(canvas: Canvas) {
        host.getLocationInWindow(loc)
        glass.maskPath(path)
        val save = canvas.save()
        canvas.translate(loc[0].toFloat(), loc[1].toFloat())
        canvas.drawPath(path, paint)
        canvas.restoreToCount(save)
    }

    override fun setAlpha(alpha: Int) {}
    override fun setColorFilter(cf: ColorFilter?) {}
    @Deprecated("Deprecated in Java")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
}

/**
 * Capsule morph on the shell's own glass. [cur] (owned by the caller, so a closing morph can start from wherever an
 * opening one got to) is interpolated from [from] to [to] with the preset's curve, spring overshoot included; each
 * frame it is handed to [glass] as the capsule that is merged into the shell, and it is the clip of [layer], which
 * holds the capsule's icons (at [ox], [oy] in host coordinates), so the icons are revealed by the same contour.
 * [tweak] runs after each interpolation (fraction, may overshoot) and may adjust [cur] before it is applied.
 * [onEnd] runs only when the morph finishes, not when it is cancelled.
 */
internal fun morphBlob(
    layer: View, ox: Float, oy: Float, glass: GlassSurfaceDrawable,
    cur: RoundRectState, from: RoundRectState, to: RoundRectState, preset: MorphPreset,
    tweak: ((Float) -> Unit)? = null, onEnd: () -> Unit,
): ValueAnimator {
    cur.lerp(from, to, 0f)
    tweak?.invoke(0f)
    layer.clipToOutline = true
    layer.outlineProvider = object : ViewOutlineProvider() {
        override fun getOutline(v: View, outline: Outline) {
            outline.setRoundRect(
                (cur.l - ox).toInt(), (cur.t - oy).toInt(), ceil(cur.r - ox).toInt(), ceil(cur.b - oy).toInt(), cur.radius,
            )
        }
    }
    fun push() {
        glass.setBulge(cur.l, cur.t, cur.r, cur.b, cur.radius)
        layer.invalidateOutline()
    }
    push()
    var cancelled = false
    return ValueAnimator.ofFloat(0f, 1f).apply {
        duration = preset.ms
        interpolator = preset.curve
        addUpdateListener {
            cur.lerp(from, to, it.animatedFraction)
            tweak?.invoke(it.animatedFraction)
            push()
        }
        addListener(object : AnimatorListenerAdapter() {
            override fun onAnimationCancel(a: Animator) { cancelled = true }
            override fun onAnimationEnd(a: Animator) {
                layer.outlineProvider = ViewOutlineProvider.BOUNDS
                layer.clipToOutline = false
                if (!cancelled) onEnd()
            }
        })
        start()
    }
}
