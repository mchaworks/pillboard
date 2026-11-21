package dev.pillboard.keyboard

import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import kotlin.math.ceil
import kotlin.math.floor

/**
 * Draws the 123 <-> ABC morph as a ViewOverlay drawable: ONE deforming key silhouette ([MorphSilhouette]), filled
 * with the key's colour, with the label riding the head and crossfading at the point of maximum stretch. Pure Canvas
 * path drawing: crisp edges, no bitmap, any API level. Works for both directions (only the labels differ).
 */
internal class LiquidMorphDrawable(private val s: Spec, density: Float) : Drawable() {

    class Spec(
        val l: Float, val t: Float, val r: Float, val b: Float,
        val radius: Float, val fill: Int, val ink: Int,
        val textSizePx: Float, val typeface: Typeface?, val from: String, val to: String,
    )

    private val h = s.b - s.t
    private val cx = (s.l + s.r) / 2f
    private val shape = MorphSilhouette(s.r - s.l, h, cx, s.b, s.radius)
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = s.fill }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = s.ink; textSize = s.textSizePx; typeface = s.typeface; textAlign = Paint.Align.CENTER
    }
    private val inkAlpha = textPaint.alpha
    private val fm = Paint.FontMetrics()
    private var p = 0f

    init {
        val padX = 8f * density
        val padTop = h * MorphTokens.STRETCH + 10f * density
        setBounds(floor(s.l - padX).toInt(), floor(s.t - padTop).toInt(), ceil(s.r + padX).toInt(), ceil(s.b + padX).toInt())
        shape.build(0f)
    }

    fun setProgress(progress: Float) {
        p = progress
        shape.build(p)
        invalidateSelf()
    }

    override fun draw(canvas: Canvas) {
        canvas.drawPath(shape.path, fillPaint)
        textPaint.getFontMetrics(fm)
        val y = shape.labelY - (fm.ascent + fm.descent) / 2f
        val a = ((p - (MorphTokens.PEAK - MorphTokens.LABEL_FADE)) / (2f * MorphTokens.LABEL_FADE)).coerceIn(0f, 1f)
        if (a < 1f) { textPaint.alpha = (inkAlpha * (1f - a)).toInt(); canvas.drawText(s.from, cx, y, textPaint) }
        if (a > 0f) { textPaint.alpha = (inkAlpha * a).toInt(); canvas.drawText(s.to, cx, y, textPaint) }
    }

    fun release() {}

    override fun setAlpha(alpha: Int) {}
    override fun setColorFilter(cf: ColorFilter?) {}
    @Deprecated("Deprecated in Java")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
}
