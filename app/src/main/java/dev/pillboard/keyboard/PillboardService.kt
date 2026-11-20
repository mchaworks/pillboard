package dev.pillboard.keyboard

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.inputmethodservice.InputMethodService
import android.os.Build
import android.os.SystemClock
import android.text.InputType
import android.view.ContextThemeWrapper
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat

class PillboardService : InputMethodService() {

    private enum class Shift { OFF, ON, LOCK }

    private var shift = Shift.OFF
    private var symbols = false
    private var lastShiftTap = 0L
    private var root: LinearLayout? = null
    private var strip: LinearLayout? = null
    private var navInset = 0
    private lateinit var themed: Context

    override fun onCreateInputView(): View {
        themed = ContextThemeWrapper(this, R.style.Theme_Pillboard)
        val r = LinearLayout(themed).apply { orientation = LinearLayout.VERTICAL }
        root = r
        // Keep keys clear of the system navigation bar (fixes overlap on edge-to-edge Android 15+)
        ViewCompat.setOnApplyWindowInsetsListener(r) { _, insets ->
            navInset = insets.getInsets(WindowInsetsCompat.Type.systemBars()).bottom
            applyPadding()
            insets
        }
        applyPadding()
        applyBlur()
        render()
        return r
    }

    override fun onStartInputView(info: EditorInfo?, restarting: Boolean) {
        super.onStartInputView(info, restarting)
        symbols = false
        autoCap()
        applyBlur()
        render()
        root?.let { ViewCompat.requestApplyInsets(it) }
    }

    private fun applyPadding() {
        root?.setPadding(4.dp, 6.dp, 4.dp, 8.dp + navInset)
    }

    /** Experimental: needs Android 12+ and "window blurs" enabled on the device. */
    private fun applyBlur() {
        val on = Prefs.bool(this, "blur", false)
        val base = themed.getColor(R.color.kb_bg)
        root?.setBackgroundColor(if (on) (base and 0x00FFFFFF) or (0xB3 shl 24) else base)
        if (Build.VERSION.SDK_INT >= 31) {
            window.window?.let { w ->
                if (on) {
                    w.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
                    w.setBackgroundBlurRadius(60.dp)
                } else w.setBackgroundBlurRadius(0)
            }
        }
    }

    // ---- layout ----
    private fun render() {
        val r = root ?: return
        r.removeAllViews()
        val keyH = Prefs.int(this, "key_h", 50).dp

        strip = if (Prefs.bool(this, "suggest", true) && suggestionsAllowed()) buildStrip().also { r.addView(it) } else null

        if (!symbols) {
            r.addView(rowOf { chars("qwertyuiop", keyH) })
            r.addView(rowOf { gap(0.5f); chars("asdfghjkl", keyH); gap(0.5f) })
            r.addView(rowOf {
                val glyph = when (shift) { Shift.OFF -> "⇧"; Shift.ON -> "⬆"; Shift.LOCK -> "⇪" }
                key(glyph, keyH, 1.5f, if (shift == Shift.OFF) KeyStyle.SPECIAL else KeyStyle.LETTER) { onShift() }
                chars("zxcvbnm", keyH)
                key("⌫", keyH, 1.5f, KeyStyle.SPECIAL, repeat = true) { backspace() }
            })
        } else {
            r.addView(rowOf { chars("1234567890", keyH) })
            r.addView(rowOf { chars("-/:;()$&@\"", keyH) })
            r.addView(rowOf {
                gap(1.5f)
                chars(".,?!'", keyH, 1.4f)
                key("⌫", keyH, 1.5f, KeyStyle.SPECIAL, repeat = true) { backspace() }
            })
        }
        r.addView(rowOf {
            key(if (symbols) "ABC" else "123", keyH, 1.3f, KeyStyle.SPECIAL) { symbols = !symbols; render() }
            key("🌐", keyH, 1f, KeyStyle.SPECIAL) { nextKeyboard() }
            key("space", keyH, 4.6f) { space() }
            val action = currentInputEditorInfo?.imeOptions?.and(EditorInfo.IME_MASK_ACTION) ?: 0
            val hasAction = action != EditorInfo.IME_ACTION_NONE && action != EditorInfo.IME_ACTION_UNSPECIFIED
            val name = when (action) {
                EditorInfo.IME_ACTION_GO -> "go"
                EditorInfo.IME_ACTION_SEARCH -> "search"
                EditorInfo.IME_ACTION_SEND -> "send"
                EditorInfo.IME_ACTION_DONE -> "done"
                EditorInfo.IME_ACTION_NEXT -> "next"
                else -> "return"
            }
            key(name, keyH, 1.6f, if (hasAction) KeyStyle.ACCENT else KeyStyle.SPECIAL) { enter() }
        })
        updateStrip()
    }

    private fun rowOf(build: LinearLayout.() -> Unit) = LinearLayout(themed).apply {
        orientation = LinearLayout.HORIZONTAL
        layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT)
        build()
    }

    private fun LinearLayout.key(
        label: String, h: Int, w: Float = 1f, style: KeyStyle = KeyStyle.LETTER,
        repeat: Boolean = false, onKey: () -> Unit,
    ) {
        val lp = LinearLayout.LayoutParams(0, h, w).apply { setMargins(3.dp, 4.dp, 3.dp, 4.dp) }
        addView(KeyView(themed, label, style, repeat, onKey), lp)
    }

    private fun LinearLayout.gap(w: Float) = addView(View(themed), LinearLayout.LayoutParams(0, 1, w))

    private fun LinearLayout.chars(s: String, h: Int, w: Float = 1f) {
        for (c in s) {
            val shown = if (shift != Shift.OFF && !symbols) c.uppercaseChar() else c
            key(shown.toString(), h, w) { type(shown.toString()) }
        }
    }

    // ---- suggestion strip (iOS-style, 3 slots) ----
    private fun suggestionsAllowed(): Boolean {
        val t = currentInputEditorInfo?.inputType ?: return true
        if (t and InputType.TYPE_MASK_CLASS != InputType.TYPE_CLASS_TEXT) return false
        return when (t and InputType.TYPE_MASK_VARIATION) {
            InputType.TYPE_TEXT_VARIATION_PASSWORD,
            InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD,
            InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD -> false
            else -> true
        }
    }

    private fun buildStrip() = LinearLayout(themed).apply {
        orientation = LinearLayout.HORIZONTAL
        layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, 40.dp)
        repeat(3) {
            addView(TextView(themed).apply {
                gravity = Gravity.CENTER
                textSize = 16f
                maxLines = 1
                setTextColor(themed.getColor(R.color.kb_key_text))
            }, LinearLayout.LayoutParams(0, MATCH_PARENT, 1f))
        }
    }

    private fun currentWord(): String {
        val before = currentInputConnection?.getTextBeforeCursor(30, 0)?.toString() ?: ""
        return before.takeLastWhile { it.isLetter() || it == '\'' }
    }

    private fun updateStrip() {
        val s = strip ?: return
        val word = currentWord()
        val list = Suggestions.forPrefix(word)
        for (i in 0 until 3) {
            val tv = s.getChildAt(i) as TextView
            val sug = list.getOrNull(i)
            tv.text = sug ?: ""
            tv.setOnClickListener(if (sug == null) null else View.OnClickListener {
                currentInputConnection?.let { ic ->
                    ic.deleteSurroundingText(word.length, 0)
                    ic.commitText("$sug ", 1)
                }
                afterEdit()
            })
        }
    }

    // ---- actions ----
    private fun type(s: String) {
        currentInputConnection?.commitText(s, 1)
        afterEdit()
    }

    private fun backspace() {
        sendDownUpKeyEvents(KeyEvent.KEYCODE_DEL)
        afterEdit()
    }

    /** iOS-style: double space becomes ". " */
    private fun space() {
        val ic = currentInputConnection ?: return
        val before = ic.getTextBeforeCursor(2, 0)?.toString() ?: ""
        if (Prefs.bool(this, "dblspace", true) && before.length == 2 && before[1] == ' ' && before[0].isLetterOrDigit()) {
            ic.deleteSurroundingText(1, 0)
            ic.commitText(". ", 1)
        } else {
            ic.commitText(" ", 1)
        }
        afterEdit()
    }

    private fun enter() {
        if (!sendDefaultEditorAction(true)) sendDownUpKeyEvents(KeyEvent.KEYCODE_ENTER)
        afterEdit()
    }

    private fun onShift() {
        val now = SystemClock.uptimeMillis()
        shift = when {
            shift == Shift.LOCK -> Shift.OFF
            now - lastShiftTap < 300 -> Shift.LOCK
            shift == Shift.ON -> Shift.OFF
            else -> Shift.ON
        }
        lastShiftTap = now
        render()
    }

    private fun nextKeyboard() {
        val imm = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
        val switched = Build.VERSION.SDK_INT >= 28 && switchToNextInputMethod(false)
        if (!switched) imm.showInputMethodPicker()
    }

    private fun autoCap() {
        if (shift == Shift.LOCK) return
        if (!Prefs.bool(this, "autocap", true)) { shift = Shift.OFF; return }
        val ei = currentInputEditorInfo
        val caps = if (ei != null && ei.inputType != 0)
            currentInputConnection?.getCursorCapsMode(ei.inputType) ?: 0 else 0
        shift = if (caps != 0) Shift.ON else Shift.OFF
    }

    private fun afterEdit() {
        val prev = shift
        autoCap()
        if (prev != shift) render() else updateStrip()
    }
}
