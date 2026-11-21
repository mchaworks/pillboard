package dev.pillboard.keyboard

/** Tunables of the container morph (long-press popup). */
internal object MorphTokens {
    // content fades in once the container is partly open
    const val CONTAINER_FADE_FROM = 0.30f
    const val CONTAINER_FADE_SPAN = 0.50f
    const val CONTAINER_START_H = 0.62f   // start height as a fraction of the final height

    // keycap shape morph at peak: straight edges, no bumps. The body only squashes a little (wider and shorter, as a
    // fraction of its size) while the label swaps, then settles. The KEY_BULGE_* values bow the edges outward as a
    // Bezier; they are 0 (off) because top/bottom swelling looked bad. Raise them only for experiments.
    const val KEY_SQUASH = 0.04f
    const val KEY_BULGE_TOP = 0f
    const val KEY_BULGE_SIDE = 0f
    const val KEY_BULGE_BOTTOM = 0f
    const val KEY_BULGE_LEAN = 0f
}
