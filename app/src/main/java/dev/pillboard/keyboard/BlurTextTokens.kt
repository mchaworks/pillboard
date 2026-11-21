package dev.pillboard.keyboard

import android.view.animation.PathInterpolator

/** Every tunable of the Blur Replace text transition (iOS 26 `.blurReplace` feel) in one place. */
internal object BlurTextTokens {
    const val MAX_BLUR_DP = 6f        // blur radius of fully departed / not yet arrived text
    const val BLUR_STEP_PX = 0.5f     // blur is quantised so the RenderEffect is only rebuilt when it really changes
    const val OUT_MS = 170L           // departure
    const val IN_MS = 260L            // arrival
    const val IN_DELAY_MS = 60L       // arrival starts a little after departure: never two opaque labels at once
    const val SCALE_MIN = 0.90f       // scale of fully blurred text (old shrinks into it, new settles out of it)
    const val SHIFT_DP = 2f           // small vertical drift: old drifts up, new rises from below
    const val RAPID_GAP_MS = 160L     // changes closer than this (fast typing) snap instead of animating
    const val TOTAL_MS = IN_DELAY_MS + IN_MS   // whole text transition; the keycap shape morph runs on the same length
    const val MAX_OUTGOING = 2        // old labels kept alive during rapid changes

    // fast start so the old text leaves immediately; emphasized decelerate so the new text lands softly
    val outEase = PathInterpolator(0.2f, 0f, 0f, 1f)
    val inEase = PathInterpolator(0.05f, 0.7f, 0.1f, 1f)
}
