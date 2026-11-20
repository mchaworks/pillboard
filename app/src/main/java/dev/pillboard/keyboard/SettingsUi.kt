package dev.pillboard.keyboard

import android.app.Activity
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import androidx.core.content.res.ResourcesCompat
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import com.google.android.material.button.MaterialButton
import com.google.android.material.color.MaterialColors
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.slider.LabelFormatter
import com.google.android.material.slider.Slider
import com.google.android.material.R as MR

/** Shared design tokens + row builders for every settings page, so pages can't drift apart. */
class SettingsUi(private val a: Activity) {
    // ---- tokens ----
    val boxRadius = 26.dp.toFloat()
    val rowPad = 20.dp
    val rowMinH = 76.dp
    private fun color(attr: Int, fb: Int) = MaterialColors.getColor(a, attr, fb)
    val surface get() = color(MR.attr.colorSurfaceContainerHigh, 0xFF2B2B2B.toInt())
    val inputBg get() = color(MR.attr.colorSurfaceContainerLow, 0xFF1C1B1F.toInt())
    val onSurface get() = color(MR.attr.colorOnSurface, Color.WHITE)
    val onSurfaceVar get() = color(MR.attr.colorOnSurfaceVariant, Color.LTGRAY)
    val primary get() = color(MR.attr.colorPrimary, Color.WHITE)
    val error get() = color(MR.attr.colorError, Color.RED)
    private val medium: Typeface get() = ResourcesCompat.getFont(a, R.font.gsr_medium) ?: Typeface.create("sans-serif-medium", Typeface.NORMAL)

    fun lp(bottom: Int = 0) = LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = bottom }

    // ---- structure ----
    fun section(title: String) = TextView(a).apply {
        text = title; textSize = 18f; setTextColor(primary); typeface = medium
        setPadding(8.dp, 28.dp, 0, 8.dp)
    }

    /** One rounded container, rows inside, thin inset dividers. */
    fun box(rows: List<View>): View = LinearLayout(a).apply {
        orientation = LinearLayout.VERTICAL
        background = GradientDrawable().apply { setColor(surface); cornerRadius = boxRadius }
        clipToOutline = true
        val divider = (onSurface and 0x00FFFFFF) or (0x1F shl 24)
        rows.forEachIndexed { i, v ->
            if (i > 0) addView(View(a).apply { setBackgroundColor(divider) },
                LinearLayout.LayoutParams(-1, 1.dp).apply { marginStart = rowPad; marginEnd = rowPad })
            addView(v, LinearLayout.LayoutParams(-1, -2))
        }
    }

    fun button(label: String, filled: Boolean, compact: Boolean = false, onClick: () -> Unit) = MaterialButton(a).apply {
        text = label; setTextSize(TypedValue.COMPLEX_UNIT_SP, if (compact) 13f else 14f); isAllCaps = false
        cornerRadius = 14.dp; insetTop = 0; insetBottom = 0; minHeight = 0; minimumHeight = 0; minWidth = 0; minimumWidth = 0
        setPadding(if (compact) 14.dp else 16.dp, 0, if (compact) 14.dp else 16.dp, 0)
        val bg = if (filled) primary else color(MR.attr.colorSecondaryContainer, Color.DKGRAY)
        val fg = if (filled) color(MR.attr.colorOnPrimary, Color.BLACK) else color(MR.attr.colorOnSecondaryContainer, Color.WHITE)
        backgroundTintList = ColorStateList.valueOf(bg); setTextColor(fg)
        setOnClickListener { onClick() }
    }

    fun badge(): TextView = TextView(a).apply {
        textSize = 13f; typeface = medium
        setTextColor(color(MR.attr.colorOnSecondaryContainer, Color.WHITE))
        setPadding(12.dp, 6.dp, 12.dp, 6.dp)
        background = GradientDrawable().apply { cornerRadius = 100.dp.toFloat(); setColor(color(MR.attr.colorSecondaryContainer, Color.DKGRAY)) }
    }

    fun title(text: String, size: Float = 28f) = TextView(a).apply { this.text = text; textSize = size; setTextColor(onSurface); typeface = medium }
    fun caption(text: String) = TextView(a).apply { this.text = text; textSize = 14f; setTextColor(onSurfaceVar) }

    // ---- rows ----
    private fun texts(title: String, sub: String?) = LinearLayout(a).apply {
        orientation = LinearLayout.VERTICAL
        addView(TextView(a).apply { text = title; textSize = 16f; setTextColor(onSurface) })
        if (!sub.isNullOrEmpty()) addView(TextView(a).apply { text = sub; textSize = 14f; setTextColor(onSurfaceVar) })
    }

    private fun ripple(v: View) {
        val t = a.obtainStyledAttributes(intArrayOf(android.R.attr.selectableItemBackground))
        v.foreground = t.getDrawable(0); t.recycle()
    }

    private fun row() = LinearLayout(a).apply {
        orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
        minimumHeight = rowMinH; setPadding(rowPad, 12.dp, 16.dp, 12.dp)
    }

    fun toggle(title: String, sub: String, key: String, def: Boolean, enabled: Boolean = true, onChange: ((Boolean) -> Unit)? = null): View {
        val r = row()
        r.addView(texts(title, sub), LinearLayout.LayoutParams(0, -2, 1f).apply { marginEnd = 12.dp })
        val sw = MaterialSwitch(a).apply {
            isChecked = enabled && Prefs.bool(a, key, def); isEnabled = enabled
            setOnCheckedChangeListener { _, v -> Prefs.setBool(a, key, v); onChange?.invoke(v) }
        }
        r.addView(sw)
        if (enabled) { r.setOnClickListener { sw.toggle() }; ripple(r) } else r.alpha = 0.5f
        return r
    }

    /** Navigation / action row. [chevron] false for rows that open a dialog instead of a page. */
    fun link(title: String, sub: String?, chevron: Boolean = true, onClick: () -> Unit): View = row().apply {
        addView(texts(title, sub), LinearLayout.LayoutParams(0, -2, 1f))
        if (chevron) addView(TextView(a).apply { text = "›"; textSize = 22f; setTextColor(onSurfaceVar) })
        setOnClickListener { onClick() }; ripple(this)
    }

    fun info(title: String, value: String): View = row().apply {
        addView(TextView(a).apply { text = title; textSize = 16f; setTextColor(onSurface) }, LinearLayout.LayoutParams(0, -2, 1f))
        addView(TextView(a).apply { text = value; textSize = 14f; setTextColor(onSurfaceVar) })
    }

    /** Continuous slider: value readout, no tick dots, presets, small reset shown only when changed. */
    fun slider(title: String, from: Int, to: Int, key: String, def: Int, presets: List<Pair<String, Int>>, onChange: () -> Unit): View =
        LinearLayout(a).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(rowPad, 14.dp, rowPad, 10.dp)
            val readout = TextView(a).apply { textSize = 14f; setTextColor(primary); typeface = medium }
            val s = Slider(a).apply {
                valueFrom = from.toFloat(); valueTo = to.toFloat(); stepSize = 1f
                isTickVisible = false; labelBehavior = LabelFormatter.LABEL_GONE; trackHeight = 6.dp
                value = Prefs.int(a, key, def).toFloat().coerceIn(valueFrom, valueTo)
            }
            val reset = TextView(a).apply {
                text = "Reset"; textSize = 13f; setTextColor(onSurfaceVar); setPadding(12.dp, 4.dp, 0, 4.dp)
                setOnClickListener { s.value = def.toFloat() }
            }
            fun show(v: Int) { readout.text = "$v dp"; reset.visibility = if (v == def) View.GONE else View.VISIBLE }
            show(s.value.toInt())
            s.addOnChangeListener { _, v, _ -> Prefs.setInt(a, key, v.toInt()); show(v.toInt()); onChange() }
            addView(LinearLayout(a).apply {
                gravity = Gravity.CENTER_VERTICAL
                addView(TextView(a).apply { text = title; textSize = 16f; setTextColor(onSurface) }, LinearLayout.LayoutParams(0, -2, 1f))
                addView(readout); addView(reset)
            })
            addView(s, lp())
            addView(LinearLayout(a).apply {
                presets.forEach { (n, v) -> addView(button(n, false, compact = true) { s.value = v.toFloat() },
                    LinearLayout.LayoutParams(0, 36.dp, 1f).apply { marginEnd = 6.dp }) }
            }, lp())
        }

    fun accent(onChange: () -> Unit): View = LinearLayout(a).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(rowPad, 14.dp, rowPad, 14.dp)
        addView(TextView(a).apply { text = "Accent color"; textSize = 16f; setTextColor(onSurface) })
        val swatches = LinearLayout(a).apply { setPadding(0, 12.dp, 0, 0) }
        val custom = Prefs.PRESETS.size
        val hue = Slider(a).apply {
            valueFrom = 0f; valueTo = 359f; stepSize = 1f; isTickVisible = false
            labelBehavior = LabelFormatter.LABEL_GONE; trackHeight = 6.dp
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

    fun searchField(hint: String): EditText = EditText(a).apply {
        this.hint = hint; textSize = 16f; maxLines = 1; isSingleLine = true
        setTextColor(onSurface); setHintTextColor(onSurfaceVar)
        setPadding(18.dp, 12.dp, 18.dp, 12.dp)
        background = GradientDrawable().apply { cornerRadius = 100.dp.toFloat(); setColor(surface) }
    }

    fun testField(): Pair<View, EditText> {
        val input = EditText(a).apply {
            hint = "Type here to test…"; textSize = 16f
            setTextColor(onSurface); setHintTextColor(onSurfaceVar)
            minLines = 2; gravity = Gravity.TOP or Gravity.START
            setPadding(16.dp, 12.dp, 16.dp, 12.dp)
            background = GradientDrawable().apply { cornerRadius = 16.dp.toFloat(); setColor(inputBg) }
        }
        val wrap = LinearLayout(a).apply { setPadding(16.dp, 14.dp, 16.dp, 14.dp); addView(input, LinearLayout.LayoutParams(-1, -2)) }
        return wrap to input
    }
}
