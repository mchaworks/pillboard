package dev.pillboard.keyboard

import android.content.Context
import android.content.res.Configuration
import android.graphics.Color
import androidx.core.graphics.ColorUtils
import com.google.android.material.color.MaterialColors
import com.google.android.material.R as MR

/** One place for every keyboard color. Resolved from the (dynamic or static) Material theme of [ctx]. */
class KbTokens(private val ctx: Context) {
    private fun c(attr: Int, fb: Int): Int = MaterialColors.getColor(ctx, attr, fb)
    private val night = ctx.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES

    // Liquid Expressive: glass belongs to the outer shell; keycaps remain readable tonal surfaces.
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
}
