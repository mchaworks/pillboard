package dev.pillboard.keyboard

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.os.SystemClock
import android.widget.TextView
import kotlin.math.max

/**
 * Blur Replace state machine for one [TextView]: old text blurs, fades, shrinks and drifts away while the new text
 * arrives from blur and settles sharp (iOS 26 `.blurReplace`). The host's real text is already the final text, so input
 * and accessibility never wait; this only changes what is painted until it settles.
 *
 * Interruption safe: a change during a running transition turns the arriving label into a departing one from its
 * current blur/opacity (see [BlurTextLayer]), so rapid updates never pop and a stale label can never overwrite a newer
 * one. One shared clock; it stops itself when everything has settled.
 */
internal class BlurTextTransition(private val host: TextView) {
    private val renderer = BlurTextRenderer(host.resources.displayMetrics.density)
    private val layers = ArrayList<BlurTextLayer>(4)
    private var clock: ValueAnimator? = null

    /** True while the transition owns painting of the text. */
    val active: Boolean get() = layers.isNotEmpty()

    /** Copy of the running state, to carry an interrupted transition over to a rebuilt view. */
    fun snapshot(): List<BlurTextLayer>? = if (layers.isEmpty()) null else layers.map { it.copy() }

    /**
     * Start heading to [next]. [from] is the label on screen now (used only when nothing is running);
     * [inherited] is a [snapshot] of a transition that was running on a view this one replaces.
     */
    fun change(next: CharSequence, from: CharSequence?, inherited: List<BlurTextLayer>? = null) {
        val now = SystemClock.uptimeMillis()
        val laid = host.width > 0
        if (inherited != null && layers.isEmpty()) layers.addAll(inherited)

        // whatever was arriving now departs, from exactly where it is
        for (l in layers) if (!l.outgoing) {
            l.outgoing = true
            l.from = l.s; l.to = 1f
            l.delay = 0L
            l.dur = max(90L, (BlurTextTokens.OUT_MS * (1f - l.s)).toLong())
            l.startAt = if (laid) now else -1L
        }
        if (layers.isEmpty() && !from.isNullOrEmpty()) {
            layers.add(BlurTextLayer(from, -1).also {
                it.outgoing = true; it.from = 0f; it.to = 1f; it.dur = BlurTextTokens.OUT_MS
                it.startAt = if (laid) now else -1L
            })
        }
        while (layers.size > BlurTextTokens.MAX_OUTGOING) {
            val worst = layers.maxByOrNull { it.s } ?: break
            renderer.release(worst); layers.remove(worst)
        }
        if (next.isNotEmpty()) {
            val d = if (layers.isEmpty()) 0L else BlurTextTokens.IN_DELAY_MS
            layers.add(BlurTextLayer(next, 1).also {
                it.s = 1f; it.from = 1f; it.to = 0f; it.dur = BlurTextTokens.IN_MS; it.delay = d
                it.startAt = if (laid) now + d else -1L
            })
        }
        if (layers.isEmpty()) { host.invalidate(); return }
        host.invalidate()
        startClock()
    }

    fun draw(canvas: Canvas) {
        for (l in layers) renderer.draw(canvas, host, l)     // departing first, arriving on top
    }

    /** Snap to the final text immediately. */
    fun cancel() {
        clock?.cancel(); clock = null
        if (layers.isEmpty()) return
        for (l in layers) renderer.release(l)
        layers.clear()
        host.invalidate()
    }

    private fun startClock() {
        if (clock?.isRunning == true) return
        clock = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 1000L
            repeatCount = ValueAnimator.INFINITE
            addUpdateListener { tick() }
            start()
        }
    }

    private fun tick() {
        val now = SystemClock.uptimeMillis()
        var running = false
        for (i in layers.indices.reversed()) {
            val l = layers[i]
            if (l.startAt < 0L) {                       // waiting for the first laid-out frame
                if (host.width > 0) l.startAt = now + l.delay
                running = true
                continue
            }
            val x = ((now - l.startAt).toFloat() / l.dur).coerceIn(0f, 1f)
            val e = if (l.outgoing) BlurTextTokens.outEase.getInterpolation(x) else BlurTextTokens.inEase.getInterpolation(x)
            l.s = l.from + (l.to - l.from) * e
            if (x < 1f) running = true
            else if (l.outgoing) { renderer.release(l); layers.removeAt(i) }
        }
        if (!running) { cancel(); return }
        host.invalidate()
    }
}

/**
 * [TextView] that can change its text with Blur Replace. Reusable: any label that changes meaningfully (keyboard mode,
 * suggestions, modifier labels) can extend or use this; static text is simply never routed through [setTextBlur].
 */
open class BlurTextView(context: Context) : TextView(context) {
    private var blur: BlurTextTransition? = null
    private var lastChangeAt = 0L
    private var inChange = false

    /**
     * Set [next] now (input never waits) and play Blur Replace from the current label (or [from]).
     * Changes closer than [minGapMs] to the previous one snap instead (typing responsiveness first); animations
     * switched off in system settings also snap. [inherited] carries a running transition over from a replaced view.
     */
    internal fun setTextBlur(next: CharSequence, from: CharSequence? = null, minGapMs: Long = 0L, inherited: List<BlurTextLayer>? = null) {
        val prev = text
        val now = SystemClock.uptimeMillis()
        val rapid = minGapMs > 0L && now - lastChangeAt < minGapMs
        lastChangeAt = now
        if (!animationsOn(context) || rapid) { blur?.cancel(); setText(next); return }
        if (from == null && inherited == null && prev.contentEquals(next)) return
        val t = blur ?: BlurTextTransition(this).also { blur = it }
        inChange = true
        setText(next)
        inChange = false
        t.change(next, from ?: prev, inherited)
    }

    /** State of a running transition, to hand to a replacement view's [setTextBlur]. */
    internal fun blurSnapshot(): List<BlurTextLayer>? = blur?.snapshot()

    override fun setText(text: CharSequence?, type: BufferType?) {
        if (!inChange) blur?.cancel()           // any other text change wins over a running transition
        super.setText(text, type)
    }

    override fun onDraw(canvas: Canvas) {
        val b = blur
        if (b != null && b.active) b.draw(canvas) else super.onDraw(canvas)
    }

    override fun onDetachedFromWindow() {
        blur?.cancel()
        super.onDetachedFromWindow()
    }
}
