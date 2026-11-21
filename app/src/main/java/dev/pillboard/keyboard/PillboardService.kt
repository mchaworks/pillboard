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
    private var host: ShellHost? = null          // input view: shell + transparent headroom; its background is the shell's glass
    private var menuLayer: FrameLayout? = null   // icons of the capsule above the top-right strip button (hold); the glass is the shell's own
    private var menuIcons: List<BlurIconView> = emptyList()
    private var menuClosing = false
    private var menuMorph: android.animation.ValueAnimator? = null
    private var menuFrom: RoundRectState? = null
    private var menuTo: RoundRectState? = null
    private val menuCur = RoundRectState()
    private var menuOx = 0f
    private var menuOy = 0f
    private val menuAutoClose = Runnable { dismissMenu(true) }
    private var stripIcon: BlurIconView? = null  // the strip button's icon (active feature)
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
        runCatching { getSystemService(android.content.ClipboardManager::class.java).addPrimaryClipChangedListener(clipListener) }
    }

    override fun onCreateInputView(): View {
        refraction?.release()
        refraction = null
        builtDynamic = Prefs.dynamic(this)
        builtSeed = Prefs.seedColor(this)
        builtFx = Prefs.bool(this, "glass_fx", true)
        shell = null
        capBlurAnim?.cancel(); capBlurK = 0f          // a rebuilt host has no headroom: the blur starts whole
        themed = ContextThemeWrapper(this, if (builtDynamic) R.style.Theme_Pillboard else R.style.Theme_Pillboard_Static)
        tokens = KbTokens(themed)
        preview = KeyPreview(themed, tokens)
        val radius = tokens.containerRadius
        val r = LinearLayout(themed).apply {
            orientation = LinearLayout.VERTICAL
            // the glass is drawn by the host (it also covers the capsule); the shell view only clips its keys to the rounded shape
            outlineProvider = object : android.view.ViewOutlineProvider() {
                override fun getOutline(v: View, outline: android.graphics.Outline) {
                    outline.setRoundRect(0, 0, v.width, v.height, radius)
                }
            }
            clipToOutline = true
        }
        root = r
        val h = ShellHost(themed).apply {
            capTop = 0          // headroom only exists while the capsule is open (see setHeadroom): otherwise the window blur would cover it
            onOutsideTap = { dismissMenu(true) }
            addView(r, FrameLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT, Gravity.BOTTOM).apply { topMargin = capTop })
        }
        host = h
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
        return h
    }

    /**
     * The headroom above the shell belongs to the window but not to the keyboard: apps lay out above the shell's top
     * edge, and touches in the headroom fall through to the app, except while the capsule is open (then the whole host
     * takes them, so a tap outside the capsule closes it).
     */
    override fun onComputeInsets(outInsets: InputMethodService.Insets) {
        super.onComputeInsets(outInsets)
        val h = host ?: return
        if (h.height <= 0 || h.visibility != View.VISIBLE) return
        val loc = IntArray(2); h.getLocationInWindow(loc)
        val top = loc[1] + h.capTop
        outInsets.contentTopInsets = top
        outInsets.visibleTopInsets = top
        if (h.menuOpen) {
            outInsets.touchableInsets = InputMethodService.Insets.TOUCHABLE_INSETS_REGION
            outInsets.touchableRegion.set(loc[0], loc[1], loc[0] + h.width, loc[1] + h.height)
        }
    }

    override fun onStartInputView(info: EditorInfo?, restarting: Boolean) {
        super.onStartInputView(info, restarting)
        if (Prefs.dynamic(this) != builtDynamic || Prefs.seedColor(this) != builtSeed || Prefs.bool(this, "glass_fx", true) != builtFx) setInputView(onCreateInputView())
        symbols = false
        swapAnim?.cancel(); panel = Panel.NONE; selecting = false
        captureClip()
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
        trHandler.removeCallbacks(clipExpire); trHandler.removeCallbacks(stripRefresh)
        runCatching { getSystemService(android.content.ClipboardManager::class.java).removePrimaryClipChangedListener(clipListener) }
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
    private var blurOn = false
    private var outlineHost: View? = null
    /** 0 = normal window blur, 1 = blur off + opaque fill: eased while the strip capsule's headroom exists. */
    private var capBlurK = 0f
    private var capBlurAnim: android.animation.ValueAnimator? = null

    private fun applyCapsuleBlur() {
        if (!blurOn) return
        val k = capBlurK
        window.window?.let { w ->
            if (Build.VERSION.SDK_INT >= 31) w.setBackgroundBlurRadius((tokens.blurRadius * (1f - k)).toInt())
        }
        shell?.setFill(androidx.core.graphics.ColorUtils.blendARGB(tokens.glassFillBlur, tokens.glassFillNoBlur, k))
    }

    /**
     * The window blur always covers the whole window rect. While the capsule's transparent headroom exists that rect
     * is much bigger than the glass (a blurred slab beside/above the capsule), so the blur fades out (and the glass
     * fill firms up to compensate) when the headroom appears and fades back when it is gone.
     */
    private fun fadeCapsuleBlur(to: Float) {
        capBlurAnim?.cancel()
        if (!blurOn || !animationsOn(this)) { capBlurK = to; applyCapsuleBlur(); return }
        capBlurAnim = android.animation.ValueAnimator.ofFloat(capBlurK, to).apply {
            duration = if (to > capBlurK) 100L else 160L      // out fast (the slab must not show), back a bit slower
            addUpdateListener { capBlurK = it.animatedValue as Float; applyCapsuleBlur() }
            start()
        }
    }

    private fun applyBlur() {
        // "High contrast text" (accessibility) = Reduce Transparency: solid shell, no blur
        val highContrast = android.provider.Settings.Secure.getInt(contentResolver, "high_text_contrast_enabled", 0) == 1
        val blurRequested = Prefs.bool(this, "blur", false) && !highContrast
        val blur = blurRequested && blurSupported()
        blurOn = blur
        val fill = when {
            blur -> androidx.core.graphics.ColorUtils.blendARGB(tokens.glassFillBlur, tokens.glassFillNoBlur, capBlurK)
            blurRequested -> tokens.glassFillNoBlur
            else -> tokens.background
        }
        // refraction re-samples the shell layer, so it is off for "high contrast text" like the blur
        refraction?.update(Prefs.bool(this, "refract", false) && !highContrast)
        val glass = shell?.also { it.setFill(fill) } ?: tokens.shell(fill).also { shell = it }
        glass.topPad = host?.capTop ?: 0
        if (host?.background !== glass) host?.background = glass
        window.window?.let { w ->
            val hv = host
            if (blur && hv != null) {
                val mask = ShapeMaskDrawable(glass, hv)
                glass.onShapeChanged = { mask.invalidateSelf(); w.decorView.invalidateOutline() }
                w.setBackgroundDrawable(mask)
                w.decorView.invalidateOutline()
                hv.post { w.decorView.invalidateOutline() }   // host position is known only after layout
                if (outlineHost !== hv) {                     // the outline has bounds: follow the host when its size changes
                    outlineHost = hv
                    hv.addOnLayoutChangeListener { _, l, t, r, b, oldL, oldT, oldR, oldB ->
                        if (r - l != oldR - oldL || b - t != oldB - oldT) w.decorView.invalidateOutline()
                    }
                }
            } else {
                glass.onShapeChanged = null
                w.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            }
            // Android's window-blur docs: the blur is drawn under the window surface, so the window must be translucent
            if (blur) w.setFormat(PixelFormat.TRANSLUCENT)
            if (Build.VERSION.SDK_INT >= 31) w.setBackgroundBlurRadius(if (blur) (tokens.blurRadius * (1f - capBlurK)).toInt() else 0)
        }
    }

    // ---- layout ----
    private fun render(animateRows: Boolean = false, dir: BlurDir = BlurDir.UP) {
        val r = root ?: return
        if (menuLayer != null) dismissMenu()
        // labels of the rows above the bottom row (text + any running Blur Replace), kept to animate only what changes
        val oldRows = if (animateRows) (0 until r.childCount).map { r.getChildAt(it) }
            .filter { it !== strip && it !== r.getChildAt(r.childCount - 1) && it is ViewGroup }
            .map { row -> (row as ViewGroup).children.filterIsInstance<KeyView>().filter { it.text.length == 1 }
                .map { OldKey(it.text.toString(), it.blurSnapshot(), it.visualW, it.visualH,
                    row.left + it.left, row.top + it.top, it.width, it.height, it.textSize, it.typeface, it.currentTextColor) }.toList() }
        else null
        r.removeAllViews()
        letterKeys.clear(); shiftKey = null; pageKey = null
        if (panel != Panel.NONE) { strip = null; emojiGrid = null; r.addView(if (panel == Panel.CLIPBOARD) buildClipPanel() else buildEditPanel()); return }
        val keyH = keyHeight()

        val grid = emojiMode && !emojiSearch
        emojiGrid = null
        strip = when {
            emojiMode && emojiSearch -> buildSearchBar().also { r.addView(it) }
            grid -> buildEmojiTabs().also { r.addView(it) }
            translateMode -> buildTranslateBar().also { r.addView(it) }
            (Prefs.bool(this, "suggest", true) || Prefs.bool(this, "translate", false)) -> buildStrip().also { r.addView(it) }
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



    // ---- panels (clipboard, text editing): the shell's whole content is replaced with Blur Replace ----
    private enum class Panel { NONE, CLIPBOARD, EDIT }
    private var panel = Panel.NONE
    private var panelH = 0
    private var swapAnim: android.animation.ValueAnimator? = null
    private val clips = ArrayList<String>()
    private var clipList: LinearLayout? = null
    private var selecting = false
    private var selectKey: KeyView? = null
    private var copyKey: KeyView? = null
    private val clipListener = android.content.ClipboardManager.OnPrimaryClipChangedListener { captureClip() }

    /** In: the old content blurs out (up), the panel arrives from blur. Out: the same, the other way (down). */
    private fun openPanel(p: Panel) {
        val r = root ?: return
        panelH = (r.height - r.paddingTop - r.paddingBottom).coerceAtLeast(180.dp)   // the shell keeps its height
        selecting = false
        if (p == Panel.CLIPBOARD) captureClip()
        swapShell(BlurDir.UP) { panel = p; render() }
    }

    private fun closePanel() = swapShell(BlurDir.DOWN) { panel = Panel.NONE; selecting = false; render() }

    /**
     * Blur Replace of everything inside the shell: a bitmap of what is shown leaves (blur, fade, drift along [dir]) while
     * the rebuilt content arrives from blur, on the same out/in timing as the text transition.
     */
    private fun swapShell(dir: BlurDir, change: () -> Unit) {
        val r = root
        if (r == null || r.width <= 0 || r.height <= 0 || !animationsOn(this)) { change(); return }
        swapAnim?.cancel()
        val bmp = android.graphics.Bitmap.createBitmap(r.width, r.height, android.graphics.Bitmap.Config.ARGB_8888)
        r.draw(android.graphics.Canvas(bmp))
        val ghost = android.widget.ImageView(themed).apply {
            setImageBitmap(bmp); scaleType = android.widget.ImageView.ScaleType.FIT_XY
            layout(0, 0, r.width, r.height)
        }
        change()
        val kids = (0 until r.childCount).map { r.getChildAt(it) }
        r.overlay.add(ghost)
        kids.forEach { it.blurFx(1f, dir, 1) }
        swapAnim = android.animation.ValueAnimator.ofFloat(0f, 1f).apply {
            duration = BlurTextTokens.TOTAL_MS
            interpolator = android.view.animation.LinearInterpolator()
            addUpdateListener {
                val t = it.animatedFraction * BlurTextTokens.TOTAL_MS
                ghost.blurFx(BlurTextTokens.outEase.getInterpolation((t / BlurTextTokens.OUT_MS).coerceIn(0f, 1f)), dir, -1)
                val x = ((t - BlurTextTokens.IN_DELAY_MS) / BlurTextTokens.IN_MS).coerceIn(0f, 1f)
                val s = 1f - BlurTextTokens.inEase.getInterpolation(x)
                kids.forEach { v -> v.blurFx(s, dir, 1) }
            }
            addListener(object : android.animation.AnimatorListenerAdapter() {     // also runs when cancelled
                override fun onAnimationEnd(a: android.animation.Animator) {
                    r.overlay.remove(ghost); bmp.recycle()
                    kids.forEach { v -> v.blurFx(0f, dir, 1) }
                    if (swapAnim === a) swapAnim = null
                }
            })
            start()
        }
    }

    private fun lp(w: Int, h: Int, weight: Float = 0f) = LinearLayout.LayoutParams(w, h, weight)

    /** Back arrow, title and an optional text action: the panel's own top bar (same footprint as the suggestion strip). */
    private fun panelBar(title: String, action: String? = null, onAction: () -> Unit = {}) = LinearLayout(themed).apply {
        orientation = LinearLayout.HORIZONTAL
        isBaselineAligned = false
        layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, 42.dp).apply { setMargins(3.dp, 0, 3.dp, 3.dp) }
        setPadding(3.dp, 3.dp, 3.dp, 3.dp)
        val medium = ResourcesCompat.getFont(themed, R.font.gsr_medium)
        addView(android.widget.ImageView(themed).apply {
            setImageResource(R.drawable.ic_arrow_back)
            imageTintList = android.content.res.ColorStateList.valueOf(tokens.keyText)
            scaleType = android.widget.ImageView.ScaleType.CENTER_INSIDE
            setPadding(10.dp, 10.dp, 10.dp, 10.dp); imageAlpha = 220
            contentDescription = "Back"
            background = tokens.stripItemBackground()
            setOnClickListener { closePanel() }
        }, lp(46.dp, MATCH_PARENT))
        addView(TextView(themed).apply {
            text = title; textSize = 16f; gravity = Gravity.CENTER_VERTICAL
            setPadding(8.dp, 0, 8.dp, 0); setTextColor(tokens.keyText)
            if (medium != null) typeface = medium
        }, lp(0, MATCH_PARENT, 1f))
        if (action != null) addView(TextView(themed).apply {
            text = action; textSize = 14f; gravity = Gravity.CENTER
            setPadding(14.dp, 0, 14.dp, 0); setTextColor(tokens.keyText)
            background = tokens.stripItemBackground()
            setOnClickListener { onAction() }
        }, lp(WRAP_CONTENT, MATCH_PARENT))
    }

    // clipboard: Android has no history API, so copies are collected while the keyboard process lives (memory only, never written to disk)
    private fun captureClip() {
        runCatching {
            val c = getSystemService(android.content.ClipboardManager::class.java).primaryClip ?: return
            if (c.itemCount == 0) return
            if (Build.VERSION.SDK_INT >= 33 && c.description.extras?.getBoolean("android.content.extra.IS_SENSITIVE") == true) {
                clipTimeMs = 0L          // a sensitive copy (password manager) is never offered on the strip
                return
            }
            val t = c.getItemAt(0).coerceToText(this)?.toString()?.takeIf { it.isNotBlank() } ?: return
            // when this clip was copied: a copy made before the keyboard opened still counts as fresh
            val ts = c.description.timestamp.takeIf { it > 0L } ?: System.currentTimeMillis()
            if (ts != clipTimeMs) { clipTimeMs = ts; clipDismissed = null }    // a new copy (even of the same text) is offered again
            if (clips.firstOrNull() != t) {
                clips.remove(t); clips.add(0, t)
                while (clips.size > 20) clips.removeAt(clips.lastIndex)
                if (panel == Panel.CLIPBOARD) fillClips()
            }
            trHandler.post { updateStrip() }
        }
    }

    // ---- clipboard chip on the suggestion strip (like Gboard): a fresh copy can be pasted with one tap ----
    private val clipFreshMs = 60_000L            // empty field: offered for a minute
    private val clipFreshTypingMs = 20_000L      // field with text: only briefly, the predictions matter more
    private var clipTimeMs = 0L                  // when the current primary clip was copied (0 = nothing to offer)
    private var clipDismissed: String? = null    // pasted already: not offered again until copied anew
    private var clipLayoutOn = false
    private var clipChip: LinearLayout? = null
    private var clipText: TextView? = null
    private var lastStripKey: String? = null
    private val clipExpire = Runnable { updateStrip() }
    private val stripRefresh = Runnable { if (panel == Panel.NONE) refreshStripIfStale() }

    private fun freshClip(maxAgeMs: Long): String? {
        val t = clips.firstOrNull() ?: return null
        if (t == clipDismissed || clipTimeMs <= 0L) return null
        return if (System.currentTimeMillis() - clipTimeMs <= maxAgeMs) t else null
    }

    private fun clipPreview(t: String) = t.replace(Regex("\\s+"), " ").trim().take(80)

    private fun pasteClip(t: String) {
        lastFix = null
        currentInputConnection?.commitText(t, 1)
        clipDismissed = t
        afterEdit()
    }

    /** Never put a copied text on the strip in a password field. */
    private fun isPasswordField(): Boolean {
        val t = currentInputEditorInfo?.inputType ?: return false
        val v = t and InputType.TYPE_MASK_VARIATION
        return when (t and InputType.TYPE_MASK_CLASS) {
            InputType.TYPE_CLASS_TEXT -> v == InputType.TYPE_TEXT_VARIATION_PASSWORD ||
                v == InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD || v == InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD
            InputType.TYPE_CLASS_NUMBER -> v == InputType.TYPE_NUMBER_VARIATION_PASSWORD
            else -> false
        }
    }

    /** While the chip shows, the three suggestion slots collapse and the chip (icon + text, centred) takes the whole strip. */
    private fun setClipChipLayout(s: LinearLayout, on: Boolean, text: String?) {
        val chip = clipChip ?: return
        if (on) clipText?.text = text
        if (clipLayoutOn == on) return
        clipLayoutOn = on
        for (i in 0 until 3) (s.getChildAt(i * 2).layoutParams as LinearLayout.LayoutParams).weight = if (on) 0f else 1f
        chip.animate().cancel()
        if (on) {
            chip.visibility = View.VISIBLE
            if (animationsOn(this) && s.isLaidOut) {
                chip.alpha = 0f; chip.scaleX = 0.94f; chip.scaleY = 0.94f
                chip.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(BlurTextTokens.TOTAL_MS).setInterpolator(BlurTextTokens.inEase).start()
            } else { chip.alpha = 1f; chip.scaleX = 1f; chip.scaleY = 1f }
        } else {
            chip.visibility = View.GONE
            chip.alpha = 1f; chip.scaleX = 1f; chip.scaleY = 1f
        }
        s.requestLayout()
    }

    private fun stripKey(): String {
        val ic = currentInputConnection ?: return "-"
        return "${ic.getTextBeforeCursor(120, 0)}\u0001${ic.getTextAfterCursor(1, 0)}\u0001${ic.getSelectedText(0)?.length ?: 0}"
    }

    private fun refreshStripIfStale() { if (stripKey() != lastStripKey) updateStrip() }

    /** The field changed under us (the app cleared it after Send, select-all, a tap that moved the caret): the strip must follow. */
    override fun onUpdateSelection(oldSelStart: Int, oldSelEnd: Int, newSelStart: Int, newSelEnd: Int, candidatesStart: Int, candidatesEnd: Int) {
        super.onUpdateSelection(oldSelStart, oldSelEnd, newSelStart, newSelEnd, candidatesStart, candidatesEnd)
        if (oldSelStart == newSelStart && oldSelEnd == newSelEnd) return
        trHandler.removeCallbacks(stripRefresh)
        trHandler.post(stripRefresh)
    }

    private fun buildClipPanel() = LinearLayout(themed).apply {
        orientation = LinearLayout.VERTICAL
        layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, panelH)
        addView(panelBar("Clipboard", "Clear") { clips.clear(); fillClips() })
        val list = LinearLayout(themed).apply { orientation = LinearLayout.VERTICAL }
        clipList = list
        addView(android.widget.ScrollView(themed).apply {
            isVerticalScrollBarEnabled = false; overScrollMode = View.OVER_SCROLL_NEVER; addView(list)
        }, lp(MATCH_PARENT, 0, 1f))
        fillClips()
    }

    private fun fillClips() {
        val box = clipList ?: return
        box.removeAllViews()
        if (clips.isEmpty()) {
            box.addView(TextView(themed).apply {
                text = "Copied text shows up here"; textSize = 14f; gravity = Gravity.CENTER
                setTextColor(tokens.keyText); alpha = 0.5f
            }, lp(MATCH_PARENT, 120.dp))
            return
        }
        val rad = Prefs.int(this, "key_r", KbTokens.KEY_R_DEFAULT).dp.toFloat()
        fun shape(c: Int) = GradientDrawable().apply { setColor(c); cornerRadius = rad }
        for (t in clips.toList()) box.addView(LinearLayout(themed).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            background = android.graphics.drawable.StateListDrawable().apply {
                addState(intArrayOf(android.R.attr.state_pressed), shape(tokens.pressed))
                addState(intArrayOf(), shape(tokens.key))
            }
            setOnClickListener {
                lastFix = null
                currentInputConnection?.commitText(t, 1)
                clipDismissed = t
                afterEdit()
                closePanel()
            }
            addView(TextView(themed).apply {
                text = t; textSize = 14.5f; maxLines = 3; ellipsize = android.text.TextUtils.TruncateAt.END
                setTextColor(tokens.keyText); setPadding(14.dp, 10.dp, 6.dp, 10.dp)
            }, lp(0, WRAP_CONTENT, 1f))
            addView(TextView(themed).apply {
                text = "\u2715"; textSize = 14f; gravity = Gravity.CENTER; alpha = 0.55f
                setTextColor(tokens.keyText); contentDescription = "Delete"
                setOnClickListener { clips.remove(t); fillClips() }
            }, lp(44.dp, 44.dp))
        }, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { setMargins(3.dp, 0, 3.dp, 6.dp) })
    }

    // text editing: arrows, Select (arrows then extend the selection), select all / copy / paste, line start / end, backspace
    private fun LinearLayout.pk(w: Float, label: String, style: KeyStyle = KeyStyle.SPECIAL, repeat: Boolean = false, icon: Int = 0, onKey: () -> Unit): KeyView {
        val g = Prefs.int(this@PillboardService, "key_gap", 3)
        return KeyView(themed, label, style, tokens, repeat, icon, onKey).also {
            it.setHitInset(g.dp, (g + 2).dp)
            it.glass = shell
            addView(it, if (orientation == LinearLayout.VERTICAL) lp(MATCH_PARENT, 0, w) else lp(0, MATCH_PARENT, w))
        }
    }

    private fun LinearLayout.group(w: Float, vertical: Boolean, build: LinearLayout.() -> Unit) = addView(LinearLayout(themed).apply {
        orientation = if (vertical) LinearLayout.VERTICAL else LinearLayout.HORIZONTAL
        isBaselineAligned = false
        build()
    }, if (this.orientation == LinearLayout.VERTICAL) lp(MATCH_PARENT, 0, w) else lp(0, MATCH_PARENT, w))

    private fun buildEditPanel() = LinearLayout(themed).apply {
        orientation = LinearLayout.VERTICAL
        layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, panelH)
        addView(panelBar("Text editing"))
        val body = LinearLayout(themed).apply { orientation = LinearLayout.VERTICAL }
        addView(body, lp(MATCH_PARENT, 0, 1f))
        body.group(3f, vertical = false) {
            pk(1f, "Left", repeat = true, icon = R.drawable.ic_chevron_left) { moveKey(KeyEvent.KEYCODE_DPAD_LEFT) }
            group(1f, vertical = true) {
                pk(1f, "Up", repeat = true, icon = R.drawable.ic_chevron_up) { moveKey(KeyEvent.KEYCODE_DPAD_UP) }
                selectKey = pk(1f, "Select", if (selecting) KeyStyle.ACTIVE else KeyStyle.SPECIAL) {
                    selecting = !selecting
                    selectKey?.setKeyStyle(if (selecting) KeyStyle.ACTIVE else KeyStyle.SPECIAL)
                }
                pk(1f, "Down", repeat = true, icon = R.drawable.ic_chevron_down) { moveKey(KeyEvent.KEYCODE_DPAD_DOWN) }
            }
            pk(1f, "Right", repeat = true, icon = R.drawable.ic_chevron_right) { moveKey(KeyEvent.KEYCODE_DPAD_RIGHT) }
            group(1f, vertical = true) {
                pk(1f, "Select all") { editAction(android.R.id.selectAll) }
                copyKey = pk(1f, "Copy") { editAction(android.R.id.copy); selecting = false; selectKey?.setKeyStyle(KeyStyle.SPECIAL) }
                pk(1f, "Paste") { lastFix = null; editAction(android.R.id.paste); afterEdit() }
            }
        }
        body.group(1f, vertical = false) {
            pk(1.5f, "Line start", repeat = true, icon = R.drawable.ic_first_page) { moveKey(KeyEvent.KEYCODE_MOVE_HOME) }
            pk(1.5f, "Line end", repeat = true, icon = R.drawable.ic_last_page) { moveKey(KeyEvent.KEYCODE_MOVE_END) }
            pk(1f, "Backspace", repeat = true, icon = R.drawable.ic_backspace) { backspace() }
        }
        refreshEdit()
    }

    /** Arrow / home / end; with Select on, Shift is held so the selection grows instead of the caret moving. */
    private fun moveKey(code: Int) {
        val ic = currentInputConnection ?: return
        val meta = if (selecting) KeyEvent.META_SHIFT_ON or KeyEvent.META_SHIFT_LEFT_ON else 0
        val t = SystemClock.uptimeMillis()
        ic.sendKeyEvent(KeyEvent(t, t, KeyEvent.ACTION_DOWN, code, 0, meta))
        ic.sendKeyEvent(KeyEvent(t, t, KeyEvent.ACTION_UP, code, 0, meta))
        refreshEdit()
    }

    private fun editAction(id: Int) { currentInputConnection?.performContextMenuAction(id); refreshEdit() }

    /** Copy is dimmed while nothing is selected (the selection updates a moment after the key event). */
    private fun refreshEdit() = trHandler.postDelayed({
        if (panel == Panel.EDIT) copyKey?.alpha = if (currentInputConnection?.getSelectedText(0)?.isNotEmpty() == true) 1f else 0.4f
    }, 60)

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
        dismissMenu()
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


    // ---- strip button (top-right): a feature slot. tap = run it · swipe up/down = switch feature · hold = all features ----
    private fun View.attachGestures(onLong: (View) -> Unit, onSwipe: (up: Boolean) -> Unit) {
        val slop = android.view.ViewConfiguration.get(context).scaledTouchSlop
        val swipeDist = 22.dp
        var x0 = 0f; var y0 = 0f
        var moved = false; var done = false
        val longRun = Runnable {
            if (done) return@Runnable
            done = true; isPressed = false
            performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
            onLong(this)
        }
        setOnTouchListener { v, e ->
            when (e.actionMasked) {
                android.view.MotionEvent.ACTION_DOWN -> {
                    x0 = e.x; y0 = e.y; moved = false; done = false
                    v.isPressed = true
                    v.postDelayed(longRun, android.view.ViewConfiguration.getLongPressTimeout().toLong())
                }
                android.view.MotionEvent.ACTION_MOVE -> if (!done) {
                    val dx = e.x - x0; val dy = e.y - y0
                    if (Math.abs(dx) > slop || Math.abs(dy) > slop) { moved = true; v.removeCallbacks(longRun) }
                    if (Math.abs(dy) >= swipeDist && Math.abs(dy) > Math.abs(dx)) {   // vertical only
                        done = true; v.isPressed = false
                        v.performHapticFeedback(android.view.HapticFeedbackConstants.KEYBOARD_TAP)
                        onSwipe(dy < 0)
                    }
                }
                android.view.MotionEvent.ACTION_UP -> {
                    v.removeCallbacks(longRun); v.isPressed = false
                    if (!done && !moved) v.performClick()
                }
                android.view.MotionEvent.ACTION_CANCEL -> { v.removeCallbacks(longRun); v.isPressed = false }
            }
            true
        }
    }

    private fun runFeature(f: StripFeature) = when (f) {
        StripFeature.TRANSLATE -> startTranslate()
        StripFeature.CLIPBOARD -> openPanel(Panel.CLIPBOARD)
        StripFeature.TEXT_EDIT -> openPanel(Panel.EDIT)
        StripFeature.SETTINGS -> openApp()
    }

    private fun setFeature(f: StripFeature, dir: BlurDir, animate: Boolean = true) {
        Prefs.setStr(this, "strip_feature", f.id)
        stripIcon?.set(f.icon, dir, animate)
        stripIcon?.contentDescription = f.label
    }

    /** Swipe up = next feature (icon blurs upward), swipe down = previous (icon blurs downward). */
    private fun cycleFeature(up: Boolean) {
        val l = StripFeature.available(this)
        if (l.size < 2) return
        val i = l.indexOf(StripFeature.current(this)).coerceAtLeast(0)
        setFeature(l[(i + if (up) 1 else l.size - 1) % l.size], if (up) BlurDir.UP else BlurDir.DOWN)
    }

    private fun openApp() {
        startActivity(android.content.Intent(this, SettingsActivity::class.java).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    /**
     * The capsule's right edge reaches the shell edge a little later than it rises (so it never pokes out of the shell's
     * rounded corner while it is still low), and the icon column follows the capsule's centre so the active icon starts
     * exactly on the button's icon. [full] is the open capsule and [rest] the button; [begin] where this morph starts.
     */
    private fun capsuleTweak(layer: FrameLayout, begin: RoundRectState, rest: RoundRectState, full: RoundRectState): (Float) -> Unit {
        val opening = begin === rest
        val col = layer.getChildAt(0)          // the icon column (the layer itself must not move: it is clipped by the contour)
        val r0 = begin.r; val r1 = if (opening) full.r else rest.r
        return { f ->
            val x = ((f - 0.3f) / 0.5f).coerceIn(0f, 1f)
            val lag = x * x * (3f - 2f * x)
            menuCur.r = r0 + (r1 - r0) * lag
            col.translationX = -(full.r - menuCur.r) / 2f
        }
    }

    private fun clearMenu() {
        trHandler.removeCallbacks(menuAutoClose)
        menuMorph?.cancel(); menuMorph = null
        menuLayer?.let { host?.removeView(it) }
        menuLayer = null; menuIcons = emptyList(); menuClosing = false; menuFrom = null; menuTo = null
        shell?.clearBulge()
        stripIcon?.alpha = 1f
        host?.let { it.menuOpen = false; it.requestLayout() }
        setHeadroom(0)
    }

    /**
     * Close the capsule with the morph run backwards from wherever it is: it sinks back into the shell (the neck pinches
     * off, the contour returns to the plain shell) while its icons blur out.
     */
    private fun dismissMenu(animated: Boolean = false) {
        val layer = menuLayer ?: return
        val g = shell; val from = menuFrom
        if (!animated || !animationsOn(this) || g == null || from == null) { clearMenu(); return }
        if (menuClosing) return
        menuClosing = true
        trHandler.removeCallbacks(menuAutoClose)
        menuMorph?.cancel()
        menuIcons.drop(1).forEachIndexed { i, v -> v.fadeOut(delay = i * 20L, dir = BlurDir.DOWN) }   // [0] is the active icon: it stays, it IS the button
        val start = RoundRectState(menuCur.l, menuCur.t, menuCur.r, menuCur.b, menuCur.radius)
        val to = menuTo ?: from
        menuMorph = morphBlob(layer, menuOx, menuOy, g, menuCur, start, from, MorphPreset(220L, BlurTextTokens.outEase),
            tweak = capsuleTweak(layer, start, from, to)) { clearMenu() }
    }

    /**
     * Hold on the strip button: the shell itself swells at the button and a capsule grows upward out of it. Shell and
     * capsule are ONE shape (one SDF contour with a liquid neck, see [GlassSurfaceDrawable]); there is no second window,
     * no second outline. Bottom = the active feature, which stays exactly where the button's icon is; above it the other
     * features, on top a "+". The other icons arrive with Blur Replace and leave the same way.
     */
    private fun setHeadroom(px: Int) {
        val h = host ?: return
        if (h.capTop == px) return
        h.capTop = px
        fadeCapsuleBlur(if (px > 0) 1f else 0f)
        (root?.layoutParams as? FrameLayout.LayoutParams)?.topMargin = px
        shell?.topPad = px
        h.requestLayout()
        window.window?.decorView?.let { d -> d.post { d.invalidateOutline() } }     // the window outline depends on the headroom
    }

    /** Grow the window upward first, build the capsule once that layout has happened. */
    private fun showStripMenu(anchor: View) {
        dismissMenu()
        val h = host ?: return
        val need = StripCapsule.headroom(StripFeature.available(this).size)
        if (h.capTop == need) { showStripMenuNow(anchor); return }
        setHeadroom(need)
        h.viewTreeObserver.addOnGlobalLayoutListener(object : android.view.ViewTreeObserver.OnGlobalLayoutListener {
            override fun onGlobalLayout() {
                h.viewTreeObserver.removeOnGlobalLayoutListener(this)
                if (anchor.isAttachedToWindow) showStripMenuNow(anchor) else setHeadroom(0)
            }
        })
    }

    private fun showStripMenuNow(anchor: View) {
        val h0 = host ?: return
        val g = shell ?: return
        val feats = StripFeature.available(this)
        val cur = StripFeature.current(this)
        val rotated = feats.indexOf(cur).let { i -> feats.drop(i) + feats.take(i) }     // active first
        val itemH = StripCapsule.ITEM_H.dp
        val pad = StripCapsule.PAD.dp
        val w = anchor.width
        val n = rotated.size + 1
        val fullH = n * itemH + 2 * pad
        // column is top -> bottom: "+", then the others, the active feature last (on the button)
        val col = LinearLayout(themed).apply { orientation = LinearLayout.VERTICAL }
        val icons = ArrayList<BlurIconView>()        // bottom first
        val layer = FrameLayout(themed).apply {
            setPadding(pad, pad, pad, pad)
            addView(col)
        }
        fun cell(res: Int, action: () -> Unit): BlurIconView = BlurIconView(themed, tokens.keyText, 0.9f).apply {
            background = tokens.stripItemBackground()
            setOnClickListener { if (!menuClosing) action() }
            set(res, animate = false)
        }
        val plus = cell(R.drawable.ic_add) { dismissMenu(true) }          // add-feature: placeholder, features come later
        col.addView(plus, LinearLayout.LayoutParams(MATCH_PARENT, itemH))
        val rest = rotated.drop(1).reversed()
        val restCells = rest.map { f ->
            cell(f.icon) { setFeature(f, BlurDir.UP, animate = false); icons.firstOrNull()?.set(f.icon, BlurDir.UP); dismissMenu(true) }
        }
        restCells.forEach { col.addView(it, LinearLayout.LayoutParams(MATCH_PARENT, itemH)) }
        val active = cell(cur.icon) { dismissMenu(true) }
        col.addView(active, LinearLayout.LayoutParams(MATCH_PARENT, itemH))
        icons += active; icons += restCells.reversed(); icons += plus

        // everything below is in host (= glass drawable) coordinates
        val al = IntArray(2); anchor.getLocationInWindow(al)
        val hl = IntArray(2); h0.getLocationInWindow(hl)
        val ax = al[0] - hl[0]; val ay = al[1] - hl[1]
        val bottom = ay + anchor.height / 2 + itemH / 2 + pad          // the active cell is centred on the button
        val top = (bottom - fullH).coerceAtLeast(0)
        // the capsule's right side is flush with the shell's right edge (no corner to fill, no curve down the side)
        val right = h0.width
        val lw = (right - ax).coerceAtLeast(w)
        h0.addView(layer, FrameLayout.LayoutParams(lw, bottom - top, Gravity.TOP or Gravity.START).apply { leftMargin = ax; topMargin = top })
        menuLayer = layer; menuIcons = icons
        menuOx = ax.toFloat(); menuOy = top.toFloat()
        val bh = anchor.height.toFloat()
        // starts as exactly the button's rounded rect (inside the shell: invisible), ends as the whole capsule
        val from = RoundRectState(ax.toFloat(), ay.toFloat(), (ax + w).toFloat(), ay + bh, bh / 2f)
        val to = RoundRectState(ax.toFloat(), top.toFloat(), (ax + lw).toFloat(), bottom.toFloat(), lw / 2f)
        menuFrom = from; menuTo = to
        h0.menuRect.set(ax - 6.dp, top - 6.dp, ax + lw + 6.dp, bottom + 6.dp)
        h0.menuOpen = true
        h0.requestLayout()                       // re-runs onComputeInsets: the headroom now takes touches
        stripIcon?.alpha = 0f                    // the capsule's active cell is the button's icon now
        if (animationsOn(this)) {
            icons.drop(1).forEachIndexed { i, v -> v.fadeIn(delay = 60L + i * 40L, dir = BlurDir.UP) }
            menuMorph = morphBlob(layer, menuOx, menuOy, g, menuCur, from, to, MorphPresets.container(tokens),
                tweak = capsuleTweak(layer, from, from, to)) { menuMorph = null }
        } else {
            menuCur.lerp(from, to, 1f)
            g.setBulge(to.l, to.t, to.r, to.b, to.radius)
        }
        trHandler.postDelayed(menuAutoClose, 8000)
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
        val stripH = if (Prefs.bool(this, "suggest", true) || Prefs.bool(this, "translate", false)) 45.dp else 0
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
        divAnim?.cancel(); dividersOn = null        // decided by the first updateStrip()
        clipLayoutOn = false; lastStripKey = null
        repeat(3) { i ->
            if (i > 0) addView(View(themed).apply { setBackgroundColor(tokens.divider); blurFx(1f, BlurDir.UP, 1) },
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
        run {   // clipboard chip: a pill with the icon and the copied text centred as ONE group; takes the slots' place while a fresh copy is on offer
            val iv = android.widget.ImageView(themed).apply {
                setImageResource(R.drawable.ic_clipboard)
                imageTintList = android.content.res.ColorStateList.valueOf(tokens.keyText)
                imageAlpha = 200
            }
            val tx = TextView(themed).apply {
                textSize = 15f; maxLines = 1; includeFontPadding = false
                ellipsize = android.text.TextUtils.TruncateAt.END
                setTextColor(tokens.keyText)
                if (medium != null) typeface = medium
            }
            val chip = LinearLayout(themed).apply {
                orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER
                visibility = View.GONE
                setPadding(14.dp, 0, 16.dp, 0)
                fun pill(c: Int) = GradientDrawable().apply { setColor(c); cornerRadius = tokens.stripRadius - 4.dp }
                background = android.graphics.drawable.StateListDrawable().apply {
                    addState(intArrayOf(android.R.attr.state_pressed), pill(tokens.stripPressed))
                    addState(intArrayOf(), pill(androidx.core.graphics.ColorUtils.setAlphaComponent(tokens.keyText, 0x14)))
                }
                contentDescription = "Paste copied text"
                addView(iv, LinearLayout.LayoutParams(18.dp, 18.dp).apply { marginEnd = 9.dp })
                addView(tx, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT))
            }
            clipChip = chip; clipText = tx
            addView(chip, LinearLayout.LayoutParams(0, MATCH_PARENT, 1f).apply { setMargins(2.dp, 1.dp, 2.dp, 1.dp) })
        }
        run {
            addView(View(themed).apply { setBackgroundColor(tokens.divider) },
                LinearLayout.LayoutParams(1.dp, 14.dp).apply { gravity = Gravity.CENTER_VERTICAL })
            val f = StripFeature.current(this@PillboardService)
            val icon = BlurIconView(themed, tokens.keyText).apply {
                set(f.icon, animate = false)
                contentDescription = f.label
                background = tokens.stripItemBackground()
                setOnClickListener { runFeature(StripFeature.current(this@PillboardService)) }
                attachGestures(onLong = { showStripMenu(it) }, onSwipe = { up -> cycleFeature(up) })
            }
            stripIcon = icon
            addView(icon, LinearLayout.LayoutParams(46.dp, MATCH_PARENT))
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
        lastStripKey = stripKey()
        val c = suggestionCtx()
        // empty field: the centre slot carries the name with no dividers, whatever the engine would offer (start-of-sentence words)
        val empty = fieldIsEmpty()
        val brand = empty || !suggestionsAllowed()      // terminals, passwords, numbers: just the name and the dot widget
        // a fresh copy takes the whole strip (like Gboard) until the person starts a word, pastes it, or it gets old
        val maxAge = if (empty) clipFreshMs else clipFreshTypingMs
        val clip = if (c.word.isEmpty() && !isPasswordField()) freshClip(maxAge) else null
        val res = if (clip == null && !brand && Prefs.bool(this, "suggest", true)) Suggestions.suggest(c) else null
        val any = (0 until 3).any { res?.slot(it) != null }
        setClipChipLayout(s, clip != null, clip?.let { clipPreview(it) })
        clipChip?.setOnClickListener(if (clip != null) View.OnClickListener { pasteClip(clip) } else null)
        for (i in 0 until 3) {
            val tv = s.getChildAt(i * 2) as BlurTextView
            val cand = res?.slot(i)          // slot 1 (centre) is the lead candidate
            val nextText = when {
                clip != null -> ""
                brand && i == 1 -> "Glimkey"
                else -> cand?.label ?: ""
            }
            if (i == 1) {       // the name is bold, the lead candidate medium
                val f = if (brand) ResourcesCompat.getFont(themed, R.font.gsr_bold) else ResourcesCompat.getFont(themed, R.font.gsr_medium)
                if (f != null && tv.typeface !== f) tv.typeface = f
            }
            // tv.tag = the text this slot is heading to. Blur Replace sets the text at once and only animates the paint;
            // changes faster than RAPID_GAP_MS (fast typing) snap, so typing never waits on the effect.
            if (tv.tag != nextText) {
                tv.tag = nextText
                tv.setTextBlur(nextText, minGapMs = BlurTextTokens.RAPID_GAP_MS)
            }
            val action: View.OnClickListener? = when {
                cand != null -> View.OnClickListener { applySuggestion(c, cand) }
                else -> null
            }
            tv.setOnClickListener(action)
            tv.isClickable = action != null      // setOnClickListener(null) would leave the name pressable
        }
        setDividers(s, any)
        trHandler.removeCallbacks(clipExpire)
        if (clip != null) trHandler.postDelayed(clipExpire, (maxAge - (System.currentTimeMillis() - clipTimeMs)).coerceAtLeast(0L) + 50L)
    }

    private var dividersOn: Boolean? = null
    private var divAnim: android.animation.ValueAnimator? = null

    /** Empty = nothing before, after or selected. An unknown answer (null) is never "empty", and a select-all is not empty. */
    private fun fieldIsEmpty(): Boolean {
        val ic = currentInputConnection ?: return false
        val before = ic.getTextBeforeCursor(1, 0) ?: return false
        val after = ic.getTextAfterCursor(1, 0) ?: return false
        if (before.isNotEmpty() || after.isNotEmpty()) return false
        return ic.getSelectedText(0).isNullOrEmpty()
    }

    /** The two dividers between the suggestion slots: Blur Replace in when suggestions appear, out when they go (same timing as the text). */
    private fun setDividers(s: LinearLayout, on: Boolean) {
        val first = dividersOn == null
        if (dividersOn == on) return
        dividersOn = on
        val ds = listOf(s.getChildAt(1), s.getChildAt(3))
        divAnim?.cancel()
        val to = if (on) 0f else 1f
        if (first || !animationsOn(this) || !s.isLaidOut) { ds.forEach { it.blurFx(to, BlurDir.UP, 1) }; return }
        val start = 1f - Math.sqrt(ds[0].alpha.coerceIn(0f, 1f).toDouble()).toFloat()     // continue from where an interrupted run is
        val dir = if (on) BlurDir.UP else BlurDir.DOWN
        val sign = if (on) 1 else -1
        divAnim = android.animation.ValueAnimator.ofFloat(0f, 1f).apply {
            duration = if (on) BlurTextTokens.IN_MS else BlurTextTokens.OUT_MS
            startDelay = if (on) BlurTextTokens.IN_DELAY_MS else 0L        // after the words, like the text's own arrival
            interpolator = if (on) BlurTextTokens.inEase else BlurTextTokens.outEase
            addUpdateListener { anim -> val v1 = start + (to - start) * (anim.animatedValue as Float); ds.forEach { d -> d.blurFx(v1, dir, sign) } }
            start()
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
