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

/** Material 3 Expressive-style settings: tonal hero, grouped list with varied corner radii, pill buttons. */
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
            setPadding(16.dp, 56.dp, 16.dp, 56.dp)
        }
        col.addView(hero(), lp(bottom = 8.dp))
        previewHolder = LinearLayout(this)
        col.addView(previewHolder, lp(bottom = 4.dp))
        buildPreview()

        group("Typing",
            toggle("Suggestions", "Word bar above the keys", "suggest", true),
            toggle("Auto-capitalization", "Capital letter at the start of sentences", "autocap", true),
            toggle("Double-space period", "Tap space twice to insert a period", "dblspace", true),
        )
        group("Look & feel",
            toggle(
                "Material You colors",
                if (Build.VERSION.SDK_INT >= 31) "Follow your wallpaper accent" else "Needs Android 12+, using default palette",
                "dynamic", true, enabled = Build.VERSION.SDK_INT >= 31, onChange = { recreate() },
            ),
            *(if (Prefs.dynamic(this)) emptyArray() else arrayOf(accentRow())),
            toggle("Haptic feedback", "Vibrate on key press", "haptic", true),
            toggle(
                "Blur background",
                if (blurOk) "Frosted glass behind the keyboard" else "Not available on this device",
                "blur", false, enabled = blurOk,
            ),
            sliderRow("Key height", 42, 62, "key_h", 50),
        )
        group("Try it", testField())
        col.addView(pill("Reset all settings", color(MR.attr.colorErrorContainer, Color.DKGRAY), color(MR.attr.colorOnErrorContainer, Color.WHITE)) {
            AlertDialog.Builder(this).setTitle("Reset all settings?").setMessage("Everything goes back to default.")
                .setPositiveButton("Reset") { _, _ -> Prefs.clear(this); recreate() }
                .setNegativeButton("Cancel", null).show()
        }, LinearLayout.LayoutParams(-1, 56.dp).apply { topMargin = 28.dp })

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

    private fun hero(): View {
        val onC = color(MR.attr.colorOnPrimaryContainer, Color.WHITE)
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(28.dp, 32.dp, 28.dp, 24.dp)
            background = GradientDrawable().apply {
                cornerRadius = 36.dp.toFloat()
                setColor(color(MR.attr.colorPrimaryContainer, 0xFF4F378B.toInt()))
            }
            addView(TextView(context).apply {
                text = "Pillboard"; textSize = 38f; setTextColor(onC)
                typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            })
            addView(TextView(context).apply {
                text = "Rounded. Expressive. A little iOS."; textSize = 15f; setTextColor(onC); alpha = 0.75f
                setPadding(0, 4.dp, 0, 16.dp)
            })
            status = TextView(context).apply {
                textSize = 14f; setTextColor(onC)
                typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                setPadding(0, 0, 0, 20.dp)
            }
            addView(status)
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                addView(pill("Enable", color(MR.attr.colorPrimary, Color.WHITE), color(MR.attr.colorOnPrimary, Color.BLACK)) {
                    startActivity(Intent(Settings.ACTION_INPUT_METHOD_SETTINGS))
                }, LinearLayout.LayoutParams(0, 56.dp, 1f).apply { marginEnd = 4.dp })
                addView(pill("Switch", color(MR.attr.colorSecondaryContainer, Color.DKGRAY), color(MR.attr.colorOnSecondaryContainer, Color.WHITE)) {
                    (getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager).showInputMethodPicker()
                }, LinearLayout.LayoutParams(0, 56.dp, 1f).apply { marginStart = 4.dp })
            })
        }
    }

    private fun pill(label: String, bg: Int, fg: Int, onClick: () -> Unit) = MaterialButton(this).apply {
        text = label; textSize = 16f; isAllCaps = false
        cornerRadius = 100.dp; insetTop = 0; insetBottom = 0
        backgroundTintList = ColorStateList.valueOf(bg); setTextColor(fg)
        setOnClickListener { onClick() }
    }

    /** Header + rows as separate containers: big radius on the outer corners, small between (M3 Expressive grouping). */
    private fun group(title: String, vararg rows: View) {
        col.addView(TextView(this).apply {
            text = title; textSize = 14f; setTextColor(color(MR.attr.colorPrimary, Color.WHITE))
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            setPadding(16.dp, 24.dp, 0, 8.dp)
        })
        val big = 26.dp.toFloat(); val small = 6.dp.toFloat()
        rows.forEachIndexed { i, v ->
            val t = if (i == 0) big else small
            val b = if (i == rows.lastIndex) big else small
            v.background = GradientDrawable().apply {
                setColor(surface); cornerRadii = floatArrayOf(t, t, t, t, b, b, b, b)
            }
            col.addView(v, lp(bottom = if (i == rows.lastIndex) 0 else 3.dp))
        }
    }

    private fun toggle(title: String, sub: String, key: String, def: Boolean, enabled: Boolean = true, onChange: ((Boolean) -> Unit)? = null): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(22.dp, 16.dp, 18.dp, 16.dp)
        }
        val texts = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        texts.addView(TextView(this).apply { text = title; textSize = 17f })
        texts.addView(TextView(this).apply { text = sub; textSize = 13f; alpha = 0.65f })
        row.addView(texts, LinearLayout.LayoutParams(0, -2, 1f))
        val sw = MaterialSwitch(this).apply {
            isChecked = enabled && Prefs.bool(this@SettingsActivity, key, def)
            isEnabled = enabled
            setOnCheckedChangeListener { _, v -> Prefs.setBool(this@SettingsActivity, key, v); onChange?.invoke(v) }
        }
        row.addView(sw)
        if (enabled) row.setOnClickListener { sw.toggle() } else row.alpha = 0.5f
        return row
    }

    private fun sliderRow(title: String, from: Int, to: Int, key: String, def: Int): View =
        LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(22.dp, 16.dp, 22.dp, 8.dp)
            val label = TextView(context).apply { textSize = 17f }
            val slider = Slider(context).apply {
                valueFrom = from.toFloat(); valueTo = to.toFloat(); stepSize = 2f
                value = Prefs.int(context, key, def).toFloat().coerceIn(valueFrom, valueTo)
            }
            fun show(v: Int) { label.text = "$title: $v dp" }
            show(slider.value.toInt())
            slider.addOnChangeListener { _, v, _ ->
                Prefs.setInt(context, key, v.toInt()); show(v.toInt()); buildPreview()
            }
            val reset = MaterialButton(context, null, MR.attr.borderlessButtonStyle).apply {
                text = "Reset"; isAllCaps = false
                setOnClickListener { slider.value = def.toFloat() }
            }
            addView(LinearLayout(context).apply {
                gravity = Gravity.CENTER_VERTICAL
                addView(label, LinearLayout.LayoutParams(0, -2, 1f)); addView(reset)
            })
            addView(slider)
        }

    private fun accentRow(): View = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(22.dp, 16.dp, 22.dp, 16.dp)
        addView(TextView(context).apply { text = "Accent color"; textSize = 17f })
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

    /** Live preview built from the same KeyView + KbTokens the real keyboard uses. */
    private fun buildPreview() {
        val t = KbTokens(this)
        val h = Prefs.int(this, "key_h", 50).dp
        previewHolder.removeAllViews()
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(6.dp, 8.dp, 6.dp, 8.dp)
            background = GradientDrawable().apply { setColor(t.background); cornerRadius = 28.dp.toFloat() }
        }
        fun row(keys: List<Pair<String, Float>>, special: Set<String> = emptySet()) = LinearLayout(this).apply {
            keys.forEach { (k, w) ->
                addView(KeyView(context, k, if (k in special) KeyStyle.SPECIAL else KeyStyle.LETTER, t, false) {},
                    LinearLayout.LayoutParams(0, h, w).apply { setMargins(3.dp, 4.dp, 3.dp, 4.dp) })
            }
        }
        card.addView(row("qwertyuiop".map { "$it" to 1f }))
        card.addView(row("asdfghjkl".map { "$it" to 1f }))
        card.addView(row(listOf("⇧" to 1.5f) + "zxcvbnm".map { "$it" to 1f } + ("⌫" to 1.5f), setOf("⇧", "⌫")))
        previewHolder.addView(card, LinearLayout.LayoutParams(-1, -2))
    }

    private fun testField(): View = LinearLayout(this).apply {
        setPadding(22.dp, 8.dp, 22.dp, 8.dp)
        addView(EditText(context).apply { hint = "Type here to test…" }, LinearLayout.LayoutParams(-1, -2))
    }
}
