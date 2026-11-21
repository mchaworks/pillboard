package dev.pillboard.keyboard

import android.graphics.RenderEffect
import android.graphics.Shader
import android.os.Build
import android.view.View
import kotlin.math.round

/**
 * Blur Replace for a whole view (a panel or a snapshot of the shell content), same scalar as [FxImage]:
 * [s] 0 = sharp / opaque / in place, 1 = blurred / transparent / shrunk / drifted. [sign] -1 leaves along [dir], +1 arrives along it.
 * The scale is half of the text's: a whole surface shrinking by 10% reads as a zoom, not as a swap.
 */
internal fun View.blurFx(s: Float, dir: BlurDir, sign: Int) {
    val c = s.coerceIn(0f, 1f)
    val k = 1f - c
    alpha = k * k
    val sc = 1f - (1f - BlurTextTokens.SCALE_MIN) * 0.5f * c
    scaleX = sc; scaleY = sc
    val d = -sign * BlurTextTokens.SHIFT_DP * 1.5f * resources.displayMetrics.density * c
    translationX = dir.mx * d
    translationY = dir.my * d
    if (Build.VERSION.SDK_INT >= 31) {
        val step = BlurTextTokens.BLUR_STEP_PX
        val r = round(BlurTextTokens.MAX_BLUR_DP * resources.displayMetrics.density * c / step) * step
        if ((tag as? Float) != r) {
            tag = r
            setRenderEffect(if (r > 0f) RenderEffect.createBlurEffect(r, r, Shader.TileMode.DECAL) else null)
        }
    }
}
