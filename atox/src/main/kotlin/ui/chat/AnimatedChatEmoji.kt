// SPDX-License-Identifier: GPL-3.0-only

package ltd.evilcorp.atox.ui.chat

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Rect
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.text.Spannable
import android.text.SpannableStringBuilder
import android.text.style.ImageSpan
import android.util.LruCache
import android.util.TypedValue
import android.widget.TextView
import com.airbnb.lottie.LottieComposition
import com.airbnb.lottie.LottieCompositionFactory
import com.airbnb.lottie.LottieDrawable
import java.util.Collections
import java.util.IdentityHashMap
import java.util.concurrent.Executors
import org.json.JSONObject
import ltd.evilcorp.atox.R

private const val EMOJI_INDEX_ASSET = "animated_emoji/index.json"
private const val ANIMATION_INTERVAL_MS = 20_000L
private const val MAX_RUNNING_ANIMATIONS = 4

internal data class AnimatedEmojiMatch(val start: Int, val end: Int, val asset: String)

internal object AnimatedEmojiAssets {
    private val executor = Executors.newSingleThreadExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())
    private val callbacks = mutableListOf<() -> Unit>()
    @Volatile private var index: Map<String, String>? = null
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
                val json = appContext.assets.open(EMOJI_INDEX_ASSET).bufferedReader().use { JSONObject(it.readText()) }
                buildMap {
                    json.keys().forEach { emoji ->
                        put(emoji, "animated_emoji/${json.getString(emoji).replace('\\', '/')}")
                    }
                }
            }.getOrDefault(emptyMap())
            index = loaded
            val pending = synchronized(this) {
                loading = false
                callbacks.toList().also { callbacks.clear() }
            }
            mainHandler.post { pending.forEach { it() } }
        }
    }

    fun matches(text: String): List<AnimatedEmojiMatch> {
        val paths = index ?: return emptyList()
        if (paths.isEmpty() || text.isEmpty()) return emptyList()
        val keys = paths.keys.sortedByDescending(String::length)
        val result = mutableListOf<AnimatedEmojiMatch>()
        var offset = 0
        while (offset < text.length) {
            val key = keys.firstOrNull { text.startsWith(it, offset) }
            if (key == null) {
                offset += Character.charCount(Character.codePointAt(text, offset))
            } else {
                result += AnimatedEmojiMatch(offset, offset + key.length, paths.getValue(key))
                offset += key.length
            }
        }
        return result
    }

    fun supportedEmojiOnly(text: String, matches: List<AnimatedEmojiMatch>): Boolean {
        val trimmed = text.trim()
        if (trimmed.isEmpty() || matches.isEmpty()) return false
        var offset = text.indexOf(trimmed)
        val end = offset + trimmed.length
        for (match in matches) {
            if (match.end <= offset || match.start >= end) continue
            if (text.substring(offset, match.start).any { !it.isWhitespace() }) return false
            offset = match.end
        }
        return offset == end || text.substring(offset, end).all(Char::isWhitespace)
    }
}

class EmojiAnimationCoordinator {
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
            if (active) handler.postDelayed(this, 500L)
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
        if (!active) return
        handler.removeCallbacks(tick)
        handler.postDelayed(tick, delayMs)
    }

    private fun startEligibleAnimations() {
        if (!active || scrolling || !systemAnimationsEnabled()) return
        val now = System.currentTimeMillis()
        entries.entries
            .asSequence()
            .filter { it.key.isShown && it.key.getGlobalVisibleRect(Rect()) }
            .sortedByDescending { it.value.emojiOnly }
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

internal class AnimatedEmojiRenderer(
    private val context: Context,
    private val coordinator: EmojiAnimationCoordinator,
    private val enabled: Boolean,
    onAssetsReady: () -> Unit,
) {
    private val compositions = object : LruCache<String, LottieComposition>(24) {}

    init {
        if (enabled) AnimatedEmojiAssets.load(context, onAssetsReady)
    }

    fun reset(view: TextView, defaultTextSizePx: Float) {
        coordinator.unregister(view)
        view.setTag(R.id.animated_emoji_bind_token, null)
        view.setTextSize(TypedValue.COMPLEX_UNIT_PX, defaultTextSizePx)
    }

    fun bind(view: TextView, messageId: Long) {
        if (!enabled) return
        val rendered = view.text.toString()
        val matches = AnimatedEmojiAssets.matches(rendered)
        if (matches.isEmpty()) return
        val emojiOnly = AnimatedEmojiAssets.supportedEmojiOnly(rendered, matches)
        val size = emojiSizePx(view, if (emojiOnly) matches.size else 0)
        if (emojiOnly) {
            view.background = null
            view.setPadding(0, 0, 0, 0)
        }
        val token = "$messageId:${System.nanoTime()}"
        view.setTag(R.id.animated_emoji_bind_token, token)
        val loaded = arrayOfNulls<LottieComposition>(matches.size)
        var remaining = matches.size
        matches.forEachIndexed { index, match ->
            val cached = compositions.get(match.asset)
            if (cached != null) {
                loaded[index] = cached
                remaining--
                if (remaining == 0) apply(view, token, messageId, matches, loaded, size, emojiOnly)
            } else {
                LottieCompositionFactory.fromAsset(context, match.asset)
                    .addListener { composition ->
                        compositions.put(match.asset, composition)
                        loaded[index] = composition
                        remaining--
                        if (remaining == 0) apply(view, token, messageId, matches, loaded, size, emojiOnly)
                    }
                    .addFailureListener {
                        remaining--
                        if (remaining == 0) apply(view, token, messageId, matches, loaded, size, emojiOnly)
                    }
            }
        }
    }

    private fun apply(
        view: TextView,
        token: String,
        messageId: Long,
        matches: List<AnimatedEmojiMatch>,
        loaded: Array<LottieComposition?>,
        size: Int,
        emojiOnly: Boolean,
    ) {
        if (view.getTag(R.id.animated_emoji_bind_token) != token) return
        val text = SpannableStringBuilder(view.text)
        val drawables = mutableListOf<LottieDrawable>()
        matches.forEachIndexed { index, match ->
            val composition = loaded[index] ?: return@forEachIndexed
            val drawable = LottieDrawable().apply {
                setComposition(composition)
                setBounds(0, 0, size, size)
                callback = view
                progress = 0f
            }
            drawables += drawable
            text.setSpan(ImageSpan(drawable, ImageSpan.ALIGN_BASELINE), match.start, match.end, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        view.text = text
        coordinator.register(view, messageId, drawables, emojiOnly)
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
