package dev.pillboard.keyboard

import android.content.Context
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import androidx.core.graphics.ColorUtils
import com.google.android.material.color.MaterialColors
import com.google.android.material.R as MR

/**
 * Liquid Expressive design tokens: every color, radius, elevation and timing the keyboard uses.
 * Resolved from the (dynamic or static) Material theme of [ctx]. Components must read from here
 * instead of hard-coding values, so the keyboard stays one coherent product.
 */
class KbTokens(private val ctx: Context) {
    companion object {
        const val KEY_R_DEFAULT = 10
        /** Shadow room around floating popups (a popup window clips anything outside its bounds). */
        val POPUP_SHADOW_PAD get() = 10.dp
    }

    private fun c(attr: Int, fb: Int): Int = MaterialColors.getColor(ctx, attr, fb)
    val night = ctx.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES

    // ---- geometry ----
    val containerRadius = 30.dp.toFloat()
    val stripRadius = 20.dp.toFloat()      // near-pill: the strip reads as a capsule inside the shell
    val popupRadius = 20.dp.toFloat()
    val specialRadiusBoost = 0.dp      // iOS 26: every keycap shares one corner radius
    val actionRadiusBoost = 2.dp       // Enter / active Shift: a touch rounder
    val pressRadiusBoost = 2.dp        // corner morph while pressed

    // ---- elevation ----
    val keyElevation = 1f * ctx.resources.displayMetrics.density
    val popupElevation = 6.dp.toFloat()

    // ---- motion (kept lightweight; input never waits on these) ----
    val pressMs = 65L
    val pressScale = 0.94f
    val springDamping = 0.62f          // < 1: a small, controlled overshoot, never a wobble
    val springOmega = 22f
    val spring = SpringInterpolator(springDamping, springOmega)
    val releaseMs = spring.settleMs    // ~340ms
    val morphMs = 180L                 // shape/tone change (Shift states)
    val radiusReleaseMs = 140L         // corner morph back after a press
    val popupInMs = 100L
    val fadeOutMs = 70L
    val rowInMs = 140L                 // ?123 <-> ABC page change
    /** Android's window-blur guidance: ~80px reads well, above ~150px costs real performance. */
    val blurRadius = minOf(28.dp, 110)

    // ---- color: glass belongs to the shell, keycaps stay solid tonal surfaces ----
    // Light theme: shell sits a notch darker so white keycaps separate from it (validated contrast ~1.24:1).
    val background = c(MR.attr.colorSurfaceContainerLow, if (night) 0xFF1C1B1F.toInt() else 0xFFF3EDF7.toInt())
        .let { if (night) tone(it, Color.BLACK, 0.30f) else tone(it, Color.BLACK, 0.05f) }
    val glassFill = ColorUtils.setAlphaComponent(background, if (night) 0xD8 else 0xE8)
    /** Real backdrop blur keeps text legible, so the shell can be more translucent. */
    val glassFillBlur = ColorUtils.setAlphaComponent(background, if (night) 0xB4 else 0xC8)
    /** Blur requested but unavailable: no blur behind, so stay nearly opaque for readability. */
    val glassFillNoBlur = ColorUtils.setAlphaComponent(background, 0xF2)
    val glassStroke = ColorUtils.setAlphaComponent(c(MR.attr.colorOutlineVariant, if (night) Color.WHITE else Color.BLACK), if (night) 0x48 else 0x30)
    val glassHighlight = ColorUtils.setAlphaComponent(Color.WHITE, if (night) 0x24 else 0x66)
    val key = if (night) tone(c(MR.attr.colorSurfaceContainerHighest, 0xFF36343B.toInt()), Color.WHITE, 0.10f)
              else c(MR.attr.colorSurfaceContainerLowest, Color.WHITE)
    val keyText = c(MR.attr.colorOnSurface, if (night) Color.WHITE else Color.BLACK)
    private val seed: Int? = if (Prefs.dynamic(ctx)) null else Prefs.seedColor(ctx)

    // iOS 26 direction: neutral gray keycaps (special keys one step darker), accent only for the primary action
    private val hint = seed ?: c(MR.attr.colorPrimary, 0xFF6750A4.toInt())
    val special = ColorUtils.blendARGB(tone(key, Color.BLACK, if (night) 0.30f else 0.20f), hint, 0.06f)
    val specialText = keyText
    val pressed = if (night) tone(key, Color.WHITE, 0.16f) else tone(key, Color.BLACK, 0.12f)
    val pressedText = keyText
    val accent = seed ?: c(MR.attr.colorPrimary, Color.BLUE)
    val onAccent = seed?.let { if (ColorUtils.calculateLuminance(it) > 0.5) Color.BLACK else Color.WHITE }
        ?: c(MR.attr.colorOnPrimary, Color.WHITE)
    val keyStroke = ColorUtils.setAlphaComponent(keyText, if (night) 0x22 else 0x18)
    val accentPressed = tone(accent, Color.BLACK, 0.18f)

    // suggestion strip: a faint tonal wash of the text color over the glass, so it belongs to the shell
    val stripFill = Color.TRANSPARENT          // iOS 26: no capsule, just words and dividers
    val stripStroke = Color.TRANSPARENT
    val stripPressed = ColorUtils.setAlphaComponent(keyText, 0x24)
    val divider = ColorUtils.setAlphaComponent(keyText, 0x26)

    // floating popups sit over arbitrary app content, so they stay (almost) opaque for legibility
    val popupFill = ColorUtils.setAlphaComponent(key, 0xF2)

    /** Blend [c] toward [target] without touching its alpha. */
    private fun tone(c: Int, target: Int, r: Float) =
        ColorUtils.setAlphaComponent(ColorUtils.blendARGB(c or 0xFF000000.toInt(), target, r), Color.alpha(c))

    /** Keycaps are flat solid surfaces (iOS 26); kept as a two-stop array so morph animations stay generic. */
    fun sheen(base: Int): IntArray = intArrayOf(base, base)

    // ---- shared surfaces ----
    /**
     * Floating glass shell, drawn on Canvas by [GlassSurfaceDrawable]: tonal gradient fill, directional specular rim
     * and soft upper light (AGSL on API 33+ when "Liquid light" is on, gradients otherwise), plus a hairline outline.
     * Also supplies the rounded outline used for clipping and for the window-blur mask.
     */
    fun shell(fill: Int = glassFill): GlassSurfaceDrawable =
        GlassSurfaceDrawable(this, containerRadius, fill, GlassSurfaceDrawable.Style.SHELL, Prefs.bool(ctx, "glass_fx", true))

    fun stripBackground(): Drawable = GradientDrawable().apply {
        setColor(stripFill); cornerRadius = stripRadius; setStroke(1.dp, stripStroke)
    }

    fun stripItemBackground(): Drawable = StateListDrawable().apply {
        addState(intArrayOf(android.R.attr.state_pressed), GradientDrawable().apply {
            setColor(stripPressed); cornerRadius = stripRadius - 4.dp
        })
        addState(intArrayOf(), ColorDrawable(Color.TRANSPARENT))
    }

    /** Floating popups share the glass renderer (rim + hairline) but stay near-opaque over arbitrary app content. */
    fun popupBackground(): Drawable =
        GlassSurfaceDrawable(this, popupRadius, popupFill, GlassSurfaceDrawable.Style.FLOATING, useShader = false)
}
