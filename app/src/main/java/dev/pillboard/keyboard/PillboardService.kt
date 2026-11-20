package dev.pillboard.keyboard

import android.content.Context
import android.inputmethodservice.InputMethodService
import android.os.Build
import android.os.SystemClock
import android.view.ContextThemeWrapper
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.LinearLayout

class PillboardService : InputMethodService() {

    private enum class Shift { OFF, ON, LOCK }

    private var shift = Shift.OFF
    private var symbols = false
    private var lastShiftTap = 0L
    private var root: LinearLayout? = null
    private lateinit var themed: Context

    override fun onCreateInputView(): View {
        themed = ContextThemeWrapper(this, R.style.Theme_Pillboard)
        val r = LinearLayout(themed).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(themed.getColor(R.color.kb_bg))
            setPadding(4.dp, 6.dp, 4.dp, 8.dp)
        }
        root = r
        render()
        return r
    }

    override fun onStartInputView(info: EditorInfo?, restarting: Boolean) {
        super.onStartInputView(info, restarting)
        symbols = false
        autoCap()
        render()
    }

    // ---- layout ----
    private fun render() {
        val r = root ?: return
        r.removeAllViews()
        if (!symbols) {
            r.addView(rowOf { chars("qwertyuiop") })
            r.addView(rowOf { gap(0.5f); chars("asdfghjkl"); gap(0.5f) })
            r.addView(rowOf {
                val glyph = when (shift) { Shift.OFF -> "⇧"; Shift.ON -> "⬆"; Shift.LOCK -> "⇪" }
                key(glyph, 1.5f, if (shift == Shift.OFF) KeyStyle.SPECIAL else KeyStyle.LETTER) { onShift() }
                chars("zxcvbnm")
                key("⌫", 1.5f, KeyStyle.SPECIAL, repeat = true) { backspace() }
            })
        } else {
            r.addView(rowOf { chars("1234567890") })
            r.addView(rowOf { chars("-/:;()$&@\"") })
            r.addView(rowOf {
                gap(1.5f)
                chars(".,?!'", 1.4f)
                key("⌫", 1.5f, KeyStyle.SPECIAL, repeat = true) { backspace() }
            })
        }
        r.addView(rowOf {
            key(if (symbols) "ABC" else "123", 1.3f, KeyStyle.SPECIAL) { symbols = !symbols; render() }
            key("🌐", 1f, KeyStyle.SPECIAL) { nextKeyboard() }
            key("space", 4.6f) { space() }
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
            key(name, 1.6f, if (hasAction) KeyStyle.ACCENT else KeyStyle.SPECIAL) { enter() }
        })
    }

    private fun rowOf(build: LinearLayout.() -> Unit) = LinearLayout(themed).apply {
        orientation = LinearLayout.HORIZONTAL
        layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT)
        build()
    }

    private fun LinearLayout.key(
        label: String, w: Float = 1f, style: KeyStyle = KeyStyle.LETTER,
        repeat: Boolean = false, onKey: () -> Unit,
    ) {
        val lp = LinearLayout.LayoutParams(0, 50.dp, w).apply { setMargins(3.dp, 4.dp, 3.dp, 4.dp) }
        addView(KeyView(themed, label, style, repeat, onKey), lp)
    }

    private fun LinearLayout.gap(w: Float) = addView(View(themed), LinearLayout.LayoutParams(0, 1, w))

    private fun LinearLayout.chars(s: String, w: Float = 1f) {
        for (c in s) {
            val shown = if (shift != Shift.OFF && !symbols) c.uppercaseChar() else c
            key(shown.toString(), w) { type(shown.toString()) }
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
        if (before.length == 2 && before[1] == ' ' && before[0].isLetterOrDigit()) {
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
        val ei = currentInputEditorInfo
        val caps = if (ei != null && ei.inputType != 0)
            currentInputConnection?.getCursorCapsMode(ei.inputType) ?: 0 else 0
        shift = if (caps != 0) Shift.ON else Shift.OFF
    }

    private fun afterEdit() {
        val prev = shift
        autoCap()
        if (prev != shift) render()
    }
}
