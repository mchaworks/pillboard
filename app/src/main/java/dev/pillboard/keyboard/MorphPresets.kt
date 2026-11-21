package dev.pillboard.keyboard

import android.animation.TimeInterpolator

/** Duration + curve of one kind of morph. Every component picks a preset instead of inventing its own motion. */
internal class MorphPreset(val ms: Long, val curve: TimeInterpolator)

internal object MorphPresets {
    /** Container morph (popups, panels): the shared pop spring. */
    fun container(t: KbTokens) = MorphPreset(t.popMs, t.popSpring)
    /** Release / state change of a pressed control: the shared key spring. */
    fun release(t: KbTokens) = MorphPreset(t.releaseMs, t.spring)
}
