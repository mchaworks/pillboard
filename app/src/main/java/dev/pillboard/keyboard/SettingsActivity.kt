package dev.pillboard.keyboard

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.google.android.material.button.MaterialButton

class SettingsActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24.dp, 72.dp, 24.dp, 24.dp)
        }
        col.addView(TextView(this).apply { text = "Pillboard"; textSize = 34f })
        col.addView(TextView(this).apply {
            text = "Rounded. Expressive. A little iOS.\n\n1. Enable the keyboard\n2. Switch to it\n3. Try it below"
            textSize = 16f
            setPadding(0, 12.dp, 0, 24.dp)
        })
        col.addView(MaterialButton(this).apply {
            text = "Enable keyboard"
            setOnClickListener { startActivity(Intent(Settings.ACTION_INPUT_METHOD_SETTINGS)) }
        })
        col.addView(MaterialButton(this).apply {
            text = "Switch keyboard"
            setOnClickListener {
                (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager).showInputMethodPicker()
            }
        })
        col.addView(EditText(this).apply { hint = "Type here to test…"; setPadding(0, 24.dp, 0, 24.dp) })
        setContentView(ScrollView(this).apply { addView(col) })
    }
}
