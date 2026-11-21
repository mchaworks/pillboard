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
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.PopupWindow
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
    // emoji panel: grid mode (tabs + grid) or search mode (letter keys type into [query], results in the top bar)
    private var emojiMode = false
    private var emojiSearch = false
    private var emojiCat = 0
    private val query = StringBuilder()
    private var emojiGrid: EmojiGridView? = null
    private var emojiTabs: List<TextView> = emptyList()
    private var searchLabel: TextView? = null
    private var searchScroll: android.widget.HorizontalScrollView? = null
    private var searchResults: LinearLayout? = null
    // translate mode: the strip becomes a live translation of the whole field; tap it to replace the field's text
    private enum class TrState { IDLE, LOADING, OK, ERROR }
    private var translateMode = false
    private var trState = TrState.IDLE
    private var trText: String? = null        // last good translation
    private var trMsg = ""                    // hint / error line
    private var trSrc: String? = null         // detected source language
    private var trShown: String = "en"        // target actually used for trText
    private var trSeq = 0                     // newest request wins
    private var trChip: TextView? = null
    private var trResult: TextView? = null
    private var trPopup: PopupWindow? = null
    private val trHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private val trDebounce = Runnable { requestTranslate() }
    private val trExec = java.util.concurrent.Executors.newSingleThreadExecutor()
    /** One glass drawable per built input view: its AGSL layer and light animation are reused across applyBlur() calls. */
    private var shell: GlassSurfaceDrawable? = null
    /** Stage 4: edge refraction of the shell layer (API 33+, "Liquid refraction" setting). */
    private var refraction: ShellRefraction? = null

    override fun onCreate() {
        super.onCreate()
        // dictionaries load off the main thread; the strip fills in as soon as they are ready
        Suggestions.init(applicationContext) { trHandler.post { updateStrip() } }
    }

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
        emojiMode = false; emojiSearch = false; query.setLength(0)
        stopTranslateState()
        autoCap()
        applyBlur()
        render()
        root?.let { ViewCompat.requestApplyInsets(it) }
    }

    override fun onFinishInputView(finishingInput: Boolean) {
        preview?.dismiss(immediate = true)
        emojiGrid?.dismissTones()
        stopTranslateState()
        Suggestions.save()
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
        trExec.shutdownNow()
        Suggestions.save()
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
    private fun render(animateRows: Boolean = false, dir: BlurDir = BlurDir.UP) {
        val r = root ?: return
        // labels of the rows above the bottom row (text + any running Blur Replace), kept to animate only what changes
        val oldRows = if (animateRows) (0 until r.childCount).map { r.getChildAt(it) }
            .filter { it !== strip && it !== r.getChildAt(r.childCount - 1) && it is ViewGroup }
            .map { row -> (row as ViewGroup).children.filterIsInstance<KeyView>().filter { it.text.length == 1 }
                .map { OldKey(it.text.toString(), it.blurSnapshot(), it.visualW, it.visualH,
                    row.left + it.left, row.top + it.top, it.width, it.height, it.textSize, it.typeface, it.currentTextColor) }.toList() }
        else null
        r.removeAllViews()
        letterKeys.clear(); shiftKey = null; pageKey = null
        val keyH = keyHeight()

        val grid = emojiMode && !emojiSearch
        emojiGrid = null
        strip = when {
            emojiMode && emojiSearch -> buildSearchBar().also { r.addView(it) }
            grid -> buildEmojiTabs().also { r.addView(it) }
            translateMode -> buildTranslateBar().also { r.addView(it) }
            (Prefs.bool(this, "suggest", true) || Prefs.bool(this, "translate", false)) && suggestionsAllowed() -> buildStrip().also { r.addView(it) }
            else -> null
        }

        if (grid) {
            r.addView(buildEmojiGrid(keyH))
        } else if (!symbols) {
            if (Prefs.bool(this, "numrow", false)) r.addView(rowOf { chars("1234567890", (keyH * 0.8f).toInt()) })
            r.addView(rowOf { chars("qwertyuiop", keyH) })
            r.addView(rowOf { gap(0.5f); chars("asdfghjkl", keyH); gap(0.5f) })
            r.addView(rowOf {
                shiftKey = key(shiftDesc(), keyH, 1.5f, shiftStyle(), icon = shiftIcon()) { onShift() }
                chars("zxcvbnm", keyH)
                key("Backspace", keyH, 1.5f, KeyStyle.SPECIAL, repeat = true, icon = R.drawable.ic_backspace) { backspace() }
            })
        } else {
            val emojiOn = Prefs.bool(this, "emoji", true)
            r.addView(rowOf { chars("1234567890", keyH) })
            r.addView(rowOf { chars("-/:;()$&@\"", keyH) })
            r.addView(rowOf {
                // the slot the Shift key holds on the letter page: the emoji key (otherwise it would sit empty)
                if (emojiOn) key("Emoji", keyH, 1.5f, KeyStyle.SPECIAL, icon = R.drawable.ic_emoji) { setEmoji(true) } else gap(1.5f)
                chars(".,?!'", keyH, 1.4f)
                key("Backspace", keyH, 1.5f, KeyStyle.SPECIAL, repeat = true, icon = R.drawable.ic_backspace) { backspace() }
            })
        }
        if (grid) r.addView(emojiBottomRow(keyH)) else r.addView(rowOf {
            pageKey = key(if (symbols) "ABC" else "123", keyH, 1.35f, KeyStyle.SPECIAL) {
                // snapshot first: render() rebuilds the row, and a transition still running on the old key carries over
                val from = if (symbols) "ABC" else "123"
                val running = pageKey?.blurSnapshot()
                val oldW = pageKey?.visualW ?: 0
                val oldH = pageKey?.visualH ?: 0
                symbols = !symbols
                val dir = if (symbols) BlurDir.UP else BlurDir.DOWN         // same vertical language as Shift: 123 rises, ABC settles
                render(animateRows = true, dir = dir)    // the new label is already in place; Blur Replace only changes what is painted
                pageKey?.morphLabel(if (symbols) "ABC" else "123", from = from, inherited = running, oldW = oldW, oldH = oldH, dir = dir)
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
            val rows = (0 until r.childCount).map { r.getChildAt(it) }.filter { it !== strip && it !== r.getChildAt(r.childCount - 1) && it is ViewGroup }
            for (ri in rows.indices) {
                val old = oldRows.getOrNull(oldRows.size - rows.size + ri)
                var ki = 0
                for (k in (rows[ri] as ViewGroup).children.filterIsInstance<KeyView>()) {
                    if (k.text.length != 1) continue
                    val o = old?.getOrNull(ki++)
                    val same = o != null && o.snap == null && o.text == k.text.toString()
                    if (same) {
                        // same character: only a genuinely different key size animates (its body), the text stays still
                        if (kotlin.math.abs(o!!.w - k.width) > 1 || kotlin.math.abs(o.h - k.height) > 1) k.startShapeMorph(o.w, o.h)
                        continue
                    }
                    // no counterpart on the old page (e.g. n, m coming back from the symbols page): arrive on the same
                    // delay as its neighbours instead of 60ms ahead of them
                    k.morphLabel(k.text.toString(), from = o?.text ?: "", inherited = o?.snap, oldW = o?.w ?: 0, oldH = o?.h ?: 0,
                        syncDelay = o == null, dir = dir)
                }
                // old keys the new row has no slot for (n, m going to the symbols page): their views are gone, so
                // let the label blur out on its own instead of vanishing in one frame
                old?.drop(ki)?.forEach { ghost(r, it, dir) }
            }
        }
    }



    // ---- translate mode ----
    private fun trTarget(): String = Prefs.str(this, "tr_target", "").ifEmpty { Languages.defaultTarget(this) }
    private fun sameLang(a: String, b: String) = a.substringBefore('-').equals(b.substringBefore('-'), ignoreCase = true)

    private fun startTranslate() {
        if (!Prefs.bool(this, "translate", false)) return
        translateMode = true
        trState = TrState.IDLE; trText = null; trSrc = null; trMsg = ""
        render()
        requestTranslate()
    }

    private fun stopTranslate() { stopTranslateState(); render() }

    /** State only (no rebuild): used when the keyboard is torn down or another panel takes over the strip. */
    private fun stopTranslateState() {
        translateMode = false
        trSeq++
        trHandler.removeCallbacks(trDebounce)
        trPopup?.dismiss(); trPopup = null
    }

    /** The whole text of the field being typed in (extracted text first, text around the cursor as a fallback). */
    private fun fieldText(): String {
        val ic = currentInputConnection ?: return ""
        val ex = ic.getExtractedText(android.view.inputmethod.ExtractedTextRequest(), 0)?.text?.toString()
        if (!ex.isNullOrEmpty()) return ex
        return (ic.getTextBeforeCursor(1500, 0)?.toString().orEmpty()) + (ic.getTextAfterCursor(500, 0)?.toString().orEmpty())
    }

    private fun requestTranslate() {
        if (!translateMode) return
        val text = fieldText().trim()
        if (text.isEmpty()) {
            trSeq++
            trState = TrState.IDLE; trText = null; trMsg = "Type something to translate"
            updateTranslateBar(); return
        }
        trState = TrState.LOADING
        updateTranslateBar()
        val seq = ++trSeq
        val target = trTarget()
        trExec.execute {
            val outcome = runCatching {
                var tgt = target
                var r = Translator.translate(this, text, tgt)
                // already written in the target language: translate to English instead of echoing it back
                val det = r.detected
                if (det != null && sameLang(det, tgt) && !sameLang("en", tgt)) {
                    tgt = "en"; r = Translator.translate(this, text, tgt)
                }
                Triple(r.text, r.detected, tgt)
            }
            trHandler.post {
                if (seq != trSeq || !translateMode) return@post
                outcome.onSuccess { (out, det, tgt) ->
                    trText = out; trSrc = det; trShown = tgt; trState = TrState.OK
                }.onFailure { e ->
                    trState = TrState.ERROR
                    trMsg = when (e) {
                        is java.net.UnknownHostException, is java.net.SocketTimeoutException, is java.net.ConnectException -> "No connection"
                        else -> e.message?.takeIf { it.length < 60 } ?: "Couldn't translate"
                    }
                }
                updateTranslateBar()
            }
        }
    }

    /** Replace the field's text with the translation, then go back to the normal word bar. */
    private fun applyTranslation() {
        val out = trText ?: return
        val ic = currentInputConnection ?: return
        val len = fieldText().length
        ic.beginBatchEdit()
        ic.setSelection(0, len)
        ic.commitText(out, 1)
        ic.endBatchEdit()
        lastFix = null
        stopTranslate()
        afterEdit()
    }

    private fun buildTranslateBar() = LinearLayout(themed).apply {
        orientation = LinearLayout.HORIZONTAL
        isBaselineAligned = false
        layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, 42.dp).apply { setMargins(3.dp, 0, 3.dp, 3.dp) }
        background = tokens.stripBackground()
        setPadding(3.dp, 3.dp, 3.dp, 3.dp)
        val medium = ResourcesCompat.getFont(themed, R.font.gsr_medium)
        addView(TextView(themed).apply {
            text = "\u2715"; textSize = 16f; gravity = Gravity.CENTER
            setTextColor(tokens.keyText); alpha = 0.7f
            contentDescription = "Close translate"
            background = tokens.stripItemBackground()
            setOnClickListener { stopTranslate() }
        }, LinearLayout.LayoutParams(40.dp, MATCH_PARENT))
        val chip = TextView(themed).apply {
            textSize = 13f; gravity = Gravity.CENTER; maxLines = 1
            setPadding(10.dp, 0, 10.dp, 0)
            setTextColor(tokens.keyText)
            if (medium != null) typeface = medium
            contentDescription = "Target language"
            background = tokens.stripItemBackground()
            setOnClickListener { showLangPicker(this) }
        }
        trChip = chip
        addView(chip, LinearLayout.LayoutParams(WRAP_CONTENT, MATCH_PARENT))
        addView(View(themed).apply { setBackgroundColor(tokens.divider) },
            LinearLayout.LayoutParams(1.dp, 14.dp).apply { gravity = Gravity.CENTER_VERTICAL })
        val res = TextView(themed).apply {
            textSize = 14.5f; maxLines = 2; gravity = Gravity.CENTER_VERTICAL
            ellipsize = android.text.TextUtils.TruncateAt.END
            setPadding(10.dp, 0, 10.dp, 0)
            setTextColor(tokens.keyText)
            background = tokens.stripItemBackground()
            setOnClickListener { applyTranslation() }
        }
        trResult = res
        addView(res, LinearLayout.LayoutParams(0, MATCH_PARENT, 1f))
        updateTranslateBar()
    }

    private fun updateTranslateBar() {
        val chip = trChip ?: return
        val res = trResult ?: return
        val target = if (trState == TrState.OK) trShown else trTarget()
        chip.text = "${(trSrc ?: "auto").uppercase()} \u2192 ${target.uppercase()} \u25BE"
        when {
            trState == TrState.OK && trText != null -> { res.text = trText; res.alpha = 1f; res.contentDescription = "Replace text with translation" }
            trState == TrState.LOADING -> { res.text = trText ?: "Translating\u2026"; res.alpha = 0.5f }
            else -> { res.text = trMsg.ifEmpty { "Type something to translate" }; res.alpha = 0.55f }
        }
    }

    /** Target language list above the bar; system languages first. */
    private fun showLangPicker(anchor: View) {
        trPopup?.dismiss()
        val langs = Languages.ordered(this)
        val cur = trTarget()
        val col = LinearLayout(themed).apply { orientation = LinearLayout.VERTICAL }
        val sv = android.widget.ScrollView(themed).apply { isVerticalScrollBarEnabled = false; addView(col) }
        val itemH = 44.dp
        val w = 232.dp
        val h = 7 * itemH + 12.dp
        val box = FrameLayout(themed).apply {
            background = tokens.popupBackground(); elevation = tokens.popupElevation
            setPadding(6.dp, 6.dp, 6.dp, 6.dp)
            addView(sv)
        }
        val pop = PopupWindow(box, w, h, false).apply { isOutsideTouchable = true }
        langs.forEach { l ->
            col.addView(TextView(themed).apply {
                text = if (l.code == cur) l.name + "   \u2713" else l.name
                textSize = 16f; gravity = Gravity.CENTER_VERTICAL; maxLines = 1
                setPadding(14.dp, 0, 14.dp, 0)
                setTextColor(tokens.keyText)
                background = tokens.stripItemBackground()
                setOnClickListener {
                    Prefs.setStr(this@PillboardService, "tr_target", l.code)
                    pop.dismiss(); trPopup = null
                    trText = null
                    requestTranslate()
                }
            }, LinearLayout.LayoutParams(MATCH_PARENT, itemH))
        }
        trPopup = pop
        val loc = IntArray(2); anchor.getLocationOnScreen(loc)
        val win = IntArray(2); anchor.getLocationInWindow(win)
        val ox = loc[0] - win[0]; val oy = loc[1] - win[1]
        val screenW = resources.displayMetrics.widthPixels
        val x = loc[0].coerceIn(4.dp, (screenW - w - 4.dp).coerceAtLeast(4.dp))
        val y = (loc[1] - h - 4.dp).coerceAtLeast(0)
        pop.showAtLocation(anchor, Gravity.NO_GRAVITY, x - ox, y - oy)
        trHandler.postDelayed({ if (trPopup === pop) { pop.dismiss(); trPopup = null } }, 8000)
    }


    // ---- emoji ----
    private fun actionName(): String = when (currentInputEditorInfo?.imeOptions?.and(EditorInfo.IME_MASK_ACTION) ?: 0) {
        EditorInfo.IME_ACTION_GO -> "Go"
        EditorInfo.IME_ACTION_SEARCH -> "Search"
        EditorInfo.IME_ACTION_SEND -> "Send"
        EditorInfo.IME_ACTION_DONE -> "Done"
        EditorInfo.IME_ACTION_NEXT -> "Next"
        else -> "return"
    }

    /** Open/close the emoji panel. The panel settles in on the shared smooth ease (no spring). */
    private fun setEmoji(on: Boolean) {
        emojiGrid?.dismissTones()
        stopTranslateState()
        emojiMode = on; emojiSearch = false; query.setLength(0)
        if (on) emojiCat = if (EmojiData.recents(this).isEmpty()) 1 else 0
        symbols = false
        autoCap()
        render()
        val r = root ?: return
        if (!animationsOn(this)) return
        val fresh = if (on) listOfNotNull(emojiGrid) else (0 until r.childCount).map { r.getChildAt(it) }.filter { it !== strip && it !== r.getChildAt(r.childCount - 1) }
        for (v in fresh) {
            v.alpha = 0f; v.translationY = 8.dp.toFloat()
            v.animate().alpha(1f).translationY(0f).setDuration(BlurTextTokens.TOTAL_MS).setInterpolator(BlurTextTokens.inEase).start()
        }
    }

    private fun startEmojiSearch() {
        emojiGrid?.dismissTones()
        emojiSearch = true; query.setLength(0)
        autoCap()
        render()
    }

    private fun commitEmoji(e: String) {
        lastFix = null
        currentInputConnection?.commitText(e, 1)
        EmojiData.addRecent(this, e)
    }

    private fun emojiBottomRow(keyH: Int) = rowOf {
        pageKey = key("ABC", keyH, 1.35f, KeyStyle.SPECIAL) { setEmoji(false) }
        key("Search emoji", keyH, 1f, KeyStyle.SPECIAL, icon = R.drawable.ic_search) { startEmojiSearch() }
        key("space", keyH, 3.6f, KeyStyle.SPECIAL) { space() }
        key("Backspace", keyH, 1.5f, KeyStyle.SPECIAL, repeat = true, icon = R.drawable.ic_backspace) { backspace() }
        key(actionName(), keyH, 1.5f, KeyStyle.ACCENT) { enter() }
    }

    /** The grid takes exactly the height of the letter rows, so the keyboard never changes size when the panel opens. */
    private fun buildEmojiGrid(keyH: Int): View {
        val g = Prefs.int(this, "key_gap", 3)
        val m = 2 * (g + 2).dp
        val numRow = if (Prefs.bool(this, "numrow", false)) (keyH * 0.8f).toInt() + m else 0
        return EmojiGridView(themed, tokens).also { gv ->
            gv.layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, 3 * (keyH + m) + numRow)
            gv.onPick = { commitEmoji(it) }
            emojiGrid = gv
            showCategory(gv)
        }
    }

    private fun showCategory(gv: EmojiGridView) {
        if (emojiCat == 0) {
            gv.emptyHint = "Recently used emoji show up here"
            gv.items = EmojiData.recents(this)
        } else gv.items = EmojiData.categories().getOrNull(emojiCat - 1)?.items ?: emptyList()
    }

    private fun buildEmojiTabs() = LinearLayout(themed).apply {
        orientation = LinearLayout.HORIZONTAL
        isBaselineAligned = false
        layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, 42.dp).apply { setMargins(3.dp, 0, 3.dp, 3.dp) }
        setPadding(3.dp, 3.dp, 3.dp, 3.dp)
        val glyphs = listOf("\uD83D\uDD58") + EmojiData.categories().map { it.glyph }
        val names = listOf("Recent") + EmojiData.categories().map { it.name }
        emojiTabs = glyphs.mapIndexed { i, gl ->
            TextView(themed).apply {
                text = gl; textSize = 19f; gravity = Gravity.CENTER
                contentDescription = names[i]
                setOnClickListener {
                    emojiCat = i
                    emojiGrid?.dismissTones()
                    emojiGrid?.let { g -> showCategory(g) }
                    styleEmojiTabs()
                }
            }.also { addView(it, LinearLayout.LayoutParams(0, MATCH_PARENT, 1f)) }
        }
        styleEmojiTabs()
    }

    private fun styleEmojiTabs() {
        emojiTabs.forEachIndexed { i, tv ->
            val on = i == emojiCat
            tv.alpha = if (on) 1f else 0.6f
            tv.background = if (on) GradientDrawable().apply { setColor(tokens.stripPressed); cornerRadius = tokens.stripRadius - 4.dp } else null
        }
    }

    /** Search mode top bar: close, the query, then a scrolling row of matches (recents while the query is empty). */
    private fun buildSearchBar() = LinearLayout(themed).apply {
        orientation = LinearLayout.HORIZONTAL
        isBaselineAligned = false
        layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, 42.dp).apply { setMargins(3.dp, 0, 3.dp, 3.dp) }
        background = tokens.stripBackground()
        setPadding(3.dp, 3.dp, 3.dp, 3.dp)
        val medium = ResourcesCompat.getFont(themed, R.font.gsr_medium)
        addView(TextView(themed).apply {
            text = "\u2715"; textSize = 16f; gravity = Gravity.CENTER
            setTextColor(tokens.keyText); alpha = 0.7f
            contentDescription = "Close search"
            background = tokens.stripItemBackground()
            setOnClickListener { setEmoji(true) }
        }, LinearLayout.LayoutParams(40.dp, MATCH_PARENT))
        val labelView = TextView(themed).apply {
            textSize = 15f; maxLines = 1; gravity = Gravity.CENTER_VERTICAL
            ellipsize = android.text.TextUtils.TruncateAt.START
            setPadding(4.dp, 0, 8.dp, 0)
            setTextColor(tokens.keyText)
            if (medium != null) typeface = medium
        }
        searchLabel = labelView
        addView(labelView, LinearLayout.LayoutParams(112.dp, MATCH_PARENT))
        addView(View(themed).apply { setBackgroundColor(tokens.divider) },
            LinearLayout.LayoutParams(1.dp, 14.dp).apply { gravity = Gravity.CENTER_VERTICAL })
        val resultsView = LinearLayout(themed).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val scrollView = android.widget.HorizontalScrollView(themed).apply {
            isHorizontalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
            addView(resultsView, ViewGroup.LayoutParams(WRAP_CONTENT, MATCH_PARENT))
        }
        searchResults = resultsView
        searchScroll = scrollView
        addView(scrollView, LinearLayout.LayoutParams(0, MATCH_PARENT, 1f))
    }

    private fun updateSearchBar() {
        val label = searchLabel ?: return
        val box = searchResults ?: return
        if (query.isEmpty()) { label.text = "Search emoji"; label.alpha = 0.5f } else { label.text = query.toString(); label.alpha = 1f }
        box.removeAllViews()
        val list = if (query.isBlank()) EmojiData.recents(this) else EmojiData.search(query.toString())
        if (list.isEmpty() && query.isNotBlank()) {
            box.addView(TextView(themed).apply {
                text = "No emoji found"; textSize = 14f; setTextColor(tokens.keyText); alpha = 0.5f
                setPadding(10.dp, 0, 0, 0); gravity = Gravity.CENTER_VERTICAL
            }, LinearLayout.LayoutParams(WRAP_CONTENT, MATCH_PARENT))
        }
        for (e in list) box.addView(TextView(themed).apply {
            text = e; textSize = 24f; gravity = Gravity.CENTER
            background = tokens.stripItemBackground()
            setOnClickListener { commitEmoji(e) }
        }, LinearLayout.LayoutParams(42.dp, MATCH_PARENT))
        searchScroll?.scrollTo(0, 0)
    }

    /** Departing label of a key that no longer exists: a throw-away [BlurTextView] in the shell's overlay at the old spot. */
    private fun ghost(host: LinearLayout, o: OldKey, dir: BlurDir) {
        if (o.kw <= 0 || o.kh <= 0 || o.text.isBlank()) return
        val g = BlurTextView(themed).apply {
            gravity = Gravity.CENTER
            includeFontPadding = true
            setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, o.textSize)
            o.typeface?.let { typeface = it }
            setTextColor(o.color)
            measure(View.MeasureSpec.makeMeasureSpec(o.kw, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(o.kh, View.MeasureSpec.EXACTLY))
            layout(o.x, o.y, o.x + o.kw, o.y + o.kh)
        }
        host.overlay.add(g)
        // text is empty, so setTextBlur departs from [o.text] to nothing
        if (!g.setTextBlur("", from = o.text, dir = dir)) { host.overlay.remove(g); return }
        host.postDelayed({ host.overlay.remove(g) }, BlurTextTokens.TOTAL_MS + 80L)
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
            it.morphLabel(if (shift != Shift.OFF) l.uppercase() else l.lowercase(), minGapMs = BlurTextTokens.RAPID_GAP_MS,
                dir = if (shift != Shift.OFF) BlurDir.UP else BlurDir.DOWN)     // capitals rise, lowercase settles down
        }
        shiftKey?.let {
            it.setKeyStyle(shiftStyle())      // tone, corners and glyph ink flow together on the smooth timeline
            it.setIcon(shiftIcon(), shiftDesc())
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
        if (Prefs.bool(this@PillboardService, "translate", false) && suggestionsAllowed()) {
            addView(View(themed).apply { setBackgroundColor(tokens.divider) },
                LinearLayout.LayoutParams(1.dp, 14.dp).apply { gravity = Gravity.CENTER_VERTICAL })
            addView(android.widget.ImageView(themed).apply {
                setImageResource(R.drawable.ic_translate)
                imageTintList = android.content.res.ColorStateList.valueOf(tokens.keyText)
                alpha = 0.75f
                scaleType = android.widget.ImageView.ScaleType.CENTER_INSIDE
                setPadding(11.dp, 11.dp, 11.dp, 11.dp)
                contentDescription = "Translate"
                background = tokens.stripItemBackground()
                setOnClickListener { startTranslate() }
            }, LinearLayout.LayoutParams(46.dp, MATCH_PARENT))
        }
    }

    /** The text around the caret, read by the suggestion engine (language is inferred from it, never from the system). */
    private fun suggestionCtx(): SuggestCore.Ctx =
        Suggestions.context(currentInputConnection?.getTextBeforeCursor(120, 0) ?: "")

    /** Learn only where it is safe: not in passwords, emails, URLs, search filters, "no suggestions" or incognito fields. */
    private fun learningAllowed(): Boolean {
        if (!Prefs.bool(this, "learn", true) || !suggestionsAllowed()) return false
        val ei = currentInputEditorInfo ?: return false
        if (ei.imeOptions and EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING != 0) return false
        val t = ei.inputType
        if (t and InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS != 0) return false
        return when (t and InputType.TYPE_MASK_VARIATION) {
            InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS,
            InputType.TYPE_TEXT_VARIATION_WEB_EMAIL_ADDRESS,
            InputType.TYPE_TEXT_VARIATION_URI,
            InputType.TYPE_TEXT_VARIATION_FILTER -> false
            else -> true
        }
    }

    /** A word was just finished (space, punctuation, enter): the engine remembers it and which word came before. */
    private fun learnWord(c: SuggestCore.Ctx) {
        if (c.word.isNotEmpty() && learningAllowed()) Suggestions.learn(c, c.word)
    }

    private fun updateStrip() {
        if (emojiMode) { if (emojiSearch) updateSearchBar(); return }
        if (translateMode) return
        val s = strip ?: return
        val c = suggestionCtx()
        val res = if (Prefs.bool(this, "suggest", true)) Suggestions.suggest(c) else null
        for (i in 0 until 3) {
            val tv = s.getChildAt(i * 2) as BlurTextView
            val cand = res?.slot(i)          // slot 1 (centre) is the lead candidate
            val nextText = cand?.label ?: ""
            // tv.tag = the text this slot is heading to. Blur Replace sets the text at once and only animates the paint;
            // changes faster than RAPID_GAP_MS (fast typing) snap, so typing never waits on the effect.
            if (tv.tag != nextText) {
                tv.tag = nextText
                tv.setTextBlur(nextText, minGapMs = BlurTextTokens.RAPID_GAP_MS)
            }
            tv.setOnClickListener(if (cand == null) null else View.OnClickListener { applySuggestion(c, cand) })
        }
    }

    private fun applySuggestion(c: SuggestCore.Ctx, cand: SuggestCore.Cand) {
        lastFix = null
        currentInputConnection?.let { ic ->
            ic.deleteSurroundingText(c.word.length, 0)
            ic.commitText("${cand.text} ", 1)
        }
        // tapping the quoted literal means "this is a real word": remember it with extra weight
        if (learningAllowed()) Suggestions.learn(c, cand.text, if (cand.kind == SuggestCore.Kind.LITERAL) 2f else 1f)
        afterEdit()
    }

    // ---- actions ----
    /** Space-bar slide: move the cursor [n] characters (negative = left). */
    private fun moveCursor(n: Int) {
        if (emojiSearch) return
        lastFix = null
        val code = if (n < 0) KeyEvent.KEYCODE_DPAD_LEFT else KeyEvent.KEYCODE_DPAD_RIGHT
        repeat(Math.abs(n)) { sendDownUpKeyEvents(code) }
        updateStrip()
    }

    private fun type(s: String) {
        if (emojiSearch) { query.append(s.lowercase()); updateSearchBar(); return }
        lastFix = null
        // punctuation ends a word: learn it before the mark goes in
        if (s.length == 1 && !s[0].isLetterOrDigit() && s[0] != '\'' && s[0] != '’') learnWord(suggestionCtx())
        currentInputConnection?.commitText(s, 1)
        afterEdit()
    }

    private fun backspace() {
        if (emojiSearch) { if (query.isNotEmpty()) query.setLength(query.length - 1); updateSearchBar(); return }
        val fix = lastFix; lastFix = null
        val ic = currentInputConnection
        if (fix != null && ic != null && ic.getTextBeforeCursor(fix.second.length, 0)?.toString() == fix.second) {
            ic.deleteSurroundingText(fix.second.length, 0)   // undo auto-correct: restore what was typed
            ic.commitText(fix.first, 1)
            if (learningAllowed()) Suggestions.undoCorrection(fix.first)    // their spelling wins from now on
            afterEdit(); return
        }
        sendDownUpKeyEvents(KeyEvent.KEYCODE_DEL)
        afterEdit()
    }

    /** iOS-style: double space becomes ". " */
    private fun space() {
        if (emojiSearch) { if (query.isNotEmpty() && query.last() != ' ') query.append(' '); updateSearchBar(); return }
        val ic = currentInputConnection ?: return
        val c = suggestionCtx()
        if (Prefs.bool(this, "autocorrect", true) && suggestionsAllowed() && autoCorrect(ic, c)) { afterEdit(); return }
        learnWord(c)
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

    /** The engine decides (typo distance on this keyboard, language mix, what the person already uses); backspace undoes it. */
    private fun autoCorrect(ic: android.view.inputmethod.InputConnection, c: SuggestCore.Ctx): Boolean {
        val out = Suggestions.autoCorrection(c) ?: return false
        ic.deleteSurroundingText(c.word.length, 0)
        ic.commitText("$out ", 1)
        lastFix = c.word to "$out "
        if (learningAllowed()) Suggestions.learn(c, out)
        return true
    }

    private fun enter() {
        if (emojiSearch) { setEmoji(true); return }
        learnWord(suggestionCtx())
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
        if (emojiSearch) { if (shift != Shift.LOCK) shift = Shift.OFF; return }
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
        if (translateMode) { trHandler.removeCallbacks(trDebounce); trHandler.postDelayed(trDebounce, 550) }
    }
}

/** What the rebuild needs from a key of the previous page: its character, a running Blur Replace and its visual size. */
private class OldKey(
    val text: String, val snap: List<BlurTextLayer>?, val w: Int, val h: Int,
    val x: Int, val y: Int, val kw: Int, val kh: Int, val textSize: Float, val typeface: android.graphics.Typeface?, val color: Int,
)
