package dev.pillboard.keyboard

import android.animation.TimeInterpolator
import android.view.animation.LinearInterpolator

/** Duration + curve of one kind of morph. Every component picks a preset instead of inventing its own motion. */
internal class MorphPreset(val ms: Long, val curve: TimeInterpolator)

internal object MorphPresets {
    /** Single-shape organic morph: the silhouette timeline eases itself, so the clock is linear. */
    fun shape() = MorphPreset(MorphTokens.DURATION_MS, LinearInterpolator())
    /** Container morph (popups, panels): the shared pop spring. */
    fun container(t: KbTokens) = MorphPreset(t.popMs, t.popSpring)
    /** Release / state change of a pressed control: the shared key spring. */
    fun release(t: KbTokens) = MorphPreset(t.releaseMs, t.spring)
}
