package dev.pillboard.keyboard

import android.view.animation.Interpolator
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Analytic damped-spring step response (0 -> 1) as a plain [Interpolator]: real spring feel without
 * adding androidx.dynamicanimation. [damping] < 1 gives a small, controlled overshoot; [omega] is the
 * natural frequency in rad/s. Pair it with a duration of about [settleMs].
 */
class SpringInterpolator(private val damping: Float = 0.62f, private val omega: Float = 22f) : Interpolator {
    private val zeta = damping.toDouble().coerceIn(0.05, 0.999)
    private val w = omega.toDouble()
    private val wd = w * sqrt(1.0 - zeta * zeta)
    private val k = zeta * w / wd
    private val seconds = 4.6 / (zeta * w)        // envelope has decayed below ~1%

    /** Duration (ms) after which the motion has visually settled. */
    val settleMs: Long get() = (seconds * 1000).toLong()

    override fun getInterpolation(x: Float): Float {
        if (x >= 1f) return 1f
        val t = x * seconds
        return (1.0 - exp(-zeta * w * t) * (cos(wd * t) + k * sin(wd * t))).toFloat()
    }
}
