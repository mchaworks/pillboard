package dev.pillboard.keyboard

import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import androidx.core.content.res.ResourcesCompat
import android.inputmethodservice.InputMethodService
import android.os.Build
import android.os.SystemClock
import android.text.InputType
import android.view.ContextThemeWrapper
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.animation.DecelerateInterpolator
import androidx.core.view.children
import android.view.ViewGroup
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
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

        /** Long-press alternates on the symbols page. */
        val PUNCT = mapOf(
            "." to listOf("…"), "?" to listOf("¿"), "!" to listOf("¡"),
            "'" to listOf("‘", "’"), "\"" to listOf("“", "”", "«", "»"),
            "-" to listOf("–", "—", "•"), "$" to listOf("€", "£", "¥", "¢"),
            "&" to listOf("§"), "0" to listOf("°"),
        )

        /** Small built-in auto-correct list (applied on space; backspace right after undoes it). */
        val FIXES = mapOf(
            "teh" to "the", "adn" to "and", "thier" to "their", "becuase" to "because", "recieve" to "receive",
            "definately" to "definitely", "wierd" to "weird", "freind" to "friend", "alot" to "a lot",
            "tommorow" to "tomorrow", "dont" to "don't", "cant" to "can't", "wont" to "won't", "didnt" to "didn't",
            "doesnt" to "doesn't", "isnt" to "isn't", "wasnt" to "wasn't", "couldnt" to "couldn't",
            "shouldnt" to "shouldn't", "wouldnt" to "wouldn't", "youre" to "you're", "theyre" to "they're",
            "thats" to "that's", "im" to "I'm", "ive" to "I've", "i" to "I",
        )
    }

    private enum class Shift { OFF, ON, LOCK }

    private var shift = Shift.OFF
    private var symbols = false
    private var lastShiftTap = 0L
    private var lastFix: Pair<String, String>? = null
    private var root: LinearLayout? = null
    private var strip: LinearLayout? = null
    private val letterKeys = mutableListOf<KeyView>()
    private var shiftKey: KeyView? = null
    private var preview: KeyPreview? = null
    private var navInset = 0
    private lateinit var themed: Context
    private lateinit var tokens: KbTokens
    private var builtDynamic = true
    private var builtSeed = 0
    private var builtFx = true
    private var pageKey: KeyView? = null
    /** One glass drawable per built input view: its AGSL layer and light animation are reused across applyBlur() calls. */
    private var shell: GlassSurfaceDrawable? = null
    /** Stage 4: edge refraction of the shell layer (API 33+, "Liquid refraction" setting). */
    private var refraction: ShellRefraction? = null

    override fun onCreateInputView(): View {
        refraction?.release()
        refraction = null
        builtDynamic = Prefs.dynamic(this)
        builtSeed = Prefs.seedColor(this)
        builtFx = Prefs.bool(this, "glass_fx", true)
        shell = null
        themed = ContextThemeWrapper(this, if (builtDynamic) R.style.Theme_Pillboard else R.style.Theme_Pillboard_Static)
        tokens = KbTokens(themed)
        preview = KeyPreview(themed, tokens)
        val r = LinearLayout(themed).apply {
            orientation = LinearLayout.VERTICAL
            clipToOutline = true
        }
        root = r
        refraction = ShellRefraction(r, tokens.containerRadius)
        // Keep keys clear of the system navigation bar (fixes overlap on edge-to-edge Android 15+)
        ViewCompat.setOnApplyWindowInsetsListener(r) { _, insets ->
            navInset = insets.getInsets(WindowInsetsCompat.Type.systemBars()).bottom
            applyPadding()
            insets
        }
        applyPadding()
        applyBlur()
        watchBlur()
        render()
        return r
    }

    override fun onStartInputView(info: EditorInfo?, restarting: Boolean) {
        super.onStartInputView(info, restarting)
        if (Prefs.dynamic(this) != builtDynamic || Prefs.seedColor(this) != builtSeed || Prefs.bool(this, "glass_fx", true) != builtFx) setInputView(onCreateInputView())
        symbols = false
        autoCap()
        applyBlur()
        render()
        root?.let { ViewCompat.requestApplyInsets(it) }
    }

    override fun onFinishInputView(finishingInput: Boolean) {
        preview?.dismiss(immediate = true)
        super.onFinishInputView(finishingInput)
    }

    private fun applyPadding() {
        root?.setPadding(7.dp, 8.dp, 7.dp, 8.dp + navInset)
    }

    private fun blurSupported() = Build.VERSION.SDK_INT >= 31 &&
        (getSystemService(WINDOW_SERVICE) as WindowManager).isCrossWindowBlurEnabled

    private var blurListener: java.util.function.Consumer<Boolean>? = null

    /** Cross-window blur can be toggled at runtime (battery saver, developer options): re-apply when it flips. */
    private fun watchBlur() {
        if (Build.VERSION.SDK_INT < 31 || blurListener != null) return
        val l = java.util.function.Consumer<Boolean> { applyBlur() }
        blurListener = l
        (getSystemService(WINDOW_SERVICE) as WindowManager).addCrossWindowBlurEnabledListener(mainExecutor, l)
    }

    override fun onDestroy() {
        if (Build.VERSION.SDK_INT >= 31) {
            blurListener?.let { (getSystemService(WINDOW_SERVICE) as WindowManager).removeCrossWindowBlurEnabledListener(it) }
        }
        blurListener = null
        refraction?.release(); refraction = null
        super.onDestroy()
    }

    /**
     * Three tiers: real backdrop blur (translucent shell), blur requested but unavailable (near-opaque tint so
     * text stays readable), blur off (solid tonal shell). The window background is a rounded rect so the blur is
     * masked to the shell's corners instead of showing a square blurred slab.
     */
    private fun applyBlur() {
        // "High contrast text" (accessibility) = Reduce Transparency: solid shell, no blur
        val highContrast = android.provider.Settings.Secure.getInt(contentResolver, "high_text_contrast_enabled", 0) == 1
        val blurRequested = Prefs.bool(this, "blur", false) && !highContrast
        val blur = blurRequested && blurSupported()
        val fill = when {
            blur -> tokens.glassFillBlur
            blurRequested -> tokens.glassFillNoBlur
            else -> tokens.background
        }
        // refraction re-samples the shell layer, so it is off for "high contrast text" like the blur
        refraction?.update(Prefs.bool(this, "refract", false) && !highContrast)
        val glass = shell?.also { it.setFill(fill) } ?: tokens.shell(fill).also { shell = it }
        if (root?.background !== glass) root?.background = glass
        window.window?.let { w ->
            if (blur) w.setBackgroundDrawable(GradientDrawable().apply {
                setColor(0x01000000); cornerRadius = tokens.containerRadius
            }) else w.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            // Android's window-blur docs: the blur is drawn under the window surface, so the window must be translucent
            if (blur) w.setFormat(PixelFormat.TRANSLUCENT)
            if (Build.VERSION.SDK_INT >= 31) w.setBackgroundBlurRadius(if (blur) tokens.blurRadius else 0)
        }
    }

    // ---- layout ----
    private fun render(animateRows: Boolean = false) {
        val r = root ?: return
        // labels of the rows above the bottom row (text + any running Blur Replace), kept to animate only what changes
        val oldRows = if (animateRows) (0 until r.childCount).map { r.getChildAt(it) }
            .filter { it !== strip && it !== r.getChildAt(r.childCount - 1) }
            .map { row -> (row as ViewGroup).children.filterIsInstance<KeyView>().filter { it.text.length == 1 }
                .map { it.text.toString() to it.blurSnapshot() }.toList() }
        else null
        r.removeAllViews()
        letterKeys.clear(); shiftKey = null; pageKey = null
        val keyH = keyHeight()

        strip = if (Prefs.bool(this, "suggest", true) && suggestionsAllowed()) buildStrip().also { r.addView(it) } else null

        if (!symbols) {
            if (Prefs.bool(this, "numrow", false)) r.addView(rowOf { chars("1234567890", (keyH * 0.8f).toInt()) })
            r.addView(rowOf { chars("qwertyuiop", keyH) })
            r.addView(rowOf { gap(0.5f); chars("asdfghjkl", keyH); gap(0.5f) })
            r.addView(rowOf {
                shiftKey = key(shiftDesc(), keyH, 1.5f, shiftStyle(), icon = shiftIcon()) { onShift() }
                chars("zxcvbnm", keyH)
                key("Backspace", keyH, 1.5f, KeyStyle.SPECIAL, repeat = true, icon = R.drawable.ic_backspace) { backspace() }
            })
        } else {
            r.addView(rowOf { chars("1234567890", keyH) })
            r.addView(rowOf { chars("-/:;()$&@\"", keyH) })
            r.addView(rowOf {
                gap(1.5f)
                chars(".,?!'", keyH, 1.4f)
                key("Backspace", keyH, 1.5f, KeyStyle.SPECIAL, repeat = true, icon = R.drawable.ic_backspace) { backspace() }
            })
        }
        r.addView(rowOf {
            pageKey = key(if (symbols) "ABC" else "123", keyH, 1.35f, KeyStyle.SPECIAL) {
                // snapshot first: render() rebuilds the row, and a transition still running on the old key carries over
                val from = if (symbols) "ABC" else "123"
                val running = pageKey?.blurSnapshot()
                symbols = !symbols
                render(animateRows = true)    // the new label is already in place; Blur Replace only changes what is painted
                pageKey?.setTextBlur(if (symbols) "ABC" else "123", from = from, inherited = running)
            }
            key(",", keyH, 0.85f, KeyStyle.SPECIAL) { type(",") }
            key("space", keyH, 4.45f, KeyStyle.SPECIAL) { space() }.also { sp ->
                if (Prefs.bool(this@PillboardService, "spaceslide", true)) {
                    sp.onSlide = { n -> moveCursor(n) }
                    sp.onSlideState = { on -> letterKeys.forEach { it.animate().alpha(if (on) 0.35f else 1f).setDuration(120).start() } }
                }
            }
            key(".", keyH, 0.85f, KeyStyle.SPECIAL) { type(".") }
            val action = currentInputEditorInfo?.imeOptions?.and(EditorInfo.IME_MASK_ACTION) ?: 0
            // iOS: the action key is a word, not an icon ("return" in lowercase, the editor actions capitalised)
            val name = when (action) {
                EditorInfo.IME_ACTION_GO -> "Go"
                EditorInfo.IME_ACTION_SEARCH -> "Search"
                EditorInfo.IME_ACTION_SEND -> "Send"
                EditorInfo.IME_ACTION_DONE -> "Done"
                EditorInfo.IME_ACTION_NEXT -> "Next"
                else -> "return"
            }
            key(name, keyH, 1.5f, KeyStyle.ACCENT) { enter() }   // always the primary action
        })
        updateStrip()
        if (oldRows != null && animationsOn(this)) {
            // rows stay still; each key whose label differs from the old page blurs from the old to the new character.
            // Rows are matched from the bottom (a number row above QWERTY has no counterpart), keys by position.
            val rows = (0 until r.childCount).map { r.getChildAt(it) }.filter { it !== strip && it !== r.getChildAt(r.childCount - 1) }
            for (ri in rows.indices) {
                val old = oldRows.getOrNull(oldRows.size - rows.size + ri)
                var ki = 0
                for (k in (rows[ri] as ViewGroup).children.filterIsInstance<KeyView>()) {
                    if (k.text.length != 1) continue
                    val o = old?.getOrNull(ki++)
                    if (o != null && o.second == null && o.first == k.text.toString()) continue    // unchanged: stays still
                    k.setLabelBlur(k.text.toString(), from = o?.first ?: "", inherited = o?.second)
                }
            }
        }
    }

    /**
     * User key height, clamped so 4–5 rows + suggestions never take more than ~55% of the screen
     * (landscape, split-screen, short displays).
     */
    private fun keyHeight(): Int {
        val pref = Prefs.int(this, "key_h", 50).dp
        val rows = 4f + (if (Prefs.bool(this, "numrow", false) && !symbols) 0.8f else 0f)
        val stripH = if (Prefs.bool(this, "suggest", true) && suggestionsAllowed()) 45.dp else 0
        val budget = resources.displayMetrics.heightPixels * 0.55f - stripH - 16.dp - navInset
        val margins = (2 * Prefs.int(this, "key_gap", 3) + 4).dp
        val fit = (budget / rows).toInt() - margins
        return minOf(pref, fit).coerceAtLeast(34.dp)
    }

    private fun rowOf(build: LinearLayout.() -> Unit) = LinearLayout(themed).apply {
        orientation = LinearLayout.HORIZONTAL
        // Keys mix 22sp letters, 15sp labels and icons: baseline alignment would shift them vertically.
        isBaselineAligned = false
        layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT)
        build()
    }

    private fun LinearLayout.key(
        label: String, h: Int, w: Float = 1f, style: KeyStyle = KeyStyle.LETTER,
        repeat: Boolean = false, icon: Int = 0, onKey: () -> Unit,
    ): KeyView {
        val g = Prefs.int(this@PillboardService, "key_gap", 3)
        // rows breathe more than columns, like iOS 26
        val lp = LinearLayout.LayoutParams(0, h + 2 * (g + 2).dp, w)
        return KeyView(themed, label, style, tokens, repeat, icon, onKey).also {
            it.setHitInset(g.dp, (g + 2).dp)
            if (Prefs.bool(this@PillboardService, "keypreview", true)) it.preview = preview
            it.glass = shell
            addView(it, lp)
        }
    }

    private fun LinearLayout.gap(w: Float) = addView(View(themed), LinearLayout.LayoutParams(0, 1, w))

    private fun LinearLayout.chars(s: String, h: Int, w: Float = 1f) {
        for (c in s) {
            val shown = if (shift != Shift.OFF && !symbols) c.uppercaseChar() else c
            val holder = arrayOfNulls<KeyView>(1)
            holder[0] = key(shown.toString(), h, w) { type(holder[0]!!.text.toString()) }
            val kv = holder[0]!!
            if (!symbols && c.isLetter()) {
                letterKeys.add(kv)
                kv.altsFor = { l ->
                    val base = l.lowercase()
                    ALTS[base]?.map { if (l != base) it.uppercase() else it } ?: emptyList()
                }
            } else {
                kv.altsFor = { l -> PUNCT[l] ?: emptyList() }
            }
            kv.onAlt = { type(it) }
        }
    }

    /** iOS: OFF = tonal special key, ON and LOCK = inverted key; LOCK is told apart by the underlined arrow icon. */
    private fun shiftStyle() = when (shift) { Shift.OFF -> KeyStyle.SPECIAL; Shift.ON, Shift.LOCK -> KeyStyle.ACTIVE }

    private fun shiftIcon() = when (shift) {
        Shift.OFF -> R.drawable.ic_shift; Shift.ON -> R.drawable.ic_shift_on; Shift.LOCK -> R.drawable.ic_caps_lock
    }

    private fun shiftDesc() = when (shift) { Shift.OFF -> "Shift"; Shift.ON -> "Shift on"; Shift.LOCK -> "Caps lock" }

    /** Shift changed: relabel keys in place. Never rebuild views while the user is typing. */
    private fun updateShiftUi() {
        if (symbols) return
        letterKeys.forEach {
            val l = it.text.toString()
            // only keys whose label really changes animate (ON <-> LOCK keeps the same capitals); fast typing snaps
            it.setLabelBlur(if (shift != Shift.OFF) l.uppercase() else l.lowercase(), minGapMs = BlurTextTokens.RAPID_GAP_MS)
        }
        shiftKey?.let {
            it.setIcon(shiftIcon(), shiftDesc())
            it.setKeyStyle(shiftStyle())
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
        isBaselineAligned = false
        layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, 42.dp).apply { setMargins(3.dp, 0, 3.dp, 3.dp) }
        background = tokens.stripBackground()
        setPadding(3.dp, 3.dp, 3.dp, 3.dp)
        val medium = ResourcesCompat.getFont(themed, R.font.gsr_medium)
        repeat(3) { i ->
            if (i > 0) addView(View(themed).apply { setBackgroundColor(tokens.divider) },
                LinearLayout.LayoutParams(1.dp, 14.dp).apply { gravity = Gravity.CENTER_VERTICAL })
            addView(BlurTextView(themed).apply {
                gravity = Gravity.CENTER
                textSize = 16f
                maxLines = 1
                setTextColor(tokens.keyText)
                if (i == 1 && medium != null) typeface = medium   // the lead candidate reads first
                background = tokens.stripItemBackground()
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
            val tv = s.getChildAt(i * 2) as BlurTextView
            val sug = list.getOrNull(i)
            val nextText = sug ?: ""
            // tv.tag = the text this slot is heading to. Blur Replace sets the text at once and only animates the paint;
            // changes faster than RAPID_GAP_MS (fast typing) snap, so typing never waits on the effect.
            if (tv.tag != nextText) {
                tv.tag = nextText
                tv.setTextBlur(nextText, minGapMs = BlurTextTokens.RAPID_GAP_MS)
            }
            tv.setOnClickListener(if (sug == null) null else View.OnClickListener {
                lastFix = null
                currentInputConnection?.let { ic ->
                    ic.deleteSurroundingText(word.length, 0)
                    ic.commitText("$sug ", 1)
                }
                afterEdit()
            })
        }
    }

    // ---- actions ----
    /** Space-bar slide: move the cursor [n] characters (negative = left). */
    private fun moveCursor(n: Int) {
        lastFix = null
        val code = if (n < 0) KeyEvent.KEYCODE_DPAD_LEFT else KeyEvent.KEYCODE_DPAD_RIGHT
        repeat(Math.abs(n)) { sendDownUpKeyEvents(code) }
        updateStrip()
    }

    private fun type(s: String) {
        lastFix = null
        currentInputConnection?.commitText(s, 1)
        afterEdit()
    }

    private fun backspace() {
        val fix = lastFix; lastFix = null
        val ic = currentInputConnection
        if (fix != null && ic != null && ic.getTextBeforeCursor(fix.second.length, 0)?.toString() == fix.second) {
            ic.deleteSurroundingText(fix.second.length, 0)   // undo auto-correct: restore what was typed
            ic.commitText(fix.first, 1)
            afterEdit(); return
        }
        sendDownUpKeyEvents(KeyEvent.KEYCODE_DEL)
        afterEdit()
    }

    /** iOS-style: double space becomes ". " */
    private fun space() {
        val ic = currentInputConnection ?: return
        if (Prefs.bool(this, "autocorrect", true) && suggestionsAllowed() && autoCorrect(ic)) { afterEdit(); return }
        lastFix = null
        val before = ic.getTextBeforeCursor(2, 0)?.toString() ?: ""
        if (Prefs.bool(this, "dblspace", true) && before.length == 2 && before[1] == ' ' && before[0].isLetterOrDigit()) {
            ic.deleteSurroundingText(1, 0)
            ic.commitText(". ", 1)
        } else {
            ic.commitText(" ", 1)
        }
        afterEdit()
    }

    private fun autoCorrect(ic: android.view.inputmethod.InputConnection): Boolean {
        val word = currentWord()
        if (word.isEmpty()) return false
        val fix = FIXES[word.lowercase()] ?: return false
        val out = if (word != "i" && word[0].isUpperCase()) fix.replaceFirstChar { it.uppercase() } else fix
        if (out == word) return false
        ic.deleteSurroundingText(word.length, 0)
        ic.commitText("$out ", 1)
        lastFix = word to "$out "
        return true
    }

    private fun enter() {
        lastFix = null
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
