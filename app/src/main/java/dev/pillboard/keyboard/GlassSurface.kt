package dev.pillboard.keyboard

import android.animation.ValueAnimator
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.LinearGradient
import android.graphics.Outline
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.RuntimeShader
import android.graphics.Shader
import android.graphics.drawable.Drawable
import android.os.Build
import android.view.animation.DecelerateInterpolator
import androidx.annotation.RequiresApi
import androidx.core.graphics.ColorUtils

/**
 * AGSL (API 33+) lighting layer for the glass shell. It does NOT sample the backdrop (an IME window cannot read
 * the app behind it); it is simulated optics computed from the shell's own rounded-rect signed distance field:
 *  - a thin directional specular rim (key light + weaker opposite fill lobe),
 *  - a faint per-channel offset of that rim (simulated dispersion, not real refraction),
 *  - a soft light pool near the top edge that leans toward [lightDir].
 * Output is premultiplied. Compiled once per process; any compile failure just disables the layer.
 */
internal object GlassShader {
    const val SRC = """
        uniform float2 resolution;
        uniform float radius;
        uniform float dpx;
        uniform float2 touch;
        uniform float touchAmt;
        uniform float touchGlow;

        float sdRoundRect(float2 p, float2 halfSize, float r) {
            float2 q = abs(p) - halfSize + float2(r, r);
            return min(max(q.x, q.y), 0.0) + length(max(q, float2(0.0, 0.0))) - r;
        }

        half4 main(float2 fragCoord) {
            float2 c = resolution * 0.5;
            float d = sdRoundRect(fragCoord - c, c, radius);
            float cov = clamp(0.5 - d / 1.5, 0.0, 1.0);
            float2 dt = (fragCoord - touch) / (86.0 * dpx);
            float lit = exp(-dot(dt, dt)) * touchAmt * touchGlow * cov;
            return half4(half(lit), half(lit), half(lit), half(lit));
        }
    """

    @Volatile private var failed = false

    /** True when an AGSL shader can be attempted on this device. */
    fun available() = Build.VERSION.SDK_INT >= 33 && !failed

    /** New layer, or null if AGSL is unavailable or the source did not compile. */
    fun create(): GlassShaderLayer? {
        if (!available()) return null
        return try { GlassShaderLayer() } catch (e: Throwable) { failed = true; null }
    }
}

/** Holds the [RuntimeShader] so API 33 types never appear in code that runs on older devices. */
@RequiresApi(33)
internal class GlassShaderLayer {
    private val shader = RuntimeShader(GlassShader.SRC)
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).also { it.shader = shader }

    fun draw(canvas: Canvas, rect: RectF, radius: Float, density: Float, night: Boolean,
             touchX: Float, touchY: Float, touchAmt: Float) {
        shader.setFloatUniform("resolution", rect.width(), rect.height())
        shader.setFloatUniform("radius", radius)
        shader.setFloatUniform("dpx", density)
        shader.setFloatUniform("touch", touchX, touchY)
        shader.setFloatUniform("touchAmt", touchAmt)
        shader.setFloatUniform("touchGlow", if (night) 0.14f else 0.30f)
        canvas.drawRoundRect(rect, radius, radius, paint)
    }
}

/**
 * The glass surface of the keyboard: one Canvas-drawn drawable, all Paints and shaders cached.
 *
 * Layers (bottom to top): translucent tonal fill gradient; light pool + directional specular rim
 * (AGSL on API 33+, plain gradients elsewhere); outer hairline. [Style.FLOATING] is the near-opaque variant used
 * by popups. It also provides the rounded outline, so `clipToOutline` and the window-blur mask match the shell.
 */
class GlassSurfaceDrawable(
    private val t: KbTokens,
    private val radius: Float,
    private var fill: Int,
    private val style: Style = Style.SHELL,
    private val useShader: Boolean = true,
) : Drawable() {

    enum class Style { SHELL, FLOATING }

    private val rect = RectF()
    private val inner = RectF()
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 1.dp.toFloat() }
    private val rimPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 1.2f * density() }
    private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val sheenPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var shader: GlassShaderLayer? = null
    private var shaderTried = false
    private var w = 0
    private var h = 0
    private var dirty = true

    // light direction (unit vector pointing toward the light); x follows the last press
    private var lightX = DEFAULT_LIGHT_X
    private var light: ValueAnimator? = null
    // press glow: where the last key was pressed (shell pixels) and how strongly it currently lights the glass
    private var touchX = 0f
    private var touchY = 0f
    private var touchAmt = 0f

    private companion object {
        const val DEFAULT_LIGHT_X = -0.866f
        const val LIGHT_Y = -0.5f
        const val LIGHT_SWAY = 0.35f
        fun density() = android.content.res.Resources.getSystem().displayMetrics.density
    }

    fun setFill(color: Int) { if (fill != color) { fill = color; dirty = true; invalidateSelf() } }

    /** True when the AGSL layer is actually in use (for status/diagnostics). */
    val shaderActive get() = shader != null

    /**
     * Lean the light toward a press at ([px], [py]) in shell pixels and light the glass around it; null eases both
     * back to rest. No-op unless the AGSL layer is active, so the fallback path never animates or invalidates.
     */
    fun leanLight(px: Float?, py: Float?, animate: Boolean) {
        if (shader == null) return
        val down = px != null && py != null && w > 0
        val frac = if (down) (px!! / w).coerceIn(0f, 1f) else 0.5f
        val targetX = DEFAULT_LIGHT_X + (frac - 0.5f) * 2f * LIGHT_SWAY
        val targetAmt = if (down) 1f else 0f
        if (down) { touchX = px!!; touchY = py!! }       // on release the glow fades where it was
        light?.cancel()
        val fromX = lightX
        val fromAmt = touchAmt
        if (!animate) { lightX = targetX; touchAmt = targetAmt; invalidateSelf(); return }
        light = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = if (down) 200L else 520L
            interpolator = DecelerateInterpolator()
            addUpdateListener {
                val f = it.animatedFraction
                lightX = fromX + (targetX - fromX) * f
                touchAmt = fromAmt + (targetAmt - fromAmt) * f
                invalidateSelf()
            }
            start()
        }
    }

    override fun onBoundsChange(b: android.graphics.Rect) {
        super.onBoundsChange(b)
        dirty = true
    }

    private fun rebuild() {
        w = bounds.width(); h = bounds.height()
        if (w <= 0 || h <= 0) return
        dirty = false
        rect.set(0f, 0f, w.toFloat(), h.toFloat())
        val half = strokePaint.strokeWidth / 2f
        inner.set(half, half, w - half, h - half)

        val top: Int
        val bottom: Int
        if (style == Style.SHELL) { top = fill; bottom = fill }
        else if (style == Style.FLOATING) { top = lift(fill, 0.05f); bottom = fill }
        else if (t.night) { top = lift(fill, 0.06f); bottom = fill }
        else { top = fill; bottom = ColorUtils.blendARGB(fill or 0xFF000000.toInt(), Color.BLACK, 0.03f).let { ColorUtils.setAlphaComponent(it, Color.alpha(fill)) } }
        fillPaint.shader = LinearGradient(0f, 0f, 0f, h.toFloat(), top, bottom, Shader.TileMode.CLAMP)

        strokePaint.color = t.glassStroke

        // fallback lighting (no AGSL): soft light pool + diagonal specular rim
        val glowTop = ColorUtils.setAlphaComponent(Color.WHITE, if (t.night) 0x14 else 0x40)
        glowPaint.shader = LinearGradient(0f, 0f, 0f, minOf(h * 0.55f, 96.dp.toFloat()), glowTop, Color.TRANSPARENT, Shader.TileMode.CLAMP)
        val hi = t.glassHighlight
        val lo = ColorUtils.setAlphaComponent(hi, Color.alpha(hi) * 3 / 4)
        val mid = ColorUtils.setAlphaComponent(hi, 0)
        val sheen = ColorUtils.setAlphaComponent(Color.WHITE, if (t.night) 0x14 else 0x3C)
        val clear = ColorUtils.setAlphaComponent(Color.WHITE, 0)
        sheenPaint.shader = LinearGradient(0f, 0f, w * 0.85f, h * 0.55f * 1.6f,
            intArrayOf(clear, clear, sheen, clear, clear), floatArrayOf(0f, 0.30f, 0.5f, 0.70f, 1f), Shader.TileMode.CLAMP)
        rimPaint.shader = LinearGradient(0f, 0f, w.toFloat(), h.toFloat(),
            intArrayOf(hi, mid, mid, lo), floatArrayOf(0f, 0.32f, 0.72f, 1f), Shader.TileMode.CLAMP)
    }

    private fun lift(c: Int, r: Float) =
        ColorUtils.setAlphaComponent(ColorUtils.blendARGB(c or 0xFF000000.toInt(), Color.WHITE, r), Color.alpha(c))

    private fun ensureShader(canvas: Canvas) {
        if (!useShader || style != Style.SHELL || shaderTried || Build.VERSION.SDK_INT < 33) return
        if (!canvas.isHardwareAccelerated) return          // RuntimeShader needs the hardware renderer
        shaderTried = true
        shader = GlassShader.create()
    }

    override fun draw(canvas: Canvas) {
        if (dirty) rebuild()
        if (w <= 0 || h <= 0) return
        ensureShader(canvas)
        val save = canvas.save()
        canvas.translate(bounds.left.toFloat(), bounds.top.toFloat())

        canvas.drawRoundRect(rect, radius, radius, fillPaint)

        val s = shader
        if (s != null && Build.VERSION.SDK_INT >= 33 && canvas.isHardwareAccelerated) {
            s.draw(canvas, rect, radius, density(), t.night, touchX, touchY, touchAmt)
        } else if (style != Style.SHELL) {
            // soft light falling on the upper part of the shell, clipped to the rounded top
            val glowH = minOf(h * 0.55f, 96.dp.toFloat())
            val c2 = canvas.save()
            canvas.clipRect(0f, 0f, w.toFloat(), glowH)
            canvas.drawRoundRect(inner, radius - 1f, radius - 1f, glowPaint)
            canvas.restoreToCount(c2)
            if (style == Style.SHELL) canvas.drawRoundRect(inner, radius - 1f, radius - 1f, sheenPaint)   // fallback reflection streak
            canvas.drawRoundRect(inner, radius - 1f, radius - 1f, rimPaint)
        }

        canvas.drawRoundRect(inner, radius - 0.5f, radius - 0.5f, strokePaint)
        canvas.restoreToCount(save)
    }

    override fun getOutline(outline: Outline) {
        outline.setRoundRect(bounds, radius)
        outline.alpha = 1f
    }

    override fun setAlpha(alpha: Int) { fillPaint.alpha = alpha; invalidateSelf() }
    override fun setColorFilter(cf: ColorFilter?) { fillPaint.colorFilter = cf; invalidateSelf() }
    @Deprecated("Deprecated in Java")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
}
