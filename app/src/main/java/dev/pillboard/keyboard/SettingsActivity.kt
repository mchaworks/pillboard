package dev.pillboard.keyboard

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.graphics.drawable.GradientDrawable
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
import androidx.core.view.WindowInsetsCompat

/** Home + subpages (Typing, Appearance, Feedback, Advanced, About) with search. One activity, in-place navigation. */
class SettingsActivity : Activity() {

    private enum class Page(val label: String, val sub: String) {
        HOME("Pillboard", ""),
        TYPING("Typing", "Suggestions, auto-correction, capitalization"),
        APPEARANCE("Appearance", "Colors, shape, spacing, height"),
        LAYOUT("Layout", "Number row"),
        FEEDBACK("Feedback", "Haptics"),
        ADVANCED("Advanced", "Reset settings"),
        ABOUT("About", "Version and license"),
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

    private val items: List<Item> by lazy {
        listOf(
            Item(Page.TYPING, "Suggestions", "word bar predictions") { ui.toggle("Suggestions", "Word bar above the keys", "suggest", true) },
            Item(Page.TYPING, "Auto-capitalization", "capital sentence shift") { ui.toggle("Auto-capitalization", "Capital letter at the start of sentences", "autocap", true) },
            Item(Page.TYPING, "Double-space period", "dot full stop") { ui.toggle("Double-space period", "Tap space twice to insert a period", "dblspace", true) },
            Item(Page.TYPING, "Auto-correction", "fix typos undo") { ui.toggle("Auto-correction", "Fix common typos on space, backspace undoes", "autocorrect", true) },
            Item(Page.LAYOUT, "Number row", "digits numbers top") { ui.toggle("Number row", "Show 1–0 above the letters", "numrow", false) },
            Item(Page.APPEARANCE, "Material You colors", "dynamic wallpaper theme") {
                ui.toggle("Material You colors",
                    if (Build.VERSION.SDK_INT >= 31) "Follow your wallpaper accent" else "Needs Android 12+, using default palette",
                    "dynamic", true, enabled = Build.VERSION.SDK_INT >= 31, onChange = { recreate() })
            },
            Item(Page.APPEARANCE, "Accent color", "palette preset", visible = { !Prefs.dynamic(this) }) { ui.accent { refreshPreview() } },
            Item(Page.APPEARANCE, "Blur background", "frosted glass") {
                ui.toggle("Blur background", if (blurOk) "Frosted glass behind the keyboard" else "Not available on this device", "blur", false, enabled = blurOk)
            },
            Item(Page.APPEARANCE, "Key height", "size tall short dp") {
                ui.slider("Key height", 42, 62, "key_h", 50, listOf("Short" to 44, "Standard" to 50, "Tall" to 58)) { refreshPreview() }
            },
            Item(Page.APPEARANCE, "Key shape", "radius corner round square") {
                ui.slider("Key shape", 4, 20, "key_r", 10, listOf("Square" to 6, "Standard" to 10, "Round" to 16)) { refreshPreview() }
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
            addView(top, ui.lp()); addView(body, ui.lp())
            setPadding(16.dp, 24.dp, 16.dp, 32.dp)
        }
        scroll = ScrollView(this).apply { addView(col) }
        setContentView(scroll)
        // Edge-to-edge (targetSdk 35): pad by real system bar insets.
        ViewCompat.setOnApplyWindowInsetsListener(scroll) { _, insets ->
            val b = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            col.setPadding(16.dp, b.top + 16.dp, 16.dp, b.bottom + 32.dp)
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

    private fun go(p: Page) { page = p; query = ""; renderPage(); scroll.scrollTo(0, 0) }

    // ---- rendering ----
    private fun renderPage() {
        top.removeAllViews()
        status = null
        if (page == Page.HOME) {
            top.addView(ui.title("Pillboard"))
            top.addView(ui.caption("Your keyboard, your style.").apply { setPadding(0, 2.dp, 0, 12.dp) })
            val st = ui.badge().also { status = it }
            top.addView(LinearLayout(this).apply {
                gravity = Gravity.CENTER_VERTICAL
                addView(st)
                addView(View(context), LinearLayout.LayoutParams(0, 1, 1f))
                addView(ui.button("Enable", false, compact = true) { startActivity(Intent(Settings.ACTION_INPUT_METHOD_SETTINGS)) },
                    LinearLayout.LayoutParams(-2, 36.dp).apply { marginEnd = 6.dp })
                addView(ui.button("Switch", true, compact = true) {
                    (getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager).showInputMethodPicker()
                }, LinearLayout.LayoutParams(-2, 36.dp))
            }, ui.lp(bottom = 12.dp))
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
            top.addView(LinearLayout(this).apply {
                gravity = Gravity.CENTER_VERTICAL
                addView(android.widget.TextView(context).apply {
                    text = "‹"; textSize = 32f; setTextColor(ui.onSurface); setPadding(4.dp, 0, 16.dp, 4.dp)
                    contentDescription = "Back"; setOnClickListener { go(Page.HOME) }
                })
                addView(ui.title(page.label, 24f))
            })
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
            addView(ui.button("Customize", false) { go(Page.APPEARANCE) }, LinearLayout.LayoutParams(0, 44.dp, 1f).apply { marginEnd = 4.dp })
            addView(ui.button("Test typing", true) { scroll.smoothScrollTo(0, (tryHeader?.top ?: 0) + top.height); focusTest() },
                LinearLayout.LayoutParams(0, 44.dp, 1f).apply { marginStart = 4.dp })
        }, ui.lp())
        body.addView(ui.section("Settings"))
        body.addView(ui.box(listOf(Page.TYPING, Page.APPEARANCE, Page.LAYOUT, Page.FEEDBACK).map { p -> ui.link(p.label, p.sub) { go(p) } }), ui.lp())
        tryHeader = ui.section("Try it").also { body.addView(it) }
        val (wrap, input) = ui.testField(); testInput = input
        body.addView(ui.box(listOf(wrap)), ui.lp())
        body.addView(ui.section("More"))
        body.addView(ui.box(listOf(Page.ADVANCED, Page.ABOUT).map { p -> ui.link(p.label, p.sub) { go(p) } }), ui.lp())
    }

    private fun confirmReset() {
        val d = AlertDialog.Builder(this)
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
        val h = (Prefs.int(this, "key_h", 50).dp * 0.7f).toInt()
        holder.removeAllViews()
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(4.dp, 5.dp, 4.dp, 5.dp)
            background = GradientDrawable().apply { setColor(t.background); cornerRadius = 22.dp.toFloat() }
        }
        fun row(keys: List<Pair<String, Float>>, special: Set<String> = emptySet()) = LinearLayout(this).apply {
            keys.forEach { (k, w) ->
                addView(KeyView(context, k, if (k in special) KeyStyle.SPECIAL else KeyStyle.LETTER, t, false) {}
                    .apply { textSize = if (k.length == 1 && k !in special) 16f else 13f },
                    LinearLayout.LayoutParams(0, h, w).apply { val g = (Prefs.int(this@SettingsActivity, "key_gap", 3).dp * 0.7f).toInt(); setMargins(g, g + 1.dp, g, g + 1.dp) })
            }
        }
        card.addView(row("qwertyuiop".map { "$it" to 1f }))
        card.addView(row("asdfghjkl".map { "$it" to 1f }))
        card.addView(row(listOf("⇧" to 1.5f) + "zxcvbnm".map { "$it" to 1f } + ("⌫" to 1.5f), setOf("⇧", "⌫")))
        holder.addView(card, LinearLayout.LayoutParams(-1, -2))
    }
}
