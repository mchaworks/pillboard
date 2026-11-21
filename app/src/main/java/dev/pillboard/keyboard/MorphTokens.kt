package dev.pillboard.keyboard

/** Tunables of the container morph (long-press popup). */
internal object MorphTokens {
    // content fades in once the container is partly open
    const val CONTAINER_FADE_FROM = 0.30f
    const val CONTAINER_FADE_SPAN = 0.50f
    const val CONTAINER_START_H = 0.62f   // start height as a fraction of the final height

    // keycap shape morph (Bezier deformation of a changing key), fractions of the key's own size at full bulge
    const val KEY_BULGE_TOP = 0.07f       // top edge swells up
    const val KEY_BULGE_SIDE = 0.025f     // sides pinch in
    const val KEY_BULGE_BOTTOM = 0.015f   // bottom sags a little
    const val KEY_BULGE_LEAN = 0.05f      // the swell leans sideways so it is not a symmetric rubber band
}
