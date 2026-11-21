package dev.pillboard.keyboard

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RenderEffect
import android.graphics.RenderNode
import android.graphics.Shader
import android.os.Build
import android.text.TextPaint
import android.text.TextUtils
import android.view.Gravity
import android.widget.TextView
import androidx.annotation.RequiresApi
import kotlin.math.round

/**
 * One piece of text taking part in a transition. A single scalar [s] drives everything: 0 = sharp, opaque, in place;
 * 1 = fully blurred, transparent, shrunk and drifted. Outgoing layers run s -> 1, incoming layers 1 -> 0, so a layer
 * interrupted half way simply reverses direction from where it is (no jump).
 */
internal class BlurTextLayer(var text: CharSequence, val sign: Int) {
    var s = 0f
    var from = 0f
    var to = 0f
    var startAt = -1L       // uptime ms; -1 = not started yet (view not laid out)
    var delay = 0L
    var dur = 1L
    var outgoing = false
    var node: Any? = null   // API 31+ RenderNode holder (see [NodeHolder])

    fun copy() = BlurTextLayer(text, sign).also {
        it.s = s; it.from = from; it.to = to; it.startAt = startAt; it.delay = delay; it.dur = dur; it.outgoing = outgoing
    }
}

@RequiresApi(31)
private class NodeHolder {
    val node = RenderNode("blurText")
    var key = 0
    var radius = -1f
}

/**
 * Draws a [BlurTextLayer] over its host [TextView] with the host's own paint (font, size, colour, gravity).
 * API 31+ on a hardware canvas: text is recorded once into a reused RenderNode, blur is a RenderEffect on that node
 * (rebuilt only when the quantised radius changes), alpha/scale/translation are node properties: no per-frame
 * bitmaps and no re-recording. Anything else (older API, software canvas, any failure): same alpha + scale + drift
 * without the blur, drawn directly.
 */
internal class BlurTextRenderer(private val density: Float) {
    private val paint = TextPaint(Paint.ANTI_ALIAS_FLAG)
    private val fm = Paint.FontMetrics()

    private companion object {
        @Volatile var nodeFailed = false
    }

    fun release(l: BlurTextLayer) {
        if (Build.VERSION.SDK_INT >= 31) (l.node as? NodeHolder)?.node?.discardDisplayList()
        l.node = null
    }

    fun draw(canvas: Canvas, host: TextView, l: BlurTextLayer) {
        val s = l.s.coerceIn(0f, 1f)
        val k = 1f - s
        val alpha = k * k
        if (alpha < 0.004f || l.text.isEmpty()) return
        val w = host.width
        val h = host.height
        val avail = (w - host.compoundPaddingLeft - host.compoundPaddingRight).toFloat()
        if (w <= 0 || h <= 0 || avail <= 0f) return

        paint.set(host.paint)
        paint.color = host.currentTextColor
        val baseAlpha = paint.alpha
        var txt = l.text
        if (paint.measureText(txt, 0, txt.length) > avail) txt = TextUtils.ellipsize(txt, paint, avail, TextUtils.TruncateAt.END)
        val tw = paint.measureText(txt, 0, txt.length)
        paint.getFontMetrics(fm)

        // same placement the TextView uses: horizontal by gravity, vertically centred in the content box
        val left = host.compoundPaddingLeft.toFloat()
        val x = when (Gravity.getAbsoluteGravity(host.gravity, host.layoutDirection) and Gravity.HORIZONTAL_GRAVITY_MASK) {
            Gravity.LEFT -> left
            Gravity.RIGHT -> left + avail - tw
            else -> left + (avail - tw) / 2f
        }
        val top = host.compoundPaddingTop.toFloat()
        val bottom = (h - host.compoundPaddingBottom).toFloat()
        val y = (top + bottom) / 2f - (fm.ascent + fm.descent) / 2f

        val scale = 1f - (1f - BlurTextTokens.SCALE_MIN) * s
        val ty = l.sign * BlurTextTokens.SHIFT_DP * density * s
        val px = x + tw / 2f
        val py = y + (fm.ascent + fm.descent) / 2f
        val step = BlurTextTokens.BLUR_STEP_PX
        val radius = round(BlurTextTokens.MAX_BLUR_DP * density * s / step) * step

        if (radius > 0f && Build.VERSION.SDK_INT >= 31 && !nodeFailed && canvas.isHardwareAccelerated) {
            try {
                drawNode(canvas, l, txt, x, y, w, h, alpha, scale, ty, px, py, radius)
                return
            } catch (e: Throwable) {
                nodeFailed = true       // fall through to the plain path from now on
            }
        }
        val save = canvas.save()
        canvas.translate(0f, ty)
        canvas.scale(scale, scale, px, py)
        paint.alpha = (baseAlpha * alpha).toInt().coerceIn(0, 255)
        canvas.drawText(txt, 0, txt.length, x, y, paint)
        canvas.restoreToCount(save)
    }

    @RequiresApi(31)
    private fun drawNode(
        canvas: Canvas, l: BlurTextLayer, txt: CharSequence, x: Float, y: Float, w: Int, h: Int,
        alpha: Float, scale: Float, ty: Float, px: Float, py: Float, radius: Float,
    ) {
        val hold = (l.node as? NodeHolder) ?: NodeHolder().also { l.node = it }
        val n = hold.node
        var key = txt.toString().hashCode()
        key = 31 * key + paint.textSize.toBits()
        key = 31 * key + paint.color
        key = 31 * key + (paint.typeface?.hashCode() ?: 0)
        key = 31 * key + x.toBits()
        key = 31 * key + y.toBits()
        key = 31 * key + w
        key = 31 * key + h
        if (key != hold.key || !n.hasDisplayList()) {
            hold.key = key
            n.setPosition(0, 0, w, h)
            val rc = n.beginRecording(w, h)
            try { rc.drawText(txt, 0, txt.length, x, y, paint) } finally { n.endRecording() }
        }
        if (radius != hold.radius) {
            hold.radius = radius
            n.setRenderEffect(RenderEffect.createBlurEffect(radius, radius, Shader.TileMode.DECAL))
        }
        n.setPivotX(px); n.setPivotY(py)
        n.setScaleX(scale); n.setScaleY(scale)
        n.setTranslationY(ty)
        n.setAlpha(alpha)
        canvas.drawRenderNode(n)
    }
}
