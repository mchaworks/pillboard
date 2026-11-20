package dev.pillboard.keyboard

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.google.android.material.button.MaterialButton
import com.google.android.material.color.MaterialColors
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.slider.Slider

class SettingsActivity : Activity() {

    private lateinit var col: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(20.dp, 64.dp, 20.dp, 56.dp)
        }
        col.addView(TextView(this).apply { text = "Pillboard"; textSize = 34f; setPadding(4.dp, 0, 0, 4.dp) })
        col.addView(TextView(this).apply { text = "Rounded. Expressive. A little iOS."; alpha = 0.7f; setPadding(4.dp, 0, 0, 20.dp) })

        card {
            button("Enable keyboard") { startActivity(Intent(Settings.ACTION_INPUT_METHOD_SETTINGS)) }
            button("Switch keyboard") {
                (getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager).showInputMethodPicker()
            }
        }
        card {
            toggle("Haptic feedback", "Vibrate on key press", "haptic", true)
            toggle("Suggestions", "Show the word bar above the keys", "suggest", true)
            toggle("Auto-capitalization", "Capitalize the first letter of sentences", "autocap", true)
            toggle("Double-space period", "Tap space twice to insert \". \"", "dblspace", true)
        }
        card {
            toggle("Blur background", "Experimental. Android 12+ with window blur enabled", "blur", false)
            sliderRow("Key height", 42, 62, "key_h", 50)
        }
        card {
            addView(EditText(this@SettingsActivity).apply {
                hint = "Type here to test…"
                setPadding(8.dp, 8.dp, 8.dp, 8.dp)
            })
        }
        setContentView(ScrollView(this).apply { addView(col) })
    }

    // ---- tiny UI helpers: Gboard-style rounded cards ----
    private fun card(build: LinearLayout.() -> Unit) {
        val bg = GradientDrawable().apply {
            cornerRadius = 28.dp.toFloat()
            setColor(MaterialColors.getColor(this@SettingsActivity,
                com.google.android.material.R.attr.colorSurfaceContainerHigh, 0xFF2B2B2B.toInt()))
        }
        col.addView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = bg
            setPadding(16.dp, 12.dp, 16.dp, 12.dp)
            build()
        }, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = 12.dp })
    }

    private fun LinearLayout.button(label: String, onClick: () -> Unit) {
        addView(MaterialButton(this@SettingsActivity).apply {
            text = label
            setOnClickListener { onClick() }
        })
    }

    private fun LinearLayout.toggle(title: String, sub: String, key: String, def: Boolean) {
        val row = LinearLayout(this@SettingsActivity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 8.dp, 0, 8.dp)
        }
        val texts = LinearLayout(this@SettingsActivity).apply { orientation = LinearLayout.VERTICAL }
        texts.addView(TextView(this@SettingsActivity).apply { text = title; textSize = 17f })
        texts.addView(TextView(this@SettingsActivity).apply { text = sub; textSize = 13f; alpha = 0.65f })
        row.addView(texts, LinearLayout.LayoutParams(0, -2, 1f))
        row.addView(MaterialSwitch(this@SettingsActivity).apply {
            isChecked = Prefs.bool(this@SettingsActivity, key, def)
            setOnCheckedChangeListener { _, v -> Prefs.setBool(this@SettingsActivity, key, v) }
        })
        addView(row)
    }

    private fun LinearLayout.sliderRow(title: String, from: Int, to: Int, key: String, def: Int) {
        addView(TextView(this@SettingsActivity).apply { text = "$title (dp)"; textSize = 17f; setPadding(0, 8.dp, 0, 0) })
        addView(Slider(this@SettingsActivity).apply {
            valueFrom = from.toFloat(); valueTo = to.toFloat(); stepSize = 2f
            value = Prefs.int(this@SettingsActivity, key, def).toFloat().coerceIn(valueFrom, valueTo)
            addOnChangeListener { _, v, _ -> Prefs.setInt(this@SettingsActivity, key, v.toInt()) }
        })
    }
}
