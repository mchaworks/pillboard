package dev.pillboard.keyboard

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.google.android.material.button.MaterialButton
import com.google.android.material.color.MaterialColors
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.slider.Slider
import com.google.android.material.R as MR

/** Compact settings: small header, mini live preview, one grouped surface per section. */
class SettingsActivity : Activity() {

    private lateinit var col: LinearLayout
    private lateinit var status: TextView
    private lateinit var previewHolder: LinearLayout

    private fun color(attr: Int, fallback: Int) = MaterialColors.getColor(this, attr, fallback)
    private val surface get() = color(MR.attr.colorSurfaceContainerHigh, 0xFF2B2B2B.toInt())
    private val blurOk get() = Build.VERSION.SDK_INT >= 31 &&
        (getSystemService(WINDOW_SERVICE) as WindowManager).isCrossWindowBlurEnabled

    override fun onCreate(savedInstanceState: Bundle?) {
        setTheme(if (Prefs.dynamic(this)) R.style.Theme_Pillboard_App else R.style.Theme_Pillboard_App_Static)
        super.onCreate(savedInstanceState)
        col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(16.dp, 48.dp, 16.dp, 40.dp)
        }
        col.addView(header(), lp(bottom = 16.dp))
        previewHolder = LinearLayout(this)
        col.addView(previewHolder, lp(bottom = 8.dp))
        buildPreview()
        group(null, testField())

        group("Typing",
            toggle("Suggestions", "Word bar above the keys", "suggest", true),
            toggle("Auto-capitalization", "Capital letter at the start of sentences", "autocap", true),
            toggle("Double-space period", "Tap space twice to insert a period", "dblspace", true),
        )
        group("Appearance",
            toggle(
                "Material You colors",
                if (Build.VERSION.SDK_INT >= 31) "Follow your wallpaper accent" else "Needs Android 12+, using default palette",
                "dynamic", true, enabled = Build.VERSION.SDK_INT >= 31, onChange = { recreate() },
            ),
            *(if (Prefs.dynamic(this)) emptyArray() else arrayOf(accentRow())),
            toggle(
                "Blur background",
                if (blurOk) "Frosted glass behind the keyboard" else "Not available on this device",
                "blur", false, enabled = blurOk,
            ),
        )
        group("Feedback", toggle("Haptic feedback", "Vibrate on key press", "haptic", true))
        group("Layout", sliderRow("Key height", 42, 62, "key_h", 50))
        group("Advanced", resetRow())

        setContentView(ScrollView(this).apply { addView(col) })
    }

    override fun onResume() {
        super.onResume()
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        val enabled = imm.enabledInputMethodList.any { it.packageName == packageName }
        val active = Settings.Secure.getString(contentResolver, Settings.Secure.DEFAULT_INPUT_METHOD)
            ?.startsWith(packageName) == true
        status.text = when {
            active -> "●  Active keyboard"
            enabled -> "●  Enabled. Tap Switch to use it"
            else -> "●  Not enabled yet"
        }
    }

    // ---- building blocks ----
    private fun lp(bottom: Int = 0) = LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = bottom }

    private fun header(): View = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(8.dp, 0, 8.dp, 0)
        addView(TextView(context).apply {
            text = "Pillboard"; textSize = 28f
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        })
        addView(TextView(context).apply { text = "Your keyboard, your style."; textSize = 14f; alpha = 0.65f })
        status = TextView(context).apply {
            textSize = 13f; setTextColor(color(MR.attr.colorPrimary, Color.WHITE))
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            setPadding(0, 14.dp, 0, 10.dp)
        }
        addView(status)
        addView(LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(pill("Enable", color(MR.attr.colorPrimary, Color.WHITE), color(MR.attr.colorOnPrimary, Color.BLACK)) {
                startActivity(Intent(Settings.ACTION_INPUT_METHOD_SETTINGS))
            }, LinearLayout.LayoutParams(0, 40.dp, 1f).apply { marginEnd = 4.dp })
            addView(pill("Switch", color(MR.attr.colorSecondaryContainer, Color.DKGRAY), color(MR.attr.colorOnSecondaryContainer, Color.WHITE)) {
                (getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager).showInputMethodPicker()
            }, LinearLayout.LayoutParams(0, 40.dp, 1f).apply { marginStart = 4.dp })
        })
    }

    private fun pill(label: String, bg: Int, fg: Int, onClick: () -> Unit) = MaterialButton(this).apply {
        text = label; textSize = 14f; isAllCaps = false
        cornerRadius = 100.dp; insetTop = 0; insetBottom = 0; minimumHeight = 0; minHeight = 0
        backgroundTintList = ColorStateList.valueOf(bg); setTextColor(fg)
        setOnClickListener { onClick() }
    }

    /** One rounded surface per section; rows separated by thin inset dividers. */
    private fun group(title: String?, vararg rows: View) {
        if (title != null) col.addView(TextView(this).apply {
            text = title; textSize = 16f; setTextColor(color(MR.attr.colorPrimary, Color.WHITE))
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            setPadding(12.dp, 24.dp, 0, 8.dp)
        })
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply { setColor(surface); cornerRadius = 24.dp.toFloat() }
        }
        rows.forEachIndexed { i, v ->
            if (i > 0) box.addView(View(this).apply {
                setBackgroundColor(color(MR.attr.colorOutlineVariant, 0x33FFFFFF))
            }, LinearLayout.LayoutParams(-1, 1.dp).apply { marginStart = 20.dp; marginEnd = 20.dp })
            box.addView(v, lp())
        }
        col.addView(box, lp())
    }

    private fun row(title: String, sub: String, titleColor: Int? = null): Pair<LinearLayout, LinearLayout> {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = 72.dp
            setPadding(20.dp, 12.dp, 16.dp, 12.dp)
        }
        val texts = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        texts.addView(TextView(this).apply { text = title; textSize = 16f; titleColor?.let { setTextColor(it) } })
        texts.addView(TextView(this).apply { text = sub; textSize = 14f; alpha = 0.65f })
        row.addView(texts, LinearLayout.LayoutParams(0, -2, 1f))
        return row to texts
    }

    private fun toggle(title: String, sub: String, key: String, def: Boolean, enabled: Boolean = true, onChange: ((Boolean) -> Unit)? = null): View {
        val r = row(title, sub).first
        val sw = MaterialSwitch(this).apply {
            isChecked = enabled && Prefs.bool(this@SettingsActivity, key, def)
            isEnabled = enabled
            setOnCheckedChangeListener { _, v -> Prefs.setBool(this@SettingsActivity, key, v); onChange?.invoke(v) }
        }
        r.addView(sw)
        if (enabled) r.setOnClickListener { sw.toggle() } else r.alpha = 0.5f
        return r
    }

    /** Destructive action lives in Advanced; red only on the row text and the confirm dialog. */
    private fun resetRow(): View = row("Reset all settings", "Themes, layout and preferences return to default",
        color(MR.attr.colorError, Color.RED)).first.apply {
        setOnClickListener {
            AlertDialog.Builder(this@SettingsActivity).setTitle("Reset all keyboard settings?")
                .setMessage("Themes, layout and preferences will return to their defaults.")
                .setPositiveButton("Reset") { _, _ -> Prefs.clear(this@SettingsActivity); recreate() }
                .setNegativeButton("Cancel", null).show()
        }
    }

    private fun sliderRow(title: String, from: Int, to: Int, key: String, def: Int): View =
        LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(20.dp, 14.dp, 20.dp, 6.dp)
            val valueText = TextView(context).apply { textSize = 14f; alpha = 0.65f }
            val slider = Slider(context).apply {
                valueFrom = from.toFloat(); valueTo = to.toFloat(); stepSize = 1f
                isTickVisible = false
                value = Prefs.int(context, key, def).toFloat().coerceIn(valueFrom, valueTo)
            }
            val reset = MaterialButton(context, null, MR.attr.borderlessButtonStyle).apply {
                text = "Reset"; textSize = 13f; isAllCaps = false
                minWidth = 0; minimumWidth = 0; minHeight = 0; minimumHeight = 0
                insetTop = 0; insetBottom = 0
                setPadding(12.dp, 4.dp, 12.dp, 4.dp)
                setOnClickListener { slider.value = def.toFloat() }
            }
            fun show(v: Int) {
                valueText.text = "$v dp"
                reset.visibility = if (v == def) View.INVISIBLE else View.VISIBLE
            }
            show(slider.value.toInt())
            slider.addOnChangeListener { _, v, _ ->
                Prefs.setInt(context, key, v.toInt()); show(v.toInt()); buildPreview()
            }
            addView(LinearLayout(context).apply {
                gravity = Gravity.CENTER_VERTICAL
                addView(TextView(context).apply { text = title; textSize = 16f }, LinearLayout.LayoutParams(0, -2, 1f))
                addView(valueText); addView(reset)
            })
            addView(slider)
        }

    private fun accentRow(): View = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(20.dp, 14.dp, 20.dp, 14.dp)
        addView(TextView(context).apply { text = "Accent color"; textSize = 16f })
        val swatches = LinearLayout(context).apply { setPadding(0, 12.dp, 0, 0) }
        val ring = color(MR.attr.colorOnSurface, Color.WHITE)
        fun refresh() {
            for (i in 0 until swatches.childCount) swatches.getChildAt(i).background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Prefs.PRESETS[i].second)
                if (i == Prefs.preset(context)) setStroke(3.dp, ring)
            }
        }
        Prefs.PRESETS.forEachIndexed { i, (name, _) ->
            swatches.addView(View(context).apply {
                contentDescription = name
                setOnClickListener { Prefs.setInt(context, "preset", i); refresh(); buildPreview() }
            }, LinearLayout.LayoutParams(44.dp, 44.dp).apply { marginEnd = 14.dp })
        }
        refresh()
        addView(swatches)
    }

    /** Live preview built from the same KeyView + KbTokens the real keyboard uses (compact scale). */
    private fun buildPreview() {
        val t = KbTokens(this)
        val h = (Prefs.int(this, "key_h", 50) * 0.62f).toInt().dp
        previewHolder.removeAllViews()
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(4.dp, 6.dp, 4.dp, 6.dp)
            background = GradientDrawable().apply { setColor(t.background); cornerRadius = 20.dp.toFloat() }
        }
        fun row(keys: List<Pair<String, Float>>, special: Set<String> = emptySet()) = LinearLayout(this).apply {
            keys.forEach { (k, w) ->
                addView(KeyView(context, k, if (k in special) KeyStyle.SPECIAL else KeyStyle.LETTER, t, false) {}.apply { textSize = 14f },
                    LinearLayout.LayoutParams(0, h, w).apply { setMargins(2.dp, 2.dp, 2.dp, 2.dp) })
            }
        }
        card.addView(row("qwertyuiop".map { "$it" to 1f }))
        card.addView(row("asdfghjkl".map { "$it" to 1f }))
        card.addView(row(listOf("⇧" to 1.5f) + "zxcvbnm".map { "$it" to 1f } + ("⌫" to 1.5f), setOf("⇧", "⌫")))
        previewHolder.addView(card, LinearLayout.LayoutParams(-1, -2))
    }

    private fun testField(): View = LinearLayout(this).apply {
        setPadding(20.dp, 4.dp, 20.dp, 4.dp)
        addView(EditText(context).apply { hint = "Tap here to try the keyboard…"; textSize = 16f }, LinearLayout.LayoutParams(-1, -2))
    }
}
