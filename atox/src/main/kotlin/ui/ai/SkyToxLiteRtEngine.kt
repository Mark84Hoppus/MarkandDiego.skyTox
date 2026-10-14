// SPDX-FileCopyrightText: 2026 skyTox contributors
//
// SPDX-License-Identifier: GPL-3.0-only

package ltd.evilcorp.atox.ui.ai

import android.content.Context
import android.os.Build
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Content
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.Message
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

object SkyToxLiteRtEngine {
    private const val CONTEXT_TOKENS = 2048
    private const val CPU_THREADS = 4
    private const val BACKEND_PREFS = "skytox_litert_backend"
    private const val BACKEND_CPU = "cpu"
    private const val BACKEND_GPU = "gpu"

    private val mutex = Mutex()
    private var engine: Engine? = null
    private var loadedModelPath: String? = null

    suspend fun generate(
        context: Context,
        modelFile: File,
        cacheDir: File,
        systemInstruction: String,
        userMessage: String,
        imageFile: File? = null,
        maxOutputTokens: Int,
        logFile: File,
        onPartialText: ((String) -> Unit)? = null,
    ): String = mutex.withLock {
        withContext(Dispatchers.IO) {
            cacheDir.mkdirs()
            val runtime = ensureEngine(context.applicationContext, modelFile, cacheDir, logFile)
            aiLog(logFile, "LiteRT createConversation begin maxOutput=$maxOutputTokens cacheDir=${cacheDir.absolutePath}")
            val conversation = runtime.createConversation(
                ConversationConfig(
                    systemInstruction = Contents.of(systemInstruction),
                    channels = emptyList(),
                    automaticToolCalling = false,
                )
            )
            aiLog(logFile, "LiteRT createConversation ok")
            try {
                val text = StringBuilder()
                val userContent = if (imageFile != null) {
                    Contents.of(Content.Text(userMessage), Content.ImageFile(imageFile.absolutePath))
                } else {
                    Contents.of(userMessage)
                }
                aiLog(logFile, "LiteRT sendMessage begin chars=${userMessage.length} image=${imageFile?.name.orEmpty()}")
                conversation.sendMessageAsync(Message.user(userContent)).collect { message ->
                    val chunk = extractText(message)
                    if (chunk.isNotEmpty()) {
                        text.append(chunk)
                        onPartialText?.invoke(text.toString())
                    }
                }
                aiLog(logFile, "LiteRT sendMessage finished replyChars=${text.length}")
                text.toString()
            } finally {
                runCatching { conversation.close() }
                aiLog(logFile, "LiteRT conversation closed")
            }
        }
    }

    private suspend fun ensureEngine(context: Context, modelFile: File, cacheDir: File, logFile: File): Engine {
        val path = modelFile.absolutePath
        engine?.takeIf { loadedModelPath == path }?.let { return it }
        runCatching { engine?.close() }
        engine = null
        loadedModelPath = null

        val prefs = context.getSharedPreferences(BACKEND_PREFS, Context.MODE_PRIVATE)
        val preferenceKey = backendPreferenceKey(modelFile)
        val cachedBackend = prefs.getString(preferenceKey, null)
        val attempts = buildBackendAttempts(cachedBackend)
        var lastError: Throwable? = null
        for ((label, backend) in attempts) {
            try {
                aiLog(logFile, "LiteRT init begin backend=$label modelBytes=${modelFile.length()}")
                val candidate = Engine(
                    EngineConfig(
                        modelPath = path,
                        backend = backend,
                        visionBackend = backend,
                        maxNumTokens = CONTEXT_TOKENS,
                        maxNumImages = 1,
                        cacheDir = cacheDir.absolutePath,
                    )
                )
                try {
                    candidate.initialize()
                    candidate.createConversation(
                        ConversationConfig(channels = emptyList(), automaticToolCalling = false)
                    ).use { }
                    runSmokeTest(candidate, logFile)
                } catch (error: Throwable) {
                    runCatching { candidate.close() }
                    throw error
                }
                engine = candidate
                loadedModelPath = path
                prefs.edit().putString(preferenceKey, backendPreferenceValue(label)).apply()
                aiLog(logFile, "LiteRT init ok backend=$label cached=${backendPreferenceValue(label)}")
                return candidate
            } catch (error: Throwable) {
                lastError = error
                aiLog(logFile, "LiteRT init failed backend=$label ${error.javaClass.name}: ${error.message}")
                if (cachedBackend != null && backendPreferenceValue(label) == cachedBackend) {
                    prefs.edit().remove(preferenceKey).apply()
                    aiLog(logFile, "LiteRT cached backend cleared key=$preferenceKey")
                }
            }
        }
        throw IllegalStateException("LiteRT initialization failed", lastError)
    }

    private suspend fun runSmokeTest(engine: Engine, logFile: File) {
        aiLog(logFile, "LiteRT smoke begin")
        val conversation = engine.createConversation(
            ConversationConfig(
                systemInstruction = Contents.of("Reply with OK."),
                channels = emptyList(),
                automaticToolCalling = false,
                maxOutputToken = 8,
            )
        )
        try {
            val text = StringBuilder()
            conversation.sendMessageAsync(Message.user("OK")).collect { message ->
                text.append(extractText(message))
            }
            if (text.isBlank()) {
                error("Smoke test returned empty text")
            }
            aiLog(logFile, "LiteRT smoke ok chars=${text.length}")
        } finally {
            runCatching { conversation.close() }
        }
    }

    private fun extractText(message: Message): String =
        message.contents.contents
            .filterIsInstance<Content.Text>()
            .joinToString(separator = "") { it.text }
            .ifBlank { message.contents.toString().takeIf { value -> value.isNotBlank() }.orEmpty() }

    private fun buildBackendAttempts(cachedBackend: String?): List<Pair<String, Backend>> {
        val cpu = "cpu-$CPU_THREADS" to Backend.CPU(numOfThreads = CPU_THREADS)
        val gpu = "gpu" to Backend.GPU()
        return when (cachedBackend) {
            BACKEND_CPU -> listOf(cpu, gpu)
            BACKEND_GPU -> listOf(gpu, cpu)
            else -> listOf(gpu, cpu)
        }
    }

    private fun backendPreferenceValue(label: String): String =
        if (label.startsWith("cpu")) BACKEND_CPU else BACKEND_GPU

    private fun backendPreferenceKey(modelFile: File): String =
        "${Build.FINGERPRINT}|${modelFile.name}|${modelFile.length()}|${modelFile.lastModified()}"

    private fun aiLog(file: File, message: String) {
        runCatching {
            val stamp = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", java.util.Locale.US)
                .format(java.util.Date())
            file.appendText("$stamp litert $message\n", Charsets.UTF_8)
        }
    }
}
