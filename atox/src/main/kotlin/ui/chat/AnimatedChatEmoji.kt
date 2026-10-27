// SPDX-License-Identifier: GPL-3.0-only

package ltd.evilcorp.atox.ui.chat

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Rect
import android.graphics.drawable.Drawable
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.text.Spannable
import android.text.SpannableStringBuilder
import android.text.style.ImageSpan
import android.util.LruCache
import android.util.TypedValue
import android.view.ViewGroup
import android.widget.TextView
import com.airbnb.lottie.LottieComposition
import com.airbnb.lottie.LottieCompositionFactory
import com.airbnb.lottie.LottieDrawable
import java.lang.ref.WeakReference
import java.util.Collections
import java.util.IdentityHashMap
import java.util.concurrent.Executors
import ltd.evilcorp.atox.R
import org.json.JSONObject

private const val EMOJI_INDEX_ASSET = "animated_emoji/index.json"
private const val ANIMATION_INTERVAL_MS = 20_000L
private const val MAX_RUNNING_ANIMATIONS = 4

internal object AnimatedEmojiAssets {
    private val executor = Executors.newSingleThreadExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())
    private val callbacks = mutableListOf<() -> Unit>()
    @Volatile private var index: AnimatedEmojiIndex? = null
    @Volatile private var loading = false

    fun load(context: Context, ready: () -> Unit) {
        if (index != null) {
            ready()
            return
        }
        synchronized(this) {
            callbacks += ready
            if (loading) return
            loading = true
        }
        val appContext = context.applicationContext
        executor.execute {
            val loaded = runCatching {
                val json = appContext.assets.open(EMOJI_INDEX_ASSET).bufferedReader().use {
                    JSONObject(it.readText())
                }
                AnimatedEmojiIndex(buildMap {
                    json.keys().forEach { emoji ->
                        put(emoji, "animated_emoji/${json.getString(emoji).replace('\\', '/')}")
                    }
                })
            }.getOrElse { AnimatedEmojiIndex(emptyMap()) }
            index = loaded
            val pending = synchronized(this) {
                loading = false
                callbacks.toList().also { callbacks.clear() }
            }
            mainHandler.post { pending.forEach { it() } }
        }
    }

    fun matches(text: String): List<AnimatedEmojiMatch> = findAnimatedEmojiMatches(text, index)
}

class EmojiAnimationCoordinator(private val visibleContainer: ViewGroup) {
    private data class Entry(
        val messageId: Long,
        val drawables: List<LottieDrawable>,
        val emojiOnly: Boolean,
        var lastPlayedAt: Long = 0L,
    )

    private val handler = Handler(Looper.getMainLooper())
    private val entries = IdentityHashMap<TextView, Entry>()
    private val running = Collections.newSetFromMap(IdentityHashMap<LottieDrawable, Boolean>())
    private val lastPlayedByMessage = object : LinkedHashMap<Long, Long>(256, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Long, Long>?): Boolean = size > 256
    }
    private var active = false
    private var scrolling = false
    private val tick = object : Runnable {
        override fun run() {
            startEligibleAnimations()
            if (active && entries.isNotEmpty()) handler.postDelayed(this, 500L)
        }
    }

    fun register(view: TextView, messageId: Long, drawables: List<LottieDrawable>, emojiOnly: Boolean) {
        unregister(view)
        if (drawables.isEmpty()) return
        entries[view] = Entry(messageId, drawables, emojiOnly, lastPlayedByMessage[messageId] ?: 0L)
        schedule(0L)
    }

    fun unregister(view: TextView) {
        entries.remove(view)?.drawables?.forEach(::stop)
    }

    fun setActive(value: Boolean) {
        active = value
        if (!value) {
            handler.removeCallbacks(tick)
            stopAll()
        } else {
            schedule(0L)
        }
    }

    fun setScrolling(value: Boolean) {
        scrolling = value
        if (value) stopAll() else schedule(150L)
    }

    fun clear() {
        active = false
        handler.removeCallbacksAndMessages(null)
        stopAll()
        entries.clear()
    }

    private fun schedule(delayMs: Long) {
        if (!active || entries.isEmpty()) return
        handler.removeCallbacks(tick)
        handler.postDelayed(tick, delayMs)
    }

    private fun startEligibleAnimations() {
        if (!active || scrolling || !systemAnimationsEnabled()) return
        entries.keys.filter { !it.isAttachedToWindow }.forEach(::unregister)
        val now = SystemClock.uptimeMillis()
        entries.entries.asSequence()
            .filter { isVisibleInsideContainer(it.key) }
            .sortedWith(compareBy({ it.value.lastPlayedAt }, { !it.value.emojiOnly }))
            .forEach { (_, entry) ->
                if (now - entry.lastPlayedAt < ANIMATION_INTERVAL_MS) return@forEach
                var started = false
                entry.drawables.forEach { drawable ->
                    if (running.size >= MAX_RUNNING_ANIMATIONS) return@forEach
                    if (running.add(drawable)) {
                        started = true
                        drawable.progress = 0f
                        drawable.repeatCount = 0
                        drawable.addAnimatorListener(object : AnimatorListenerAdapter() {
                            override fun onAnimationEnd(animation: Animator) = finished(drawable)
                            override fun onAnimationCancel(animation: Animator) = finished(drawable)
                        })
                        drawable.playAnimation()
                    }
                }
                if (started) {
                    entry.lastPlayedAt = now
                    lastPlayedByMessage[entry.messageId] = now
                }
            }
    }

    private fun isVisibleInsideContainer(view: TextView): Boolean {
        if (!view.isShown) return false
        val viewRect = Rect()
        val containerRect = Rect()
        return view.getGlobalVisibleRect(viewRect) &&
            visibleContainer.getGlobalVisibleRect(containerRect) &&
            Rect.intersects(viewRect, containerRect)
    }

    private fun finished(drawable: LottieDrawable) {
        running.remove(drawable)
        drawable.removeAllAnimatorListeners()
    }

    private fun stop(drawable: LottieDrawable) {
        running.remove(drawable)
        drawable.cancelAnimation()
        drawable.removeAllAnimatorListeners()
        drawable.progress = 0f
    }

    private fun stopAll() = entries.values.flatMap { it.drawables }.forEach(::stop)

    private fun systemAnimationsEnabled(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.O || ValueAnimator.areAnimatorsEnabled()
}

private class TextViewDrawableCallback(view: TextView) : Drawable.Callback {
    private val viewRef = WeakReference(view)

    override fun invalidateDrawable(who: Drawable) {
        viewRef.get()?.postInvalidate()
    }

    override fun scheduleDrawable(who: Drawable, what: Runnable, `when`: Long) {
        viewRef.get()?.postDelayed(what, (`when` - SystemClock.uptimeMillis()).coerceAtLeast(0L))
    }

    override fun unscheduleDrawable(who: Drawable, what: Runnable) {
        viewRef.get()?.removeCallbacks(what)
    }
}

internal class AnimatedEmojiRenderer(
    private val context: Context,
    private val coordinator: EmojiAnimationCoordinator,
    private val enabled: Boolean,
    onAssetsReady: () -> Unit,
) {
    private data class Binding(
        val messageId: Long,
        val renderedText: String,
        val size: Int,
        val matches: List<AnimatedEmojiMatch>,
        val drawables: List<LottieDrawable>,
        @Suppress("unused") val callback: Drawable.Callback,
    )

    private val mainHandler = Handler(Looper.getMainLooper())
    private val compositions = object : LruCache<String, LottieComposition>(32) {}
    private val pendingLoads = mutableMapOf<String, MutableList<(LottieComposition?) -> Unit>>()
    private val bindings = IdentityHashMap<TextView, Binding>()

    init {
        if (enabled) AnimatedEmojiAssets.load(context, onAssetsReady)
    }

    fun resetStyle(view: TextView, defaultTextSizePx: Float) {
        view.setTextSize(TypedValue.COMPLEX_UNIT_PX, defaultTextSizePx)
    }

    fun clear(view: TextView) {
        coordinator.unregister(view)
        view.setTag(R.id.animated_emoji_bind_token, null)
        bindings.remove(view)?.drawables?.forEach { drawable ->
            drawable.cancelAnimation()
            drawable.removeAllAnimatorListeners()
            drawable.callback = null
        }
    }

    fun bind(view: TextView, messageId: Long) {
        if (!enabled) return
        val rendered = view.text.toString()
        val matches = AnimatedEmojiAssets.matches(rendered)
        if (matches.isEmpty()) {
            clear(view)
            return
        }
        val emojiOnly = isSupportedEmojiOnly(rendered, matches)
        val size = emojiSizePx(view, if (emojiOnly) matches.size else 0)
        if (emojiOnly) {
            view.background = null
            view.setPadding(0, 0, 0, 0)
        }

        val previous = bindings[view]
        if (previous != null && previous.messageId == messageId &&
            previous.renderedText == rendered && previous.size == size && previous.matches == matches
        ) {
            applySpans(view, rendered, previous.matches, previous.drawables)
            coordinator.register(view, messageId, previous.drawables, emojiOnly)
            return
        }
        clear(view)
        val token = Any()
        view.setTag(R.id.animated_emoji_bind_token, token)
        val loaded = arrayOfNulls<LottieComposition>(matches.size)
        var remaining = matches.size
        matches.forEachIndexed { index, match ->
            loadComposition(match.asset) { composition ->
                if (view.getTag(R.id.animated_emoji_bind_token) != token) return@loadComposition
                loaded[index] = composition
                remaining--
                if (remaining == 0) {
                    apply(view, token, messageId, rendered, matches, loaded, size, emojiOnly)
                }
            }
        }
    }

    private fun loadComposition(asset: String, callback: (LottieComposition?) -> Unit) {
        compositions.get(asset)?.let {
            callback(it)
            return
        }
        pendingLoads[asset]?.let {
            it += callback
            return
        }
        pendingLoads[asset] = mutableListOf(callback)
        fun complete(composition: LottieComposition?) {
            mainHandler.post {
                if (composition != null) compositions.put(asset, composition)
                pendingLoads.remove(asset).orEmpty().forEach { it(composition) }
            }
        }
        LottieCompositionFactory.fromAsset(context.applicationContext, asset)
            .addListener { complete(it) }
            .addFailureListener { complete(null) }
    }

    private fun apply(
        view: TextView,
        token: Any,
        messageId: Long,
        rendered: String,
        matches: List<AnimatedEmojiMatch>,
        loaded: Array<LottieComposition?>,
        size: Int,
        emojiOnly: Boolean,
    ) {
        if (view.getTag(R.id.animated_emoji_bind_token) !== token) return
        val callback = TextViewDrawableCallback(view)
        val drawableMatches = mutableListOf<AnimatedEmojiMatch>()
        val drawables = mutableListOf<LottieDrawable>()
        matches.forEachIndexed { index, match ->
            val composition = loaded[index] ?: return@forEachIndexed
            drawableMatches += match
            drawables += LottieDrawable().apply {
                setComposition(composition)
                setBounds(0, 0, size, size)
                this.callback = callback
                progress = 0f
            }
        }
        if (drawables.isEmpty()) return
        bindings[view] = Binding(messageId, rendered, size, drawableMatches, drawables, callback)
        applySpans(view, rendered, drawableMatches, drawables)
        coordinator.register(view, messageId, drawables, emojiOnly)
    }

    private fun applySpans(
        view: TextView,
        rendered: String,
        matches: List<AnimatedEmojiMatch>,
        drawables: List<LottieDrawable>,
    ) {
        val text = SpannableStringBuilder(rendered)
        matches.zip(drawables).forEach { (match, drawable) ->
            text.setSpan(
                ImageSpan(drawable, ImageSpan.ALIGN_BASELINE),
                match.start,
                match.end,
                Spannable.SPAN_EXCLUSIVE_EXCLUSIVE,
            )
        }
        view.text = text
        view.invalidate()
    }

    private fun emojiSizePx(view: TextView, count: Int): Int {
        val dp = when (count) {
            0 -> view.textSize / context.resources.displayMetrics.density * 1.15f
            1 -> 88f
            2, 3 -> 58f
            in 4..6 -> 40f
            else -> 30f
        }
        return (dp * context.resources.displayMetrics.density).toInt().coerceAtLeast(1)
    }
}
