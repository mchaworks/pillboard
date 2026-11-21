package dev.pillboard.keyboard

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.drawable.ColorDrawable
import android.widget.ImageView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat

/** Home + subpages (Typing, Appearance, Feedback, Advanced, About) with search. One activity, in-place navigation. */
class SettingsActivity : Activity() {

    private enum class Page(val label: String, val sub: String, val icon: Int = 0, val tile: Int = 0) {
        HOME("Pillboard", ""),
        TYPING("Typing", "Suggestions, auto-correction, capitalization", R.drawable.ic_set_typing, 0xFF0A84FF.toInt()),
        TRANSLATE("Translation", "Online translate, target language", R.drawable.ic_translate, 0xFF32ADE6.toInt()),
        APPEARANCE("Appearance", "Colors, shape, spacing, height", R.drawable.ic_set_look, 0xFFBF5AF2.toInt()),
        LAYOUT("Layout", "Number row, emoji key", R.drawable.ic_set_layout, 0xFFFF9F0A.toInt()),
        FEEDBACK("Feedback", "Haptics", R.drawable.ic_set_feedback, 0xFF30B650.toInt()),
        ADVANCED("Advanced", "Reset settings, emoji history", R.drawable.ic_set_more, 0xFF8E8E93.toInt()),
        ABOUT("About", "Version and license", R.drawable.ic_set_info, 0xFF5AB8E8.toInt()),
    }

    /** Single source of truth: pages and search both render from this list. */
    private class Item(val page: Page, val title: String, val keys: String, val visible: () -> Boolean = { true }, val make: () -> View)

    private lateinit var ui: SettingsUi
    private lateinit var scroll: ScrollView
    private lateinit var col: LinearLayout
    private lateinit var top: LinearLayout
    private lateinit var body: LinearLayout
    private var status: android.widget.TextView? = null
    private var previewHolder: LinearLayout? = null
    private var testInput: EditText? = null
    private var tryHeader: View? = null
    private var page = Page.HOME
    private var query = ""

    private val blurOk get() = Build.VERSION.SDK_INT >= 31 &&
        (getSystemService(WINDOW_SERVICE) as WindowManager).isCrossWindowBlurEnabled

    /** What the system actually allows right now, so a blur that "does nothing" has a visible reason. */
    private fun blurStatus(): String = when {
        Build.VERSION.SDK_INT < 31 -> "Needs Android 12+"
        !blurOk -> "Blocked by system"
        Prefs.bool(this, "blur", false) -> "Active"
        else -> "Available, off"
    }

    private val items: List<Item> by lazy {
        listOf(
            Item(Page.TYPING, "Suggestions", "word bar predictions") { ui.toggle("Suggestions", "Word bar above the keys", "suggest", true) },
            Item(Page.TYPING, "Auto-capitalization", "capital sentence shift") { ui.toggle("Auto-capitalization", "Capital letter at the start of sentences", "autocap", true) },
            Item(Page.TYPING, "Double-space period", "dot full stop") { ui.toggle("Double-space period", "Tap space twice to insert a period", "dblspace", true) },
            Item(Page.TYPING, "Auto-correction", "fix typos undo") { ui.toggle("Auto-correction", "Fix common typos on space, backspace undoes", "autocorrect", true) },
            Item(Page.TYPING, "Space bar cursor", "slide swipe cursor move space") { ui.toggle("Space bar cursor", "Slide on the space bar to move the cursor", "spaceslide", true) },
            Item(Page.TRANSLATE, "Translation", "translate online language word bar") {
                ui.toggle("Translation", "Adds a translate button to the word bar. While translate mode is open, the text of the field you are typing in is sent to the service chosen below. Never used in password fields.", "translate", false) { renderBody() }
            },
            Item(Page.TRANSLATE, "Service", "translate provider google mymemory libre online") {
                ui.link("Service", Translator.provider(this).label, chevron = false) { pickProvider() }
            },
            Item(Page.TRANSLATE, "Target language", "translate language target default system") {
                ui.link("Target language", Languages.nameOf(this, targetCode()), chevron = false) { pickTarget() }
            },
            Item(Page.TRANSLATE, "Server URL", "translate libre server url host", visible = { Translator.provider(this) == Translator.Provider.LIBRE }) {
                ui.link("Server URL", Prefs.str(this, "tr_url", "").ifEmpty { "Not set" }, chevron = false) { editText("Server URL", "tr_url", false) }
            },
            Item(Page.TRANSLATE, "API key", "translate libre key token", visible = { Translator.provider(this) == Translator.Provider.LIBRE }) {
                ui.link("API key", if (Prefs.str(this, "tr_key", "").isEmpty()) "Optional" else "Set", chevron = false) { editText("API key", "tr_key", true) }
            },
            Item(Page.LAYOUT, "Number row", "digits numbers top") { ui.toggle("Number row", "Show 1–0 above the letters", "numrow", false) },
            Item(Page.LAYOUT, "Emoji key", "emoji smiley picker button") { ui.toggle("Emoji key", "Emoji button on the 123 page", "emoji", true) },
            Item(Page.ADVANCED, "Clear recent emoji", "emoji recent history forget") { ui.link("Clear recent emoji", "Forget the emoji you used lately", chevron = false) { EmojiData.clearRecents(this) } },
            Item(Page.APPEARANCE, "Material You colors", "dynamic wallpaper theme") {
                ui.toggle("Material You colors",
                    if (Build.VERSION.SDK_INT >= 31) "Follow your wallpaper accent" else "Needs Android 12+, using default palette",
                    "dynamic", true, enabled = Build.VERSION.SDK_INT >= 31, onChange = { recreate() })
            },
            Item(Page.APPEARANCE, "Accent color", "palette preset", visible = { !Prefs.dynamic(this) }) { ui.accent { refreshPreview() } },
            Item(Page.APPEARANCE, "Blur background", "frosted glass") {
                ui.toggle("Blur background",
                    if (blurOk) "Liquid Glass backdrop blur" else "Glass tint fallback; system blur unavailable",
                    "blur", false, enabled = true)
            },
            Item(Page.APPEARANCE, "Blur status", "blur glass system available blocked") { ui.info("Blur status", blurStatus()) },
            Item(Page.APPEARANCE, "Press glow", "glow light press key shader") {
                ui.toggle("Press glow",
                    if (Build.VERSION.SDK_INT >= 33) "The glass lights up around the key you press" else "Needs Android 13+",
                    "glass_fx", true, enabled = Build.VERSION.SDK_INT >= 33, onChange = { refreshPreview() })
            },
            Item(Page.APPEARANCE, "Liquid refraction", "glass refraction edge bevel dispersion bend background shell") {
                ui.toggle("Liquid refraction",
                    if (Build.VERSION.SDK_INT >= 33) "The glass edge bends the keys near it, with a faint color fringe" else "Needs Android 13+",
                    "refract", false, enabled = Build.VERSION.SDK_INT >= 33, onChange = { refreshPreview() })
            },
            Item(Page.APPEARANCE, "Liquid lens", "refraction lens magnifier key preview glass") {
                ui.toggle("Liquid lens",
                    if (Build.VERSION.SDK_INT >= 33) "Key preview refracts the keys around it" else "Needs Android 13+, using the plain bubble",
                    "lens", true, enabled = Build.VERSION.SDK_INT >= 33)
            },
            Item(Page.APPEARANCE, "Key preview", "popup bubble character magnify") {
                ui.toggle("Key preview", "Floating character bubble above the pressed key", "keypreview", true)
            },
            Item(Page.APPEARANCE, "Key height", "size tall short dp") {
                ui.slider("Key height", 42, 62, "key_h", 50, listOf("Short" to 44, "Standard" to 50, "Tall" to 58)) { refreshPreview() }
            },
            Item(Page.APPEARANCE, "Key shape", "radius corner round square") {
                ui.slider("Key shape", 4, 20, "key_r", KbTokens.KEY_R_DEFAULT, listOf("Square" to 6, "Standard" to KbTokens.KEY_R_DEFAULT, "Round" to 18)) { refreshPreview() }
            },
            Item(Page.APPEARANCE, "Key spacing", "gap margin tight") {
                ui.slider("Key spacing", 2, 6, "key_gap", 3, listOf("Tight" to 2, "Standard" to 3, "Roomy" to 5)) { refreshPreview() }
            },
            Item(Page.FEEDBACK, "Haptic feedback", "vibration vibrate") { ui.toggle("Haptic feedback", "Vibrate on key press", "haptic", true) },
            Item(Page.ADVANCED, "Reset settings", "default restore") { ui.link("Reset settings", "Return all keyboard settings to default", chevron = false) { confirmReset() } },
            Item(Page.ABOUT, "Version", "build about") { ui.info("Version", versionName()) },
        )
    }

    private fun versionName(): String = try { packageManager.getPackageInfo(packageName, 0).versionName ?: "" } catch (e: Exception) { "" }

    override fun onCreate(savedInstanceState: Bundle?) {
        setTheme(if (Prefs.dynamic(this)) R.style.Theme_Pillboard_App else R.style.Theme_Pillboard_App_Static)
        super.onCreate(savedInstanceState)
        ui = SettingsUi(this)
        page = savedInstanceState?.getString("page")?.let { runCatching { Page.valueOf(it) }.getOrNull() } ?: Page.HOME

        top = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        body = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(
                this@SettingsActivity.top,
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
            )
            addView(
                this@SettingsActivity.body,
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
            )
            setPadding(16.dp, 24.dp, 16.dp, 32.dp)
        }
        scroll = ScrollView(this).apply { addView(col) }
        scroll.setBackgroundColor(ui.pageBg)
        scroll.isVerticalScrollBarEnabled = false
        window.setBackgroundDrawable(ColorDrawable(ui.pageBg))
        setContentView(scroll)
        WindowCompat.getInsetsController(window, scroll).apply { isAppearanceLightStatusBars = !ui.night; isAppearanceLightNavigationBars = !ui.night }
        // Edge-to-edge (targetSdk 35): pad by real system bar insets, and by the keyboard (IME) when open,
        // otherwise the "Test typing" field ends up hidden behind the keyboard.
        WindowCompat.setDecorFitsSystemWindows(window, false)
        ViewCompat.setOnApplyWindowInsetsListener(scroll) { _, insets ->
            val b = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            val ime = insets.getInsets(WindowInsetsCompat.Type.ime())
            col.setPadding(16.dp, b.top + 16.dp, 16.dp, maxOf(b.bottom, ime.bottom) + 32.dp)
            val field = testInput
            if (ime.bottom > 0 && field != null && field.hasFocus()) {
                scroll.post { field.requestRectangleOnScreen(android.graphics.Rect(0, 0, field.width, field.height + 24.dp), false) }
            }
            insets
        }
        renderPage()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState); outState.putString("page", page.name)
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        when {
            query.isNotEmpty() -> { query = ""; renderPage() }
            page != Page.HOME -> go(Page.HOME)
            else -> super.onBackPressed()
        }
    }

    override fun onResume() { super.onResume(); updateStatus() }

    private fun updateStatus() {
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        val enabled = imm.enabledInputMethodList.any { it.packageName == packageName }
        val active = Settings.Secure.getString(contentResolver, Settings.Secure.DEFAULT_INPUT_METHOD)?.startsWith(packageName) == true
        status?.text = when { active -> "●  Active"; enabled -> "●  Enabled"; else -> "●  Not enabled" }
    }

    private fun go(p: Page) {
        val forward = p != Page.HOME
        page = p; query = ""; renderPage(); scroll.scrollTo(0, 0)
        settle(forward)
    }

    /** Page change: the new page glides in from the side it belongs to (right = deeper, left = back) on the smooth ease. */
    private fun settle(forward: Boolean) {
        if (!animationsOn(this)) return
        val d = (if (forward) 28 else -28).dp.toFloat()
        for (v in listOf<View>(top, body)) {
            v.alpha = 0f; v.translationX = d
            v.animate().alpha(1f).translationX(0f).setDuration(300).setInterpolator(BlurTextTokens.outEase).start()
        }
    }

    // ---- rendering ----
    private fun renderPage() {
        top.removeAllViews()
        status = null
        if (page == Page.HOME) {
            top.addView(ui.title("Pillboard", 36f))
            top.addView(ui.caption("Your keyboard, your style.").apply { setPadding(2.dp, 2.dp, 0, 16.dp) })
            val st = ui.badge().also { status = it }
            top.addView(ui.card(LinearLayout(this).apply {
                gravity = Gravity.CENTER_VERTICAL
                addView(st)
                addView(View(context), LinearLayout.LayoutParams(0, 1, 1f))
                addView(ui.button("Enable", false, compact = true) { startActivity(Intent(Settings.ACTION_INPUT_METHOD_SETTINGS)) },
                    LinearLayout.LayoutParams(-2, 40.dp).apply { marginEnd = 8.dp })
                addView(ui.button("Switch", true, compact = true) {
                    (getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager).showInputMethodPicker()
                }, LinearLayout.LayoutParams(-2, 40.dp))
            }, h = 14.dp, v = 12.dp), ui.lp(bottom = 14.dp))
            top.addView(ui.searchField("Search settings").apply {
                setText(query); setSelection(query.length)
                addTextChangedListener(object : TextWatcher {
                    override fun afterTextChanged(s: Editable?) { query = s?.toString().orEmpty(); renderBody() }
                    override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                    override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                })
            }, ui.lp())
            updateStatus()
        } else {
            // iOS: accent-colored back chevron + parent name, then the large title
            top.addView(LinearLayout(this).apply {
                gravity = Gravity.CENTER_VERTICAL
                minimumHeight = 44.dp
                setPadding(0, 0, 24.dp, 0)
                contentDescription = "Back"
                addView(ImageView(context).apply {
                    setImageResource(R.drawable.ic_chevron_right); rotation = 180f
                    imageTintList = ColorStateList.valueOf(ui.primary)
                }, LinearLayout.LayoutParams(24.dp, 24.dp).apply { marginStart = (-6).dp })
                addView(android.widget.TextView(context).apply { text = "Pillboard"; textSize = 17f; setTextColor(ui.primary) })
                setOnClickListener { go(Page.HOME) }
            }, LinearLayout.LayoutParams(-2, -2))
            top.addView(ui.title(page.label, 32f).apply { setPadding(2.dp, 4.dp, 0, 4.dp) })
        }
        renderBody()
    }

    private fun renderBody() {
        body.removeAllViews(); previewHolder = null; tryHeader = null
        when {
            page == Page.HOME && query.isNotBlank() -> searchResults()
            page == Page.HOME -> home()
            else -> {
                if (page == Page.APPEARANCE) addPreview()
                body.addView(ui.box(items.filter { it.page == page && it.visible() }.map { it.make() }), ui.lp())
                if (page == Page.TRANSLATE) body.addView(ui.caption(
                    "Off by default. When it is on, text is sent over the internet only while translate mode is open, to the service you pick. " +
                    "Google uses an unofficial public endpoint that can change or stop working without notice; MyMemory has a small free daily limit; " +
                    "LibreTranslate needs your own server.").apply { textSize = 13f; setPadding(20.dp, 4.dp, 20.dp, 0) })
            }
        }
    }

    private fun searchResults() {
        val q = query.trim().lowercase()
        val hits = items.filter { it.visible() && (it.title.lowercase().contains(q) || it.keys.contains(q)) }
        if (hits.isEmpty()) {
            body.addView(ui.caption("No matching settings").apply { setPadding(8.dp, 24.dp, 0, 0) })
        } else {
            body.addView(ui.section("Results"))
            body.addView(ui.box(hits.map { it.make() }), ui.lp())
        }
    }

    private fun addPreview() {
        previewHolder = LinearLayout(this)
        body.addView(previewHolder, ui.lp(bottom = 12.dp))
        buildPreview()
    }

    private fun home() {
        addPreview()
        body.addView(LinearLayout(this).apply {
            addView(ui.button("Customize", false) { go(Page.APPEARANCE) }, LinearLayout.LayoutParams(0, 48.dp, 1f).apply { marginEnd = 5.dp })
            addView(ui.button("Test typing", true) { scroll.smoothScrollTo(0, (tryHeader?.top ?: 0) + this@SettingsActivity.top.height); focusTest() },
                LinearLayout.LayoutParams(0, 48.dp, 1f).apply { marginStart = 5.dp })
        }, ui.lp())
        body.addView(ui.section("Settings"))
        body.addView(ui.box(listOf(Page.TYPING, Page.TRANSLATE, Page.APPEARANCE, Page.LAYOUT, Page.FEEDBACK)
            .map { p -> ui.link(p.label, p.sub, icon = p.icon, tile = p.tile) { go(p) } }, inset = ui.tileInset), ui.lp())
        tryHeader = ui.section("Try it").also { body.addView(it) }
        val (wrap, input) = ui.testField(); testInput = input
        body.addView(ui.box(listOf(wrap)), ui.lp())
        body.addView(ui.section("More"))
        body.addView(ui.box(listOf(Page.ADVANCED, Page.ABOUT)
            .map { p -> ui.link(p.label, p.sub, icon = p.icon, tile = p.tile) { go(p) } }, inset = ui.tileInset), ui.lp())
    }

    private fun targetCode() = Prefs.str(this, "tr_target", "").ifEmpty { Languages.defaultTarget(this) }

    private fun pickProvider() {
        val ps = Translator.Provider.values()
        MaterialAlertDialogBuilder(this).setTitle("Translation service")
            .setSingleChoiceItems(ps.map { it.label }.toTypedArray(), ps.indexOf(Translator.provider(this))) { d, i ->
                Prefs.setStr(this, "tr_provider", ps[i].id); d.dismiss(); renderBody()
            }.setNegativeButton("Cancel", null).show()
    }

    private fun pickTarget() {
        val ls = Languages.ordered(this)
        MaterialAlertDialogBuilder(this).setTitle("Target language")
            .setSingleChoiceItems(ls.map { it.name }.toTypedArray(), ls.indexOfFirst { it.code == targetCode() }) { d, i ->
                Prefs.setStr(this, "tr_target", ls[i].code); d.dismiss(); renderBody()
            }.setNegativeButton("Cancel", null).show()
    }

    private fun editText(title: String, key: String, secret: Boolean) {
        val input = EditText(this).apply {
            setText(Prefs.str(this@SettingsActivity, key, "")); setSingleLine()
            inputType = android.text.InputType.TYPE_CLASS_TEXT or
                if (secret) android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD else android.text.InputType.TYPE_TEXT_VARIATION_URI
            hint = if (secret) "Optional" else "https://your-server.example"
        }
        val wrap = android.widget.FrameLayout(this).apply { setPadding(24.dp, 8.dp, 24.dp, 0); addView(input) }
        MaterialAlertDialogBuilder(this).setTitle(title).setView(wrap)
            .setPositiveButton("Save") { _, _ -> Prefs.setStr(this, key, input.text.toString().trim()); renderBody() }
            .setNegativeButton("Cancel", null).show()
    }

    private fun confirmReset() {
        val d = MaterialAlertDialogBuilder(this)
            .setTitle("Reset all keyboard settings?")
            .setMessage("Themes, layout and preferences will return to their defaults.")
            .setPositiveButton("Reset") { _, _ -> Prefs.clear(this); go(Page.HOME); recreate() }
            .setNegativeButton("Cancel", null).create()
        d.setOnShowListener { d.getButton(AlertDialog.BUTTON_POSITIVE)?.setTextColor(ui.error) }
        d.show()
    }

    private fun focusTest() {
        testInput?.requestFocus()
        (getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager).showSoftInput(testInput, 0)
    }

    // ---- live preview: same KeyView + KbTokens as the real keyboard, ~70% scale ----
    private fun refreshPreview() { if (previewHolder != null) buildPreview() }

    private fun buildPreview() {
        val holder = previewHolder ?: return
        val t = KbTokens(this)
        val scale = 0.7f
        val h = (Prefs.int(this, "key_h", 50).dp * scale).toInt()
        val g = (Prefs.int(this, "key_gap", 3).dp * scale).toInt()
        holder.removeAllViews()
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(5.dp, 6.dp, 5.dp, 6.dp)
            background = t.shell()
        }
        // same refraction as the real keyboard, so the toggle is visible right here
        if (Build.VERSION.SDK_INT >= 33) ShellRefraction(card, t.containerRadius).update(Prefs.bool(this, "refract", false))
        if (Prefs.bool(this, "suggest", true)) {
            card.addView(LinearLayout(this).apply {
                isBaselineAligned = false
                background = t.stripBackground()
                setPadding(2.dp, 2.dp, 2.dp, 2.dp)
                listOf("I", "The", "I'm").forEachIndexed { i, w ->
                    if (i > 0) addView(android.view.View(context).apply { setBackgroundColor(t.divider) },
                        LinearLayout.LayoutParams(1.dp, 12.dp).apply { gravity = Gravity.CENTER_VERTICAL })
                    addView(android.widget.TextView(context).apply {
                        text = w; textSize = 13f; gravity = Gravity.CENTER; setTextColor(t.keyText)
                    }, LinearLayout.LayoutParams(0, -1, 1f))
                }
            }, LinearLayout.LayoutParams(-1, 30.dp).apply { setMargins(2.dp, 0, 2.dp, 2.dp) })
        }
        /** label, weight, style, icon (0 = text key) */
        class K(val label: String, val w: Float, val style: KeyStyle = KeyStyle.LETTER, val icon: Int = 0)
        fun row(keys: List<K>) = LinearLayout(this).apply {
            isBaselineAligned = false
            keys.forEach { k ->
                addView(KeyView(context, k.label, k.style, t, false, k.icon) {}.apply {
                    textSize = if (k.label.length == 1 && k.icon == 0) 16f else 13f
                    iconSize = 16.dp
                }, LinearLayout.LayoutParams(0, h, k.w).apply { setMargins(g, g + 2.dp, g, g + 2.dp) })
            }
        }
        fun letters(s: String) = s.map { K("$it", 1f) }
        card.addView(row(letters("qwertyuiop")))
        card.addView(row(letters("asdfghjkl")))
        card.addView(row(listOf(K("Shift", 1.5f, KeyStyle.SPECIAL, R.drawable.ic_shift)) + letters("zxcvbnm") +
            K("Backspace", 1.5f, KeyStyle.SPECIAL, R.drawable.ic_backspace)))
        card.addView(row(listOf(K("123", 1.35f, KeyStyle.SPECIAL), K(",", 0.85f, KeyStyle.SPECIAL), K("space", 4.45f, KeyStyle.SPECIAL),
            K(".", 0.85f, KeyStyle.SPECIAL), K("return", 1.5f, KeyStyle.ACCENT))))
        holder.addView(card, LinearLayout.LayoutParams(-1, -2))
    }
}
