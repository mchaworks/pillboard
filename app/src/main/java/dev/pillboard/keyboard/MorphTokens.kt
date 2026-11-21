package dev.pillboard.keyboard

/** Tunables of the container morph (long-press popup). */
internal object MorphTokens {
    // content fades in once the container is partly open
    const val CONTAINER_FADE_FROM = 0.30f
    const val CONTAINER_FADE_SPAN = 0.50f
    const val CONTAINER_START_H = 0.62f   // start height as a fraction of the final height

    // keycap shape morph: a soft, symmetric "pillow" swell (all four edges bow outward a little), fractions of the
    // key's own size at full bulge. Positive = edge moves outward. Set all to 0 for size-only morphing.
    const val KEY_BULGE_TOP = 0.025f
    const val KEY_BULGE_SIDE = 0.02f
    const val KEY_BULGE_BOTTOM = 0.025f
    const val KEY_BULGE_LEAN = 0f         // sideways lean of the top swell; 0 = symmetric
}
