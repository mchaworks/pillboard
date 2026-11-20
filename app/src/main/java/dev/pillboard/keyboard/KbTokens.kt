package dev.pillboard.keyboard

import android.content.Context
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.graphics.drawable.StateListDrawable
import android.view.Gravity
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
        const val KEY_R_DEFAULT = 12
        /** Shadow room around floating popups (a popup window clips anything outside its bounds). */
        val POPUP_SHADOW_PAD get() = 10.dp
    }

    private fun c(attr: Int, fb: Int): Int = MaterialColors.getColor(ctx, attr, fb)
    val night = ctx.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES

    // ---- geometry ----
    val containerRadius = 30.dp.toFloat()
    val stripRadius = 18.dp.toFloat()
    val popupRadius = 20.dp.toFloat()
    val specialRadiusBoost = 3.dp      // special keys: slightly rounder than letters
    val actionRadiusBoost = 8.dp       // Enter / active Shift: most expressive shape
    val pressRadiusBoost = 3.dp        // corner morph while pressed

    // ---- elevation ----
    val keyElevation = 1.5f * ctx.resources.displayMetrics.density
    val popupElevation = 6.dp.toFloat()

    // ---- motion (kept lightweight; input never waits on these) ----
    val pressMs = 65L
    val releaseMs = 230L
    val pressScale = 0.94f
    val releaseOvershoot = 1.7f
    val popupInMs = 100L

    // ---- color: glass belongs to the shell, keycaps stay solid tonal surfaces ----
    val background = c(MR.attr.colorSurfaceContainerLow, if (night) 0xFF1C1B1F.toInt() else 0xFFF3EDF7.toInt())
    val glassFill = ColorUtils.setAlphaComponent(background, if (night) 0xD8 else 0xE8)
    val glassStroke = ColorUtils.setAlphaComponent(c(MR.attr.colorOutlineVariant, if (night) Color.WHITE else Color.BLACK), if (night) 0x48 else 0x30)
    val glassHighlight = ColorUtils.setAlphaComponent(Color.WHITE, if (night) 0x24 else 0x66)
    val key = if (night) c(MR.attr.colorSurfaceContainerHighest, 0xFF36343B.toInt())
              else c(MR.attr.colorSurfaceContainerLowest, Color.WHITE)
    val keyText = c(MR.attr.colorOnSurface, if (night) Color.WHITE else Color.BLACK)
    private val seed: Int? = if (Prefs.dynamic(ctx)) null else Prefs.seedColor(ctx)

    val special = seed?.let { ColorUtils.blendARGB(key, it, 0.16f) } ?: c(MR.attr.colorSecondaryContainer, 0xFF4A4458.toInt())
    val specialText = seed?.let { keyText } ?: c(MR.attr.colorOnSecondaryContainer, Color.WHITE)
    val pressed = seed?.let { ColorUtils.blendARGB(key, it, 0.34f) } ?: c(MR.attr.colorPrimaryContainer, Color.LTGRAY)
    val pressedText = seed?.let { keyText } ?: c(MR.attr.colorOnPrimaryContainer, Color.BLACK)
    val accent = seed ?: c(MR.attr.colorPrimary, Color.BLUE)
    val onAccent = seed?.let { if (ColorUtils.calculateLuminance(it) > 0.5) Color.BLACK else Color.WHITE }
        ?: c(MR.attr.colorOnPrimary, Color.WHITE)
    val keyStroke = ColorUtils.setAlphaComponent(keyText, if (night) 0x22 else 0x18)

    // suggestion strip: a faint tonal wash of the text color over the glass, so it belongs to the shell
    val stripFill = ColorUtils.setAlphaComponent(keyText, if (night) 0x14 else 0x0E)
    val stripPressed = ColorUtils.setAlphaComponent(keyText, 0x24)
    val divider = ColorUtils.setAlphaComponent(keyText, 0x26)

    // floating popups sit over arbitrary app content, so they stay (almost) opaque for legibility
    val popupFill = ColorUtils.setAlphaComponent(key, 0xF2)

    /** Blend [c] toward [target] without touching its alpha. */
    private fun tone(c: Int, target: Int, r: Float) =
        ColorUtils.setAlphaComponent(ColorUtils.blendARGB(c or 0xFF000000.toInt(), target, r), Color.alpha(c))

    /** Very subtle top-to-bottom tonal sheen for a keycap (dark: lit from above, light: soft underside). */
    fun sheen(base: Int): IntArray =
        if (night) intArrayOf(tone(base, Color.WHITE, 0.07f), base) else intArrayOf(base, tone(base, Color.BLACK, 0.05f))

    // ---- shared surfaces ----
    /** Floating glass shell: tonal gradient, hairline outline and a soft specular edge along the top. */
    fun shell(fill: Int = glassFill): Drawable {
        val colors = if (night) intArrayOf(tone(fill, Color.WHITE, 0.06f), fill) else intArrayOf(fill, tone(fill, Color.BLACK, 0.03f))
        val body = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, colors).apply {
            cornerRadius = containerRadius
            setStroke(1.dp, glassStroke)
        }
        val edge = GradientDrawable(
            GradientDrawable.Orientation.LEFT_RIGHT,
            intArrayOf(ColorUtils.setAlphaComponent(glassHighlight, 0), glassHighlight, ColorUtils.setAlphaComponent(glassHighlight, 0)),
        )
        return LayerDrawable(arrayOf(body, edge)).apply {
            setLayerGravity(1, Gravity.TOP)
            setLayerHeight(1, 1.dp)
            setLayerInsetTop(1, 1.dp)
            setLayerInsetLeft(1, 30.dp)
            setLayerInsetRight(1, 30.dp)
        }
    }

    fun stripBackground(): Drawable = GradientDrawable().apply { setColor(stripFill); cornerRadius = stripRadius }

    fun stripItemBackground(): Drawable = StateListDrawable().apply {
        addState(intArrayOf(android.R.attr.state_pressed), GradientDrawable().apply {
            setColor(stripPressed); cornerRadius = stripRadius - 4.dp
        })
        addState(intArrayOf(), ColorDrawable(Color.TRANSPARENT))
    }

    fun popupBackground(): Drawable = GradientDrawable().apply {
        setColor(popupFill); cornerRadius = popupRadius; setStroke(1.dp, glassStroke)
    }
}
