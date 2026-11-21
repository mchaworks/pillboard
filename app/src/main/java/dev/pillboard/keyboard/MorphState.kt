package dev.pillboard.keyboard

/** A rounded rectangle (view-local pixels) plus corner radius: the state a container morph interpolates between. */
internal class RoundRectState(var l: Float = 0f, var t: Float = 0f, var r: Float = 0f, var b: Float = 0f, var radius: Float = 0f) {
    /** Sets this to the blend of [from] and [to] at [f]; [f] may leave 0..1 (spring overshoot). */
    fun lerp(from: RoundRectState, to: RoundRectState, f: Float) {
        l = from.l + (to.l - from.l) * f
        t = from.t + (to.t - from.t) * f
        r = from.r + (to.r - from.r) * f
        b = from.b + (to.b - from.b) * f
        radius = (from.radius + (to.radius - from.radius) * f).coerceAtLeast(0f)
    }
}
