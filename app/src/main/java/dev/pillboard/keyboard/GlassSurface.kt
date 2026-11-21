package dev.pillboard.keyboard

import android.animation.ValueAnimator
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.LinearGradient
import android.graphics.Outline
import android.graphics.Paint
import android.graphics.Path
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
 * AGSL (API 33+) layer for the glass shell: the press glow, a soft light pool under the finger (sigma 86dp) that fades
 * in/out with the touch. It does NOT sample the backdrop (an IME window cannot read the app behind it) and draws no
 * edge/rim effect; the shell edge is just the hairline stroke. Output is premultiplied. Compiled once per process;
 * any compile failure just disables the layer.
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
            float2 p = fragCoord - c;
            float d = sdRoundRect(p, c, radius);
            float cov = clamp(0.5 - d / 1.5, 0.0, 1.0);

            // press glow
            float2 dt = (fragCoord - touch) / (86.0 * dpx);
            float lit = clamp(exp(-dot(dt, dt)) * touchAmt * touchGlow * cov, 0.0, 1.0);
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
             touchX: Float, touchY: Float, touchAmt: Float, glow: Boolean) {
        shader.setFloatUniform("resolution", rect.width(), rect.height())
        shader.setFloatUniform("radius", radius)
        shader.setFloatUniform("dpx", density)
        shader.setFloatUniform("touch", touchX, touchY)
        shader.setFloatUniform("touchAmt", touchAmt)
        shader.setFloatUniform("touchGlow", if (!glow) 0f else if (night) 0.14f else 0.30f)
        canvas.drawRoundRect(rect, radius, radius, paint)
    }
}

/**
 * AGSL (API 33+) one-pass SDF renderer for the keyboard shell when a capsule can grow out of it (see [GlassSurfaceDrawable.topPad]).
 * The shell and the capsule are two rounded-rect distance fields joined with a polynomial smooth minimum, so where they
 * touch they neck together like liquid (a metaball), and fill, press glow and the hairline are all computed from that ONE
 * field: there is a single contour, so no seam and no second outline. With the capsule inside the shell (or glue 0) the
 * field is exactly the shell's own rounded rect. The capsule is also cut off a little below the shell's top edge (smooth
 * max), so it only influences the shell where it actually leaves it. The result is clipped to the shell's right edge, so a
 * capsule whose right side is flush with the shell stays flush (the smooth union alone would swell a shared edge).
 * Output is premultiplied.
 */
internal object BlobShader {
    const val SRC = """
        uniform float4 shellRect;
        uniform float shellR;
        uniform float4 capRect;
        uniform float capR;
        uniform float glue;
        uniform float cutY;
        uniform float cutK;
        uniform float strokeW;
        uniform float4 fillC;
        uniform float4 strokeC;
        uniform float dpx;
        uniform float2 touch;
        uniform float touchAmt;
        uniform float touchGlow;

        float sdRR(float2 p, float4 r, float rad) {
            float2 c = float2((r.x + r.z) * 0.5, (r.y + r.w) * 0.5);
            float2 hs = float2((r.z - r.x) * 0.5, (r.w - r.y) * 0.5);
            float2 q = abs(p - c) - hs + float2(rad, rad);
            return min(max(q.x, q.y), 0.0) + length(max(q, float2(0.0, 0.0))) - rad;
        }

        float smin(float a, float b, float k) {
            float h = max(k - abs(a - b), 0.0) / max(k, 0.0001);
            return min(a, b) - h * h * k * 0.25;
        }

        half4 main(float2 fc) {
            float a = sdRR(fc, shellRect, shellR);
            float b = sdRR(fc, capRect, capR);
            b = -smin(-b, -(fc.y - cutY), cutK);
            float d = smin(a, b, glue);
            d = max(d, fc.x - shellRect.z);     // flush with the shell's right edge: a shared edge would otherwise swell outward
            float cov = clamp(0.5 - d, 0.0, 1.0);

            float fa = fillC.a * cov;
            float4 col = float4(fillC.rgb * fa, fa);

            // press glow (same light pool as GlassShader)
            float2 dt = (fc - touch) / (86.0 * dpx);
            float lit = clamp(exp(-dot(dt, dt)) * touchAmt * touchGlow * cov, 0.0, 1.0);
            col = float4(float3(lit, lit, lit) + col.rgb * (1.0 - lit), lit + col.a * (1.0 - lit));

            // hairline: a band just inside the merged contour
            float ds = abs(d + strokeW * 0.5) - strokeW * 0.5;
            float sm = clamp(0.5 - ds, 0.0, 1.0) * strokeC.a;
            col = float4(strokeC.rgb * sm + col.rgb * (1.0 - sm), sm + col.a * (1.0 - sm));
            return half4(col);
        }
    """

    @Volatile private var failed = false

    fun available() = Build.VERSION.SDK_INT >= 33 && !failed

    fun create(): BlobShaderLayer? {
        if (!available()) return null
        return try { BlobShaderLayer() } catch (e: Throwable) { failed = true; null }
    }
}

@RequiresApi(33)
internal class BlobShaderLayer {
    private val shader = RuntimeShader(BlobShader.SRC)
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).also { it.shader = shader }

    fun draw(canvas: Canvas, w: Float, h: Float, shell: RectF, shellR: Float, cap: RoundRectState, glue: Float,
             cutY: Float, cutK: Float, strokeW: Float, fill: Int, stroke: Int, density: Float,
             touchX: Float, touchY: Float, touchAmt: Float, touchGlow: Float) {
        shader.setFloatUniform("shellRect", shell.left, shell.top, shell.right, shell.bottom)
        shader.setFloatUniform("shellR", shellR)
        shader.setFloatUniform("capRect", cap.l, cap.t, cap.r, cap.b)
        shader.setFloatUniform("capR", cap.radius)
        shader.setFloatUniform("glue", glue)
        shader.setFloatUniform("cutY", cutY)
        shader.setFloatUniform("cutK", cutK)
        shader.setFloatUniform("strokeW", strokeW)
        shader.setFloatUniform("fillC", Color.red(fill) / 255f, Color.green(fill) / 255f, Color.blue(fill) / 255f, Color.alpha(fill) / 255f)
        shader.setFloatUniform("strokeC", Color.red(stroke) / 255f, Color.green(stroke) / 255f, Color.blue(stroke) / 255f, Color.alpha(stroke) / 255f)
        shader.setFloatUniform("dpx", density)
        shader.setFloatUniform("touch", touchX, touchY)
        shader.setFloatUniform("touchAmt", touchAmt)
        shader.setFloatUniform("touchGlow", touchGlow)
        canvas.drawRect(0f, 0f, w, h, paint)
    }
}

/**
 * The glass surface of the keyboard: one Canvas-drawn drawable, all Paints and shaders cached.
 *
 * Layers (bottom to top): translucent tonal fill gradient; press glow (AGSL on API 33+, SHELL only; popups use plain
 * gradients); outer hairline. [Style.FLOATING] is the near-opaque variant used
 * by popups. It also provides the rounded outline, so `clipToOutline` and the window-blur mask match the shell.
 *
 * Capsule merge: with [topPad] > 0 the bounds are taller than the shell by that many px of transparent headroom, and
 * [setBulge] describes a capsule (in bounds coordinates) that may rise out of the shell through it. Shell and capsule are
 * then ONE shape: [BlobShader] on API 33+, a path union (no neck fillets) below that.
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

    /** Transparent headroom above the shell inside the bounds (px). 0 = the drawable is just the shell. */
    var topPad = 0
        set(v) { if (field != v) { field = v; dirty = true; invalidateSelf() } }
    /** Called whenever the merged outline changes, so the window-blur mask can follow it. */
    var onShapeChanged: (() -> Unit)? = null
    private val cap = RoundRectState()
    private val idleCap = RoundRectState()
    private var capOn = false
    private var blob: BlobShaderLayer? = null
    private var blobTried = false
    private val pathA = Path()
    private val pathB = Path()
    private val unionPath = Path()

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
        const val GLUE_DP = 22f        // widest neck between capsule and shell
        const val CUT_DP = 26f         // the capsule stops influencing the shell this far below the shell's top edge
        fun density() = android.content.res.Resources.getSystem().displayMetrics.density
    }

    fun setFill(color: Int) { if (fill != color) { fill = color; dirty = true; invalidateSelf() } }

    /** Corner radius of the shell itself (px). */
    val shellRadius: Float get() = radius

    /** True when the AGSL layer is actually in use (for status/diagnostics). */
    val shaderActive get() = shader != null || blob != null

    /** Capsule geometry in bounds coordinates. Radius is the capsule's corner radius. */
    fun setBulge(l: Float, t: Float, r: Float, b: Float, radius: Float) {
        cap.l = l; cap.t = t; cap.r = r; cap.b = b; cap.radius = radius
        capOn = true
        invalidateSelf(); onShapeChanged?.invoke()
    }

    fun clearBulge() {
        if (!capOn) return
        capOn = false
        invalidateSelf(); onShapeChanged?.invoke()
    }

    /** How far the capsule top sits above the shell top (px); <= 0 means it is still inside the shell. */
    private fun protrusion() = if (capOn) topPad - cap.t else 0f

    /** Neck width: grows with how far the capsule has come out, so a capsule inside the shell changes nothing. */
    private fun glue() = protrusion().coerceIn(0f, GLUE_DP * density())

    /** The merged outline as a path (shell plus capsule, no neck fillets): the window-blur mask. */
    fun maskPath(out: Path) {
        val bw = bounds.width().toFloat(); val bh = bounds.height().toFloat()
        out.rewind()
        pathA.rewind()
        pathA.addRoundRect(0f, topPad.toFloat(), bw, bh, radius, radius, Path.Direction.CW)
        if (!capOn || protrusion() <= 0f) { out.addPath(pathA); return }
        pathB.rewind()
        pathB.addRoundRect(cap.l, cap.t, cap.r, cap.b, cap.radius, cap.radius, Path.Direction.CW)
        out.op(pathA, pathB, Path.Op.UNION)
    }

    private fun unionInto(out: Path, inset: Float) {
        pathA.rewind(); pathB.rewind()
        pathA.addRoundRect(rect.left + inset, rect.top + inset, rect.right - inset, rect.bottom - inset,
            radius - inset, radius - inset, Path.Direction.CW)
        pathB.addRoundRect(cap.l + inset, cap.t + inset, cap.r - inset, cap.b - inset,
            (cap.radius - inset).coerceAtLeast(0f), (cap.radius - inset).coerceAtLeast(0f), Path.Direction.CW)
        out.rewind()
        out.op(pathA, pathB, Path.Op.UNION)
    }

    /**
     * Lean the light toward a press at ([px], [py]) in shell pixels and light the glass around it; null eases both
     * back to rest. No-op unless the AGSL layer is active, so the fallback path never animates or invalidates.
     */
    fun leanLight(px: Float?, py: Float?, animate: Boolean) {
        if ((shader == null && blob == null) || !useShader) return
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
        rect.set(0f, topPad.toFloat(), w.toFloat(), h.toFloat())
        val half = strokePaint.strokeWidth / 2f
        inner.set(half, topPad + half, w - half, h - half)

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

    private fun ensureBlob(canvas: Canvas) {
        if (blobTried || style != Style.SHELL || topPad <= 0 || Build.VERSION.SDK_INT < 33) return
        if (!canvas.isHardwareAccelerated) return
        blobTried = true
        blob = BlobShader.create()
    }

    private fun ensureShader(canvas: Canvas) {
        if (topPad > 0 && Build.VERSION.SDK_INT >= 33) return      // blob mode draws the glow itself
        if (!useShader || style != Style.SHELL || shaderTried || Build.VERSION.SDK_INT < 33) return
        if (!canvas.isHardwareAccelerated) return          // RuntimeShader needs the hardware renderer
        shaderTried = true
        shader = GlassShader.create()
    }

    override fun draw(canvas: Canvas) {
        if (dirty) rebuild()
        if (w <= 0 || h <= 0) return
        ensureBlob(canvas)
        ensureShader(canvas)
        val save = canvas.save()
        canvas.translate(bounds.left.toFloat(), bounds.top.toFloat())

        val b = blob
        if (b != null && Build.VERSION.SDK_INT >= 33 && canvas.isHardwareAccelerated) {
            // one SDF pass: fill + glow + hairline of shell ∪ capsule
            val c = if (capOn) cap else idleCap.also { it.l = rect.left; it.t = rect.top; it.r = rect.right; it.b = rect.bottom; it.radius = radius }
            val dens = density()
            b.draw(canvas, w.toFloat(), h.toFloat(), rect, radius, c, glue(),
                rect.top + CUT_DP * dens, CUT_DP * dens, strokePaint.strokeWidth, fill, t.glassStroke, dens,
                touchX, touchY, touchAmt, if (!useShader) 0f else if (t.night) 0.14f else 0.30f)
            canvas.restoreToCount(save)
            return
        }

        val merged = capOn && protrusion() > 0f      // no AGSL: plain path union (no neck fillets)
        if (merged) { unionInto(unionPath, 0f); canvas.drawPath(unionPath, fillPaint) }
        else canvas.drawRoundRect(rect, radius, radius, fillPaint)

        val s = shader
        if (s != null && Build.VERSION.SDK_INT >= 33 && canvas.isHardwareAccelerated) {
            s.draw(canvas, rect, radius, density(), t.night, touchX, touchY, touchAmt, useShader)
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

        if (merged) { unionInto(unionPath, strokePaint.strokeWidth / 2f); canvas.drawPath(unionPath, strokePaint) }
        else canvas.drawRoundRect(inner, radius - 0.5f, radius - 0.5f, strokePaint)
        canvas.restoreToCount(save)
    }

    override fun getOutline(outline: Outline) {
        outline.setRoundRect(bounds.left, bounds.top + topPad, bounds.right, bounds.bottom, radius)
        outline.alpha = 1f
    }

    override fun setAlpha(alpha: Int) { fillPaint.alpha = alpha; invalidateSelf() }
    override fun setColorFilter(cf: ColorFilter?) { fillPaint.colorFilter = cf; invalidateSelf() }
    @Deprecated("Deprecated in Java")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
}
