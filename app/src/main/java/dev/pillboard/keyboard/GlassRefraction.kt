package dev.pillboard.keyboard

import android.graphics.RenderEffect
import android.graphics.RuntimeShader
import android.os.Build
import android.view.View
import androidx.annotation.RequiresApi

/**
 * Stage 4: SDF edge refraction of the keyboard shell.
 *
 * The shader is attached to the shell view as a [RenderEffect], so it processes the *rendered shell layer*:
 * glass fill + keys + suggestion words. Inside a bevel band along the rounded edge the layer is re-sampled from
 * further in, compressed like the bevel of a glass slab, with a small per-channel offset (dispersion fringe).
 * Keys near the shell edge therefore bend and fringe at the rim.
 *
 * Honest limit: this refracts Pillboard's own layer. An IME window cannot read pixels of the app behind it, so the
 * app content is only ever seen through the compositor's window blur. The outer ~3dp stay un-refracted so the
 * rim light and hairline drawn by [GlassSurfaceDrawable] remain exactly where they are.
 * Output is premultiplied. Interior pixels cost one texture sample.
 */
internal object RefractionShader {
    const val SRC = """
        uniform shader content;
        uniform float2 size;
        uniform float radius;
        uniform float bevel;
        uniform float refractPx;
        uniform float dispersion;
        uniform float dpx;

        float sdRoundRect(float2 p, float2 halfSize, float r) {
            float2 q = abs(p) - halfSize + float2(r, r);
            return min(max(q.x, q.y), 0.0) + length(max(q, float2(0.0, 0.0))) - r;
        }

        half4 main(float2 coord) {
            float2 c = size * 0.5;
            float2 p = coord - c;
            float d = sdRoundRect(p, c, radius);
            half4 base = content.eval(coord);
            if (-d >= bevel) {
                return base;
            }

            float2 g = float2(
                sdRoundRect(p + float2(1.0, 0.0), c, radius) - sdRoundRect(p - float2(1.0, 0.0), c, radius),
                sdRoundRect(p + float2(0.0, 1.0), c, radius) - sdRoundRect(p - float2(0.0, 1.0), c, radius));
            float2 n = g / max(length(g), 0.0001);

            // bevel profile: 1 at the edge, 0 at the end of the band; most of the bending sits in the outer pixels
            float t = clamp(-d / max(bevel, 1.0), 0.0, 1.0);
            float gB = pow(5.0, -2.0);
            float slope = (pow(1.0 + 4.0 * t, -2.0) - gB) / (1.0 - gB);

            // sample inward along the normal; the three channels bend by slightly different amounts
            float2 offset = n * (-slope * refractPx);
            half4 sR = content.eval(coord + offset * (1.0 - dispersion * slope));
            half4 sG = content.eval(coord + offset);
            half4 sB = content.eval(coord + offset * (1.0 + dispersion * slope));
            half4 refracted = half4(sR.r, sG.g, sB.b, sG.a);

            // keep the rim + hairline of the glass drawable untouched (outer ~3dp), refraction takes over below it
            float keep = 1.0 - smoothstep(1.0 * dpx, 3.0 * dpx, -d);
            return mix(refracted, base, half(keep));
        }
    """

    @Volatile private var failed = false

    fun available() = Build.VERSION.SDK_INT >= 33 && !failed

    fun create(): RefractionLayer? {
        if (!available()) return null
        return try { RefractionLayer() } catch (e: Throwable) { failed = true; null }
    }

    fun markFailed() { failed = true }
}

/** Owns the [RuntimeShader]; API 33 types never appear in code that runs on older devices. */
@RequiresApi(33)
internal class RefractionLayer {
    private val shader = RuntimeShader(RefractionShader.SRC)
    private var boundW = -1
    private var boundH = -1

    /** Uniforms depend only on the view size, so the effect is rebuilt when the size changes and never per frame. */
    fun apply(view: View, w: Int, h: Int, radius: Float, density: Float) {
        if (w <= 0 || h <= 0) return
        if (w == boundW && h == boundH) return
        try {
            shader.setFloatUniform("size", w.toFloat(), h.toFloat())
            shader.setFloatUniform("radius", radius)
            shader.setFloatUniform("bevel", BEVEL_DP * density)
            shader.setFloatUniform("refractPx", REFRACT_DP * density)
            shader.setFloatUniform("dispersion", DISPERSION)
            shader.setFloatUniform("dpx", density)
            view.setRenderEffect(RenderEffect.createRuntimeShaderEffect(shader, "content"))
            boundW = w; boundH = h
        } catch (e: Throwable) {
            RefractionShader.markFailed()
            view.setRenderEffect(null)
        }
    }

    fun clear(view: View) {
        view.setRenderEffect(null)
        boundW = -1; boundH = -1
    }

    private companion object {
        // tunables: width of the bevel band, how far inside the edge samples are pulled, per-channel spread
        const val BEVEL_DP = 16f
        const val REFRACT_DP = 11f
        const val DISPERSION = 0.14f
    }
}

/**
 * Keeps one [RefractionLayer] attached to the keyboard shell view while the "Liquid refraction" setting is on and
 * the platform supports it. Safe to call [update] repeatedly; it is a no-op below API 33.
 */
internal class ShellRefraction(private val view: View, private val radius: Float) {
    private var layer: RefractionLayer? = null
    private var enabled = false
    private val listener = View.OnLayoutChangeListener { _, l, t, r, b, ol, ot, orr, ob ->
        if (r - l != orr - ol || b - t != ob - ot) sync()
    }

    init { view.addOnLayoutChangeListener(listener) }

    fun update(on: Boolean) {
        enabled = on && RefractionShader.available()
        sync()
    }

    private fun sync() {
        if (Build.VERSION.SDK_INT < 33) return
        if (!enabled) { layer?.clear(view); return }
        val l = layer ?: RefractionShader.create().also { layer = it } ?: return
        l.apply(view, view.width, view.height, radius, view.resources.displayMetrics.density)
    }

    fun release() {
        view.removeOnLayoutChangeListener(listener)
        if (Build.VERSION.SDK_INT >= 33) layer?.clear(view)
        layer = null
    }
}
