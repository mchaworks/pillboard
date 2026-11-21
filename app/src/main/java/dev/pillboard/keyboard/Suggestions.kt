package dev.pillboard.keyboard

import android.content.Context
import java.io.File
import java.io.StringWriter
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Android side of the suggestion engine ([SuggestCore]): loads every dictionary in assets/dict at startup and keeps
 * what the person has learned in the app's private storage. There is no language setting and no use of the system
 * locale: all dictionaries are active, and the language is inferred from the text being written.
 */
internal object Suggestions {
    @Volatile private var core: SuggestCore? = null
    private var file: File? = null
    private val started = AtomicBoolean(false)
    private val io = Executors.newSingleThreadExecutor { r -> Thread(r, "glimkey-suggest").apply { isDaemon = true } }

    /** Load dictionaries and the learned model off the main thread; [onReady] runs on that background thread. */
    fun init(app: Context, onReady: () -> Unit) {
        val ctx = app.applicationContext
        if (!started.compareAndSet(false, true)) {
            if (core != null) onReady()
            return
        }
        io.execute {
            try {
                val dicts = (ctx.assets.list("dict") ?: emptyArray()).filter { it.endsWith(".txt") }.sorted()
                    .mapNotNull { name ->
                        try { ctx.assets.open("dict/$name").use { Lexicon.parse(name.removeSuffix(".txt"), it) } }
                        catch (_: Exception) { null }
                    }
                val f = File(ctx.filesDir, "learned.v1")
                val user = UserModel()
                if (f.exists()) try { f.reader(Charsets.UTF_8).use { user.read(it) } } catch (_: Exception) { user.clear(); user.dirty = false }
                file = f
                core = SuggestCore(dicts, user)
            } catch (_: Throwable) {
                started.set(false)      // try again next time the keyboard opens
                return@execute
            }
            onReady()
        }
    }

    fun context(before: CharSequence): SuggestCore.Ctx =
        core?.context(before) ?: SuggestCore.Ctx("", BOS, emptyList(), blocked = true)

    fun suggest(c: SuggestCore.Ctx): SuggestCore.Result? = core?.suggest(c)

    fun autoCorrection(c: SuggestCore.Ctx): String? = core?.autoCorrection(c)

    fun learn(c: SuggestCore.Ctx, surface: String, weight: Float = 1f) { core?.learn(c, surface, weight) }

    /** The person put their own spelling back after an auto-correction. */
    fun undoCorrection(typed: String) { core?.undoCorrection(typed) }

    /** Write what was learned since the last save; cheap when nothing changed. */
    fun save() {
        val u = core?.user ?: return
        val f = file ?: return
        if (!u.dirty) return
        val text = StringWriter().also { u.write(it) }.toString()
        u.dirty = false
        io.execute {
            try {
                val tmp = File(f.path + ".tmp")
                tmp.writeText(text, Charsets.UTF_8)
                if (!tmp.renameTo(f)) { f.delete(); tmp.renameTo(f) }
            } catch (_: Exception) { u.dirty = true }
        }
    }

    /** Forget everything learned: words, word pairs, habits, refused corrections. */
    fun clearLearned(app: Context) {
        core?.user?.clear()
        io.execute { try { File(app.applicationContext.filesDir, "learned.v1").delete() } catch (_: Exception) { } }
        core?.user?.dirty = false
    }
}
