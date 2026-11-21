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
 * AGSL (API 33+) layer for the glass shell: press glow + the glass rim (7dp SDF band along the perimeter: Fresnel-style
 * edge falloff, hue ramp cyan/blue/violet/magenta/warm that follows depth and edge direction, stronger at rounded
 * corners, thin specular ridge from an upper-left key light, faint inner reflection). Static: no per-frame work.
 * Older notes follow. It does NOT sample the backdrop (an IME window cannot read
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
        uniform float rimAmt;
        uniform float night;

        float sdRoundRect(float2 p, float2 halfSize, float r) {
            float2 q = abs(p) - halfSize + float2(r, r);
            return min(max(q.x, q.y), 0.0) + length(max(q, float2(0.0, 0.0))) - r;
        }

        // refracted-light ramp: cyan, blue, violet, magenta, warm
        float3 spectrum(float t) {
            float3 cyan = float3(0.10, 0.88, 1.00);
            float3 blue = float3(0.25, 0.45, 1.00);
            float3 violet = float3(0.58, 0.32, 1.00);
            float3 magenta = float3(1.00, 0.30, 0.78);
            float3 warm = float3(1.00, 0.72, 0.45);
            float x = clamp(t, 0.0, 1.0) * 4.0;
            if (x < 1.0) return mix(cyan, blue, x);
            if (x < 2.0) return mix(blue, violet, x - 1.0);
            if (x < 3.0) return mix(violet, magenta, x - 2.0);
            return mix(magenta, warm, x - 3.0);
        }

        half4 main(float2 fragCoord) {
            float2 c = resolution * 0.5;
            float2 p = fragCoord - c;
            float d = sdRoundRect(p, c, radius);
            float cov = clamp(0.5 - d / 1.5, 0.0, 1.0);

            // press glow
            float2 dt = (fragCoord - touch) / (86.0 * dpx);
            float lit = exp(-dot(dt, dt)) * touchAmt * touchGlow * cov;
            float3 rgb = float3(lit, lit, lit);
            float pa = lit;

            // glass rim: thin band along the perimeter, built from the SDF (static, no animation)
            float band = 7.0 * dpx;
            float depth = -d;
            if (rimAmt > 0.0 && depth > 0.0 && depth < band * 1.2) {
                float u = min(depth / band, 1.0);
                float2 g = float2(
                    sdRoundRect(p + float2(1.0, 0.0), c, radius) - sdRoundRect(p - float2(1.0, 0.0), c, radius),
                    sdRoundRect(p + float2(0.0, 1.0), c, radius) - sdRoundRect(p - float2(0.0, 1.0), c, radius));
                float2 n = g / max(length(g), 0.0001);          // outward normal

                // 0 on straight edges, 1 through the rounded corners
                float2 q = abs(p) - c + float2(radius, radius);
                float corner = smoothstep(-0.1 * radius, 0.5 * radius, min(q.x, q.y));

                // key light upper-left, weaker fill from the opposite side
                float2 L = float2(-0.552, -0.834);
                float lobe1 = pow(max(dot(n, L), 0.0), 2.0);
                float lobe2 = pow(max(dot(n, -L), 0.0), 3.0) * 0.45;
                float lobe = clamp(lobe1 + lobe2, 0.0, 1.0);

                // Fresnel-style: strongest at the grazing outer edge, falling off inward
                float fres = pow(1.0 - u, 3.0);

                // dispersion: hue follows depth into the bevel and drifts with the edge direction
                float phase = 0.5 + 0.5 * dot(n, float2(0.8, -0.6));
                float3 hue = spectrum(u * 0.7 + 0.3 * phase);
                float chroma = fres * (0.30 + 0.70 * corner) * (0.35 + 0.65 * lobe) * rimAmt
                    * mix(0.26, 0.46, night);

                // thin specular ridge just inside the edge (slightly warm), plus a faint inner reflection
                float rd = (depth - 1.6 * dpx) / (0.9 * dpx);
                float ridge = exp(-rd * rd);
                float spec = ridge * (lobe1 + 0.6 * lobe2) * (0.55 + 0.45 * corner) * rimAmt
                    * mix(0.50, 0.55, night);
                float id = (depth - band) / (0.8 * dpx);
                float inner = exp(-id * id) * lobe2 * 0.22 * rimAmt;

                // light theme: a faint dark shade under the rim gives the glass thickness
                float shade = (1.0 - night) * fres * 0.10 * rimAmt;

                float aScale = mix(1.0, 0.3, night);            // night: additive glow, day: normal blend
                float3 warmWhite = mix(float3(1.0, 1.0, 1.0), float3(1.0, 0.86, 0.68), 0.35);
                rgb += hue * chroma * cov + warmWhite * spec * cov + float3(inner, inner, inner) * cov;
                pa += (chroma + spec + inner) * aScale * cov + shade * cov;
            }
            rgb = clamp(rgb, float3(0.0), float3(1.0));
            pa = clamp(pa, 0.0, 1.0);
            return half4(half(rgb.r), half(rgb.g), half(rgb.b), half(pa));
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
             touchX: Float, touchY: Float, touchAmt: Float, glow: Boolean, rim: Boolean) {
        shader.setFloatUniform("resolution", rect.width(), rect.height())
        shader.setFloatUniform("radius", radius)
        shader.setFloatUniform("dpx", density)
        shader.setFloatUniform("touch", touchX, touchY)
        shader.setFloatUniform("touchAmt", touchAmt)
        shader.setFloatUniform("touchGlow", if (!glow) 0f else if (night) 0.14f else 0.30f)
        shader.setFloatUniform("rimAmt", if (rim) 1f else 0f)
        shader.setFloatUniform("night", if (night) 1f else 0f)
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
    private val rim: Boolean = false,
) : Drawable() {

    enum class Style { SHELL, FLOATING }

    private val rect = RectF()
    private val inner = RectF()
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 1.dp.toFloat() }
    private val rimPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 1.2f * density() }
    private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val sheenPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val chromaPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 2f * density() }
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
        if (shader == null || !useShader) return
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
        if (style == Style.SHELL && rim) {
            // pre-API-33 rim: faint cyan/blue/violet/magenta/warm sweep (approximation, not refraction)
            val a = if (t.night) 0x3C else 0x26
            val cols = intArrayOf(0xFF22DDFF.toInt(), 0xFF4073FF.toInt(), 0xFF9552FF.toInt(), 0xFFFF4DC7.toInt(), 0xFFFFB873.toInt(), 0xFF22DDFF.toInt())
                .map { ColorUtils.setAlphaComponent(it, a) }.toIntArray()
            chromaPaint.shader = android.graphics.SweepGradient(w / 2f, h / 2f, cols, floatArrayOf(0f, 0.2f, 0.45f, 0.7f, 0.88f, 1f))
        }
    }

    private fun lift(c: Int, r: Float) =
        ColorUtils.setAlphaComponent(ColorUtils.blendARGB(c or 0xFF000000.toInt(), Color.WHITE, r), Color.alpha(c))

    private fun ensureShader(canvas: Canvas) {
        if (!(useShader || rim) || style != Style.SHELL || shaderTried || Build.VERSION.SDK_INT < 33) return
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
            s.draw(canvas, rect, radius, density(), t.night, touchX, touchY, touchAmt, useShader, rim)
        } else if (style == Style.SHELL && rim) {
            val ci = 1.5f * density()
            val ri = RectF(ci, ci, w - ci, h - ci)
            canvas.drawRoundRect(ri, radius - ci, radius - ci, chromaPaint)
            canvas.drawRoundRect(inner, radius - 1f, radius - 1f, rimPaint)
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
