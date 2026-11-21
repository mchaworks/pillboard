package dev.pillboard.keyboard

import android.app.Activity
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.res.ResourcesCompat
import androidx.core.graphics.ColorUtils
import com.google.android.material.button.MaterialButton
import com.google.android.material.color.MaterialColors
import com.google.android.material.slider.LabelFormatter
import com.google.android.material.slider.Slider
import com.google.android.material.R as MR

/**
 * Shared design tokens + builders for every settings page, so pages can't drift apart.
 * Look: iOS inset-grouped lists (grey page, white/dark cards, colored icon tiles, large titles, uppercase section
 * labels) with Material 3 Expressive touches (dynamic color, big shapes, a switch whose thumb stretches, a gliding
 * segmented pill). Motion uses the keyboard's smooth ease: no springs.
 */
class SettingsUi(private val a: Activity) {
    // ---- tokens ----
    val night = a.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES
    val boxRadius = 26.dp.toFloat()
    val rowPad = 16.dp
    val tileSize = 30.dp
    /** Divider start for rows that lead with an icon tile (the divider begins under the text, like iOS Settings). */
    val tileInset get() = rowPad + tileSize + 14.dp
    private fun color(attr: Int, fb: Int) = MaterialColors.getColor(a, attr, fb)

    val pageBg get() = if (night) ColorUtils.blendARGB(color(MR.attr.colorSurface, 0xFF141218.toInt()), Color.BLACK, 0.45f)
        else color(MR.attr.colorSurfaceContainer, 0xFFF2F2F7.toInt())
    val surface get() = if (night) color(MR.attr.colorSurfaceContainerHigh, 0xFF2B2B2B.toInt())
        else color(MR.attr.colorSurfaceContainerLowest, Color.WHITE)
    val inputBg get() = pageBg
    val onSurface get() = color(MR.attr.colorOnSurface, Color.WHITE)
    val onSurfaceVar get() = color(MR.attr.colorOnSurfaceVariant, Color.LTGRAY)
    val primary get() = color(MR.attr.colorPrimary, Color.WHITE)
    val onPrimary get() = color(MR.attr.colorOnPrimary, Color.BLACK)
    val hairline get() = (onSurface and 0x00FFFFFF) or (0x1A shl 24)
    val error get() = color(MR.attr.colorError, Color.RED)
    private val switchOff get() = ColorUtils.blendARGB(surface, onSurface, if (night) 0.24f else 0.14f)
    private val segTrack get() = ColorUtils.blendARGB(surface, onSurface, if (night) 0.12f else 0.07f)
    private val segPill get() = if (night) ColorUtils.blendARGB(surface, onSurface, 0.30f) else Color.WHITE
    private val medium: Typeface get() = ResourcesCompat.getFont(a, R.font.gsr_medium) ?: Typeface.create("sans-serif-medium", Typeface.NORMAL)
    private val bold: Typeface get() = ResourcesCompat.getFont(a, R.font.gsr_bold) ?: Typeface.create("sans-serif", Typeface.BOLD)

    fun lp(bottom: Int = 0) = LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = bottom }

    // ---- structure ----
    /** iOS footnote-style section label. */
    fun section(title: String) = TextView(a).apply {
        text = title.uppercase(); textSize = 12.5f; letterSpacing = 0.05f; setTextColor(onSurfaceVar); typeface = medium
        setPadding(20.dp, 26.dp, 0, 8.dp)
    }

    /** One rounded card, rows inside, hairline dividers that start at [inset]. */
    fun box(rows: List<View>, inset: Int = rowPad): View = LinearLayout(a).apply {
        orientation = LinearLayout.VERTICAL
        background = GradientDrawable().apply { setColor(surface); cornerRadius = boxRadius }
        clipToOutline = true
        val divider = (onSurface and 0x00FFFFFF) or (0x16 shl 24)
        rows.forEachIndexed { i, v ->
            if (i > 0) addView(View(a).apply { setBackgroundColor(divider) },
                LinearLayout.LayoutParams(-1, 1.dp).apply { marginStart = inset })
            addView(v, LinearLayout.LayoutParams(-1, -2))
        }
    }

    /** A free-form card (status, test field): padded content in the same rounded surface. */
    fun card(content: View, h: Int = 16.dp, v: Int = 14.dp): View = box(listOf(content.apply { setPadding(h, v, h, v) }))

    /** Filled = accent pill; otherwise a tinted pill (accent text on a faint accent wash), like iOS. */
    fun button(label: String, filled: Boolean, compact: Boolean = false, onClick: () -> Unit) = MaterialButton(a).apply {
        text = label; setTextSize(TypedValue.COMPLEX_UNIT_SP, if (compact) 14f else 15f); isAllCaps = false; typeface = medium
        cornerRadius = 100.dp; insetTop = 0; insetBottom = 0; minHeight = 0; minimumHeight = 0; minWidth = 0; minimumWidth = 0
        stateListAnimator = null; elevation = 0f
        setPadding(if (compact) 16.dp else 18.dp, 0, if (compact) 16.dp else 18.dp, 0)
        backgroundTintList = ColorStateList.valueOf(if (filled) primary else ColorUtils.setAlphaComponent(primary, if (night) 0x38 else 0x24))
        setTextColor(if (filled) onPrimary else primary)
        setOnClickListener { onClick() }
    }

    fun badge(): TextView = TextView(a).apply {
        textSize = 14f; typeface = medium
        setTextColor(primary)
        setPadding(14.dp, 8.dp, 14.dp, 8.dp)
        background = GradientDrawable().apply { cornerRadius = 100.dp.toFloat(); setColor(ColorUtils.setAlphaComponent(primary, if (night) 0x38 else 0x24)) }
    }

    /** Large title (iOS): bold, big. */
    fun title(text: String, size: Float = 34f) = TextView(a).apply { this.text = text; textSize = size; setTextColor(onSurface); typeface = bold }
    fun caption(text: String) = TextView(a).apply { this.text = text; textSize = 15f; setTextColor(onSurfaceVar) }

    // ---- rows ----
    private fun texts(title: String, sub: String?) = LinearLayout(a).apply {
        orientation = LinearLayout.VERTICAL
        addView(TextView(a).apply { text = title; textSize = 17f; setTextColor(onSurface) })
        if (!sub.isNullOrEmpty()) addView(TextView(a).apply { text = sub; textSize = 13f; setTextColor(onSurfaceVar) },
            LinearLayout.LayoutParams(-2, -2).apply { topMargin = 1.dp })
    }

    private fun ripple(v: View) {
        val t = a.obtainStyledAttributes(intArrayOf(android.R.attr.selectableItemBackground))
        v.foreground = t.getDrawable(0); t.recycle()
    }

    private fun row() = LinearLayout(a).apply {
        orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
        minimumHeight = 56.dp; setPadding(rowPad, 11.dp, rowPad, 11.dp)
    }

    /** Rounded-square colored tile with a white glyph, the iOS Settings icon. */
    private fun iconTile(icon: Int, bg: Int): View = FrameLayout(a).apply {
        background = GradientDrawable().apply { setColor(bg); cornerRadius = 9.dp.toFloat() }
        addView(ImageView(a).apply { setImageResource(icon); imageTintList = ColorStateList.valueOf(Color.WHITE) },
            FrameLayout.LayoutParams(19.dp, 19.dp, Gravity.CENTER))
    }

    fun toggle(title: String, sub: String, key: String, def: Boolean, enabled: Boolean = true, onChange: ((Boolean) -> Unit)? = null): View {
        val r = row()
        r.addView(texts(title, sub), LinearLayout.LayoutParams(0, -2, 1f).apply { marginEnd = 14.dp })
        val sw = PillSwitch(a, primary, switchOff, Color.WHITE).apply {
            setChecked(enabled && Prefs.bool(a, key, def), false); isEnabled = enabled
            contentDescription = title
            onToggle = { v -> Prefs.setBool(a, key, v); onChange?.invoke(v) }
        }
        r.addView(sw, LinearLayout.LayoutParams(52.dp, 32.dp))
        if (enabled) { r.setOnClickListener { sw.toggle() }; ripple(r) } else r.alpha = 0.5f
        return r
    }

    /** Navigation / action row. [chevron] false for rows that open a dialog instead of a page. [icon]/[tile] add the colored tile. */
    fun link(title: String, sub: String?, chevron: Boolean = true, icon: Int = 0, tile: Int = 0, onClick: () -> Unit): View = row().apply {
        if (icon != 0) addView(iconTile(icon, tile), LinearLayout.LayoutParams(tileSize, tileSize).apply { marginEnd = 14.dp })
        addView(texts(title, sub), LinearLayout.LayoutParams(0, -2, 1f))
        if (chevron) addView(ImageView(a).apply {
            setImageResource(R.drawable.ic_chevron_right); imageTintList = ColorStateList.valueOf(onSurfaceVar); alpha = 0.55f
        }, LinearLayout.LayoutParams(20.dp, 20.dp))
        setOnClickListener { onClick() }; ripple(this)
    }

    fun info(title: String, value: String): View = row().apply {
        addView(TextView(a).apply { text = title; textSize = 17f; setTextColor(onSurface) }, LinearLayout.LayoutParams(0, -2, 1f))
        addView(TextView(a).apply { text = value; textSize = 16f; setTextColor(onSurfaceVar) })
    }

    /** Slider with a live readout, a gliding segmented control for the presets and a small reset shown only when changed. */
    fun slider(title: String, from: Int, to: Int, key: String, def: Int, presets: List<Pair<String, Int>>, onChange: () -> Unit): View =
        LinearLayout(a).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(rowPad, 14.dp, rowPad, 14.dp)
            val readout = TextView(a).apply { textSize = 15f; setTextColor(primary); typeface = medium }
            val s = Slider(a).apply {
                valueFrom = from.toFloat(); valueTo = to.toFloat(); stepSize = 1f
                isTickVisible = false; labelBehavior = LabelFormatter.LABEL_GONE
                trackHeight = 10.dp; thumbRadius = 13.dp; haloRadius = 22.dp
                value = Prefs.int(a, key, def).toFloat().coerceIn(valueFrom, valueTo)
            }
            val seg = Segmented(a, presets.map { it.first }, segTrack, segPill, onSurface, onSurfaceVar, medium).apply {
                onSelect = { i -> s.value = presets[i].second.toFloat() }
            }
            val reset = TextView(a).apply {
                text = "Reset"; textSize = 14f; setTextColor(primary); setPadding(14.dp, 4.dp, 0, 4.dp)
                setOnClickListener { s.value = def.toFloat() }
            }
            fun show(v: Int, animate: Boolean) {
                readout.text = "$v dp"; reset.visibility = if (v == def) View.GONE else View.VISIBLE
                seg.select(presets.indexOfFirst { it.second == v }, animate)
            }
            show(s.value.toInt(), false)
            s.addOnChangeListener { _, v, _ -> Prefs.setInt(a, key, v.toInt()); show(v.toInt(), true); onChange() }
            addView(LinearLayout(a).apply {
                gravity = Gravity.CENTER_VERTICAL
                addView(TextView(a).apply { text = title; textSize = 17f; setTextColor(onSurface) }, LinearLayout.LayoutParams(0, -2, 1f))
                addView(readout); addView(reset)
            })
            addView(s, LinearLayout.LayoutParams(-1, -2).apply { topMargin = 4.dp; bottomMargin = 6.dp })
            addView(seg, LinearLayout.LayoutParams(-1, -2))
        }

    fun accent(onChange: () -> Unit): View = LinearLayout(a).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(rowPad, 14.dp, rowPad, 16.dp)
        addView(TextView(a).apply { text = "Accent color"; textSize = 17f; setTextColor(onSurface) })
        val swatches = LinearLayout(a).apply { setPadding(0, 14.dp, 0, 0) }
        val custom = Prefs.PRESETS.size
        val hue = Slider(a).apply {
            valueFrom = 0f; valueTo = 359f; stepSize = 1f; isTickVisible = false
            labelBehavior = LabelFormatter.LABEL_GONE; trackHeight = 10.dp; thumbRadius = 13.dp; haloRadius = 22.dp
            value = Prefs.hue(a).toFloat()
        }
        fun refresh() {
            for (i in 0..custom) swatches.getChildAt(i).background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(if (i < custom) Prefs.PRESETS[i].second else Prefs.customColor(a))
                if (i == Prefs.preset(a)) setStroke(3.dp, onSurface)
            }
            hue.visibility = if (Prefs.preset(a) == custom) View.VISIBLE else View.GONE
        }
        for (i in 0..custom) swatches.addView(View(a).apply {
            contentDescription = if (i < custom) Prefs.PRESETS[i].first else "Custom"
            setOnClickListener { Prefs.setInt(a, "preset", i); refresh(); onChange() }
        }, LinearLayout.LayoutParams(36.dp, 36.dp).apply { marginEnd = 10.dp })
        hue.addOnChangeListener { _, v, _ -> Prefs.setInt(a, "hue", v.toInt()); refresh(); onChange() }
        refresh(); addView(swatches); addView(hue, lp())
    }

    /** iOS search field: a grey rounded bar with a magnifier. */
    fun searchField(hint: String): EditText = EditText(a).apply {
        this.hint = hint; textSize = 17f; maxLines = 1; isSingleLine = true
        setTextColor(onSurface); setHintTextColor(onSurfaceVar)
        setPadding(14.dp, 11.dp, 16.dp, 11.dp)
        background = GradientDrawable().apply { cornerRadius = 16.dp.toFloat(); setColor(surface) }
        compoundDrawablePadding = 10.dp
        androidx.core.content.ContextCompat.getDrawable(a, R.drawable.ic_search)?.mutate()?.let { d ->
            d.setBounds(0, 0, 20.dp, 20.dp); d.setTint(onSurfaceVar)
            setCompoundDrawablesRelative(d, null, null, null)
        }
    }

    fun testField(): Pair<View, EditText> {
        val input = EditText(a).apply {
            hint = "Type here to test…"; textSize = 17f
            setTextColor(onSurface); setHintTextColor(onSurfaceVar)
            minLines = 2; gravity = Gravity.TOP or Gravity.START
            setPadding(16.dp, 12.dp, 16.dp, 12.dp)
            background = GradientDrawable().apply { cornerRadius = 18.dp.toFloat(); setColor(inputBg) }
        }
        val wrap = LinearLayout(a).apply { setPadding(14.dp, 14.dp, 14.dp, 14.dp); addView(input, LinearLayout.LayoutParams(-1, -2)) }
        return wrap to input
    }
}
