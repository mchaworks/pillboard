package dev.pillboard.keyboard

import android.graphics.Path
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * One continuous silhouette: a single rounded key whose top region stretches up, pinches into a neck under a
 * rounded head, then retracts and settles back into the key. The contour is a half-width profile W(y) sampled
 * densely (denser at the ends) and joined with midpoint quadratic Béziers, so it is smooth with no polygon facets.
 * At extension 0 the profile is exactly the key's rounded rect. All arrays and the Path are reused: no per-frame
 * allocation.
 */
internal class MorphSilhouette(
    private val w: Float, private val h: Float, private val cx: Float, private val bottom: Float, private val radius: Float,
) {
    private val n = MorphTokens.SAMPLES
    private val count = 2 * (n + 1)
    private val xs = FloatArray(count)
    private val ys = FloatArray(count)
    val path = Path()
    /** Extension 0..1 of the current frame and the y of the head's label centre. */
    var extension = 0f; private set
    var labelY = bottom - h / 2f; private set

    fun build(p: Float) {
        val e = stretchAt(p)
        val sq = settle(p)
        extension = e
        val q = if (p > MorphTokens.PEAK) easeInOut(min((p - MorphTokens.PEAK) / (MorphTokens.RESOLVE - MorphTokens.PEAK), 1f)) else 0f
        val neckU = MorphTokens.NECK_U_OUT + (MorphTokens.NECK_U_BACK - MorphTokens.NECK_U_OUT) * q

        val hh = h * (1f - sq * 0.8f)
        val halfW = w * (1f + sq) / 2f
        val top = bottom - hh - MorphTokens.STRETCH * h * e
        val ht = bottom - top
        val rt = min(radius + (w * MorphTokens.TOP_RADIUS - radius) * e, ht / 2f)
        val rb = min(radius, hh / 2f)
        labelY = top + (h / 2f) * (1f - e) + 0.27f * ht * e

        for (i in 0..n) {
            val y = top + ht * (0.5f - 0.5f * cos(PI.toFloat() * i / n))
            val hw = halfWidth(y, top, ht, bottom, halfW, rt, rb, neckU, e)
            xs[i] = cx + hw; ys[i] = y                       // right side, top to bottom
            xs[count - 1 - i] = cx - hw; ys[count - 1 - i] = y   // left side, bottom to top
        }
        path.reset()
        path.moveTo((xs[count - 1] + xs[0]) / 2f, (ys[count - 1] + ys[0]) / 2f)
        for (i in 0 until count) {
            val j = if (i + 1 == count) 0 else i + 1
            path.quadTo(xs[i], ys[i], (xs[i] + xs[j]) / 2f, (ys[i] + ys[j]) / 2f)
        }
        path.close()
    }

    private fun halfWidth(y: Float, top: Float, ht: Float, bottom: Float, halfW: Float, rt: Float, rb: Float, neckU: Float, e: Float): Float {
        val dt = y - top
        val db = bottom - y
        var bw = halfW
        if (dt < rt) { val a = rt - dt; bw = min(bw, halfW - rt + sqrt(max(rt * rt - a * a, 0f))) }
        if (db < rb) { val a = rb - db; bw = min(bw, halfW - rb + sqrt(max(rb * rb - a * a, 0f))) }
        val u = dt / ht
        val s = (u - neckU) / MorphTokens.NECK_SIGMA
        val head = smoothstep(0.62f, 0.30f, u)               // 1 in the head, 0 in the body
        val nar = e * (MorphTokens.HEAD_NARROW * head + MorphTokens.NECK_PINCH * exp(-s * s))
        return bw * (1f - nar)
    }

    private fun smoothstep(a: Float, b: Float, x: Float): Float {
        val t = ((x - a) / (b - a)).coerceIn(0f, 1f)
        return t * t * (3f - 2f * t)
    }

    /** 0..1: stretches out (0..PEAK), retracts (PEAK..RESOLVE), then rests at 0. */
    private fun stretchAt(p: Float): Float = when {
        p < MorphTokens.PEAK -> { val i = 1f - p / MorphTokens.PEAK; 1f - i * i * i }
        p < MorphTokens.RESOLVE -> 1f - easeInOut((p - MorphTokens.PEAK) / (MorphTokens.RESOLVE - MorphTokens.PEAK))
        else -> 0f
    }

    /** Small squash/stretch undershoot while the shape settles into the key. */
    private fun settle(p: Float): Float {
        if (p < MorphTokens.RESOLVE) return 0f
        val q = (p - MorphTokens.RESOLVE) / (1f - MorphTokens.RESOLVE)
        return MorphTokens.SQUASH * (1f - q) * sin(q * 2f * PI.toFloat())
    }

    private fun easeInOut(x: Float): Float = x * x * (3f - 2f * x)
}
