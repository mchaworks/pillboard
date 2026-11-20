package dev.pillboard.keyboard

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.RenderEffect
import android.graphics.RenderNode
import android.graphics.RuntimeShader
import android.os.Build
import android.view.View
import androidx.annotation.RequiresApi
import androidx.core.graphics.ColorUtils

/**
 * Real (SDF) refraction for the key-preview lens. Unlike the shell, the lens refracts content that Pillboard owns:
 * the keyboard's own view tree is recorded into a [RenderNode] around the pressed key and handed to this shader as
 * `content`. Nothing from the app behind the IME is (or can be) sampled.
 *
 * Optics: rounded-rect SDF -> bevel profile (inverse power) -> samples pulled inward along the edge normal
 * (compressed mirror ring at the rim) -> per-channel refraction amount (dispersion fringe) -> translucent glass tint
 * -> the same two-lobe rim light as the shell. Output is premultiplied.
 */
internal object LensShader {
    const val SRC = """
        uniform shader content;
        uniform float2 size;
        uniform float radius;
        uniform float bevel;
        uniform float refractPx;
        uniform float dispersion;
        uniform float2 lightDir;
        uniform float dpx;
        uniform float4 tint;

        float sdRoundRect(float2 p, float2 halfSize, float r) {
            float2 q = abs(p) - halfSize + float2(r, r);
            return min(max(q.x, q.y), 0.0) + length(max(q, float2(0.0, 0.0))) - r;
        }

        half4 main(float2 coord) {
            float2 c = size * 0.5;
            float2 p = coord - c;
            float d = sdRoundRect(p, c, radius);
            float cov = clamp(0.5 - d / 1.5, 0.0, 1.0);
            if (cov <= 0.004) {
                return half4(0.0, 0.0, 0.0, 0.0);
            }

            float2 g = float2(
                sdRoundRect(p + float2(1.0, 0.0), c, radius) - sdRoundRect(p - float2(1.0, 0.0), c, radius),
                sdRoundRect(p + float2(0.0, 1.0), c, radius) - sdRoundRect(p - float2(0.0, 1.0), c, radius));
            float2 n = g / max(length(g), 0.0001);

            // bevel: 1 at the very edge, falling to 0 at the end of the band; most bending sits in the outer pixels
            float t = clamp(-d / max(bevel, 1.0), 0.0, 1.0);
            float gB = pow(5.0, -2.0);
            float slope = (pow(1.0 + 4.0 * t, -2.0) - gB) / (1.0 - gB);

            // sample inward along the normal: the rim shows a compressed mirror of what lies just inside it
            float2 offset = n * (-slope * refractPx);
            float2 cR = coord + offset * (1.0 - dispersion * slope);
            float2 cG = coord + offset;
            float2 cB = coord + offset * (1.0 + dispersion * slope);
            half4 sR = content.eval(cR);
            half4 sG = content.eval(cG);
            half4 sB = content.eval(cB);
            float4 s = float4(float(sR.r), float(sG.g), float(sB.b), float(sG.a));

            // translucent glass body under the refracted content (premultiplied "over")
            float3 back = tint.rgb * tint.a;
            float3 rgb = s.rgb + back * (1.0 - s.a);
            float a = s.a + tint.a * (1.0 - s.a);

            // rim light: same two-lobe model as the shell
            float facing = dot(n, lightDir);
            float lobeF = pow(max(facing, 0.0), 4.5);
            float lobeB = pow(max(-facing, 0.0), 4.5);
            float hair = clamp(1.0 - abs(d + 0.4 * dpx) / (0.75 * dpx), 0.0, 1.0);
            float glowIn = clamp((-d - 1.1 * dpx) / (0.75 * dpx), 0.0, 1.0);
            float glow = glowIn * pow(clamp(1.0 - (-d - 2.2 * dpx) / (2.2 * dpx), 0.0, 1.0), 1.5) * lobeF;
            float spec = (hair * 0.70 * (lobeF + lobeB) + glow * 0.10 * lobeF) * 0.9;

            rgb = rgb + float3(spec, spec, spec);
            a = clamp(max(a, spec), 0.0, 1.0);
            rgb = min(clamp(rgb, 0.0, 1.0), float3(a, a, a));
            return half4(half(rgb.x * cov), half(rgb.y * cov), half(rgb.z * cov), half(a * cov));
        }
    """

    @Volatile private var failed = false

    fun available() = Build.VERSION.SDK_INT >= 33 && !failed

    fun create(): LensLayer? {
        if (!available()) return null
        return try { LensLayer() } catch (e: Throwable) { failed = true; null }
    }
}

/** Owns the [RuntimeShader] and the recording [RenderNode]; API 33 types never leak into older code paths. */
@RequiresApi(33)
internal class LensLayer {
    private val shader = RuntimeShader(LensShader.SRC)
    private val node = RenderNode("pillboardLens")
    private var boundW = -1
    private var boundH = -1

    /** Uniforms only change when the lens size does, so the effect is rebuilt then and never per frame. */
    private fun bind(w: Int, h: Int, radius: Float, density: Float, tint: Int) {
        shader.setFloatUniform("size", w.toFloat(), h.toFloat())
        shader.setFloatUniform("radius", radius)
        shader.setFloatUniform("bevel", 12f * density)
        shader.setFloatUniform("refractPx", 9f * density)
        shader.setFloatUniform("dispersion", 0.12f)
        shader.setFloatUniform("lightDir", -0.866f, -0.5f)
        shader.setFloatUniform("dpx", density)
        shader.setFloatUniform("tint", Color.red(tint) / 255f, Color.green(tint) / 255f, Color.blue(tint) / 255f, Color.alpha(tint) / 255f)
        node.setRenderEffect(RenderEffect.createRuntimeShaderEffect(shader, "content"))
        boundW = w; boundH = h
    }

    /**
     * Record [src] (the keyboard window) magnified by [mag] around ([cx], [cy]) in window coordinates, then draw it
     * through the refraction effect. The recording is vector display lists, so the magnified text stays crisp.
     */
    fun draw(canvas: Canvas, src: View, w: Int, h: Int, cx: Float, cy: Float, mag: Float, radius: Float, density: Float, tint: Int) {
        if (w != boundW || h != boundH) bind(w, h, radius, density, tint)
        node.setPosition(0, 0, w, h)
        val rc = node.beginRecording(w, h)
        try {
            rc.translate(w / 2f, h / 2f)
            rc.scale(mag, mag)
            rc.translate(-cx, -cy)
            src.draw(rc)
        } finally {
            node.endRecording()
        }
        canvas.drawRenderNode(node)
    }
}

/**
 * The glass lens shown above a pressed key (iOS 26-style magnifier). Hardware canvas + API 33 only; otherwise it
 * draws a plain rounded tile with the glyph, and [KeyPreview] normally uses the ordinary bubble instead ([ready]).
 */
class LensView(ctx: Context, private val t: KbTokens) : View(ctx) {
    private val layer: LensLayer? = LensShader.create()
    val ready get() = layer != null

    private var source: View? = null
    private var cx = 0f
    private var cy = 0f
    private var glyph = ""
    private val tint = ColorUtils.setAlphaComponent(t.key, 0x59)
    private val tile = Paint(Paint.ANTI_ALIAS_FLAG)
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER; textSize = 30f * ctx.resources.displayMetrics.scaledDensity }
    private val rect = RectF()
    private val density = ctx.resources.displayMetrics.density

    /** 0 = just left the key (no magnification), 1 = fully formed; driven by the preview's spring. */
    var morph = 1f
        set(v) { if (field != v) { field = v; invalidate() } }

    /** Point the lens at [key]; the keyboard window ([View.getRootView]) is what gets recorded and refracted. */
    fun aim(key: View, g: String) {
        val loc = IntArray(2)
        key.getLocationInWindow(loc)
        cx = loc[0] + key.width / 2f
        cy = loc[1] + key.height / 2f
        source = key.rootView
        glyph = g
        invalidate()
        // the key's own press scale (65ms) is still running: capture once more when it has settled
        postDelayed({ invalidate() }, 80)
    }

    override fun onDraw(canvas: Canvas) {
        val l = layer
        val s = source
        val r = minOf(t.popupRadius, height / 2f)
        if (l != null && s != null && Build.VERSION.SDK_INT >= 33 && canvas.isHardwareAccelerated && width > 0 && height > 0) {
            l.draw(canvas, s, width, height, cx, cy, 1f + (MAG - 1f) * morph.coerceIn(0f, 1.25f), r, density, tint)
        } else {
            tile.color = t.popupFill
            rect.set(0f, 0f, width.toFloat(), height.toFloat())
            canvas.drawRoundRect(rect, r, r, tile)
            text.color = t.keyText
            canvas.drawText(glyph, width / 2f, height / 2f - (text.ascent() + text.descent()) / 2f, text)
        }
    }

    private companion object { const val MAG = 1.18f }
}
