package dev.pillboard.keyboard

/** Every tunable of the 123 <-> ABC liquid morph in one place. */
internal object MorphTokens {
    const val DURATION_MS = 520L
    const val PEAK = 0.38f         // progress of maximum stretch; the label crossfades around it
    const val RESOLVE = 0.86f      // progress where the shape has retracted and starts settling
    const val STRETCH = 0.95f      // how far the top rises, in key heights
    const val HEAD_NARROW = 0.18f  // the stretched head is a little narrower than the body
    const val NECK_PINCH = 0.30f   // extra narrowing at the neck
    const val NECK_U_OUT = 0.40f   // neck position along the silhouette (0 = top, 1 = bottom) while stretching out
    const val NECK_U_BACK = 0.24f  // ...and where it has travelled to while retracting
    const val NECK_SIGMA = 0.09f   // neck smoothness: larger = longer, softer neck
    const val TOP_RADIUS = 0.34f   // head corner radius at full stretch, as a fraction of key width
    const val SQUASH = 0.05f       // settle undershoot
    const val LABEL_FADE = 0.07f   // half-width of the label crossfade, in progress units
    const val SAMPLES = 40         // contour samples per side (smoothed with quadratic curves)

    // container morph (long-press popup): content fades in once the container is partly open
    const val CONTAINER_FADE_FROM = 0.30f
    const val CONTAINER_FADE_SPAN = 0.50f
    const val CONTAINER_START_H = 0.62f   // start height as a fraction of the final height
}
