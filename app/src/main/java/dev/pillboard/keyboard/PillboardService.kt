package dev.pillboard.keyboard

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
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
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat

class PillboardService : InputMethodService() {

    private companion object {
        /** Long-press alternates (lowercase keys). */
        val ALTS = mapOf(
            "a" to listOf("à", "á", "â", "ä", "ã", "å"),
            "e" to listOf("è", "é", "ê", "ë"),
            "i" to listOf("î", "ï", "í", "ì"),
            "o" to listOf("ô", "ö", "ò", "ó", "õ"),
            "u" to listOf("û", "ü", "ù", "ú"),
            "c" to listOf("ç"),
            "n" to listOf("ñ"),
            "s" to listOf("ß"),
            "y" to listOf("ÿ"),
        )
    }

    private enum class Shift { OFF, ON, LOCK }

    private var shift = Shift.OFF
    private var symbols = false
    private var lastShiftTap = 0L
    private var root: LinearLayout? = null
    private var strip: LinearLayout? = null
    private val letterKeys = mutableListOf<KeyView>()
    private var shiftKey: KeyView? = null
    private var navInset = 0
    private lateinit var themed: Context
    private lateinit var tokens: KbTokens
    private var builtDynamic = true
    private var builtPreset = 0

    override fun onCreateInputView(): View {
        builtDynamic = Prefs.dynamic(this)
        builtPreset = Prefs.preset(this)
        themed = ContextThemeWrapper(this, if (builtDynamic) R.style.Theme_Pillboard else R.style.Theme_Pillboard_Static)
        tokens = KbTokens(themed)
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
        if (Prefs.dynamic(this) != builtDynamic || Prefs.preset(this) != builtPreset) setInputView(onCreateInputView())
        symbols = false
        autoCap()
        applyBlur()
        render()
        root?.let { ViewCompat.requestApplyInsets(it) }
    }

    private fun applyPadding() {
        root?.setPadding(6.dp, 8.dp, 6.dp, 8.dp + navInset)
    }

    private fun blurSupported() = Build.VERSION.SDK_INT >= 31 &&
        (getSystemService(WINDOW_SERVICE) as WindowManager).isCrossWindowBlurEnabled

    /**
     * iOS 26-style rounded top corners ("concentric" with the screen). Blur is only used when the
     * device really supports it; otherwise the background stays fully opaque (no see-through).
     */
    private fun applyBlur() {
        val blur = Prefs.bool(this, "blur", false) && blurSupported()
        val base = tokens.background
        val fill = if (blur) (base and 0x00FFFFFF) or (0xB0 shl 24) else base
        val r = 28.dp.toFloat()
        fun shape() = GradientDrawable().apply {
            setColor(fill)
            cornerRadii = floatArrayOf(r, r, r, r, 0f, 0f, 0f, 0f)
        }
        root?.background = if (blur) null else shape()
        window.window?.let { w ->
            w.setBackgroundDrawable(if (blur) shape() else ColorDrawable(Color.TRANSPARENT))
            if (Build.VERSION.SDK_INT >= 31) w.setBackgroundBlurRadius(if (blur) 70.dp else 0)
        }
    }

    // ---- layout ----
    private fun render() {
        val r = root ?: return
        r.removeAllViews()
        letterKeys.clear(); shiftKey = null
        val keyH = Prefs.int(this, "key_h", 50).dp

        strip = if (Prefs.bool(this, "suggest", true) && suggestionsAllowed()) buildStrip().also { r.addView(it) } else null

        if (!symbols) {
            r.addView(rowOf { chars("qwertyuiop", keyH) })
            r.addView(rowOf { gap(0.5f); chars("asdfghjkl", keyH); gap(0.5f) })
            r.addView(rowOf {
                shiftKey = key(shiftGlyph(), keyH, 1.5f, if (shift == Shift.OFF) KeyStyle.SPECIAL else KeyStyle.LETTER) { onShift() }
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
    ): KeyView {
        val lp = LinearLayout.LayoutParams(0, h, w).apply { setMargins(3.dp, 4.dp, 3.dp, 4.dp) }
        return KeyView(themed, label, style, tokens, repeat, onKey).also { addView(it, lp) }
    }

    private fun LinearLayout.gap(w: Float) = addView(View(themed), LinearLayout.LayoutParams(0, 1, w))

    private fun LinearLayout.chars(s: String, h: Int, w: Float = 1f) {
        for (c in s) {
            val shown = if (shift != Shift.OFF && !symbols) c.uppercaseChar() else c
            val holder = arrayOfNulls<KeyView>(1)
            holder[0] = key(shown.toString(), h, w) { type(holder[0]!!.text.toString()) }
            if (!symbols) {
                val kv = holder[0]!!
                letterKeys.add(kv)
                kv.altsFor = { l ->
                    val base = l.lowercase()
                    ALTS[base]?.map { if (l != base) it.uppercase() else it } ?: emptyList()
                }
                kv.onAlt = { type(it) }
            }
        }
    }

    private fun shiftGlyph() = when (shift) { Shift.OFF -> "⇧"; Shift.ON -> "⬆"; Shift.LOCK -> "⇪" }

    /** Shift changed: relabel keys in place. Never rebuild views while the user is typing. */
    private fun updateShiftUi() {
        if (symbols) return
        letterKeys.forEach {
            val l = it.text.toString()
            it.setLabel(if (shift != Shift.OFF) l.uppercase() else l.lowercase())
        }
        shiftKey?.let {
            it.setLabel(shiftGlyph())
            it.setKeyStyle(if (shift == Shift.OFF) KeyStyle.SPECIAL else KeyStyle.LETTER)
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
        repeat(3) { i ->
            if (i > 0) addView(View(themed).apply {
                setBackgroundColor((tokens.keyText and 0x00FFFFFF) or (0x33 shl 24))
            }, LinearLayout.LayoutParams(1.dp, 18.dp).apply { gravity = Gravity.CENTER_VERTICAL })
            addView(TextView(themed).apply {
                gravity = Gravity.CENTER
                textSize = 16f
                maxLines = 1
                setTextColor(tokens.keyText)
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
            val tv = s.getChildAt(i * 2) as TextView
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
        updateShiftUi()
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
        if (prev != shift) updateShiftUi()
        updateStrip()
    }
}
