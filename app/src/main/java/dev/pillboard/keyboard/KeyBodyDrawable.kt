package dev.pillboard.keyboard

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.LinearGradient
import android.graphics.Outline
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.drawable.Drawable

/**
 * The keycap body. Same surface as the GradientDrawable it replaces (colours, four corner radii) so existing press /
 * Shift / release code is unchanged, plus an organic mode: [setMorph] changes the face size around its centre (a key
 * whose width or height really differs on the new page) and adds [bulge] (0..1), a soft Bezier deformation: the top
 * edge swells up and leans a little, the sides pinch, the bottom sags, like a flexible material. At rest it is a plain
 * rounded rect. One Path and one Paint, reused every frame; no bitmaps.
 */
internal class KeyBodyDrawable : Drawable() {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val path = Path()
    private val rect = RectF()
    private val radii = FloatArray(8)
    private var c0 = Color.TRANSPARENT
    private var c1 = Color.TRANSPARENT
    private var alphaMul = 255
    private var shaderFor = 0L

    var dW = 0f; private set
    var dH = 0f; private set
    var bulge = 0f; private set
    /** How far the face may grow beyond its bounds (the hit inset: the gap between keys). */
    var limitX = 0f
    var limitY = 0f

    fun setColors(cs: IntArray) { c0 = cs[0]; c1 = cs[1]; invalidateSelf() }
    fun setCornerRadii(r: FloatArray) { System.arraycopy(r, 0, radii, 0, 8); invalidateSelf() }
    @Suppress("UNUSED_PARAMETER") fun setStroke(width: Int, color: Int) {}      // flat keycaps: no outline

    fun setMorph(w: Float, h: Float, b: Float) {
        dW = w; dH = h; bulge = b
        invalidateSelf()
    }

    override fun draw(canvas: Canvas) {
        val bd = bounds
        if (bd.isEmpty) return
        val cx = bd.exactCenterX()
        val cy = bd.exactCenterY()
        val w = (bd.width() + dW.coerceIn(-2f * limitX - bd.width(), 2f * limitX)).coerceAtLeast(2f)
        val h = (bd.height() + dH.coerceIn(-2f * limitY - bd.height(), 2f * limitY)).coerceAtLeast(2f)
        rect.set(cx - w / 2f, cy - h / 2f, cx + w / 2f, cy + h / 2f)

        if (c0 == c1) { paint.shader = null; paint.color = c0 }
        else {
            val key = c0.toLong() * 31 + c1 + rect.top.toBits() * 7L + rect.bottom.toBits()
            if (key != shaderFor) { shaderFor = key; paint.shader = LinearGradient(0f, rect.top, 0f, rect.bottom, c0, c1, Shader.TileMode.CLAMP) }
        }
        paint.alpha = Color.alpha(c0) * alphaMul / 255

        val maxR = minOf(w, h) / 2f
        path.reset()
        if (bulge <= 0.002f) {
            for (i in 0 until 8) radii[i] = radii[i].coerceIn(0f, maxR)
            path.addRoundRect(rect, radii, Path.Direction.CW)
        } else organic(rect, minOf(radii[0], maxR), minOf(radii[2], maxR), minOf(radii[4], maxR), minOf(radii[6], maxR), bulge)
        canvas.drawPath(path, paint)
    }

    /** Rounded rect whose four edges are cubic curves pushed by [bu]; corners stay circular arcs (kappa). */
    private fun organic(r: RectF, rTL: Float, rTR: Float, rBR: Float, rBL: Float, bu: Float) {
        val l = r.left; val t = r.top; val rt = r.right; val b = r.bottom
        val w = rt - l; val h = b - t
        val up = bu * MorphTokens.KEY_BULGE_TOP * h
        val side = bu * MorphTokens.KEY_BULGE_SIDE * w
        val down = bu * MorphTokens.KEY_BULGE_BOTTOM * h
        val lean = bu * MorphTokens.KEY_BULGE_LEAN * w
        val k = 0.5523f

        val x0 = l + rTL; val x1 = rt - rTR; val dx = x1 - x0
        path.moveTo(x0, t)
        path.cubicTo(x0 + dx / 3f + lean, t - up, x0 + 2f * dx / 3f + lean, t - up, x1, t)
        path.cubicTo(x1 + k * rTR, t, rt, t + rTR - k * rTR, rt, t + rTR)

        val y0 = t + rTR; val y1 = b - rBR; val dy = y1 - y0
        path.cubicTo(rt - side, y0 + dy / 3f, rt - side, y0 + 2f * dy / 3f, rt, y1)
        path.cubicTo(rt, y1 + k * rBR, rt - rBR + k * rBR, b, rt - rBR, b)

        val x2 = rt - rBR; val x3 = l + rBL; val dx2 = x2 - x3
        path.cubicTo(x2 - dx2 / 3f, b + down, x2 - 2f * dx2 / 3f, b + down, x3, b)
        path.cubicTo(x3 - k * rBL, b, l, b - rBL + k * rBL, l, b - rBL)

        val y2 = b - rBL; val y3 = t + rTL; val dy2 = y2 - y3
        path.cubicTo(l + side, y2 - dy2 / 3f, l + side, y2 - 2f * dy2 / 3f, l, y3)
        path.cubicTo(l, y3 - k * rTL, x0 - k * rTL, t, x0, t)
        path.close()
    }

    override fun getOutline(outline: Outline) {
        val r = (radii[0] + radii[2] + radii[4] + radii[6]) / 4f
        outline.setRoundRect(bounds, r.coerceAtLeast(0f))
        outline.alpha = 1f
    }

    override fun setAlpha(alpha: Int) { alphaMul = alpha; invalidateSelf() }
    override fun setColorFilter(cf: ColorFilter?) { paint.colorFilter = cf; invalidateSelf() }
    @Deprecated("Deprecated in Java")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
}
